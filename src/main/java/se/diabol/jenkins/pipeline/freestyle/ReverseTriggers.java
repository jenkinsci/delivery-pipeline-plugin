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

import hudson.model.Items;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.Trigger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import jenkins.triggers.ReverseBuildTrigger;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

/**
 * Which Pipeline jobs a job triggers through their "Build after other projects are built" trigger, which lives on
 * the downstream job and is not in the dependency graph. The index over every Pipeline job is rebuilt at most
 * every thirty seconds, as the system, so that it does not depend on who asks.
 */
final class ReverseTriggers {

    private static final long REFRESH_MILLIS = 30_000;

    /** Full names of the Pipeline jobs triggered by each job, by the upstream job's full name. */
    private static volatile Map<String, List<String>> index = Map.of();
    private static volatile long builtAt;

    private ReverseTriggers() {
    }

    static List<Job<?, ?>> jobsTriggeredBy(Job<?, ?> upstream) {
        Map<String, List<String>> current = index;
        if (System.currentTimeMillis() - builtAt > REFRESH_MILLIS) {
            current = build();
            index = current;
            builtAt = System.currentTimeMillis();
        }
        List<Job<?, ?>> result = new ArrayList<>();
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            for (String name : current.getOrDefault(upstream.getFullName(), List.of())) {
                Job<?, ?> job = Jenkins.get().getItemByFullName(name, Job.class);
                if (job != null) {
                    result.add(job);
                }
            }
        }
        return result;
    }

    private static Map<String, List<String>> build() {
        Map<String, List<String>> result = new HashMap<>();
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            for (WorkflowJob job : Jenkins.get().getAllItems(WorkflowJob.class)) {
                for (Trigger<?> trigger : job.getTriggers().values()) {
                    if (trigger instanceof ReverseBuildTrigger reverse) {
                        for (Job<?, ?> upstream : Items.fromNameList(job.getParent(), reverse.getUpstreamProjects(),
                                Job.class)) {
                            result.computeIfAbsent(upstream.getFullName(), key -> new ArrayList<>()).add(job.getFullName());
                        }
                    }
                }
            }
        }
        return result;
    }
}
