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
package se.diabol.jenkins.pipeline.consolidated;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.tasks.BuildTrigger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import se.diabol.jenkins.pipeline.PipelineException;
import se.diabol.jenkins.pipeline.consolidated.ConsolidatedRun.Entry;
import se.diabol.jenkins.pipeline.consolidated.ConsolidatedRun.State;
import se.diabol.jenkins.pipeline.consolidated.ConsolidatedRuns.Target;
import se.diabol.jenkins.pipeline.model.StatusType;

/** Runs of the consolidated pipeline: batches, what counts as the end of a pipeline, sleeping, stopping, keeping. */
@WithJenkins
class ConsolidatedRunsTest {

    private static final String VIEW = "view/All/";

    private JenkinsRule jenkins;
    private ConsolidatedRuns runs;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        jenkins = rule;
        runs = ConsolidatedRuns.get();
        System.setProperty(ConsolidatedRuns.SETTLE_SECONDS_PROPERTY, "0");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ConsolidatedRuns.SETTLE_SECONDS_PROPERTY);
    }

    /** A pipeline of two chained jobs, "name" and "name_prune"; the second one is returned by {@link #last}. */
    private FreeStyleProject chain(String name) throws Exception {
        FreeStyleProject first = jenkins.createFreeStyleProject(name);
        jenkins.createFreeStyleProject(name + "_prune");
        first.getPublishersList().add(new BuildTrigger(name + "_prune", Result.FAILURE));
        jenkins.getInstance().rebuildDependencyGraph();
        return first;
    }

    private FreeStyleProject last(String name) {
        return jenkins.getInstance().getItemByFullName(name + "_prune", FreeStyleProject.class);
    }

    private static List<Target> targets(String... names) {
        List<Target> targets = new ArrayList<>();
        for (String name : names) {
            targets.add(new Target(name, name, null));
        }
        return targets;
    }

    /** Moves the runs on until the view's run is as asked for. */
    private ConsolidatedRun await(String what, Predicate<ConsolidatedRun> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (true) {
            runs.tick();
            ConsolidatedRun run = runs.of(VIEW);
            if (run != null && condition.test(run)) {
                return run;
            }
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting until " + what + "; the run is " + describe(run));
            }
            Thread.sleep(100);
        }
    }

    private static String describe(ConsolidatedRun run) {
        if (run == null) {
            return "missing";
        }
        StringBuilder text = new StringBuilder(run.getState() + " in batch " + run.getBatch());
        for (Entry entry : run.getEntries()) {
            text.append(' ').append(entry.getName()).append('=').append(entry.getStatus());
        }
        return text.toString();
    }

    private static List<StatusType> statuses(ConsolidatedRun run) {
        List<StatusType> statuses = new ArrayList<>();
        for (Entry entry : run.getEntries()) {
            statuses.add(entry.getStatus());
        }
        return statuses;
    }

    @Test
    void aBatchEndsWhenNothingOfItsPipelinesIsRunningOrQueuedAndOnlyThenTheNextStarts() throws Exception {
        chain("a");
        chain("b");
        chain("c");
        // like a clean-up job that the builds of the batch hold back: it sits in the queue
        last("a").setAssignedLabel(Label.get("nowhere"));

        ConsolidatedRun started = runs.start(VIEW, "All", targets("a", "b", "c"), 2, 0, null, "tester");
        assertThat(started.getNumber(), is(1));
        assertThat(started.getBatches(), is(2));
        assertThat(started.getBatch(), is(1));
        assertThat(statuses(started), contains(StatusType.QUEUED, StatusType.QUEUED, StatusType.IDLE));

        ConsolidatedRun held = await("b has ended and a waits for its queued job",
                run -> run.getEntries().get(1).isOver() && run.getEntries().get(0).getStatus() == StatusType.RUNNING
                        && last("a").isInQueue());
        assertThat(held.getState(), is(State.RUNNING));
        assertThat(held.getBatch(), is(1));
        assertThat(held.getEntries().get(1).getStatus(), is(StatusType.SUCCESS));
        assertThat(held.getEntries().get(0).getBuildNumber(), is(1));
        for (int i = 0; i < 5; i++) {
            runs.tick();
        }
        assertThat("the queued job holds the batch", runs.of(VIEW).getBatch(), is(1));
        assertThat("and nothing of the next batch has started", jenkins.getInstance()
                .getItemByFullName("c", FreeStyleProject.class).getLastBuild(), nullValue());

        last("a").setAssignedLabel(null);
        jenkins.getInstance().getQueue().scheduleMaintenance();
        ConsolidatedRun finished = await("the run has finished", run -> run.getState() == State.FINISHED);
        assertThat(statuses(finished), contains(StatusType.SUCCESS, StatusType.SUCCESS, StatusType.SUCCESS));
        assertThat(finished.getBatch(), is(2));
        assertThat(finished.getFinishedAt(), greaterThan(0L));
        FreeStyleBuild build = jenkins.getInstance().getItemByFullName("c", FreeStyleProject.class).getLastBuild();
        ConsolidatedCause cause = build.getCause(ConsolidatedCause.class);
        assertThat(cause, notNullValue());
        assertThat(cause.getShortDescription(), is("Started by run #1 of the consolidated pipeline of view All"));
        assertThat("the clean-up of the last batch ran before the run ended", last("c").getLastBuild(), notNullValue());
    }

    @Test
    void aFailedPipelineDoesNotHoldBackTheNextBatch() throws Exception {
        chain("a").getBuildersList().add(new FailureBuilder());
        chain("b");
        runs.start(VIEW, "All", targets("a", "b"), 1, 0, null, "tester");
        ConsolidatedRun finished = await("the run has finished", run -> run.getState() == State.FINISHED);
        assertThat(statuses(finished), contains(StatusType.FAILED, StatusType.SUCCESS));
        assertThat("a failed build still triggers a job that runs on failure", last("a").getLastBuild(), notNullValue());
    }

    @Test
    void theRunSleepsBetweenBatchesAndStoppingItThenEndsItAtOnce() throws Exception {
        chain("a");
        chain("b");
        runs.start(VIEW, "All", targets("a", "b"), 1, 3600, null, "tester");
        ConsolidatedRun sleeping = await("the run sleeps", run -> run.getState() == State.SLEEPING);
        assertThat(sleeping.getBatch(), is(1));
        assertThat(sleeping.getNextBatchAt(), greaterThan(System.currentTimeMillis() + 3_000_000L));
        runs.tick();
        assertThat(jenkins.getInstance().getItemByFullName("b", FreeStyleProject.class).getLastBuild(), nullValue());

        PipelineException refused = assertThrows(PipelineException.class,
                () -> runs.start(VIEW, "All", targets("a", "b"), 1, 0, null, "tester"));
        assertThat(refused.getMessage(), containsString("still going"));

        ConsolidatedRun stopped = runs.stop(VIEW, "someone");
        assertThat(stopped.getState(), is(State.STOPPED));
        assertThat(stopped.getStoppedBy(), is("someone"));
        assertThat(statuses(stopped), contains(StatusType.SUCCESS, StatusType.NOT_BUILT));
        assertThat(stopped.getEntries().get(1).getNote(), is("not started"));
        assertThrows(PipelineException.class, () -> runs.stop(VIEW, "someone"));

        ConsolidatedRun next = runs.start(VIEW, "All", targets("a"), 1, 0, null, "tester");
        assertThat("runs of a view are numbered", next.getNumber(), is(2));
        assertThat("and remember how long a pipeline took", next.getEntries().get(0).getEstimate() >= 0, is(true));
    }

    @Test
    void aRunThatIsStoppedLetsItsBatchFinish() throws Exception {
        chain("a");
        chain("b");
        last("a").setAssignedLabel(Label.get("nowhere"));
        runs.start(VIEW, "All", targets("a", "b"), 1, 0, null, "tester");
        await("a waits for its queued job", run -> run.getEntries().get(0).getStatus() == StatusType.RUNNING
                && last("a").isInQueue());
        assertThat(runs.stop(VIEW, "someone").getState(), is(State.STOPPING));
        runs.tick();
        assertThat(runs.of(VIEW).getState(), is(State.STOPPING));

        last("a").setAssignedLabel(null);
        jenkins.getInstance().getQueue().scheduleMaintenance();
        ConsolidatedRun stopped = await("the run has stopped", run -> run.getState() == State.STOPPED);
        assertThat(statuses(stopped), contains(StatusType.SUCCESS, StatusType.NOT_BUILT));
        assertThat(jenkins.getInstance().getItemByFullName("b", FreeStyleProject.class).getLastBuild(), nullValue());
    }

    @Test
    void stoppingARunThatIsStoppingAlreadyEndsItWithoutWaiting() throws Exception {
        chain("a");
        chain("b");
        last("a").setAssignedLabel(Label.get("nowhere"));
        runs.start(VIEW, "All", targets("a", "b"), 1, 0, null, "tester");
        await("a waits for its queued job", run -> run.getEntries().get(0).getStatus() == StatusType.RUNNING
                && last("a").isInQueue());
        assertThat(runs.stop(VIEW, "someone").getState(), is(State.STOPPING));

        ConsolidatedRun ended = runs.stop(VIEW, "someone");
        assertThat(ended.getState(), is(State.STOPPED));
        assertThat(statuses(ended), contains(StatusType.CANCELLED, StatusType.NOT_BUILT));
        assertThat(ended.getEntries().get(0).getNote(), is("not waited for"));
        assertThat("the view can run its pipelines again", runs.of(VIEW).isActive(), is(false));
        jenkins.getInstance().getQueue().cancel(last("a"));
    }

    @Test
    void aJobThatCannotBeBuiltIsLeftOutAndAQueueItemThatIsCancelledEndsItsPipeline() throws Exception {
        chain("a").disable();
        FreeStyleProject b = chain("b");
        b.setAssignedLabel(Label.get("nowhere"));
        ConsolidatedRun started = runs.start(VIEW, "All", targets("a", "b", "gone"), 3, 0, null, "tester");
        assertThat(statuses(started), contains(StatusType.NOT_BUILT, StatusType.QUEUED, StatusType.NOT_BUILT));
        assertThat(started.getEntries().get(0).getNote(), is("the job is disabled"));
        assertThat(started.getEntries().get(2).getNote(), is("the job no longer exists"));

        jenkins.getInstance().getQueue().cancel(b);
        ConsolidatedRun finished = await("the run has finished", run -> run.getState() == State.FINISHED);
        assertThat(finished.getEntries().get(1).getStatus(), is(StatusType.CANCELLED));
        assertThat(finished.getEntries().get(1).getNote(), is("cancelled in the queue"));
    }

    @Test
    void parametersTakeTheirDefaults() throws Exception {
        FreeStyleProject a = chain("a");
        a.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("TARGET", "everything")));
        runs.start(VIEW, "All", targets("a"), 1, 0, null, "tester");
        await("the run has finished", run -> run.getState() == State.FINISHED);
        StringParameterValue value = (StringParameterValue) a.getLastBuild()
                .getAction(hudson.model.ParametersAction.class).getParameter("TARGET");
        assertThat(value.getValue(), is("everything"));
    }

    @Test
    void aPipelineJobCountsUntilItsRunIsOver() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "wf");
        job.setDefinition(new CpsFlowDefinition("stage('Build') {\n  echo 'built'\n}\nstage('Test') {\n  unstable('flaky')\n}",
                true));
        runs.start(VIEW, "All", targets("wf"), 1, 0, null, "tester");
        ConsolidatedRun finished = await("the run has finished", run -> run.getState() == State.FINISHED);
        assertThat(statuses(finished), contains(StatusType.UNSTABLE));
        assertThat(finished.getEntries().get(0).getBuildNumber(), is(1));
    }

    @Test
    void aPipelineOnlyCountsAsOverAfterItHasBeenQuietForAWhile() throws Exception {
        System.setProperty(ConsolidatedRuns.SETTLE_SECONDS_PROPERTY, "3600");
        chain("a");
        runs.start(VIEW, "All", targets("a"), 1, 0, null, "tester");
        await("a's builds are done", run -> run.getEntries().get(0).getBuildNumber() != null
                && last("a").getLastBuild() != null && !last("a").getLastBuild().isLogUpdated());
        for (int i = 0; i < 3; i++) {
            runs.tick();
        }
        assertThat(runs.of(VIEW).getEntries().get(0).getStatus(), is(StatusType.RUNNING));
        System.setProperty(ConsolidatedRuns.SETTLE_SECONDS_PROPERTY, "0");
        await("the run has finished", run -> run.getState() == State.FINISHED);
    }

    @Test
    void theRunsAreKeptOnDisk() throws Exception {
        chain("a");
        chain("b");
        runs.start(VIEW, "All", targets("a", "b"), 1, 3600, "alice", "Alice");
        ConsolidatedRun sleeping = await("the run sleeps", run -> run.getState() == State.SLEEPING);

        ConsolidatedRun read = ConsolidatedRuns.reloaded().of(VIEW);
        assertThat(read, notNullValue());
        assertThat(read.getState(), is(State.SLEEPING));
        assertThat(read.getNumber(), is(1));
        assertThat(read.getViewName(), is("All"));
        assertThat(read.getStartedById(), is("alice"));
        assertThat(read.getStartedBy(), is("Alice"));
        assertThat(read.getConcurrentPipelines(), is(1));
        assertThat(read.getSleepSeconds(), is(3600));
        assertThat(read.getNextBatchAt(), is(sleeping.getNextBatchAt()));
        assertThat(read.getEntries(), hasSize(2));
        assertThat(read.getEntries().get(0).getStatus(), is(StatusType.SUCCESS));
        assertThat(read.getEntries().get(0).getBuildNumber(), is(1));
        assertThat(read.getEntries().get(1).getStatus(), is(StatusType.IDLE));
        assertThat(read.getEntries().get(1).getJobFullName(), is("b"));
        runs.stop(VIEW, "tester");
    }

    @Test
    void aViewWithoutPipelinesHasNothingToRun() {
        PipelineException refused = assertThrows(PipelineException.class,
                () -> runs.start(VIEW, "All", List.of(), 3, 10, null, "tester"));
        assertThat(refused.getMessage(), containsString("no pipelines"));
        assertThat(runs.of(VIEW), nullValue());
    }
}
