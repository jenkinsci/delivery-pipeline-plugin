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
package se.diabol.jenkins.workflow.api;

import hudson.model.Result;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.pipelinegraphanalysis.GenericStatus;
import org.jenkinsci.plugins.workflow.pipelinegraphanalysis.StageChunkFinder;
import org.jenkinsci.plugins.workflow.pipelinegraphanalysis.StatusAndTiming;
import org.jenkinsci.plugins.workflow.pipelinegraphanalysis.TimingInfo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads stages, their nodes, status and timing straight from a run's flow graph, with the same
 * pipeline-graph-analysis algorithms that Pipeline Stage View and Pipeline Graph View build on.
 * Status names follow the Stage View vocabulary the rest of the workflow model expects.
 */
public final class FlowAnalysis {

    private static final StageChunkFinder STAGES = new StageChunkFinder();

    private FlowAnalysis() {
    }

    /** Whether the node starts a stage: a block-scoped stage step, or a legacy stage step without a block. */
    public static boolean isStageNode(FlowNode node) {
        return node != null && STAGES.isChunkStart(node, null);
    }

    /** Every node of the execution; empty when the run has not started or its graph is gone. */
    public static List<FlowNode> allNodes(FlowExecution execution) {
        List<FlowNode> result = new ArrayList<>();
        if (execution == null) {
            return result;
        }
        DepthFirstScanner scanner = new DepthFirstScanner();
        if (scanner.setup(execution.getCurrentHeads())) {
            for (FlowNode node : scanner) {
                result.add(node);
            }
        }
        result.sort(Comparator.comparingLong(FlowAnalysis::nodeOrder));
        return result;
    }

    /** Start nodes of the top-level stages in execution order; stages nested in other stages are left out. */
    public static List<FlowNode> stageStartNodes(List<FlowNode> allNodes) {
        List<FlowNode> result = new ArrayList<>();
        for (FlowNode node : allNodes) {
            if (isStageNode(node) && !hasEnclosingStage(node)) {
                result.add(node);
            }
        }
        result.sort(Comparator.comparingLong(FlowAnalysis::nodeOrder));
        return result;
    }

    public static boolean hasEnclosingStage(FlowNode node) {
        for (BlockStartNode enclosing : node.getEnclosingBlocks()) {
            if (isStageNode(enclosing)) {
                return true;
            }
        }
        return false;
    }

    /** The nodes inside a stage: its block, or for a legacy stage step everything up to the next stage. */
    public static List<FlowNode> nodesOf(FlowNode stageStart, List<FlowNode> allNodes, List<FlowNode> stageStarts) {
        List<FlowNode> result = new ArrayList<>();
        if (stageStart instanceof BlockStartNode) {
            for (FlowNode node : allNodes) {
                if (node.getAllEnclosingIds().contains(stageStart.getId())) {
                    result.add(node);
                }
            }
        } else {
            long from = nodeOrder(stageStart);
            long to = Long.MAX_VALUE;
            for (FlowNode other : stageStarts) {
                long order = nodeOrder(other);
                if (order > from && order < to) {
                    to = order;
                }
            }
            for (FlowNode node : allNodes) {
                long order = nodeOrder(node);
                if (order > from && order < to) {
                    result.add(node);
                }
            }
        }
        return result;
    }

    /** Status and timing of one stage, as Stage View would report them. */
    public static Stage stage(WorkflowRun run, FlowNode stageStart, List<FlowNode> allNodes, List<FlowNode> stageStarts) {
        List<FlowNode> nodes = nodesOf(stageStart, allNodes, stageStarts);
        FlowNode last = stageStart;
        FlowNode after = null;
        BlockEndNode<?> end = stageStart instanceof BlockStartNode ? endOf(allNodes, (BlockStartNode) stageStart) : null;
        if (end != null) {
            last = end;
            after = nodeAfter(allNodes, end);
        } else {
            last = lastNodeOf(run, stageStart, nodes);
            after = nodeAfter(allNodes, last);
        }
        FlowNode before = stageStart.getParents().isEmpty() ? null : stageStart.getParents().get(0);
        GenericStatus status = StatusAndTiming.computeChunkStatus2(run, before, stageStart, last, after);
        TimingInfo timing = StatusAndTiming.computeChunkTiming(run, 0, stageStart, last, after);
        long start = timing == null || timing.getStartTimeMillis() == 0 ? run.getStartTimeInMillis() : timing.getStartTimeMillis();
        long duration = timing == null ? 0 : timing.getTotalDurationMillis();
        return new Stage(stageStart.getId(), stageStart.getDisplayName(), statusName(status), Instant.ofEpochMilli(start), duration);
    }

    /**
     * The last node of a stage that has no end node yet: the newest of its nodes that is a current head, preferring a
     * step over the end of an already finished parallel branch, so that a running stage reads as running.
     */
    private static FlowNode lastNodeOf(WorkflowRun run, FlowNode stageStart, List<FlowNode> nodes) {
        FlowNode last = stageStart;
        FlowNode head = null;
        FlowExecution execution = run.getExecution();
        for (FlowNode node : nodes) {
            if (nodeOrder(node) > nodeOrder(last)) {
                last = node;
            }
            if (execution != null && execution.isCurrentHead(node)
                    && (head == null || (head instanceof BlockEndNode && !(node instanceof BlockEndNode))
                        || (nodeOrder(node) > nodeOrder(head) && (head instanceof BlockEndNode) == (node instanceof BlockEndNode)))) {
                head = node;
            }
        }
        return head != null ? head : last;
    }

    /** The overall status of a run in the Stage View vocabulary. */
    public static String runStatus(WorkflowRun run) {
        if (run.isBuilding()) {
            return StatusAndTiming.isPendingInput(run) ? "PAUSED_PENDING_INPUT" : "IN_PROGRESS";
        }
        Result result = run.getResult();
        if (result == null) {
            return "IN_PROGRESS";
        }
        if (result == Result.SUCCESS) {
            return "SUCCESS";
        }
        if (result == Result.UNSTABLE) {
            return "UNSTABLE";
        }
        if (result == Result.FAILURE) {
            return "FAILED";
        }
        if (result == Result.ABORTED) {
            return "ABORTED";
        }
        return "NOT_EXECUTED";
    }

    public static String statusName(GenericStatus status) {
        if (status == null) {
            return "NOT_EXECUTED";
        }
        switch (status.name()) {
            case "FAILURE":
                return "FAILED";
            case "QUEUED":
                return "IN_PROGRESS";
            default:
                return status.name();
        }
    }

    public static BlockEndNode<?> endOf(List<FlowNode> allNodes, BlockStartNode block) {
        for (FlowNode node : allNodes) {
            if (node instanceof BlockEndNode && ((BlockEndNode<?>) node).getStartNode().getId().equals(block.getId())) {
                return (BlockEndNode<?>) node;
            }
        }
        return null;
    }

    public static FlowNode nodeAfter(List<FlowNode> allNodes, FlowNode node) {
        for (FlowNode candidate : allNodes) {
            for (FlowNode parent : candidate.getParents()) {
                if (parent.getId().equals(node.getId())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    public static long nodeOrder(FlowNode node) {
        try {
            return Long.parseLong(node.getId());
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
