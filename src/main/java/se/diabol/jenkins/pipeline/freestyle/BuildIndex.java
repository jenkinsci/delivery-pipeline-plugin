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
import hudson.model.Cause;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;

/**
 * Relates builds of the projects in a chain to the build of the first project that started them, following the
 * upstream causes Jenkins records. Each project's builds are scanned newest first and only as far as a lookup needs,
 * so showing the latest pipelines stays cheap on jobs with a long history.
 *
 * <p>Three things keep that walk from becoming the cost of the whole board on a long chain. Every hop loads a build,
 * so a chain of n jobs used to cost O(n) loads for every build of every job, with almost all of the work repeated:
 *
 * <ul>
 * <li>{@link #firstBuildOf} remembers its answer for every build it passes through, not just the one it was asked
 * about. The walks up a linear chain are nested prefixes of one another, so the second one is almost free.</li>
 * <li>A cause naming a job outside the chain ends the walk before the upstream build is loaded. A job in the chain
 * also has builds of its own that the view does not show, and those used to be followed to their roots.</li>
 * <li>Both scans stop at a time bound rather than draining the history. Builds come newest first and a build cannot
 * have been started by a pipeline that began after it did, so a job that did not take part in the pipeline being
 * looked up - which on a fail-stop chain is every job behind a failure - is answered without reading the rest.</li>
 * </ul>
 */
final class BuildIndex {

    private static final int MAX_UPSTREAM_DEPTH = 100;

    private final AbstractProject<?, ?> first;
    /** The full names of the jobs the chain shows, or null when the walk should follow every cause. */
    private final Set<String> chainJobs;
    private final Map<String, Scan> scans = new HashMap<>();
    /** What {@link #firstBuildOf} answered for a build, including "none", so a shared walk is made once. */
    private final Map<String, AbstractBuild<?, ?>> firstBuilds = new HashMap<>();
    private long oldestFirstBuildMillis;
    private boolean oldestFirstBuildKnown;

    private final class Scan {
        private final Iterator<? extends AbstractBuild<?, ?>> remaining;
        private final Map<Integer, AbstractBuild<?, ?>> byFirstBuild = new HashMap<>();
        private AbstractBuild<?, ?> newestFirstBuild;
        private boolean newestKnown;
        /** When the oldest build scanned so far started; builds come newest first. */
        private long oldestScanned = Long.MAX_VALUE;

        Scan(AbstractProject<?, ?> project) {
            remaining = project.getBuilds().iterator();
        }

        /** Scans one more build; false when there is none left. */
        boolean advance() {
            if (!remaining.hasNext()) {
                return false;
            }
            AbstractBuild<?, ?> build = remaining.next();
            oldestScanned = build.getTimeInMillis();
            AbstractBuild<?, ?> start = firstBuildOf(build);
            if (start != null) {
                byFirstBuild.putIfAbsent(start.getNumber(), build);
                if (!newestKnown) {
                    newestFirstBuild = start;
                    newestKnown = true;
                }
            }
            return true;
        }
    }

    BuildIndex(AbstractProject<?, ?> first) {
        this(first, null);
    }

    /**
     * @param chainJobs the full names of the jobs the chain shows. The chain is the whole downstream closure of the
     *     first project, so a cause chain that leaves it cannot come back to the first project, and the walk can end
     *     there without loading the build. Null follows every cause, as before.
     */
    BuildIndex(AbstractProject<?, ?> first, Set<String> chainJobs) {
        this.first = first;
        this.chainJobs = chainJobs == null ? null : Set.copyOf(chainJobs);
    }

    AbstractProject<?, ?> first() {
        return first;
    }

    /** The newest build of the project that was started, directly or through other jobs, by the given first build. */
    AbstractBuild<?, ?> buildOf(AbstractProject<?, ?> project, AbstractBuild<?, ?> firstBuild) {
        if (firstBuild == null) {
            return null;
        }
        if (project.getFullName().equals(first.getFullName())) {
            return firstBuild;
        }
        Scan scan = scans.computeIfAbsent(project.getFullName(), name -> new Scan(project));
        while (!scan.byFirstBuild.containsKey(firstBuild.getNumber())) {
            // Past the moment the pipeline started there is nothing of it left to find. The scan is left where it
            // is rather than closed, so a lookup for an older pipeline carries on from here.
            if (scan.oldestScanned < firstBuild.getTimeInMillis() || !scan.advance()) {
                return null;
            }
        }
        return scan.byFirstBuild.get(firstBuild.getNumber());
    }

    /** The first-project build that the project's newest build in the chain belongs to, or null. */
    AbstractBuild<?, ?> newestFirstBuildReaching(AbstractProject<?, ?> project) {
        Scan scan = scans.computeIfAbsent(project.getFullName(), name -> new Scan(project));
        while (!scan.newestKnown) {
            // Nothing older than the oldest build of the first project that still exists can belong to any of its
            // pipelines: an older build of the first project would have to be found to attribute it to, and it is
            // gone. So a project that never took part answers here instead of reading its whole history.
            if (scan.oldestScanned < oldestFirstBuildMillis() || !scan.advance()) {
                return null;
            }
        }
        return scan.newestFirstBuild;
    }

