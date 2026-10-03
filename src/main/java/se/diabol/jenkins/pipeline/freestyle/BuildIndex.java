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
import jenkins.model.Jenkins;

/**
 * Relates builds of the projects in a chain to the build of the first project that started them, following the
 * upstream causes Jenkins records. Each project's builds are scanned newest first and only as far as a lookup needs,
 * so showing the latest pipelines stays cheap on jobs with a long history.
 */
final class BuildIndex {

    private static final int MAX_UPSTREAM_DEPTH = 100;

    private final AbstractProject<?, ?> first;
    private final Map<String, Scan> scans = new HashMap<>();

    private final class Scan {
        private final Iterator<? extends AbstractBuild<?, ?>> remaining;
        private final Map<Integer, AbstractBuild<?, ?>> byFirstBuild = new HashMap<>();
        private AbstractBuild<?, ?> newestFirstBuild;
        private boolean newestKnown;

        Scan(AbstractProject<?, ?> project) {
            remaining = project.getBuilds().iterator();
        }

        /** Scans one more build; false when there is none left. */
        boolean advance() {
            if (!remaining.hasNext()) {
                return false;
            }
            AbstractBuild<?, ?> build = remaining.next();
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
        this.first = first;
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
            if (!scan.advance()) {
                return null;
            }
        }
        return scan.byFirstBuild.get(firstBuild.getNumber());
    }

    /** The first-project build that the project's newest build in the chain belongs to, or null. */
    AbstractBuild<?, ?> newestFirstBuildReaching(AbstractProject<?, ?> project) {
        Scan scan = scans.computeIfAbsent(project.getFullName(), name -> new Scan(project));
        while (!scan.newestKnown) {
            if (!scan.advance()) {
                return null;
            }
        }
        return scan.newestFirstBuild;
    }

    /** The build of the first project that started the given build, following upstream causes; null if none. */
    AbstractBuild<?, ?> firstBuildOf(Run<?, ?> build) {
        Run<?, ?> current = build;
        for (int depth = 0; current != null && depth < MAX_UPSTREAM_DEPTH; depth++) {
            if (current.getParent().getFullName().equals(first.getFullName())) {
                return current instanceof AbstractBuild<?, ?> match ? match : null;
            }
            current = upstreamBuildOf(current);
        }
        return null;
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
