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
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import se.diabol.jenkins.pipeline.model.Status;
import se.diabol.jenkins.pipeline.model.StatusType;

/** Turns builds into statuses. */
public final class Statuses {

    private Statuses() {
    }

    static Status of(AbstractProject<?, ?> project, AbstractBuild<?, ?> build, boolean queued) {
        if (build == null) {
            if (queued) {
                Queue.Item item = project.getQueueItem();
                return Status.queued(item == null ? 0 : item.getInQueueSince());
            }
            return project.isDisabled() ? Status.disabled() : Status.idle();
        }
        return of(build);
    }

    /** The status of a build that exists. */
    public static Status of(Run<?, ?> build) {
        if (build.isBuilding()) {
            return Status.running(build.getTimeInMillis(), build.getEstimatedDuration());
        }
        return Status.finished(typeOf(build.getResult()), build.getTimeInMillis(), build.getDuration());
    }

    public static StatusType typeOf(Result result) {
        if (result == null) {
            return StatusType.NOT_BUILT;
        }
        if (result == Result.SUCCESS) {
            return StatusType.SUCCESS;
        }
        if (result == Result.UNSTABLE) {
            return StatusType.UNSTABLE;
        }
        if (result == Result.FAILURE) {
            return StatusType.FAILED;
        }
        if (result == Result.ABORTED) {
            return StatusType.CANCELLED;
        }
        return StatusType.NOT_BUILT;
    }
}
