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

import hudson.model.Cause;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.User;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import jenkins.model.Jenkins;
import se.diabol.jenkins.pipeline.model.Trigger;

/** Describes what started a build. */
public final class Triggers {

    private Triggers() {
    }

    public static List<Trigger> of(List<Cause> causes) {
        LinkedHashSet<Trigger> result = new LinkedHashSet<>();
        for (Cause cause : causes) {
            result.add(of(cause));
        }
        return new ArrayList<>(result);
    }

    static Trigger of(Cause cause) {
        if (cause instanceof Cause.UserIdCause user) {
            return new Trigger(Trigger.MANUAL, "user " + displayNameOf(user.getUserId(), user.getUserName()));
        }
        if (cause instanceof Cause.RemoteCause) {
            return new Trigger(Trigger.REMOTE, "remote trigger");
        }
        if (cause instanceof Cause.UpstreamCause.DeeplyNestedUpstreamCause) {
            return new Trigger(Trigger.UPSTREAM, "upstream");
        }
        if (cause instanceof Cause.UpstreamCause upstream) {
            return new Trigger(Trigger.UPSTREAM, upstreamDescription(upstream));
        }
        if (cause instanceof SCMTrigger.SCMTriggerCause) {
            return new Trigger(Trigger.SCM, "SCM change");
        }
        if (cause instanceof TimerTrigger.TimerTriggerCause) {
            return new Trigger(Trigger.TIMER, "timer");
        }
        String description = cause.getShortDescription();
        return new Trigger(Trigger.UNKNOWN, description == null || description.isBlank() ? "unknown cause" : description);
    }

    private static String upstreamDescription(Cause.UpstreamCause cause) {
        Job<?, ?> job = Jenkins.get().getItemByFullName(cause.getUpstreamProject(), Job.class);
        if (job == null) {
            return "upstream project";
        }
        Run<?, ?> run = job.getBuildByNumber(cause.getUpstreamBuild());
        return "upstream project " + job.getDisplayName() + (run == null ? "" : " build " + run.getDisplayName());
    }

    private static String displayNameOf(String userId, String userName) {
        User user = userId == null ? null : User.getById(userId, false);
        if (user != null) {
            return user.getDisplayName();
        }
        return userName == null || userName.isBlank() ? "anonymous" : userName;
    }
}
