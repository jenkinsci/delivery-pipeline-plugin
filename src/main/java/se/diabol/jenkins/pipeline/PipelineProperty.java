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
import hudson.model.AbstractProject;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.util.FormValidation;
import java.util.TreeSet;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.export.Exported;

/**
 * Names a job's task and stage in the pipeline and gives its task a description template. Without it, the job's
 * display name is used for both.
 */
public class PipelineProperty extends JobProperty<AbstractProject<?, ?>> {

    private String taskName;
    private String stageName;
    private String descriptionTemplate;

    @DataBoundConstructor
    public PipelineProperty(String taskName, String stageName, String descriptionTemplate) {
        this.taskName = nullIfBlank(taskName);
        this.stageName = nullIfBlank(stageName);
        this.descriptionTemplate = nullIfBlank(descriptionTemplate);
    }

    @Exported
    public String getTaskName() {
        return taskName;
    }

    @Exported
    public String getStageName() {
        return stageName;
    }

    @Exported
    public String getDescriptionTemplate() {
        return descriptionTemplate;
    }

    public void setTaskName(String taskName) {
        this.taskName = nullIfBlank(taskName);
    }

    public void setStageName(String stageName) {
        this.stageName = nullIfBlank(stageName);
    }

    public void setDescriptionTemplate(String descriptionTemplate) {
        this.descriptionTemplate = nullIfBlank(descriptionTemplate);
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Extension
    public static final class DescriptorImpl extends JobPropertyDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Delivery Pipeline configuration";
        }

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return AbstractProject.class.isAssignableFrom(jobType);
        }

        /** Only whoever may configure the job gets to validate its form; the checks are cheap and reveal nothing. */
        static void checkConfigure(Item item) {
            if (item != null) {
                item.checkPermission(Item.CONFIGURE);
            } else {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            }
        }

        /** Stage names that other jobs the caller may read already use. */
        @SuppressWarnings("lgtm[jenkins/csrf]")
        public AutoCompletionCandidates doAutoCompleteStageName(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigure(item);
            AutoCompletionCandidates candidates = new AutoCompletionCandidates();
            if (value == null) {
                return candidates;
            }
            TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (AbstractProject<?, ?> project : Jenkins.get().getAllItems(AbstractProject.class)) {
                PipelineProperty property = project.getProperty(PipelineProperty.class);
                if (property != null && property.getStageName() != null
                        && property.getStageName().toLowerCase().startsWith(value.toLowerCase())) {
                    names.add(property.getStageName());
                }
            }
            names.forEach(candidates::add);
            return candidates;
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public FormValidation doCheckStageName(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigure(item);
            return checkName(value);
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public FormValidation doCheckTaskName(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigure(item);
            return checkName(value);
        }

        private static FormValidation checkName(String value) {
            if (value != null && !value.isEmpty() && value.isBlank()) {
                return FormValidation.error("The name must be empty or contain letters or digits");
            }
            return FormValidation.ok();
        }

        @Override
        public PipelineProperty newInstance(StaplerRequest2 req, JSONObject formData) throws FormException {
            if (formData == null || !formData.optBoolean("enabled", false)) {
                return null;
            }
            PipelineProperty property = new PipelineProperty(formData.optString("taskName"),
                    formData.optString("stageName"), formData.optString("descriptionTemplate"));
            return property.getTaskName() == null && property.getStageName() == null ? null : property;
        }
    }
}
