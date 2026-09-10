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
package se.diabol.jenkins.pipeline.freestyle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.diabol.jenkins.pipeline.PipelineException;

/**
 * Places stages on a grid. A stage's column is the length of the longest path leading to it from the first stage, so
 * every arrow points to the right. Rows are handed out per column in the order stages are reached when the longest
 * chains are followed first, which keeps the main line of the pipeline on the top row.
 */
final class StageLayout {

    /** Grid position of a stage. */
    record Position(int row, int column) {
    }

    private StageLayout() {
    }

    /**
     * @param ids the stage ids in configuration order
     * @param edges downstream stage ids per stage id
     * @param first the stage the pipeline starts with
     * @return positions keyed by stage id, in row-then-column order
     * @throws PipelineException when the stages depend on each other in a circle
     */
    static Map<String, Position> place(List<String> ids, Map<String, List<String>> edges, String first)
            throws PipelineException {
        Map<String, Integer> column = longestPathColumns(ids, edges, first);
        Map<String, Integer> depth = depthToSink(ids, edges);
        Map<String, Position> positions = new HashMap<>();
        Map<Integer, Integer> rowsUsed = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(first);
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (positions.containsKey(id)) {
                continue;
            }
            int col = column.get(id);
            int row = rowsUsed.merge(col, 1, Integer::sum) - 1;
            positions.put(id, new Position(row, col));
            List<String> children = new ArrayList<>(edges.getOrDefault(id, List.of()));
            // longest chain first; push in reverse so that it is popped first
            children.sort((a, b) -> Integer.compare(depth.get(b), depth.get(a)));
            for (int i = children.size() - 1; i >= 0; i--) {
                if (!positions.containsKey(children.get(i))) {
                    stack.push(children.get(i));
                }
            }
        }
        for (String id : ids) {
            if (!positions.containsKey(id)) {
                int col = column.get(id);
                positions.put(id, new Position(rowsUsed.merge(col, 1, Integer::sum) - 1, col));
            }
        }
        List<String> ordered = new ArrayList<>(ids);
        ordered.sort((a, b) -> {
            Position pa = positions.get(a);
            Position pb = positions.get(b);
            return pa.row() != pb.row() ? Integer.compare(pa.row(), pb.row()) : Integer.compare(pa.column(), pb.column());
        });
        Map<String, Position> result = new LinkedHashMap<>();
        for (String id : ordered) {
            result.put(id, positions.get(id));
        }
        return result;
    }

    /** Topological pass; the longest path from the first stage decides the column. */
    private static Map<String, Integer> longestPathColumns(List<String> ids, Map<String, List<String>> edges,
                                                           String first) throws PipelineException {
        Map<String, Integer> indegree = new HashMap<>();
        for (String id : ids) {
            indegree.putIfAbsent(id, 0);
            for (String target : edges.getOrDefault(id, List.of())) {
                indegree.merge(target, 1, Integer::sum);
            }
        }
        Map<String, Integer> column = new HashMap<>();
        Deque<String> ready = new ArrayDeque<>();
        for (String id : ids) {
            column.put(id, 0);
            if (indegree.get(id) == 0) {
                ready.add(id);
            }
        }
        Set<String> done = new HashSet<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            done.add(id);
            for (String target : edges.getOrDefault(id, List.of())) {
                column.merge(target, column.get(id) + 1, Math::max);
                if (indegree.merge(target, -1, Integer::sum) == 0) {
                    ready.add(target);
                }
            }
        }
        if (done.size() < ids.size()) {
            List<String> circular = new ArrayList<>(ids);
            circular.removeAll(done);
            throw new PipelineException("Circular dependencies between stages: " + String.join(", ", circular));
        }
        int offset = column.getOrDefault(first, 0);
        if (offset > 0) {
            column.replaceAll((id, col) -> Math.max(0, col - offset));
        }
        return column;
    }

    /** Number of stages on the longest chain starting at each stage, for choosing which branch to follow first. */
    private static Map<String, Integer> depthToSink(List<String> ids, Map<String, List<String>> edges) {
        Map<String, Integer> depth = new HashMap<>();
        for (String id : ids) {
            depthOf(id, edges, depth, new HashSet<>());
        }
        return depth;
    }

    private static int depthOf(String id, Map<String, List<String>> edges, Map<String, Integer> depth,
                               Set<String> visiting) {
        Integer known = depth.get(id);
        if (known != null) {
            return known;
        }
        if (!visiting.add(id)) {
            return 0;
        }
        int result = 1;
        for (String target : edges.getOrDefault(id, List.of())) {
            result = Math.max(result, 1 + depthOf(target, edges, depth, visiting));
        }
        visiting.remove(id);
        depth.put(id, result);
        return result;
    }
}
