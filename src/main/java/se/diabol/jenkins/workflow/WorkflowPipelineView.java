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

import com.google.common.collect.Sets;
import hudson.DescriptorExtensionList;
import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Api;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.TopLevelItem;
import hudson.model.View;
import hudson.model.ViewDescriptor;
import hudson.model.ViewGroup;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.springframework.security.core.AuthenticationException;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.export.Exported;
import se.diabol.jenkins.core.PipelineView;
import se.diabol.jenkins.core.TimestampFormat;
import se.diabol.jenkins.pipeline.PipelineApi;
import se.diabol.jenkins.pipeline.domain.PipelineException;
import se.diabol.jenkins.pipeline.sort.ComponentComparatorDescriptor;
import se.diabol.jenkins.pipeline.sort.GenericComponentComparator;
import se.diabol.jenkins.pipeline.trigger.TriggerException;
import se.diabol.jenkins.pipeline.util.JenkinsUtil;
import se.diabol.jenkins.pipeline.util.ProjectUtil;
import se.diabol.jenkins.workflow.model.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.annotation.Nonnull;
import jakarta.servlet.ServletException;

/**
 * View for Pipeline jobs only.
 *
 * @deprecated since 1.5: a {@link se.diabol.jenkins.pipeline.DeliveryPipelineView} accepts Pipeline jobs as
 *     components next to freestyle chains and shares one page and script. Existing views of this type keep
 *     working; the type is no longer offered when creating a view.
 */
@Deprecated
public class WorkflowPipelineView extends View implements PipelineView {

    private static final Logger LOG = Logger.getLogger(WorkflowPipelineView.class.getName());

    public static final int DEFAULT_INTERVAL = 2;

    public static final int DEFAULT_NO_OF_PIPELINES = 3;
    private static final int MAX_NO_OF_PIPELINES = 50;
    private static final String NONE_SORTER = "none";

    private int updateInterval = DEFAULT_INTERVAL;
    private int noOfPipelines = DEFAULT_NO_OF_PIPELINES;
    private int noOfColumns = 1;
    private String sorting = NONE_SORTER;
    private boolean allowPipelineStart = false;
    private boolean allowAbort = false;
    private boolean showChanges = false;
    private boolean showAbsoluteDateTime = false;
    private int maxNumberOfVisiblePipelines = -1;
    @Deprecated
    private String project;
    private List<ComponentSpec> componentSpecs;
    private boolean linkToConsoleLog = false;
    private String description = null;

    private transient String error;

    @DataBoundConstructor
    public WorkflowPipelineView(String name) {
        super(name);
    }

    public WorkflowPipelineView(String name, ViewGroup owner) {
        super(name, owner);
    }

    public int getNoOfColumns() {
        return noOfColumns;
    }

    @DataBoundSetter
    public void setNoOfColumns(int noOfColumns) {
        this.noOfColumns = noOfColumns;
    }

    public int getUpdateInterval() {
        //This occurs when the plugin has been updated and as long as the view has not been updated
        //Jenkins will set the default value to 0
        if (updateInterval == 0) {
            updateInterval = DEFAULT_INTERVAL;
        }

        return updateInterval;
    }

    @DataBoundSetter
    public void setUpdateInterval(int updateInterval) {
        this.updateInterval = updateInterval;
    }

    public int getNoOfPipelines() {
        return noOfPipelines;
    }

    @DataBoundSetter
    public void setNoOfPipelines(int noOfPipelines) {
        this.noOfPipelines = noOfPipelines;
    }

    public String getSorting() {
        return sorting;
    }

    @DataBoundSetter
    public void setSorting(String sorting) {
        this.sorting = sorting;
    }

    @Exported
    @Override
    public boolean isAllowPipelineStart() {
        return allowPipelineStart;
    }

    @DataBoundSetter
    public void setAllowPipelineStart(boolean allowPipelineStart) {
        this.allowPipelineStart = allowPipelineStart;
    }

    @Exported
    @Override
    public boolean isAllowAbort() {
        return allowAbort;
    }

    @DataBoundSetter
    public void setAllowAbort(boolean allowAbort) {
        this.allowAbort = allowAbort;
    }

    public boolean isShowChanges() {
        return showChanges;
    }

    @DataBoundSetter
    public void setShowChanges(boolean showChanges) {
        this.showChanges = showChanges;
    }

    @Exported
    public boolean isShowAbsoluteDateTime() {
        return showAbsoluteDateTime;
    }

