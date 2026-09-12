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

import hudson.model.AbstractProject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.diabol.jenkins.pipeline.PipelineProperty;

/**
 * The static shape of a chain of jobs: every project reachable downstream of the first one (up to the last one, when
 * given), each with the names its {@link PipelineProperty} gives it and the projects it triggers within the chain.
 */
final class ChainGraph {

    /**
     * One project of the chain.
     *
     * @param id the project's full name, which identifies the task
     * @param downstream ids of the projects this one triggers that are part of the chain
     */
    record Node(AbstractProject<?, ?> project, String id, String taskName, String stageName, String descriptionTemplate,
                boolean initial, List<String> downstream) {
    }

    private final AbstractProject<?, ?> first;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, List<Node>> stages = new LinkedHashMap<>();

    private ChainGraph(AbstractProject<?, ?> first) {
        this.first = first;
    }

    static ChainGraph of(AbstractProject<?, ?> first, AbstractProject<?, ?> last) {
        Map<String, AbstractProject<?, ?>> projects = new LinkedHashMap<>();
        collect(first, last, projects);
        ChainGraph graph = new ChainGraph(first);
        for (AbstractProject<?, ?> project : projects.values()) {
            boolean isLast = last != null && project.getFullName().equals(last.getFullName());
            List<String> downstream = new ArrayList<>();
            if (!isLast) {
                for (AbstractProject<?, ?> target : DownstreamResolver.resolve(project)) {
                    String id = target.getFullName();
                    if (projects.containsKey(id) && !id.equals(project.getFullName()) && !downstream.contains(id)) {
                        downstream.add(id);
                    }
                }
            }
            Node node = new Node(project, project.getFullName(), taskNameOf(project), stageNameOf(project),
                    descriptionTemplateOf(project), project.getFullName().equals(first.getFullName()),
                    List.copyOf(downstream));
            graph.nodes.put(node.id(), node);
            graph.stages.computeIfAbsent(node.stageName(), name -> new ArrayList<>()).add(node);
        }
        return graph;
    }

    private static void collect(AbstractProject<?, ?> project, AbstractProject<?, ?> last,
                                Map<String, AbstractProject<?, ?>> into) {
        if (project == null || into.containsKey(project.getFullName())) {
            return;
        }
        into.put(project.getFullName(), project);
        if (last != null && project.getFullName().equals(last.getFullName())) {
            return;
        }
        for (AbstractProject<?, ?> downstream : DownstreamResolver.resolve(project)) {
            collect(downstream, last, into);
        }
    }

    AbstractProject<?, ?> first() {
        return first;
    }

    Collection<Node> nodes() {
        return nodes.values();
    }

    Node node(String id) {
        return nodes.get(id);
    }

    /** The projects grouped by stage name, in the order the chain was walked. */
    Map<String, List<Node>> stages() {
        return stages;
    }

    List<AbstractProject<?, ?>> projects() {
        List<AbstractProject<?, ?>> result = new ArrayList<>();
        for (Node node : nodes.values()) {
            result.add(node.project());
        }
        return result;
    }

    /** The stage the project with the given id belongs to, or null. */
    String stageOf(String id) {
        Node node = nodes.get(id);
        return node == null ? null : node.stageName();
    }

    static PipelineProperty ownProperty(AbstractProject<?, ?> project) {
        return project.getProperty(PipelineProperty.class);
    }

    /** A matrix configuration without a property of its own uses its parent project's. */
    static PipelineProperty inheritedProperty(AbstractProject<?, ?> project) {
        PipelineProperty property = ownProperty(project);
        if (property == null && project.getParent() instanceof AbstractProject<?, ?> parent) {
            return parent.getProperty(PipelineProperty.class);
        }
        return property;
    }

    static String taskNameOf(AbstractProject<?, ?> project) {
        PipelineProperty own = ownProperty(project);
        if (own != null) {
            return isBlank(own.getTaskName()) ? project.getDisplayName() : own.getTaskName();
        }
        PipelineProperty inherited = inheritedProperty(project);
        if (inherited != null && !isBlank(inherited.getTaskName())) {
            return inherited.getTaskName() + " " + project.getName();
        }
        return project.getDisplayName();
    }

    static String stageNameOf(AbstractProject<?, ?> project) {
        PipelineProperty property = inheritedProperty(project);
        return property == null || isBlank(property.getStageName()) ? project.getDisplayName() : property.getStageName();
    }

    static String descriptionTemplateOf(AbstractProject<?, ?> project) {
        PipelineProperty property = inheritedProperty(project);
        return property == null || isBlank(property.getDescriptionTemplate()) ? "" : property.getDescriptionTemplate();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