    /**
     * The build of the first project that started the given build, following upstream causes; null if none.
     *
     * <p>Every build the walk passes through gets the same answer, and it is remembered for all of them. On a linear
     * chain that turns one walk per build of every job into one walk for the whole chain.
     */
    AbstractBuild<?, ?> firstBuildOf(Run<?, ?> build) {
        List<String> walked = new ArrayList<>();
        AbstractBuild<?, ?> found = null;
        Run<?, ?> current = build;
        for (int depth = 0; current != null && depth < MAX_UPSTREAM_DEPTH; depth++) {
            String key = keyOf(current);
            if (firstBuilds.containsKey(key)) {
                found = firstBuilds.get(key);
                break;
            }
            if (current.getParent().getFullName().equals(first.getFullName())) {
                found = current instanceof AbstractBuild<?, ?> match ? match : null;
                firstBuilds.put(key, found);
                break;
            }
            walked.add(key);
            current = upstreamBuildInChain(current);
        }
        for (String key : walked) {
            firstBuilds.put(key, found);
        }
        return found;
    }

    private static String keyOf(Run<?, ?> build) {
        return build.getParent().getFullName() + "#" + build.getNumber();
    }

    /** When the oldest build of the first project that still exists started, or {@link Long#MAX_VALUE} if none. */
    private long oldestFirstBuildMillis() {
        if (!oldestFirstBuildKnown) {
            Run<?, ?> oldest = first.getFirstBuild();
            oldestFirstBuildMillis = oldest == null ? Long.MAX_VALUE : oldest.getTimeInMillis();
            oldestFirstBuildKnown = true;
        }
        return oldestFirstBuildMillis;
    }

    /**
     * The same link {@link #upstreamBuildOf} follows, except that a cause naming a job outside the chain ends the
     * walk without loading its build. Every job in the chain also has builds triggered by things the view does not
     * show - a cleaner's own schedule, a manual run - and following those to their roots was most of the work.
     */
    private Run<?, ?> upstreamBuildInChain(Run<?, ?> build) {
        for (Cause cause : build.getCauses()) {
            if (cause instanceof Cause.UpstreamCause upstream) {
                if (!inChain(upstream.getUpstreamProject())) {
                    return null;
                }
                Job<?, ?> job = Jenkins.get().getItemByFullName(upstream.getUpstreamProject(), Job.class);
                return job == null ? null : job.getBuildByNumber(upstream.getUpstreamBuild());
            }
        }
        return null;
    }

    /** Whether the job belongs to the chain, counting a matrix configuration or a promotion as its owner. */
    private boolean inChain(String jobFullName) {
        if (chainJobs == null || chainJobs.contains(jobFullName)) {
            return true;
        }
        for (String job : chainJobs) {
            if (jobFullName.startsWith(job + "/") || job.startsWith(jobFullName + "/")) {
                return true;
            }
        }
        return false;
    }

    static Run<?, ?> upstreamBuildOf(Run<?, ?> build) {
        for (Cause cause : build.getCauses()) {
            if (cause instanceof Cause.UpstreamCause upstream) {
                Job<?, ?> job = Jenkins.get().getItemByFullName(upstream.getUpstreamProject(), Job.class);
                return job == null ? null : job.getBuildByNumber(upstream.getUpstreamBuild());
            }
        }
        return null;
    }

    /**
     * Whether the project is queued for the pipeline of the given first build: queued at all when there is no first
     * build, otherwise queued by an upstream build that belongs to that pipeline.
     */
    boolean isQueued(AbstractProject<?, ?> project, AbstractBuild<?, ?> firstBuild) {
        if (!project.isInQueue()) {
            return false;
        }
        if (firstBuild == null) {
            return true;
        }
        Queue.Item item = project.getQueueItem();
        if (item == null) {
            return false;
        }
        List<Cause.UpstreamCause> causes = new ArrayList<>();
        for (Cause cause : item.getCauses()) {
            if (cause instanceof Cause.UpstreamCause upstream) {
                causes.add(upstream);
            }
        }
        for (AbstractProject<?, ?> upstreamProject : project.getUpstreamProjects()) {
            AbstractBuild<?, ?> upstreamBuild = buildOf(upstreamProject, firstBuild);
            if (upstreamBuild == null) {
                continue;
            }
            for (Cause.UpstreamCause cause : causes) {
                if (cause.getUpstreamBuild() == upstreamBuild.getNumber()
                        && upstreamProject.getFullName().equals(cause.getUpstreamProject())) {
                    return true;
                }
            }
        }
        return false;
    }
}
