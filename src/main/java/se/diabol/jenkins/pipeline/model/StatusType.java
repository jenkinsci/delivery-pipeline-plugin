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

/** The state of a task, in the order the legend shows them. */
public enum StatusType {
    IDLE,
    QUEUED,
    RUNNING,
    PAUSED_PENDING_INPUT,
    SUCCESS,
    UNSTABLE,
    FAILED,
    CANCELLED,
    NOT_BUILT,
    DISABLED;

    /** Whether a build happened and came to an end. */
    public boolean isFinished() {
        return this == SUCCESS || this == UNSTABLE || this == FAILED || this == CANCELLED || this == NOT_BUILT;
    }

    /** Whether a build is going on right now. */
    public boolean isActive() {
        return this == RUNNING || this == PAUSED_PENDING_INPUT;
    }
}
