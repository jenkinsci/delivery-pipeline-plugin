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
package se.diabol.jenkins.pipeline.source;

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.TopLevelItem;
import java.util.Collection;
import se.diabol.jenkins.pipeline.PipelineException;
import se.diabol.jenkins.pipeline.model.Component;

/**
 * Builds the model of a component from a kind of job: a chain of jobs with downstream dependencies, or a Pipeline
 * job. The first source that {@linkplain #supports supports} a job owns it, in extension order.
 */
public abstract class ComponentSource implements ExtensionPoint {

    /** Whether this source knows how to build a component starting at the given job. */
    public abstract boolean supports(Job<?, ?> job);

    /** Builds the component; a failure is reported to the user as the component's error. */
    public abstract Component resolve(ComponentRequest request) throws PipelineException;

    /** The jobs that make up the component, which the view lists as its items. */
    public abstract Collection<? extends Job<?, ?>> jobsOf(Job<?, ?> firstJob, Job<?, ?> lastJob);

    /** Schedules a build of the job like the one with the given number: same causes and parameters. */
    public void rebuild(Job<?, ?> job, int buildNumber) throws PipelineException {
        throw new PipelineException("Rebuilding is not supported for " + job.getFullName());
    }

    /**
     * Rebuilds as {@link #rebuild(Job, int)} does, or when a stage is named, restarts the build from that stage
     * where the kind of job supports it.
     */
    public void rebuild(Job<?, ?> job, int buildNumber, String stage) throws PipelineException {
        if (stage != null && !stage.isBlank()) {
            throw new PipelineException("Restarting from a stage is not supported for " + job.getFullName());
        }
        rebuild(job, buildNumber);
    }

    /** Stops the build with the given number. */
    public void abort(Job<?, ?> job, int buildNumber) throws PipelineException {
        throw new PipelineException("Aborting is not supported for " + job.getFullName());
    }

    /** Performs the manual trigger of the job, continuing from the given build of the upstream job. */
    public void triggerManual(Job<?, ?> job, Job<?, ?> upstream, int upstreamBuild,
                              ItemGroup<? extends TopLevelItem> context) throws PipelineException {
        throw new PipelineException("Manual triggers are not supported for " + job.getFullName());
    }

    /** Lets the build with the given number continue past the input step it is waiting at. */
    public void proceedInput(Job<?, ?> job, int buildNumber) throws PipelineException {
        throw new PipelineException("Input steps are not supported for " + job.getFullName());
    }

    /**
     * Lets the build continue past the input step the task with the given id is waiting at, or past the first one
     * when no task is named or the source does not tell them apart.
     */
    public void proceedInput(Job<?, ?> job, int buildNumber, String task) throws PipelineException {
        proceedInput(job, buildNumber);
    }

    public static ExtensionList<ComponentSource> all() {
        return ExtensionList.lookup(ComponentSource.class);
    }

    /** The source that owns the job. */
    public static ComponentSource forJob(Job<?, ?> job) throws PipelineException {
        for (ComponentSource source : all()) {
            if (source.supports(job)) {
                return source;
            }
        }
        throw new PipelineException("No pipeline can start at " + job.getFullName() + " (" + job.getClass().getSimpleName() + ")");
    }
}
