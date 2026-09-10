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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import org.jenkinsci.plugins.workflow.actions.TagsAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.StepNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.pipelinegraphanalysis.StageChunkFinder;

/** Reads the structure of a run's flow graph: which nodes start stages and branches, and what each one encloses. */
final class FlowGraph {

    private static final StageChunkFinder STAGES = new StageChunkFinder();

    /** The tag Declarative Pipeline puts on the stages it generates itself, such as "Declarative: Post Actions". */
    private static final String SYNTHETIC_STAGE_TAG = "SYNTHETIC_STAGE";

    /** The tag Declarative Pipeline puts on a stage it did not run, with a value that starts with "SKIPPED". */
    private static final String STAGE_STATUS_TAG = "STAGE_STATUS";

    private FlowGraph() {
    }

    /**
     * Whether the node starts a stage the Jenkinsfile declared: a block-scoped stage step, or a legacy stage step
     * without a block. The stages Declarative Pipeline generates around them (checkout, tool installation, post
     * actions) are left out, as Blue Ocean and Pipeline Graph View leave them out.
     */
    static boolean isStage(FlowNode node) {
        return node != null && STAGES.isChunkStart(node, null) && !isSynthetic(node);
    }

    static boolean isSynthetic(FlowNode node) {
        if (hasSyntheticTag(node)) {
            return true;
        }
        for (FlowNode parent : node.getParents()) {
            if (hasSyntheticTag(parent)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSyntheticTag(FlowNode node) {
        TagsAction tags = node.getAction(TagsAction.class);
        return tags != null && tags.getTagValue(SYNTHETIC_STAGE_TAG) != null;
    }

    /**
     * Whether Declarative Pipeline skipped the stage: because of a when condition, an earlier failure, an unstable
     * result or a restart from a later stage. The tag sits on the stage's node or its enclosing step node.
     */
    static boolean isSkipped(FlowNode node) {
        if (hasSkippedTag(node)) {
            return true;
        }
        for (FlowNode parent : node.getParents()) {
            if (hasSkippedTag(parent)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSkippedTag(FlowNode node) {
        TagsAction tags = node.getAction(TagsAction.class);
        String status = tags == null ? null : tags.getTagValue(STAGE_STATUS_TAG);
        return status != null && status.startsWith("SKIPPED");
    }

    /** Whether the node starts a parallel branch. */
    static boolean isBranch(FlowNode node) {
        return node != null && node.getAction(ThreadNameAction.class) != null;
    }

    /** Whether the node starts a block of the deprecated 1.x {@code task} step. */
    @SuppressWarnings("deprecation")
    static boolean isTaskStep(FlowNode node) {
        return node instanceof StepNode step && step.getDescriptor() instanceof TaskStep.DescriptorImpl;
    }

    /** Every node of the execution in id order; empty when the run has not started or its graph is gone. */
    static List<FlowNode> allNodes(FlowExecution execution) {
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
        result.sort(Comparator.comparingLong(FlowGraph::order));
        return result;
    }

    /** Start nodes of the top-level stages in execution order; stages nested in other stages are left out. */
    static List<FlowNode> stageStarts(List<FlowNode> allNodes) {
        List<FlowNode> result = new ArrayList<>();
        for (FlowNode node : allNodes) {
            if (isStage(node) && !hasEnclosing(node, FlowGraph::isStage)) {
                result.add(node);
            }
        }
        return result;
    }

    static boolean hasEnclosing(FlowNode node, Predicate<FlowNode> kind) {
        for (BlockStartNode enclosing : node.getEnclosingBlocks()) {
            if (kind.test(enclosing)) {
                return true;
            }
        }
        return false;
    }

    /** The nodes inside a stage: its block, or for a legacy stage step everything up to the next stage. */
    static List<FlowNode> nodesOf(FlowNode stageStart, List<FlowNode> allNodes, List<FlowNode> stageStarts) {
        if (stageStart instanceof BlockStartNode block) {
            return enclosedBy(allNodes, block);
        }
        List<FlowNode> result = new ArrayList<>();
        long from = order(stageStart);
        long to = Long.MAX_VALUE;
        for (FlowNode other : stageStarts) {
            long position = order(other);
            if (position > from && position < to) {
                to = position;
            }
        }
        for (FlowNode node : allNodes) {
            long position = order(node);
            if (position > from && position < to) {
                result.add(node);
            }
        }
        return result;
    }

    /** The nodes of the list that sit inside the block. */
    static List<FlowNode> enclosedBy(List<FlowNode> nodes, BlockStartNode block) {
        List<FlowNode> result = new ArrayList<>();
        for (FlowNode node : nodes) {
            if (node.getAllEnclosingIds().contains(block.getId())) {
                result.add(node);
            }
        }
        return result;
    }

    /** Blocks of the given kind that sit directly in the block, with no block of the same kind in between. */
    static List<BlockStartNode> directChildren(List<FlowNode> nodes, FlowNode block, Predicate<FlowNode> kind) {
        List<BlockStartNode> result = new ArrayList<>();
        for (FlowNode node : nodes) {
            if (node instanceof BlockStartNode start && !node.getId().equals(block.getId()) && kind.test(node)
                    && !hasEnclosingOfKindBelow(node, block, kind)) {
                result.add(start);
            }
        }
        result.sort(Comparator.comparingLong(FlowGraph::order));
        return result;
    }

    private static boolean hasEnclosingOfKindBelow(FlowNode node, FlowNode block, Predicate<FlowNode> kind) {
        for (BlockStartNode enclosing : node.getEnclosingBlocks()) {
            if (enclosing.getId().equals(block.getId())) {
                return false;
            }
            if (kind.test(enclosing)) {
                return true;
            }
        }
        return false;
    }

    static BlockEndNode<?> endOf(List<FlowNode> allNodes, BlockStartNode block) {
        for (FlowNode node : allNodes) {
            if (node instanceof BlockEndNode<?> end && end.getStartNode().getId().equals(block.getId())) {
                return end;
            }
        }
        return null;
    }

    static FlowNode nodeAfter(List<FlowNode> allNodes, FlowNode node) {
        for (FlowNode candidate : allNodes) {
            for (FlowNode parent : candidate.getParents()) {
                if (parent.getId().equals(node.getId())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** The newest node enclosed by the block, preferring a current head that is a step over a finished branch end. */
    static FlowNode lastNodeOf(FlowExecution execution, BlockStartNode block, List<FlowNode> allNodes) {
        FlowNode last = block;
        FlowNode head = null;
        for (FlowNode node : allNodes) {
            if (!node.getAllEnclosingIds().contains(block.getId())) {
                continue;
            }
            if (order(node) > order(last)) {
                last = node;
            }
            if (execution != null && execution.isCurrentHead(node) && isBetterHead(head, node)) {
                head = node;
            }
        }
        return head != null ? head : last;
    }

    private static boolean isBetterHead(FlowNode current, FlowNode candidate) {
        if (current == null) {
            return true;
        }
        boolean currentIsEnd = current instanceof BlockEndNode;
        boolean candidateIsEnd = candidate instanceof BlockEndNode;
        if (currentIsEnd != candidateIsEnd) {
            return currentIsEnd;
        }
        return order(candidate) > order(current);
    }

    static long order(FlowNode node) {
        try {
            return Long.parseLong(node.getId());
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
