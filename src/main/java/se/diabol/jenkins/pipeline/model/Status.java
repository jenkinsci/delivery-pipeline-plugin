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

import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * The status of a task.
 *
 * <p>{@code timestamp} is when the build started (or was queued); {@code duration} is how long a finished build took;
 * for a running build {@code estimatedDuration} is the job's estimate. Elapsed time and progress of a running build are
 * derived from the clock while exporting, so a cached status stays correct without recomputation.
 *
 * @param type the state
 * @param timestamp epoch milliseconds of the start (finished and running builds) or queue entry, 0 when unknown
 * @param duration milliseconds of a finished build, 0 otherwise
 * @param estimatedDuration estimated milliseconds of a running build, or -1
 */
@ExportedBean(defaultVisibility = 100)
public record Status(StatusType type, long timestamp, long duration, long estimatedDuration) {

    public static Status idle() {
        return new Status(StatusType.IDLE, 0, 0, -1);
    }

    public static Status disabled() {
        return new Status(StatusType.DISABLED, 0, 0, -1);
    }

    public static Status queued(long since) {
        return new Status(StatusType.QUEUED, since, 0, -1);
    }

    public static Status running(long started, long estimatedDuration) {
        return new Status(StatusType.RUNNING, started, 0, estimatedDuration);
    }

    public static Status pausedPendingInput(long started, long estimatedDuration) {
        return new Status(StatusType.PAUSED_PENDING_INPUT, started, 0, estimatedDuration);
    }

    public static Status finished(StatusType type, long started, long duration) {
        if (!type.isFinished()) {
            throw new IllegalArgumentException(type + " is not a finished state");
        }
        return new Status(type, started, duration, -1);
    }

    @Exported(name = "type")
    public StatusType type() {
        return type;
    }

    /** Epoch milliseconds of the last activity, or null when nothing happened yet. */
    @Exported(name = "timestamp")
    public Long exportedTimestamp() {
        return timestamp > 0 ? timestamp : null;
    }

    /** Duration of a finished build, or the time a running build has been going on, in milliseconds. */
    @Exported(name = "duration")
    public long exportedDuration() {
        if (type.isActive()) {
            return Math.max(0, System.currentTimeMillis() - timestamp);
        }
        return duration;
    }

    /** Progress of a running build in percent, capped at 99 until it finishes; null when not running. */
    @Exported(name = "progress")
    public Integer progress() {
        if (!type.isActive()) {
            return null;
        }
        return progress(System.currentTimeMillis());
    }

    int progress(long now) {
        if (estimatedDuration <= 0) {
            return 99;
        }
        long elapsed = now - timestamp;
        int percent = (int) Math.round(100.0d * elapsed / estimatedDuration);
        return Math.max(0, Math.min(99, percent));
    }

    /** Whether this status counts as a failure when sorting components. */
    public boolean isFailed() {
        return type == StatusType.FAILED;
    }
}
