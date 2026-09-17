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

import hudson.model.Item;
import hudson.model.Job;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.springframework.security.access.AccessDeniedException;

/**
 * What marks a component as the consolidated pipeline of its view, the parent that runs the view's pipelines a few
 * at a time, and tells where its run stands. The component's one pipeline has a stage per batch and a task per
 * pipeline of the view.
 *
 * @param state {@link #IDLE} when the view never ran its pipelines, otherwise the state of the run going on or of
 *              the last one: {@link #RUNNING} a batch, {@link #SLEEPING} between two batches, {@link #STOPPING}
 *              when no further batch will start and the pipelines of the current one are still going,
 *              {@link #FINISHED} or {@link #STOPPED}
 * @param number the number of that run within its view, 0 when there is none
 * @param batch the 1-based batch the run is at or ended with, 0 when there is none
 * @param batches how many batches the run has, or a run started now would have
 * @param finished how many pipelines of the run came to an end, whatever the outcome
 * @param failed how many of them ended failed or aborted
 * @param total how many pipelines the run has, or a run started now would have
 * @param concurrentPipelines how many pipelines a batch holds
 * @param sleepSeconds the pause between the end of a batch and the start of the next
 * @param nextBatchAt epoch milliseconds at which the next batch starts, while sleeping; null otherwise
 * @param startedAt epoch milliseconds the run started at, or null
 * @param finishedAt epoch milliseconds the run ended at, or null
 * @param startedBy the display name of the user who started the run, or null
 * @param stoppedBy the display name of the user who stopped the run, or null
 * @param jobs the full names of the first jobs a run started now would build, which decide who may start one
 */
@ExportedBean(defaultVisibility = 100)
public record Consolidated(@Exported String state, @Exported int number, @Exported int batch, @Exported int batches,
                           @Exported int finished, @Exported int failed, @Exported int total,
                           @Exported int concurrentPipelines, @Exported int sleepSeconds,
                           @Exported Long nextBatchAt, @Exported Long startedAt, @Exported Long finishedAt,
                           @Exported String startedBy, @Exported String stoppedBy, List<String> jobs) {

    public static final String IDLE = "IDLE";
    public static final String RUNNING = "RUNNING";
    public static final String SLEEPING = "SLEEPING";
    public static final String STOPPING = "STOPPING";
    public static final String FINISHED = "FINISHED";
    public static final String STOPPED = "STOPPED";

    public Consolidated {
        jobs = List.copyOf(jobs);
    }

    /** Whether a run is going on: no other can start, and this one can be stopped. */
    @Exported
    public boolean active() {
        return RUNNING.equals(state) || SLEEPING.equals(state) || STOPPING.equals(state);
    }

    /**
     * Whether the current user may start and stop a run: there is a pipeline to run and the user may build the first
     * job of every one. Computed while exporting, like {@link Permissions}, never cached.
     */
    @Exported
    public boolean permitted() {
        return !jobs.isEmpty() && mayBuildAll(jobs);
    }

    /** Whether the current user may build every one of the jobs; a job that is gone or hidden counts as a no. */
    public static boolean mayBuildAll(List<String> jobFullNames) {
        for (String fullName : jobFullNames) {
            try {
                Job<?, ?> job = Jenkins.get().getItemByFullName(fullName, Job.class);
                if (job == null || !job.hasPermission(Item.BUILD)) {
                    return false;
                }
            } catch (AccessDeniedException e) {
                return false;  // discoverable but not readable
            }
        }
        return true;
    }
}
