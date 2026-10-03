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

import hudson.model.Action;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import se.diabol.jenkins.pipeline.details.TaskDetailsContributor;
import se.diabol.jenkins.pipeline.freestyle.Statuses;
import se.diabol.jenkins.pipeline.model.Stage;
import se.diabol.jenkins.pipeline.model.Status;
import se.diabol.jenkins.pipeline.model.StatusType;
import se.diabol.jenkins.pipeline.model.Task;

/**
 * Turns the top-level stages of a run into the stages of the view, one column each. The tasks of a stage are the
 * stages nested directly in it, or else its parallel branches, or else the stage itself.
 */
final class FlowStages {

    /** The action Pipeline Graph View adds to a run for its console page, which can open on a chosen stage. */
    private static final String GRAPH_VIEW_CONSOLE_ACTION =
            "io.jenkins.plugins.pipelinegraphview.consoleview.PipelineConsoleViewAction";

    private FlowStages() {
    }

    /**
     * @param restartable names of the top-level stages the run can be restarted from; their tasks get a restart
     */
    static List<Stage> stagesOf(WorkflowRun run, List<FlowNode> allNodes, List<FlowNode> stageStarts,
                                List<FlowRuns.StageTiming> timings, FlowRuns.Analysis previous,
                                Set<String> restartable) {
        List<Stage> stages = new ArrayList<>();
        String console = stageConsoleUrlName(run);
        for (int i = 0; i < stageStarts.size(); i++) {
            FlowNode stageStart = stageStarts.get(i);
            FlowRuns.StageTiming timing = timings.get(i);
            List<FlowNode> nodes = FlowGraph.nodesOf(stageStart, allNodes, stageStarts);
            List<Task> tasks = nestedTasks(run, stageStart, nodes, allNodes, previous, restartable, console);
            if (tasks.isEmpty()) {
                tasks.add(task(run, stageStart, timing.name(), timing, previous, timing.name(), restartable, console));
            }
            List<String> downstream = i + 1 < stageStarts.size() ? List.of(stageStarts.get(i + 1).getId()) : List.of();
            stages.add(new Stage(stageStart.getId(), timing.name(), 0, i, null, tasks, downstream,
                    statusOf(timing, previous, timing.name(), timing.name())));
        }
        if (stages.isEmpty()) {
            stages.add(wholeRun(run, allNodes, previous, restartable, console));
        }
        return stages;
    }

    /**
     * A run without stages, as a scripted Pipeline of plain steps is, shown as one stage named after its job whose
     * tasks are the run's parallel branches, or else the run itself as one task the way a chained job without a
     * stage name is, with the run's status, its test results and its link. A run that is still starting when the
     * previous run had stages is shown as starting instead: its stages are yet to come.
     */
    private static Stage wholeRun(WorkflowRun run, List<FlowNode> allNodes, FlowRuns.Analysis previous,
                                  Set<String> restartable, String console) {
        WorkflowJob job = run.getParent();
        if (run.isBuilding() && previous != null && !previous.pipeline().stages().isEmpty()) {
            Task starting = new Task("starting", "Starting", run.getUrl(), job.getFullName(), run.getNumber(),
                    Status.running(run.getTimeInMillis(), run.getEstimatedDuration()), null, false, null, false, null,
                    null, List.of(), List.of(), List.of(), List.of());
            return new Stage("starting", job.getDisplayName(), 0, 0, null, List.of(starting), List.of(),
                    starting.status());
        }
        List<Task> tasks = allNodes.isEmpty() ? new ArrayList<>()
                : nestedTasks(run, allNodes.get(0), allNodes, allNodes, previous, restartable, console);
        if (tasks.isEmpty()) {
            Status status = runStatus(run);
            boolean requiresInput = status.type() == StatusType.PAUSED_PENDING_INPUT;
            tasks.add(new Task("run", job.getDisplayName(),
                    taskUrl(run.getUrl(), null, null, status.type() == StatusType.RUNNING), job.getFullName(),
                    run.getNumber(), status, null, false, null, requiresInput,
                    requiresInput ? inputUrlOf(run, "run") : null, null, TaskDetailsContributor.testsOf(run),
                    List.of(), List.of(), List.of()));
        }
        return new Stage("run", job.getDisplayName(), 0, 0, null, tasks, List.of(), runStatus(run));
    }

