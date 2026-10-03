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

import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.tasks.BuildTrigger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import se.diabol.jenkins.pipeline.flow.DownstreamRuns;

/**
 * The Pipeline runs a build of a chained job triggered. The core build trigger ("Build other projects") starts
 * Pipeline jobs without listing them in the dependency graph, and a Pipeline job's own "Build after other projects
 * are built" trigger is not in it either, so a chain of jobs used to stop where a Pipeline job began. The runs are
 * found by their upstream cause among the recent runs of those jobs; one not started yet is reported without a
 * number while it waits in the queue for that build.
 */
@Extension
public class TriggeredRuns extends DownstreamRuns {

    /** How many runs of a triggered job are looked at, newest first, before giving up. */
    private static final int RUNS_SCANNED = 200;

    @Override
    public List<Started> startedBy(Run<?, ?> run) {
        if (!(run instanceof AbstractBuild<?, ?> build)) {
            return List.of();
        }
        List<Started> result = new ArrayList<>();
        for (Job<?, ?> job : triggeredJobs(build.getProject())) {
            result.addAll(runsOf(job, build));
        }
        return result;
    }

    /** The jobs the project triggers that the dependency graph leaves out: the ones that are not projects. */
    static List<Job<?, ?>> triggeredJobs(AbstractProject<?, ?> project) {
        Set<String> seen = new LinkedHashSet<>();
        List<Job<?, ?>> result = new ArrayList<>();
        for (BuildTrigger trigger : project.getPublishersList().getAll(BuildTrigger.class)) {
            for (Job<?, ?> job : trigger.getChildJobs(project)) {
                addTriggered(result, seen, job);
            }
        }
        for (Job<?, ?> job : ReverseTriggers.jobsTriggeredBy(project)) {
            addTriggered(result, seen, job);
        }
        return result;
    }

    /** Adds the job unless it is a project, which the chain of jobs shows already, or was added before. */
    public static void addTriggered(List<Job<?, ?>> into, Set<String> seen, Job<?, ?> job) {
        if (!(job instanceof AbstractProject) && seen.add(job.getFullName())) {
            into.add(job);
        }
    }

    /**
     * The runs of the job with an upstream cause pointing at the build, newest first, or failing that one entry
     * without a number when the job waits in the queue for that build.
     */
    public static List<Started> runsOf(Job<?, ?> job, Run<?, ?> upstream) {
        List<Started> result = new ArrayList<>();
        int scanned = 0;
        for (Run<?, ?> run : job.getBuilds()) {
            if (scanned++ >= RUNS_SCANNED || run.getTimeInMillis() < upstream.getTimeInMillis()) {
                break;
            }
            if (pointsTo(run.getCauses(), upstream)) {
                result.add(new Started(null, job.getFullName(), run.getNumber()));
            }
        }
        if (result.isEmpty() && job instanceof Queue.Task task) {
            for (Queue.Item item : Jenkins.get().getQueue().getItems(task)) {
                if (pointsTo(item.getCauses(), upstream)) {
                    result.add(new Started(null, job.getFullName(), null));
                    break;
                }
            }
        }
        return result;
    }

    private static boolean pointsTo(List<Cause> causes, Run<?, ?> upstream) {
        for (Cause cause : causes) {
            if (cause instanceof Cause.UpstreamCause up && up.pointsTo(upstream)) {
                return true;
            }
        }
        return false;
    }
}
