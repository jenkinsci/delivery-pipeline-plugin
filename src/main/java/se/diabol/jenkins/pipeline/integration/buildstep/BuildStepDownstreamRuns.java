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
package se.diabol.jenkins.pipeline.integration.buildstep;

import hudson.Extension;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.List;
import org.jenkinsci.plugins.workflow.support.steps.build.DownstreamBuildAction;
import se.diabol.jenkins.pipeline.flow.DownstreamRuns;

/**
 * The runs a Pipeline run started with the {@code build} step, from the record the Pipeline: Build Step plugin keeps
 * on the upstream run since its version 539 (December 2023). Older versions keep no record, and the runs they start
 * stay separate.
 */
@Extension(optional = true)
public class BuildStepDownstreamRuns extends DownstreamRuns {

    @Override
    public List<Started> startedBy(Run<?, ?> run) {
        DownstreamBuildAction action = run.getAction(DownstreamBuildAction.class);
        if (action == null) {
            return List.of();
        }
        List<Started> result = new ArrayList<>();
        for (DownstreamBuildAction.DownstreamBuild build : action.getDownstreamBuilds()) {
            result.add(new Started(build.getFlowNodeId(), build.getJobFullName(), build.getBuildNumber()));
        }
        return result;
    }
}
