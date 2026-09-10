/*
This file is part of Delivery Pipeline Plugin.

Delivery Pipeline Plugin is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Delivery Pipeline Plugin is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Delivery Pipeline Plugin.
If not, see <http://www.gnu.org/licenses/>.
*/
package se.diabol.jenkins.pipeline.flow;

import hudson.Extension;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.pipelinegraphanalysis.GenericStatus;
import org.jenkinsci.plugins.workflow.pipelinegraphanalysis.StatusAndTiming;
import org.jenkinsci.plugins.workflow.pipelinegraphanalysis.TimingInfo;
import se.diabol.jenkins.pipeline.details.TaskDetailsContributor;
import se.diabol.jenkins.pipeline.freestyle.Changes;
import se.diabol.jenkins.pipeline.freestyle.Triggers;
import se.diabol.jenkins.pipeline.model.Change;
import se.diabol.jenkins.pipeline.model.Pipeline;
import se.diabol.jenkins.pipeline.model.Stage;
import se.diabol.jenkins.pipeline.model.Status;
import se.diabol.jenkins.pipeline.model.StatusType;
import se.diabol.jenkins.pipeline.model.Task;
import se.diabol.jenkins.pipeline.model.TestSummary;

/** The stages of Pipeline runs with their status and timing; finished runs are analysed once and remembered. */
public final class FlowRuns {

    /** One analysed run: the timing of its top-level stages and its view model. */
    record Analysis(List<StageTiming> stages, Pipeline pipeline) {
        StageTiming stage(String name) {
            for (StageTiming stage : stages) {
                if (stage.name().equals(name)) {
                    return stage;
                }
            }
            return null;
        }
    }

    /** Status and timing of a block: a top-level stage, a nested stage or a parallel branch. */
    record StageTiming(String id, String name, StatusType type, long start, long duration) {
    }

    private static final int CACHE_SIZE = 500;
    private static final Map<String, Analysis> FINISHED = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Analysis> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    private FlowRuns() {
    }

    /** The analysis of a run; cached once the run has finished. */
    static Analysis of(WorkflowRun run) {
        if (run.isBuilding()) {
            return analyse(run);
        }
        return FINISHED.computeIfAbsent(run.getExternalizableId(), key -> analyse(run));
    }

    static void forget(Run<?, ?> run) {
        FINISHED.remove(run.getExternalizableId());
    }

    /** The newest run that has finished, for estimating how long the stages of a running one will take. */
    static Analysis lastFinished(WorkflowJob job) {
        for (WorkflowRun candidate : job.getBuilds()) {
            if (!candidate.isBuilding()) {
                return of(candidate);
            }
        }
        return null;
    }

    private static Analysis analyse(WorkflowRun run) {
        List<FlowNode> allNodes = FlowGraph.allNodes(run.getExecution());
        List<FlowNode> stageStarts = FlowGraph.stageStarts(allNodes);
        List<StageTiming> timings = new ArrayList<>();
        for (FlowNode stageStart : stageStarts) {
            timings.add(timingOf(run, stageStart, allNodes, stageStarts));
        }
        Analysis previous = run.isBuilding() ? lastFinished(run.getParent()) : null;
        Set<String> restartable = run.isBuilding() ? Set.of() : StageRestart.restartableStagesOf(run);
        List<Stage> stages = FlowStages.stagesOf(run, allNodes, stageStarts, timings, previous, restartable);
        List<Change> changes = Changes.of(run);
        long totalBuildTime = run.isBuilding() ? System.currentTimeMillis() - run.getTimeInMillis() : run.getDuration();
        // Static analysis results belong to the run, not to one of its stages; test results are found per stage,
        // and what was recorded outside the stages shown stays with the run.
        Pipeline pipeline = new Pipeline(run.getParent().getFullName() + "#" + run.getNumber(), run.getDisplayName(),
                run.getTimeInMillis(), false, run.getParent().getFullName(), run.getNumber(), !run.isBuilding(),
                Triggers.of(run.getCauses()), Changes.contributorsOf(changes), changes, changes.size(), totalBuildTime,
                remainingTests(run, stages), TaskDetailsContributor.analysisOf(run), stages);
        return new Analysis(timings, pipeline);
    }

    /**
     * Test results the run recorded outside the tasks shown: in a synthetic Declarative stage such as the post
     * section, in a stage whose tasks are its nested stages, or at the top level of a scripted Pipeline.
     */
    private static List<TestSummary> remainingTests(WorkflowRun run, List<Stage> stages) {
        List<TestSummary> result = new ArrayList<>();
        for (TestSummary all : TaskDetailsContributor.testsOf(run)) {
            int total = all.total();
            int failed = all.failed();
            int skipped = all.skipped();
            for (Stage stage : stages) {
                for (Task task : stage.tasks()) {
                    for (TestSummary shown : task.tests()) {
                        if (shown.name().equals(all.name())) {
                            total -= shown.total();
                            failed -= shown.failed();
                            skipped -= shown.skipped();
                        }
                    }
                }
            }
            if (total > 0) {
                result.add(new TestSummary(all.name(), all.url(), total, Math.max(0, failed), Math.max(0, skipped)));
            }
        }
        return result;
    }

