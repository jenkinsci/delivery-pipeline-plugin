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

import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Result;
import java.util.List;
import se.diabol.jenkins.pipeline.model.ManualStep;

/** Works out whether a manually triggered task can be triggered for a pipeline instance right now. */
final class ManualSteps {

    private ManualSteps() {
    }

    /**
     * @param project the task's project
     * @param build the project's build in the pipeline instance, or null
     * @param firstBuild the build that started the pipeline instance, or null for a queued one
     * @param queued whether the project is queued for the instance
     * @return the manual step, or null when nothing triggers the project manually
     */
    static ManualStep of(BuildIndex index, AbstractProject<?, ?> project, AbstractBuild<?, ?> build,
                         AbstractBuild<?, ?> firstBuild, boolean queued) {
        List<AbstractProject<?, ?>> upstreams = ManualTriggerProvider.manualUpstreams(project);
        if (upstreams.isEmpty()) {
            return null;
        }
        for (int i = 0; i < upstreams.size(); i++) {
            AbstractProject<?, ?> upstream = upstreams.get(i);
            AbstractBuild<?, ?> upstreamBuild = index.buildOf(upstream, firstBuild);
            if (upstreamBuild != null && !upstreamBuild.isBuilding() && !queued) {
                if (build == null) {
                    Result result = upstreamBuild.getResult();
                    boolean enabled = result != null && !result.isWorseThan(Result.UNSTABLE);
                    return new ManualStep(upstream.getFullName(), upstreamBuild.getNumber(), enabled);
                }
                Result result = build.getResult();
                if (!build.isBuilding() && result != null && result.isWorseThan(Result.UNSTABLE)) {
                    // the task failed or was cancelled: it may be triggered again
                    return new ManualStep(upstream.getFullName(), upstreamBuild.getNumber(), true);
                }
            }
            if (i == upstreams.size() - 1) {
                return new ManualStep(upstream.getFullName(), null, false);
            }
        }
        return null;
    }
}
