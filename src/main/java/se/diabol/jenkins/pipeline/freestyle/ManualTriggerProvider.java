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
package se.diabol.jenkins.pipeline.freestyle;

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.AbstractProject;
import hudson.model.ItemGroup;
import hudson.model.TopLevelItem;
import java.util.ArrayList;
import java.util.List;
import se.diabol.jenkins.pipeline.PipelineException;

/**
 * Knows which projects are triggered by a person rather than automatically, and how to perform such a trigger. The
 * Build Pipeline plugin's manual trigger is the one implementation that ships with this plugin.
 */
public abstract class ManualTriggerProvider implements ExtensionPoint {

    /** The upstream projects whose manual trigger leads to the given project, in configuration order. */
    public abstract List<AbstractProject<?, ?>> manualUpstreamsOf(AbstractProject<?, ?> project);

    /**
     * Performs the manual trigger of the project, continuing from the given build of the upstream project.
     *
     * @return false when this provider does not own the relation between the two projects
     */
    public abstract boolean trigger(AbstractProject<?, ?> project, AbstractProject<?, ?> upstream, int upstreamBuild,
                                    ItemGroup<? extends TopLevelItem> context) throws PipelineException;

    public static ExtensionList<ManualTriggerProvider> all() {
        return ExtensionList.lookup(ManualTriggerProvider.class);
    }

    public static List<AbstractProject<?, ?>> manualUpstreams(AbstractProject<?, ?> project) {
        List<AbstractProject<?, ?>> result = new ArrayList<>();
        for (ManualTriggerProvider provider : all()) {
            result.addAll(provider.manualUpstreamsOf(project));
        }
        return result;
    }
}
