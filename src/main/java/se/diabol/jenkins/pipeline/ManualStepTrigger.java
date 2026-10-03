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
package se.diabol.jenkins.pipeline;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.AutoCompletionCandidates;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.DependencyGraph;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Project;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.listeners.ItemListener;
import hudson.model.queue.QueueTaskFuture;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.DependencyDeclarer;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import se.diabol.jenkins.pipeline.freestyle.ManualTriggerProvider;

/**
 * Post-build action that makes other jobs manual steps of the pipeline. They count as downstream of this job, so the
 * view shows them in the chain, but nothing starts them automatically: a person does, with the button on the task.
 * The build that is started carries the upstream build's parameters, with the downstream job's defaults for the
 * rest, and an upstream cause, so it belongs to the same pipeline instance.
 *
 * <p>This is the plugin's own replacement for the Build Pipeline plugin's manual trigger, which is still recognised
 * when that plugin is installed.
 */
public class ManualStepTrigger extends Notifier implements DependencyDeclarer {

    private static final Logger LOG = Logger.getLogger(ManualStepTrigger.class.getName());

    private String downstreamProjectNames;

    @DataBoundConstructor
    public ManualStepTrigger(String downstreamProjectNames) {
        this.downstreamProjectNames = Util.fixNull(downstreamProjectNames).trim();
    }

    /** Comma-separated names of the jobs, relative to this job's folder. */
    public String getDownstreamProjectNames() {
        return downstreamProjectNames;
    }

    /** The jobs the names resolve to in the given folder; names that resolve to nothing are left out. */
    public List<AbstractProject<?, ?>> getDownstreamProjects(ItemGroup<?> context) {
        List<AbstractProject<?, ?>> result = new ArrayList<>();
        for (AbstractProject<?, ?> project : Items.fromNameList(context, downstreamProjectNames, AbstractProject.class)) {
            result.add(project);
        }
        return result;
    }

