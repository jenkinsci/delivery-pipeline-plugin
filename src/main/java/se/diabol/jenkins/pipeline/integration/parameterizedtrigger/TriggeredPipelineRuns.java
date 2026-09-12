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
package se.diabol.jenkins.pipeline.integration.parameterizedtrigger;

import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.Run;
import hudson.plugins.parameterizedtrigger.BlockableBuildTriggerConfig;
import hudson.plugins.parameterizedtrigger.BuildTrigger;
import hudson.plugins.parameterizedtrigger.BuildTriggerConfig;
import hudson.plugins.parameterizedtrigger.SubProjectsAction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import se.diabol.jenkins.pipeline.flow.DownstreamRuns;
import se.diabol.jenkins.pipeline.freestyle.TriggeredRuns;

/**
 * The Pipeline runs a build triggered through the Parameterized Trigger plugin, as a post-build trigger or a
 * blocking sub-project call; the projects it triggers are part of the chain of jobs already.
 */
@Extension(optional = true)
public class TriggeredPipelineRuns extends DownstreamRuns {

    @SuppressWarnings("unused")
    private static final Class<?> REQUIRED = BuildTriggerConfig.class;

    @Override
    public List<Started> startedBy(Run<?, ?> run) {
        if (!(run instanceof AbstractBuild<?, ?> build)) {
            return List.of();
        }
        AbstractProject<?, ?> project = build.getProject();
        Set<String> seen = new LinkedHashSet<>();
        List<Job<?, ?>> jobs = new ArrayList<>();
        for (BuildTrigger trigger : project.getPublishersList().getAll(BuildTrigger.class)) {
            for (BuildTriggerConfig config : trigger.getConfigs()) {
                for (Job<?, ?> job : config.getJobs(project.getParent(), null)) {
                    TriggeredRuns.addTriggered(jobs, seen, job);
                }
            }
        }
        for (SubProjectsAction action : project.getActions(SubProjectsAction.class)) {
            for (BlockableBuildTriggerConfig config : action.getConfigs()) {
                for (Job<?, ?> job : config.getJobs(project.getParent(), null)) {
                    TriggeredRuns.addTriggered(jobs, seen, job);
                }
            }
        }
        List<Started> result = new ArrayList<>();
        for (Job<?, ?> job : jobs) {
            result.addAll(TriggeredRuns.runsOf(job, build));
        }
        return result;
    }
}
