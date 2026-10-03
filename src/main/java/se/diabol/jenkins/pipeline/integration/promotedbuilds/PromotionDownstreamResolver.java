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
package se.diabol.jenkins.pipeline.integration.promotedbuilds;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.DependencyGraph;
import hudson.model.Items;
import hudson.plugins.promoted_builds.JobPropertyImpl;
import hudson.plugins.promoted_builds.PromotionProcess;
import hudson.tasks.BuildStep;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.DependencyDeclarer;
import se.diabol.jenkins.pipeline.freestyle.DownstreamResolver;

/** Jobs that a promotion of a job triggers, so that promotion-driven chains show as pipelines too. */
@Extension(optional = true, ordinal = 150)
public class PromotionDownstreamResolver extends DownstreamResolver {

    @SuppressWarnings("unused")
    private static final Class<?> REQUIRED = PromotionProcess.class;

    @Override
    public List<AbstractProject<?, ?>> downstreamOf(AbstractProject<?, ?> project) {
        List<AbstractProject<?, ?>> result = new ArrayList<>();
        JobPropertyImpl property = project.getProperty(JobPropertyImpl.class);
        if (property == null) {
            return result;
        }
        for (PromotionProcess process : property.getActiveItems()) {
            DependencyGraph graph = new DependencyGraph();
            for (BuildStep step : process.getBuildSteps()) {
                if (step instanceof DependencyDeclarer declarer) {
                    declarer.buildDependencyGraph(process, graph);
                }
                if (step instanceof hudson.tasks.BuildTrigger trigger) {
                    for (AbstractProject<?, ?> target : Items.fromNameList(project.getParent(),
                            trigger.getChildProjectsValue(), AbstractProject.class)) {
                        result.add(target);
                    }
                }
            }
            for (AbstractProject<?, ?> target : graph.getDownstream(process)) {
                result.add(target);
            }
        }
        return result;
    }
}