    /** The status of a run as a whole: waiting at an input step, running, or finished with its result. */
    static Status runStatus(WorkflowRun run) {
        if (run.isBuilding() && pendingInputOf(run, "run") != null) {
            return Status.pausedPendingInput(run.getTimeInMillis(), -1);
        }
        return Statuses.of(run);
    }

    /**
     * Declarative and scripted Pipelines nest stages (sequential or inside parallel branches) and parallel branches
     * inside a stage. Each innermost stage nested in the stage becomes a task; if there are none, each block of the
     * deprecated {@code task} step does, and failing that each parallel branch, down to the innermost.
     */
    private static List<Task> nestedTasks(WorkflowRun run, FlowNode stageStart, List<FlowNode> nodes,
                                          List<FlowNode> allNodes, FlowRuns.Analysis previous,
                                          Set<String> restartable, String console) {
        List<BlockStartNode> blocks = leafStages(nodes, stageStart);
        if (blocks.isEmpty()) {
            blocks = FlowGraph.directChildren(nodes, stageStart, FlowGraph::isTaskStep);
        }
        if (blocks.isEmpty()) {
            blocks = leafBranches(nodes, stageStart);
        }
        List<Task> result = new ArrayList<>();
        for (BlockStartNode block : blocks) {
            BlockEndNode<?> end = FlowGraph.endOf(allNodes, block);
            FlowNode last = end != null ? end : FlowGraph.lastNodeOf(run.getExecution(), block, allNodes);
            FlowRuns.StageTiming timing = FlowRuns.timingOf(run, block, last, FlowGraph.nodeAfter(allNodes, last));
            ThreadNameAction branch = block.getAction(ThreadNameAction.class);
            String name = branch != null && !FlowGraph.isStage(block) ? branch.getThreadName() : block.getDisplayName();
            result.add(task(run, block, cellName(taskName(block, name, stageStart)), timing, previous,
                    stageStart.getDisplayName(), restartable, console));
        }
        return result;
    }

    /**
     * The stages nested in the block, down to the innermost: a stage that holds other stages is shown as those, so
     * that sequential stages inside a parallel branch each get a task. A matrix cell stays one task: its stages are
     * the same for every cell, and the cell is what tells one combination from another.
     */
    private static List<BlockStartNode> leafStages(List<FlowNode> nodes, FlowNode block) {
        List<BlockStartNode> result = new ArrayList<>();
        for (BlockStartNode stage : FlowGraph.directChildren(nodes, block, FlowGraph::isStage)) {
            List<BlockStartNode> inner = isMatrixCell(stage)
                    ? List.of() : leafStages(FlowGraph.enclosedBy(nodes, stage), stage);
            if (inner.isEmpty()) {
                result.add(stage);
            } else {
                result.addAll(inner);
            }
        }
        return result;
    }

    private static boolean isMatrixCell(FlowNode stage) {
        return stage.getDisplayName().startsWith("Matrix - ");
    }

    /**
     * The parallel branches that sit directly in the block. A branch that holds a parallel of its own, which only
     * scripted Pipelines can write, is shown as its inner branches, down to the leaves.
     */
    private static List<BlockStartNode> leafBranches(List<FlowNode> nodes, FlowNode block) {
        List<BlockStartNode> result = new ArrayList<>();
        for (BlockStartNode branch : FlowGraph.directChildren(nodes, block, FlowGraph::isBranch)) {
            List<BlockStartNode> inner = leafBranches(FlowGraph.enclosedBy(nodes, branch), branch);
            if (inner.isEmpty()) {
                result.add(branch);
            } else {
                result.addAll(inner);
            }
        }
        return result;
    }

