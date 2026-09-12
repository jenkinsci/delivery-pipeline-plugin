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
import hudson.model.AbstractProject;
import hudson.plugins.parameterizedtrigger.BlockableBuildTriggerConfig;
import hudson.plugins.parameterizedtrigger.SubProjectsAction;
import java.util.ArrayList;
import java.util.List;
import se.diabol.jenkins.pipeline.freestyle.DownstreamResolver;

/**
 * Jobs called as blocking sub-projects by the Parameterized Trigger plugin's "Trigger/call builds on other projects"
 * build step, which the core dependency graph does not list.
 */
@Extension(optional = true, ordinal = 200)
public class SubProjectDownstreamResolver extends DownstreamResolver {

    @SuppressWarnings("unused")
    private static final Class<?> REQUIRED = SubProjectsAction.class;

    @Override
    public List<AbstractProject<?, ?>> downstreamOf(AbstractProject<?, ?> project) {
        List<AbstractProject<?, ?>> result = new ArrayList<>();
        for (SubProjectsAction action : project.getActions(SubProjectsAction.class)) {
            for (BlockableBuildTriggerConfig config : action.getConfigs()) {
                for (AbstractProject<?, ?> target : config.getProjectList(project.getParent(), null)) {
                    result.add(target);
                }
            }
        }
        return result;
    }
}
