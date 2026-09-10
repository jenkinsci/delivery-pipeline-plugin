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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import se.diabol.jenkins.pipeline.details.TaskDetailsContributor;
import se.diabol.jenkins.pipeline.model.Stage;
import se.diabol.jenkins.pipeline.model.Status;
import se.diabol.jenkins.pipeline.model.StatusType;
import se.diabol.jenkins.pipeline.model.Task;

/**
 * Turns the top-level stages of a run into the stages of the view, one column each. The tasks of a stage are the
 * stages nested directly in it, or else its parallel branches, or else the stage itself.
 */
final class FlowStages {

    private FlowStages() {
    }

    /**
     * @param restartable names of the top-level stages the run can be restarted from; their tasks get a restart
     */
    static List<Stage> stagesOf(WorkflowRun run, List<FlowNode> allNodes, List<FlowNode> stageStarts,
                                List<FlowRuns.StageTiming> timings, FlowRuns.Analysis previous,
                                Set<String> restartable) {
        List<Stage> stages = new ArrayList<>();
        for (int i = 0; i < stageStarts.size(); i++) {
            FlowNode stageStart = stageStarts.get(i);
            FlowRuns.StageTiming timing = timings.get(i);
            List<FlowNode> nodes = FlowGraph.nodesOf(stageStart, allNodes, stageStarts);
            List<Task> tasks = nestedTasks(run, stageStart, nodes, allNodes, previous, restartable);
            if (tasks.isEmpty()) {
                tasks.add(task(run, stageStart, timing.name(), timing, previous, timing.name(), restartable));
            }
            List<String> downstream = i + 1 < stageStarts.size() ? List.of(stageStarts.get(i + 1).getId()) : List.of();
            stages.add(new Stage(stageStart.getId(), timing.name(), 0, i, null, tasks, downstream));
        }
        if (stages.isEmpty() && run.isBuilding()) {
            Task starting = new Task("starting", "Starting", run.getUrl(), run.getParent().getFullName(),
                    run.getNumber(), Status.running(run.getTimeInMillis(), run.getEstimatedDuration()), null, false,
                    null, false, null, null, List.of(), List.of(), List.of(), List.of());
            stages.add(new Stage("starting", run.getParent().getDisplayName(), 0, 0, null, List.of(starting), List.of()));
        }
        return stages;
    }

    /**
     * Declarative and scripted Pipelines nest stages (sequential or inside parallel branches) and parallel branches
     * inside a stage. Each stage that sits directly in the stage becomes a task; if there are none, each block of
     * the deprecated {@code task} step does, and failing that each parallel branch that sits directly in the stage.
     */
    private static List<Task> nestedTasks(WorkflowRun run, FlowNode stageStart, List<FlowNode> nodes,
                                          List<FlowNode> allNodes, FlowRuns.Analysis previous,
                                          Set<String> restartable) {
        List<BlockStartNode> blocks = FlowGraph.directChildren(nodes, stageStart, FlowGraph::isStage);
        if (blocks.isEmpty()) {
            blocks = FlowGraph.directChildren(nodes, stageStart, FlowGraph::isTaskStep);
        }
        if (blocks.isEmpty()) {
            blocks = FlowGraph.directChildren(nodes, stageStart, FlowGraph::isBranch);
        }
        List<Task> result = new ArrayList<>();
        for (BlockStartNode block : blocks) {
            BlockEndNode<?> end = FlowGraph.endOf(allNodes, block);
            FlowNode last = end != null ? end : FlowGraph.lastNodeOf(run.getExecution(), block, allNodes);
            FlowRuns.StageTiming timing = FlowRuns.timingOf(run, block, last, FlowGraph.nodeAfter(allNodes, last));
            ThreadNameAction branch = block.getAction(ThreadNameAction.class);
            String name = branch != null && !FlowGraph.isStage(block) ? branch.getThreadName() : block.getDisplayName();
            result.add(task(run, block, name, timing, previous, stageStart.getDisplayName(), restartable));
        }
        return result;
    }

    /**
     * The task of a block. A running one links to the console; its test results are the ones recorded inside the
     * block; a rebuild of it restarts the run from its top-level stage, when the run can be restarted from there;
     * when it waits at an input step with parameters, the task links to the input page instead of offering the
     * proceed button.
     */
    private static Task task(WorkflowRun run, FlowNode node, String name, FlowRuns.StageTiming timing,
                             FlowRuns.Analysis previous, String parentStageName, Set<String> restartable) {
        Status status = statusOf(timing, previous, name, parentStageName);
        String restart = restartable.contains(parentStageName) ? parentStageName : null;
        boolean requiresInput = status.type() == StatusType.PAUSED_PENDING_INPUT;
        String url = status.type() == StatusType.RUNNING ? run.getUrl() + "console" : run.getUrl();
        return new Task(node.getId(), name, url, run.getParent().getFullName(), run.getNumber(), status,
                null, restart != null, restart, requiresInput, requiresInput ? inputUrlOf(run, node) : null, null,
                TaskDetailsContributor.testsOf(run, node.getId()), List.of(), List.of(), List.of());
    }

    /** The input page of the run when the input step waiting inside the block has parameters, else null. */
    private static String inputUrlOf(WorkflowRun run, FlowNode block) {
        InputStepExecution execution = pendingInputOf(run, block.getId());
        if (execution == null || execution.getInput().getParameters().isEmpty()) {
            return null;
        }
        InputAction action = run.getAction(InputAction.class);
        return run.getUrl() + (action == null ? "input" : action.getUrlName()) + "/";
    }

    /**
     * The input step waiting inside the block with the given node id, or failing that the first one waiting in the
     * run (a legacy stage step has no block to enclose it), or null.
     */
    static InputStepExecution pendingInputOf(WorkflowRun run, String blockId) {
        InputAction action = run.getAction(InputAction.class);
        if (action == null) {
            return null;
        }
        try {
            List<InputStepExecution> executions = action.getExecutions();
            for (InputStepExecution execution : executions) {
                FlowNode node = execution.getContext().get(FlowNode.class);
                if (node != null && (node.getId().equals(blockId) || node.getAllEnclosingIds().contains(blockId))) {
                    return execution;
                }
            }
            return executions.isEmpty() ? null : executions.get(0);
        } catch (IOException | TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Status statusOf(FlowRuns.StageTiming timing, FlowRuns.Analysis previous, String name,
                                   String parentStageName) {
        return switch (timing.type()) {
            case RUNNING -> Status.running(timing.start(), estimate(previous, name, parentStageName));
            case PAUSED_PENDING_INPUT -> Status.pausedPendingInput(timing.start(), -1);
            case QUEUED -> Status.queued(timing.start());
            case IDLE -> Status.idle();
            case DISABLED -> Status.disabled();
            default -> Status.finished(timing.type(), timing.start(), timing.duration());
        };
    }

    /** How long the same stage took in the last finished run, or -1 when there is nothing to go by. */
    private static long estimate(FlowRuns.Analysis previous, String name, String parentStageName) {
        if (previous == null) {
            return -1;
        }
        FlowRuns.StageTiming stage = previous.stage(name);
        if (stage == null) {
            stage = previous.stage(parentStageName);
        }
        return stage == null || stage.duration() <= 0 ? -1 : stage.duration();
    }
}