    /**
     * The name of a task: its own, prefixed with the names of the stages and parallel branches between it and its
     * top-level stage, outermost first, as "linux: Compile" for a stage inside a scripted branch or "Linux: Unit"
     * for a stage nested in a stage inside a Declarative branch, so that the same stage in two branches stays
     * apart. A branch that Declarative names after the stage it wraps adds nothing, and a block of the deprecated
     * task step keeps its own name.
     */
    private static String taskName(FlowNode block, String name, FlowNode stageStart) {
        if (!FlowGraph.isStage(block) && !FlowGraph.isBranch(block)) {
            return name;
        }
        String result = name;
        String innermost = name;
        for (BlockStartNode enclosing : block.getEnclosingBlocks()) {
            if (enclosing.getId().equals(stageStart.getId())) {
                break;
            }
            String label = labelOf(enclosing);
            if (label != null && !label.equals(innermost)) {
                result = label + ": " + result;
                innermost = label;
            }
        }
        return result;
    }

    /** The name a stage or a parallel branch lends to the tasks inside it; null for any other block. */
    private static String labelOf(BlockStartNode block) {
        if (FlowGraph.isStage(block)) {
            return block.getDisplayName();
        }
        ThreadNameAction branch = block.getAction(ThreadNameAction.class);
        return branch == null ? null : branch.getThreadName();
    }

    /** Declarative names every cell of a matrix "Matrix - OS = 'linux', ..."; the axes alone say what the cell is. */
    private static String cellName(String name) {
        return name.startsWith("Matrix - ") ? name.substring("Matrix - ".length()) : name;
    }

    /**
     * The task of a block. It links to its own log when Pipeline Graph View provides one, else a running one links
     * to the console and a finished one to the run; its test results are the ones recorded inside the block; a
     * rebuild of it restarts the run from its top-level stage, when the run can be restarted from there; when it
     * waits at an input step with parameters, the task links to the input page instead of offering the proceed
     * button.
     */
    private static Task task(WorkflowRun run, FlowNode node, String name, FlowRuns.StageTiming timing,
                             FlowRuns.Analysis previous, String parentStageName, Set<String> restartable,
                             String console) {
        Status status = statusOf(timing, previous, name, parentStageName);
        String restart = restartable.contains(parentStageName) ? parentStageName : null;
        boolean requiresInput = status.type() == StatusType.PAUSED_PENDING_INPUT;
        // the console page addresses blocks; a legacy stage step without a block is not among its nodes
        String url = taskUrl(run.getUrl(), node instanceof BlockStartNode ? console : null, consoleNodeOf(node),
                status.type() == StatusType.RUNNING);
        return new Task(node.getId(), name, url, run.getParent().getFullName(), run.getNumber(), status,
                null, restart != null, restart, requiresInput, requiresInput ? inputUrlOf(run, node.getId()) : null, null,
                TaskDetailsContributor.testsOf(run, node.getId()), List.of(), List.of(), List.of());
    }

    /**
     * Where a task links to: the run's Pipeline Graph View console opened on the task's node when that plugin
     * provides one, else the run's console while the task runs and the run page otherwise.
     */
    static String taskUrl(String runUrl, String consoleUrlName, String nodeId, boolean running) {
        if (consoleUrlName != null) {
            return runUrl + consoleUrlName + "/?selected-node=" + nodeId;
        }
        return running ? runUrl + "console" : runUrl;
    }

    /**
     * The node Pipeline Graph View selects for the task: a stage that sits directly inside a parallel branch, as
     * Declarative Pipeline nests them, is addressed by the branch, everything else by its own node.
     */
    static String consoleNodeOf(FlowNode node) {
        if (FlowGraph.isStage(node)) {
            for (BlockStartNode enclosing : node.getEnclosingBlocks()) {
                if (FlowGraph.isBranch(enclosing)) {
                    return enclosing.getId();
                }
                if (FlowGraph.isStage(enclosing)) {
                    break;
                }
            }
        }
        return node.getId();
    }

    /**
     * The URL name of Pipeline Graph View's console page on the run ("stages" today, "pipeline-console" in older
     * releases), read from the action the plugin adds to runs, or null when the plugin is not installed.
     */
    static String stageConsoleUrlName(WorkflowRun run) {
        for (Action action : run.getAllActions()) {
            if (GRAPH_VIEW_CONSOLE_ACTION.equals(action.getClass().getName())) {
                return action.getUrlName();
            }
        }
        return null;
    }

    /** The input page of the run when the input step waiting inside the block has parameters, else null. */
    private static String inputUrlOf(WorkflowRun run, String blockId) {
        InputStepExecution execution = pendingInputOf(run, blockId);
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
