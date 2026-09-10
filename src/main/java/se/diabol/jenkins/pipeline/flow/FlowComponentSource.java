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
package se.diabol.jenkins.pipeline.flow;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import jenkins.model.ParameterizedJobMixIn;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import se.diabol.jenkins.pipeline.PipelineException;
import se.diabol.jenkins.pipeline.model.Component;
import se.diabol.jenkins.pipeline.model.JobRef;
import se.diabol.jenkins.pipeline.model.Paging;
import se.diabol.jenkins.pipeline.model.Pipeline;
import se.diabol.jenkins.pipeline.model.ViewSettings;
import se.diabol.jenkins.pipeline.source.ComponentRequest;
import se.diabol.jenkins.pipeline.source.ComponentSource;

/** Components that are a single Pipeline job: each run is a pipeline instance, each top-level stage a stage. */
@Extension(ordinal = 50)
public class FlowComponentSource extends ComponentSource {

    @Override
    public boolean supports(Job<?, ?> job) {
        return job instanceof WorkflowJob;
    }

    @Override
    public Collection<? extends Job<?, ?>> jobsOf(Job<?, ?> firstJob, Job<?, ?> lastJob) {
        return List.of(firstJob);
    }

    @Override
    public Component resolve(ComponentRequest request) throws PipelineException {
        WorkflowJob job = (WorkflowJob) request.firstJob();
        ViewSettings settings = request.settings();
        int total = job.getBuilds().size();
        Paging paging = request.paging() && settings.pagingEnabled()
                ? new Paging(Math.max(1, request.page()), settings.noOfPipelines(), total) : null;
        List<Pipeline> pipelines = new ArrayList<>();
        Queue.Item queued = job.getQueueItem();
        if (queued != null && (paging == null || paging.page() == 1)) {
            pipelines.add(FlowRuns.queued(job, queued).forSettings(settings));
        }
        Iterator<WorkflowRun> it = job.getBuilds().iterator();
        for (int skip = paging == null ? 0 : paging.offset(); skip > 0 && it.hasNext(); skip--) {
            it.next();
        }
        for (int i = 0; i < settings.noOfPipelines() && it.hasNext(); i++) {
            pipelines.add(FlowRuns.of(it.next()).pipeline().forSettings(settings));
        }
        return new Component(request.name(), request.index(), JobRef.of(job), paging, pipelines, null);
    }

    /**
     * Runs the Pipeline again with the parameters of the given run, or when a stage is named, restarts that run
     * from the stage through the {@link StageRestart} that supports it.
     */
    @Override
    public void rebuild(Job<?, ?> job, int buildNumber, String stage) throws PipelineException {
        WorkflowJob workflowJob = (WorkflowJob) job;
        workflowJob.checkPermission(Item.BUILD);
        WorkflowRun run = workflowJob.getBuildByNumber(buildNumber);
        if (run == null) {
            throw new PipelineException("Build " + buildNumber + " of " + job.getFullName() + " does not exist");
        }
        if (run.isBuilding()) {
            throw new PipelineException(run.getFullDisplayName() + " is still running");
        }
        if (stage != null && !stage.isBlank()) {
            StageRestart.restartFrom(run, stage.trim());
            return;
        }
        List<Cause> causes = new ArrayList<>();
        for (Cause cause : run.getCauses()) {
            if (!(cause instanceof Cause.UserIdCause)) {
                causes.add(cause);
            }
        }
        causes.add(new Cause.UserIdCause());
        List<Action> actions = new ArrayList<>();
        actions.add(new CauseAction(causes));
        ParametersAction parameters = run.getAction(ParametersAction.class);
        if (parameters != null) {
            actions.add(parameters);
        }
        if (ParameterizedJobMixIn.scheduleBuild2(workflowJob, workflowJob.getQuietPeriod(),
                actions.toArray(new Action[0])) == null) {
            throw new PipelineException("Could not schedule a build of " + job.getFullName());
        }
    }

    @Override
    public void abort(Job<?, ?> job, int buildNumber) throws PipelineException {
        WorkflowJob workflowJob = (WorkflowJob) job;
        workflowJob.checkPermission(Item.CANCEL);
        WorkflowRun run = workflowJob.getBuildByNumber(buildNumber);
        if (run == null || !run.isBuilding()) {
            throw new PipelineException("Build " + buildNumber + " of " + job.getFullName() + " is not running");
        }
        run.doStop();
    }

    /**
     * Proceeds the input step the task with the given id is waiting at, or the run's first pending one, as its
     * "Proceed" button would; the step checks who may.
     */
    @Override
    public void proceedInput(Job<?, ?> job, int buildNumber, String task) throws PipelineException {
        WorkflowRun run = ((WorkflowJob) job).getBuildByNumber(buildNumber);
        if (run == null) {
            throw new PipelineException("Build " + buildNumber + " of " + job.getFullName() + " does not exist");
        }
        InputStepExecution execution = FlowStages.pendingInputOf(run, task == null ? "" : task.trim());
        if (execution == null) {
            throw new PipelineException(run.getFullDisplayName() + " is not waiting for input");
        }
        try {
            execution.doProceedEmpty();
        } catch (IOException e) {
            throw new PipelineException("Could not proceed the input step of " + run.getFullDisplayName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("Interrupted while proceeding the input step of " + run.getFullDisplayName(), e);
        }
    }
}
