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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.CauseAction;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.stapler.DataBoundConstructor;
import se.diabol.jenkins.pipeline.freestyle.BuildIndexAccess;

/**
 * Build wrapper of the first job of a pipeline that computes a version from a template and hands it down the chain
 * as the {@code PIPELINE_VERSION} variable, optionally making it the build's display name.
 */
public class PipelineVersionContributor extends BuildWrapper {

    public static final String VERSION_PARAMETER = "PIPELINE_VERSION";

    private static final Logger LOG = Logger.getLogger(PipelineVersionContributor.class.getName());

    private final String versionTemplate;
    private final boolean updateDisplayName;

    @DataBoundConstructor
    public PipelineVersionContributor(boolean updateDisplayName, String versionTemplate) {
        this.updateDisplayName = updateDisplayName;
        this.versionTemplate = versionTemplate;
    }

    public String getVersionTemplate() {
        return versionTemplate;
    }

    public boolean isUpdateDisplayName() {
        return updateDisplayName;
    }

    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener)
            throws IOException, InterruptedException {
        try {
            String version = TokenMacro.expandAll(build, listener, versionTemplate);
            setVersion(build, version);
            listener.getLogger().println("Creating version: " + version);
            if (updateDisplayName) {
                build.setDisplayName(version);
            }
        } catch (MacroEvaluationException e) {
            listener.getLogger().println("Error creating version: " + e.getMessage());
            LOG.log(Level.WARNING, "Error creating version", e);
        }
        return new Environment() {
        };
    }

    @CheckForNull
    public static String getVersion(Run<?, ?> build) {
        PipelineVersionAction action = build.getAction(PipelineVersionAction.class);
        return action == null ? null : action.getVersion();
    }

    static void setVersion(Run<?, ?> build, String version) {
        PipelineVersionAction action = build.getAction(PipelineVersionAction.class);
        if (action == null) {
            build.addAction(new PipelineVersionAction(version));
        } else {
            build.replaceAction(new PipelineVersionAction(version));
        }
    }

    /** Carries the version on a build; a {@link CauseAction} for the sake of builds recorded by 1.x. */
    public static class PipelineVersionAction extends CauseAction {
        private final String version;

        PipelineVersionAction(String version) {
            super(new java.util.ArrayList<>());
            this.version = version;
        }

        public String getVersion() {
            return version;
        }
    }

    /** Copies the version of the upstream build onto every build it triggers. */
    @Extension
    public static class Inheritor extends RunListener<Run<?, ?>> {
        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {
            Run<?, ?> upstream = BuildIndexAccess.upstreamBuildOf(run);
            if (upstream == null) {
                return;
            }
            String version = getVersion(upstream);
            if (version != null) {
                setVersion(run, version);
                listener.getLogger().println("Setting version to: " + version + " from upstream version");
            }
        }
    }

    /** Exposes the version as the {@code PIPELINE_VERSION} build variable. */
    @Extension
    public static class Variable extends hudson.model.BuildVariableContributor {
        @Override
        public void buildVariablesFor(AbstractBuild build, Map<String, String> variablesOut) {
            String version = getVersion(build);
            if (version != null) {
                variablesOut.put(VERSION_PARAMETER, version);
            }
        }
    }

    @Extension
    public static class DescriptorImpl extends BuildWrapperDescriptor {
        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Create Delivery Pipeline version";
        }
    }

    static Jenkins jenkins() {
        return Jenkins.get();
    }
}