    @DataBoundSetter
    public void setShowAbsoluteDateTime(boolean showAbsoluteDateTime) {
        this.showAbsoluteDateTime = showAbsoluteDateTime;
    }

    public int getMaxNumberOfVisiblePipelines() {
        return maxNumberOfVisiblePipelines;
    }

    @DataBoundSetter
    public void setMaxNumberOfVisiblePipelines(int maxNumberOfVisiblePipelines) {
        this.maxNumberOfVisiblePipelines = maxNumberOfVisiblePipelines;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public List<ComponentSpec> getComponentSpecs() {
        if (componentSpecs == null) {
            componentSpecs = new ArrayList<>();
        }
        return componentSpecs;
    }

    @DataBoundSetter
    public void setComponentSpecs(List<ComponentSpec> componentSpecs) {
        this.componentSpecs = componentSpecs;
    }

    @Exported
    @Override
    public String getLastUpdated() {
        return TimestampFormat.formatTimestamp(System.currentTimeMillis());
    }

    @Exported
    public boolean isLinkToConsoleLog() {
        return linkToConsoleLog;
    }

    @DataBoundSetter
    public void setLinkToConsoleLog(boolean linkToConsoleLog) {
        this.linkToConsoleLog = linkToConsoleLog;
    }

    @Exported
    @Override
    public String getDescription() {
        if (super.description == null) {
            setDescription(this.description);
        }
        return super.description;
    }

    @DataBoundSetter
    public void setDescription(String description) {
        super.description = description;
        this.description = description;
    }

    @Exported
    @Override
    public String getError() {
        return error;
    }

    @Exported
    @Override
    public List<Component> getPipelines() {
        try {
            LOG.fine("Getting pipelines");
            List<Component> components = new ArrayList<>();
            backwardsCompatibilityHandling();
            for (ComponentSpec componentSpec : getComponentSpecs()) {
                WorkflowJob job = getWorkflowJob(componentSpec.job);
                Component component = Component.resolve(componentSpec.name, job, noOfPipelines, showChanges);
                this.error = null;
                components.add(component);
            }
            if (sortingConfigured()) {
                sort(components);
            }
            if (maxNumberOfVisiblePipelines > 0) {
                LOG.fine("Limiting number of jobs to: " + maxNumberOfVisiblePipelines);
                components = components.subList(0, Math.min(components.size(), maxNumberOfVisiblePipelines));
            }
            return components;
        } catch (PipelineException e) {
            error = e.getMessage();
            return Collections.emptyList();
        }
    }

    private boolean sortingConfigured() {
        return getSorting() != null && !getSorting().equals(NONE_SORTER);
    }

    private void sort(List<Component> components) {
        ComponentComparatorDescriptor comparatorDescriptor = GenericComponentComparator.all().find(sorting);
        if (comparatorDescriptor != null) {
            components.sort(comparatorDescriptor.createInstance());
        }
    }

    /**
     * Automatically migrate views created prior to the introduction of component specs
     * to use the new structure. Support subject to removal in a future release.
     */
    private void backwardsCompatibilityHandling() {
        if (project != null && getComponentSpecs().isEmpty()) {
            LOG.fine("Backwards compatibility check: Migrating legacy configuration to current structure");
            componentSpecs.addAll(migrateToComponentSpec(project));
            project = null;
        }
    }

    private List<ComponentSpec> migrateToComponentSpec(String projectName) {
        return Collections.singletonList(new ComponentSpec(projectName, projectName));
    }

    @Override
    @Exported
    public String getViewUrl() {
        return super.getViewUrl();
    }

    @Override
    public Api getApi() {
        return new PipelineApi(this);
    }

    @Override
    public Item doCreateItem(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        if (!isDefault()) {
            return getOwner().getPrimaryView().doCreateItem(req, rsp);
        } else {
            return jenkins().doCreateItem(req, rsp);
        }
    }

    @Override
    public void triggerManual(String projectName, String upstreamName, String buildId) throws AuthenticationException {
        LOG.fine("Manual/Input step called for project: " + projectName + " and build id: " + buildId);
        try {
            WorkflowRuns.proceedInput(getWorkflowJob(projectName), buildId);
        } catch (PipelineException e) {
            LOG.warning("Failed to resolve project to trigger manual/input: " + e);
        }
    }

    @Override
    public void triggerRebuild(String projectName, String buildId) {
        LOG.log(Level.SEVERE, "Rebuild not implemented for workflow/pipeline projects");
    }

    @Override
    public void abortBuild(String projectName, String buildId) throws TriggerException {
        try {
            WorkflowRuns.abort(getWorkflowJob(projectName), buildId);
        } catch (PipelineException e) {
            throw new TriggerException("Could not abort build");
        }
    }

    @Override
    public Collection<TopLevelItem> getItems() {
        Set<TopLevelItem> jobs = Sets.newHashSet();
        addJobsFromComponentSpecs(jobs);
        return jobs;
    }

    private void addJobsFromComponentSpecs(Set<TopLevelItem> jobs) {
        if (componentSpecs == null) {
            return;
        }
        for (ComponentSpec spec : componentSpecs) {
            try {
                WorkflowJob job = getWorkflowJob(spec.job);
                jobs.add(job);
            } catch (PipelineException e) {
                LOG.log(Level.SEVERE, "Failed to resolve WorkflowJob for configured job name: " + spec.job, e);
            }
        }
    }

    @Override
    public boolean contains(TopLevelItem item) {
        return getItems().contains(item);
    }

    private ItemGroup<? extends TopLevelItem> ownerItemGroup() {
        ViewGroup owner = getOwner();
        return owner == null ? null : owner.getItemGroup();
    }

    @Override
    protected void submit(StaplerRequest2 req) throws IOException, ServletException, FormException {
        req.bindJSON(this, req.getSubmittedForm());
        componentSpecs = req.bindJSONToList(ComponentSpec.class, req.getSubmittedForm().get("componentSpecs"));
    }

    private WorkflowJob getWorkflowJob(final String projectName) throws PipelineException {
        WorkflowJob job = ProjectUtil.getWorkflowJob(projectName, ownerItemGroup());
        if (job == null) {
            throw new PipelineException("Failed to resolve job with name: " + projectName);
        }
        return job;
    }

    @Extension
    public static class DescriptorImpl extends ViewDescriptor {
        public ListBoxModel doFillNoOfColumnsItems(@AncestorInPath ItemGroup<?> context) {
            ListBoxModel options = new ListBoxModel();
            options.add("1", "1");
            options.add("2", "2");
            options.add("3", "3");
            return options;
        }

        public ListBoxModel doFillProjectsItems(@AncestorInPath ItemGroup<?> context) {
            return ProjectUtil.fillAllProjects(context, WorkflowJob.class);
        }

        public ListBoxModel doFillNoOfPipelinesItems(@AncestorInPath ItemGroup<?> context) {
            ListBoxModel options = new ListBoxModel();
            for (int i = 1; i <= MAX_NO_OF_PIPELINES; i++) {
                String opt = String.valueOf(i);
                options.add(opt, opt);
            }
            return options;
        }

        public ListBoxModel doFillSortingItems() {
            DescriptorExtensionList<GenericComponentComparator, ComponentComparatorDescriptor> descriptors =
                    GenericComponentComparator.all();
            ListBoxModel options = new ListBoxModel();
            options.add("None", NONE_SORTER);
            for (ComponentComparatorDescriptor descriptor : descriptors) {
                options.add(descriptor.getDisplayName(), descriptor.getId());
            }
            return options;
        }

        public FormValidation doCheckUpdateInterval(@QueryParameter String value) {
            int valueAsInt;
            try {
                valueAsInt = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return FormValidation.error(e, "Value must be an integer");
            }
            if (valueAsInt <= 0) {
                return FormValidation.error("Value must be greater than 0");
            }
            return FormValidation.ok();
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Delivery Pipeline View for Jenkins Pipelines (deprecated)";
        }

        /** Existing views keep working, but new ones should be Delivery Pipeline views with Pipeline components. */
        @Override
        public boolean isInstantiable() {
            return false;
        }
    }

    public static class ComponentSpec implements Describable<ComponentSpec> {
        private String name;
        private String job;

        @DataBoundConstructor
        public ComponentSpec(String name, String job) {
            this.name = name;
            this.job = job;
        }

        public String getName() {
            return name;
        }

        public String getJob() {
            return job;
        }

        public void setJob(String job) {
            this.job = job;
        }

        @Extension
        public static class DescriptorImpl extends Descriptor<ComponentSpec> {

            @Nonnull
            @Override
            public String getDisplayName() {
                return "";
            }

            public ListBoxModel doFillJobItems(@AncestorInPath ItemGroup<?> context) {
                return ProjectUtil.fillAllProjects(context, WorkflowJob.class);
            }

            public FormValidation doCheckName(@QueryParameter String value) {
                if (value != null && !"".equals(value.trim())) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error("Please supply a title");
                }
            }
        }
    }

    private static Jenkins jenkins() {
        return JenkinsUtil.getInstance();
    }

}