    boolean triggers(AbstractProject<?, ?> owner, AbstractProject<?, ?> project) {
        for (AbstractProject<?, ?> downstream : getDownstreamProjects(owner.getParent())) {
            if (downstream.getFullName().equals(project.getFullName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
        if (!downstreamProjectNames.isEmpty()) {
            listener.getLogger().println("Manual step: " + downstreamProjectNames
                    + " can now be started from the Delivery Pipeline view");
        }
        return true;
    }

    /** Lists the manual steps as downstream jobs without ever triggering them. */
    @Override
    public void buildDependencyGraph(AbstractProject owner, DependencyGraph graph) {
        for (AbstractProject<?, ?> downstream : getDownstreamProjects(owner.getParent())) {
            graph.addDependency(new DependencyGraph.Dependency(owner, downstream) {
                @Override
                public boolean shouldTriggerBuild(AbstractBuild build, TaskListener listener, List<Action> actions) {
                    return false;
                }
            });
        }
    }

    /**
     * Starts a build of the project for the given upstream build: the upstream build's parameters where the project
     * defines them, the project's defaults for the rest, and an upstream cause next to the user's.
     *
     * @return the queued build, or null when the project cannot be built
     */
    static QueueTaskFuture<?> start(AbstractProject<?, ?> project, AbstractBuild<?, ?> upstreamBuild) {
        List<ParameterValue> values = new ArrayList<>();
        ParametersDefinitionProperty definitions = project.getProperty(ParametersDefinitionProperty.class);
        ParametersAction upstreamParameters = upstreamBuild.getAction(ParametersAction.class);
        if (definitions != null) {
            for (ParameterDefinition definition : definitions.getParameterDefinitions()) {
                ParameterValue value = upstreamParameters == null ? null : upstreamParameters.getParameter(definition.getName());
                if (value == null) {
                    value = definition.getDefaultParameterValue();
                }
                if (value != null) {
                    values.add(value);
                }
            }
        }
        List<Action> actions = new ArrayList<>();
        actions.add(new CauseAction(new Cause.UpstreamCause(upstreamBuild), new Cause.UserIdCause()));
        if (!values.isEmpty()) {
            actions.add(new ParametersAction(values));
        }
        return project.scheduleBuild2(project.getQuietPeriod(), null, actions);
    }

    @Extension
    @Symbol("deliveryPipelineManualStep")
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Delivery Pipeline manual step";
        }

        /** Only whoever may configure the job gets to validate its form; the checks are cheap and reveal nothing. */
        static void checkConfigure(Item item) {
            if (item != null) {
                item.checkPermission(Item.CONFIGURE);
            } else {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            }
        }

        /** Names of the jobs the caller may read, as the candidates are filtered by permission. */
        @SuppressWarnings("lgtm[jenkins/csrf]")
        public AutoCompletionCandidates doAutoCompleteDownstreamProjectNames(@AncestorInPath Item item,
                                                                             @AncestorInPath ItemGroup<?> context,
                                                                             @QueryParameter String value) {
            checkConfigure(item);
            return AutoCompletionCandidates.ofJobNames(AbstractProject.class, value, context);
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public FormValidation doCheckDownstreamProjectNames(@AncestorInPath AbstractProject<?, ?> project,
                                                            @QueryParameter String value) {
            checkConfigure(project);
            if (project == null) {
                return FormValidation.ok();
            }
            ItemGroup<?> context = project.getParent();
            for (String name : Util.fixNull(value).split(",")) {
                String trimmed = name.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                Item item = Jenkins.get().getItem(trimmed, context, Item.class);
                if (item == null) {
                    AbstractProject<?, ?> nearest = AbstractProject.findNearest(trimmed, context);
                    return FormValidation.error("No such job: " + trimmed
                            + (nearest == null ? "" : ". Did you mean " + nearest.getRelativeNameFrom(context) + "?"));
                }
                if (!(item instanceof AbstractProject)) {
                    return FormValidation.error(trimmed + " is not a job that can be a manual step");
                }
                if (item.getFullName().equals(project.getFullName())) {
                    return FormValidation.error("A job cannot be its own manual step");
                }
            }
            return FormValidation.ok();
        }
    }

    /** Recognises the manual steps this action defines and performs them. */
    @Extension
    public static class Provider extends ManualTriggerProvider {

        @Override
        public List<AbstractProject<?, ?>> manualUpstreamsOf(AbstractProject<?, ?> project) {
            List<AbstractProject<?, ?>> result = new ArrayList<>();
            for (AbstractProject<?, ?> upstream : project.getUpstreamProjects()) {
                ManualStepTrigger trigger = upstream.getPublishersList().get(ManualStepTrigger.class);
                if (trigger != null && trigger.triggers(upstream, project)) {
                    result.add(upstream);
                }
            }
            return result;
        }

        @Override
        public boolean trigger(AbstractProject<?, ?> project, AbstractProject<?, ?> upstream, int upstreamBuild,
                               ItemGroup<? extends TopLevelItem> context) throws PipelineException {
            ManualStepTrigger trigger = upstream.getPublishersList().get(ManualStepTrigger.class);
            if (trigger == null || !trigger.triggers(upstream, project)) {
                return false;
            }
            AbstractBuild<?, ?> build = upstream.getBuildByNumber(upstreamBuild);
            if (build == null) {
                throw new PipelineException("Build " + upstreamBuild + " of " + upstream.getFullName() + " does not exist");
            }
            if (start(project, build) == null) {
                throw new PipelineException("Could not schedule a build of " + project.getFullName());
            }
            return true;
        }
    }

    /** Keeps the names current when a job is renamed or moved, as the core build trigger does. */
    @Extension
    public static class RenameListener extends ItemListener {
        @Override
        public void onLocationChanged(Item item, String oldFullName, String newFullName) {
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                for (Project<?, ?> project : Jenkins.get().allItems(Project.class)) {
                    ManualStepTrigger trigger = project.getPublishersList().get(ManualStepTrigger.class);
                    if (trigger == null) {
                        continue;
                    }
                    String renamed = Items.computeRelativeNamesAfterRenaming(oldFullName, newFullName,
                            trigger.downstreamProjectNames, project.getParent());
                    if (!renamed.equals(trigger.downstreamProjectNames)) {
                        trigger.downstreamProjectNames = renamed;
                        try {
                            project.save();
                        } catch (IOException e) {
                            LOG.log(Level.WARNING, "Could not save " + project.getFullName() + " after renaming "
                                    + oldFullName + " to " + newFullName, e);
                        }
                    }
                }
            }
        }
    }
}
