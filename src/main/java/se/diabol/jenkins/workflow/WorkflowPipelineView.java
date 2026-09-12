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
package se.diabol.jenkins.workflow;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.View;
import java.util.ArrayList;
import java.util.List;
import se.diabol.jenkins.pipeline.DeliveryPipelineView;

/**
 * The "Delivery Pipeline View for Jenkins Pipelines" of 1.x. It no longer exists as a view type: a saved one is
 * read from disk and becomes a {@link DeliveryPipelineView} with the same components, so nothing is lost. The class
 * is only here so that XStream can read the old configuration; it is never instantiated otherwise.
 */
@Deprecated
@SuppressFBWarnings(value = "UUF_UNUSED_FIELD", justification = "the fields are read from the old configuration by XStream")
public final class WorkflowPipelineView extends View {

    private int updateInterval;
    private int noOfPipelines;
    private int noOfColumns;
    private String sorting;
    private boolean allowPipelineStart;
    private boolean allowAbort;
    private boolean showChanges;
    private boolean showAbsoluteDateTime;
    private int maxNumberOfVisiblePipelines;
    private String project;
    private List<ComponentSpec> componentSpecs;
    private boolean linkToConsoleLog;
    private String description;

    private WorkflowPipelineView(String name) {
        super(name);
    }

    /**
     * Called by XStream after the fields are read; the returned view takes this one's place. The owner is part of
     * what was read (views are saved with a reference to their view group), and the replacement must keep it, or
     * the folder or controller that lists the view fails as soon as it asks the view for its permissions.
     */
    protected Object readResolve() {
        DeliveryPipelineView view = owner == null ? new DeliveryPipelineView(name) : new DeliveryPipelineView(name, owner);
        view.setDescription(super.description != null ? super.description : description);
        view.setFilterExecutors(filterExecutors);
        view.setFilterQueue(filterQueue);
        view.setUpdateInterval(updateInterval);
        view.setNoOfPipelines(noOfPipelines);
        view.setNoOfColumns(noOfColumns);
        view.setSorting(sorting);
        view.setAllowPipelineStart(allowPipelineStart);
        view.setAllowAbort(allowAbort);
        view.setShowChanges(showChanges);
        view.setShowAbsoluteDateTime(showAbsoluteDateTime);
        view.setMaxNumberOfVisiblePipelines(maxNumberOfVisiblePipelines);
        List<DeliveryPipelineView.ComponentSpec> specs = new ArrayList<>();
        if (componentSpecs != null) {
            for (ComponentSpec spec : componentSpecs) {
                specs.add(new DeliveryPipelineView.ComponentSpec(spec.name, spec.job, null, false));
            }
        } else if (project != null && !project.isBlank()) {
            specs.add(new DeliveryPipelineView.ComponentSpec(project, project, null, false));
        }
        view.setComponentSpecs(specs);
        return view;
    }

    @Override
    public java.util.Collection<hudson.model.TopLevelItem> getItems() {
        return List.of();
    }

    @Override
    public boolean contains(hudson.model.TopLevelItem item) {
        return false;
    }

    /** A component of the old view: a name and a Pipeline job. */
    public static final class ComponentSpec {
        private String name;
        private String job;
    }
}
