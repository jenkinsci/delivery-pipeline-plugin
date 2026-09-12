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
package se.diabol.jenkins.pipeline.model;

import java.util.List;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * One pipeline of the view with its recent instances.
 *
 * @param name the heading
 * @param index the 1-based position in the view's configuration, used to address paging requests
 * @param firstJob the job the pipeline starts with, or null when it could not be resolved
 * @param paging where the shown pipelines are among all of them, or null when the view does not page
 * @param pipelines the aggregated pipeline (first, when shown) and the run instances, newest first
 * @param error why the component could not be resolved, or null
 */
@ExportedBean(defaultVisibility = 100)
public record Component(@Exported String name, @Exported int index, @Exported JobRef firstJob, @Exported Paging paging,
                        @Exported List<Pipeline> pipelines, @Exported String error) {

    public Component {
        pipelines = List.copyOf(pipelines);
    }

    public static Component failed(String name, int index, String error) {
        return new Component(name, index, null, null, List.of(), error);
    }

    /** Epoch milliseconds of the most recent task activity, 0 when none. */
    @Exported
    public long lastActivity() {
        long result = 0;
        for (Pipeline pipeline : pipelines) {
            result = Math.max(result, pipeline.lastActivity());
        }
        return result;
    }

    /** Whether the newest pipeline instance has a failed task. */
    public boolean hasFailure() {
        for (Pipeline pipeline : pipelines) {
            if (!pipeline.aggregated()) {
                return pipeline.hasFailedTask();
            }
        }
        return false;
    }

    /** Whether the model contains a build that is still going on, which makes a cached copy go stale quickly. */
    public boolean isActive() {
        for (Pipeline pipeline : pipelines) {
            for (Stage stage : pipeline.stages()) {
                for (Task task : stage.tasks()) {
                    if (task.status().type().isActive() || task.status().type() == StatusType.QUEUED) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
