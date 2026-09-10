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
package se.diabol.jenkins.pipeline.flow;

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the runs a run started, as the {@code build} step of Pipeline does. The plugin that provides such a step
 * records them on the run that started them; an integration reads that record when the plugin is installed.
 */
public abstract class DownstreamRuns implements ExtensionPoint {

    /**
     * A run started by another.
     *
     * @param flowNodeId the flow node of the step that started it, or null when unknown
     * @param jobFullName the full name of the job started
     * @param buildNumber the build started, or null while it waits in the queue or when it was cancelled before it
     *                    started
     */
    public record Started(String flowNodeId, String jobFullName, Integer buildNumber) {
    }

    /** The runs the given run started, in the order it started them. */
    public abstract List<Started> startedBy(Run<?, ?> run);

    public static ExtensionList<DownstreamRuns> all() {
        return ExtensionList.lookup(DownstreamRuns.class);
    }

    /** The runs started by the run, as every integration reports them. */
    public static List<Started> of(Run<?, ?> run) {
        List<Started> result = new ArrayList<>();
        for (DownstreamRuns source : all()) {
            result.addAll(source.startedBy(run));
        }
        return result;
    }
}