    /**
     * A placeholder instance for a job waiting in the queue, laid out like its last finished run so that the
     * board keeps its shape, with the first stage queued and the rest idle.
     */
    static Pipeline queued(WorkflowJob job, Queue.Item item) {
        long since = item.getInQueueSince();
        Analysis previous = lastFinished(job);
        List<Stage> stages = new ArrayList<>();
        if (previous == null || previous.pipeline().stages().isEmpty()) {
            Task task = new Task("queued", "Queued", job.getUrl(), job.getFullName(), null, Status.queued(since), null,
                    false, null, false, null, null, List.of(), List.of(), List.of(), List.of());
            stages.add(new Stage("queued", job.getDisplayName(), 0, 0, null, List.of(task), List.of()));
        } else {
            boolean first = true;
            for (Stage stage : previous.pipeline().stages()) {
                List<Task> tasks = new ArrayList<>();
                for (Task task : stage.tasks()) {
                    tasks.add(new Task(task.id(), task.name(), job.getUrl(), job.getFullName(), null,
                            first ? Status.queued(since) : Status.idle(), null, false, null, false, null, null,
                            List.of(), List.of(), List.of(), task.downstream()));
                }
                stages.add(stage.withTasks(tasks, null));
                first = false;
            }
        }
        return new Pipeline(job.getFullName() + "#queued", "#" + job.getNextBuildNumber(), since, false,
                job.getFullName(), null, false, Triggers.of(item.getCauses()), List.of(), List.of(), 0, 0, List.of(),
                List.of(), stages);
    }

    /** Status and timing of a top-level stage. */
    static StageTiming timingOf(WorkflowRun run, FlowNode stageStart, List<FlowNode> allNodes,
                                List<FlowNode> stageStarts) {
        FlowNode last;
        FlowNode after;
        if (stageStart instanceof BlockStartNode block) {
            BlockEndNode<?> end = FlowGraph.endOf(allNodes, block);
            last = end != null ? end : FlowGraph.lastNodeOf(run.getExecution(), block, allNodes);
        } else {
            last = stageStart;
            for (FlowNode node : FlowGraph.nodesOf(stageStart, allNodes, stageStarts)) {
                if (FlowGraph.order(node) > FlowGraph.order(last)) {
                    last = node;
                }
            }
            FlowExecution execution = run.getExecution();
            if (execution != null) {
                for (FlowNode node : FlowGraph.nodesOf(stageStart, allNodes, stageStarts)) {
                    if (execution.isCurrentHead(node)) {
                        last = node;
                    }
                }
            }
        }
        after = FlowGraph.nodeAfter(allNodes, last);
        return timingOf(run, stageStart, last, after);
    }

    /** Status and timing of a block that ends at the given node. */
    static StageTiming timingOf(WorkflowRun run, FlowNode start, FlowNode last, FlowNode after) {
        FlowNode before = start.getParents().isEmpty() ? null : start.getParents().get(0);
        GenericStatus status = StatusAndTiming.computeChunkStatus2(run, before, start, last, after);
        TimingInfo timing = StatusAndTiming.computeChunkTiming(run, 0, start, last, after);
        long startTime = timing == null || timing.getStartTimeMillis() == 0
                ? run.getTimeInMillis() : timing.getStartTimeMillis();
        long duration = timing == null ? 0 : timing.getTotalDurationMillis();
        return new StageTiming(start.getId(), start.getDisplayName(), typeOf(status), startTime, duration);
    }

    static StatusType typeOf(GenericStatus status) {
        if (status == null) {
            return StatusType.NOT_BUILT;
        }
        return switch (status) {
            case SUCCESS -> StatusType.SUCCESS;
            case UNSTABLE -> StatusType.UNSTABLE;
            case FAILURE -> StatusType.FAILED;
            case ABORTED -> StatusType.CANCELLED;
            case IN_PROGRESS -> StatusType.RUNNING;
            case QUEUED -> StatusType.QUEUED;
            case PAUSED_PENDING_INPUT -> StatusType.PAUSED_PENDING_INPUT;
            default -> StatusType.NOT_BUILT;
        };
    }

    /** Drops the analysis of a run that is deleted, so that a later run with the same number starts afresh. */
    @Extension
    public static class Forgetter extends RunListener<Run<?, ?>> {
        @Override
        public void onDeleted(Run<?, ?> run) {
            forget(run);
        }

        @Override
        public void onCompleted(Run<?, ?> run, TaskListener listener) {
            // the analysis taken while the run was still building must not linger
            forget(run);
        }
    }
}
