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
package se.diabol.jenkins.pipeline.integration.buildpipeline;

import au.com.centrumsystems.hudson.plugin.buildpipeline.extension.BuildCardExtension;
import au.com.centrumsystems.hudson.plugin.buildpipeline.extension.StandardBuildCard;
import au.com.centrumsystems.hudson.plugin.buildpipeline.trigger.BuildPipelineTrigger;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.TopLevelItem;
import hudson.tasks.Publisher;
import java.util.ArrayList;
import java.util.List;
import se.diabol.jenkins.pipeline.PipelineException;
import se.diabol.jenkins.pipeline.freestyle.ManualTriggerProvider;

/**
 * Manual steps as the Build Pipeline plugin defines them: a downstream job listed in an upstream job's "Build other
 * projects (manual step)" post-build action. Triggering one hands the upstream build's parameters down, exactly as
 * the Build Pipeline view does.
 */
@Extension(optional = true)
public class BuildPipelineManualTriggers extends ManualTriggerProvider {

    /** Makes the extension fail to load, and so be left out, when the Build Pipeline plugin is absent. */
    @SuppressWarnings("unused")
    private static final Class<?> REQUIRED = BuildPipelineTrigger.class;

    @Override
    public List<AbstractProject<?, ?>> manualUpstreamsOf(AbstractProject<?, ?> project) {
        List<AbstractProject<?, ?>> result = new ArrayList<>();
        for (AbstractProject<?, ?> upstream : project.getUpstreamProjects()) {
            if (triggersManually(upstream, project)) {
                result.add(upstream);
            }
        }
        return result;
    }

    @Override
    public boolean trigger(AbstractProject<?, ?> project, AbstractProject<?, ?> upstream, int upstreamBuild,
                           ItemGroup<? extends TopLevelItem> context) throws PipelineException {
        if (!triggersManually(upstream, project)) {
            return false;
        }
        if (upstream.getBuildByNumber(upstreamBuild) == null) {
            throw new PipelineException("Build " + upstreamBuild + " of " + upstream.getFullName() + " does not exist");
        }
        BuildCardExtension card = BuildCardExtension.all().get(StandardBuildCard.class);
        if (card == null) {
            card = new StandardBuildCard();
        }
        ItemGroup<?> group = context == null ? jenkins.model.Jenkins.get() : context;
        int number = card.triggerManualBuild(group, upstreamBuild, project.getRelativeNameFrom(group),
                upstream.getRelativeNameFrom(group));
        if (number < 0) {
            throw new PipelineException("The Build Pipeline plugin could not trigger " + project.getFullName());
        }
        return true;
    }

    static boolean triggersManually(AbstractProject<?, ?> upstream, AbstractProject<?, ?> project) {
        for (Publisher publisher : upstream.getPublishersList()) {
            if (publisher instanceof BuildPipelineTrigger trigger) {
                List<AbstractProject> targets = Items.fromNameList(upstream.getParent(),
                        trigger.getDownstreamProjectNames() == null ? "" : trigger.getDownstreamProjectNames(),
                        AbstractProject.class);
                if (targets.contains(project)) {
                    return true;
                }
            }
        }
        return false;
    }
}
