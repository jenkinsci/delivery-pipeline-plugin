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

import hudson.Extension;
import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PeriodicWork;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.Saveable;
import hudson.model.queue.ScheduleResult;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import se.diabol.jenkins.pipeline.PipelineException;
import se.diabol.jenkins.pipeline.cache.ModelCache;
import se.diabol.jenkins.pipeline.consolidated.ConsolidatedRun.Entry;
import se.diabol.jenkins.pipeline.consolidated.ConsolidatedRun.State;
import se.diabol.jenkins.pipeline.freestyle.Statuses;
import se.diabol.jenkins.pipeline.model.Pipeline;
import se.diabol.jenkins.pipeline.model.Stage;
import se.diabol.jenkins.pipeline.model.Status;
import se.diabol.jenkins.pipeline.model.StatusType;
import se.diabol.jenkins.pipeline.model.Task;
import se.diabol.jenkins.pipeline.model.ViewSettings;
import se.diabol.jenkins.pipeline.source.ComponentSource;

/**
 * Runs the consolidated pipelines of the views: the pipelines of a view a few at a time. A run takes the view's
 * pipelines in the order the view is configured in and cuts them into batches of the configured size. It builds the
 * first job of every pipeline of a batch, waits until all of them have come to an end, sleeps for the configured
 * time and goes on with the next batch, whatever the outcome of the last one.
 *
 * <p>A pipeline has come to an end when nothing of it is running or waiting in the queue any more, blocked items
 * included, and that has been so for {@linkplain #settleMillis() a few seconds}: the whole chain downstream of the
 * first build counts, or for a Pipeline job the run with the runs it started, as the view shows it. So a batch
 * leaves the agents idle before the next one starts, and jobs that wait for the others to finish, such as a clean-up
 * that the builds of the batch block, get their turn in between. A pipeline that waits for a person at an input step
 * holds its batch until someone answers or the run is stopped; a manual step that nobody triggers does not.
 *
 * <p>The runs are kept in {@code $JENKINS_HOME/se.diabol.jenkins.pipeline.consolidated.ConsolidatedRuns.xml}, the
 * one going on or the last one per view, so that a run goes on after a restart and a view shows how its last run
 * went. They are kept apart from the views because a view is a part of its owner's configuration, which a seed job
 * replaces while a run is going.
 *
 * <p>Threads: one lock guards the runs. Readers never take it; they get the copy that was published after the last
 * change, because the view model is computed inside the model cache and the cache is emptied from inside the queue.
 */
@Extension
public class ConsolidatedRuns implements Saveable {

    private static final Logger LOG = Logger.getLogger(ConsolidatedRuns.class.getName());

    static final String SETTLE_SECONDS_PROPERTY = ConsolidatedRuns.class.getName() + ".settleSeconds";
    static final long TICK_MILLIS = 2000;

    /** How many of a job's newest builds are searched for the one that came from a queue item. */
    private static final int BUILDS_SEARCHED = 50;

    /** Runs that ended this long ago are forgotten when another run starts. */
    private static final long KEPT_MILLIS = 90L * 24 * 60 * 60 * 1000;

    /** What finding out where a pipeline stands needs of the view's options: nothing but the tasks. */
    private static final ViewSettings TASKS_ONLY = new ViewSettings(1, 1, 5, false, false, false, false, false, false,
            false, false, false, false, false, false, false);

    /**
     * A pipeline a run is started with.
     *
     * @param estimate how long the pipeline took the last time it ran to its end, in milliseconds, or -1; it is what
     *                 the expected end of the run goes by until the pipeline has been through a run of its own
     */
    public record Target(String name, String jobFullName, String lastJobFullName, long estimate) {

        public Target(String name, String jobFullName, String lastJobFullName) {
            this(name, jobFullName, lastJobFullName, -1);
        }
    }

    private Map<String, ConsolidatedRun> runs = new LinkedHashMap<>();
    private final transient Map<String, ConsolidatedRun> published = new ConcurrentHashMap<>();
    private transient volatile boolean anyActive;

    public ConsolidatedRuns() {
        load();
    }

    public static ConsolidatedRuns get() {
        return ExtensionList.lookupSingleton(ConsolidatedRuns.class);
    }

    /** How long nothing of a pipeline must have been running or queued before it counts as finished. */
    static long settleMillis() {
        return 1000L * Long.getLong(SETTLE_SECONDS_PROPERTY, 3);
    }

    /* ------------------------------------------------------------------ what views ask for */

    /** The run of the view that is going on, or else its last one, or null; a copy that nothing changes any more. */
    public ConsolidatedRun of(String viewKey) {
        return published.get(viewKey);
    }

    /**
     * Starts a run of the view and its first batch. The caller has checked that the user may build the targets.
     *
     * @throws PipelineException when a run of the view is going on already, or there is nothing to run
     */
    public ConsolidatedRun start(String viewKey, String viewName, List<Target> targets, int concurrentPipelines,
                                 int sleepSeconds, String userId, String userName) throws PipelineException {
        if (targets.isEmpty()) {
            throw new PipelineException("The view has no pipelines to run");
        }
        int size = Math.max(1, concurrentPipelines);
        ConsolidatedRun copy;
        synchronized (this) {
            ConsolidatedRun last = runs.get(viewKey);
            if (last != null && last.isActive()) {
                throw new PipelineException("Run #" + last.getNumber() + " of the consolidated pipeline is still going");
            }
            long now = System.currentTimeMillis();
            runs.values().removeIf(run -> !run.isActive() && now - run.getFinishedAt() > KEPT_MILLIS);
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                Target target = targets.get(i);
                Entry before = last == null ? null : last.entryOf(target.jobFullName());
                boolean known = before != null && before.getDuration() > 0
                        && (before.getStatus() == StatusType.SUCCESS || before.getStatus() == StatusType.UNSTABLE);
                // how long it took in the last run, which had the same company on the agents, before anything else
                entries.add(new Entry(target.name(), target.jobFullName(), target.lastJobFullName(), i / size + 1,
                        known ? before.getDuration() : target.estimate()));
            }
            long estimate = last != null && last.getState() == State.FINISHED
                    ? last.getFinishedAt() - last.getStartedAt() : -1;
            ConsolidatedRun run = new ConsolidatedRun(viewKey, viewName, last == null ? 1 : last.getNumber() + 1, size,
                    Math.max(0, sleepSeconds), userId, userName, now, estimate, entries);
            runs.put(viewKey, run);
            LOG.log(Level.INFO, "{0}: started by {1} with {2} pipelines, {3} at a time, {4} s between batches",
                    new Object[] {describe(run), userName, entries.size(), size, run.getSleepSeconds()});
            startBatch(run, 1, now);
            copy = publish(run);
        }
        changed(List.of(copy));
        return copy;
    }

    /**
     * Stops the view's run: no further batch starts. The pipelines that are running go on, and the run ends when
     * they have. Stopping a run that is stopping already gives up waiting for them and ends the run at once, which
     * is the way out when a pipeline can never end, such as one whose job waits in the queue for an agent that is
     * gone.
     *
     * @throws PipelineException when no run of the view is going on
     */
    public ConsolidatedRun stop(String viewKey, String userName) throws PipelineException {
        ConsolidatedRun copy;
        synchronized (this) {
            ConsolidatedRun run = runs.get(viewKey);
            if (run == null || !run.isActive()) {
                throw new PipelineException("No run of the consolidated pipeline is going on");
            }
            long now = System.currentTimeMillis();
            if (run.getState() == State.STOPPING) {
                LOG.log(Level.INFO, "{0}: {1} gave up waiting for batch {2}",
                        new Object[] {describe(run), userName, run.getBatch()});
                for (Entry entry : run.entriesOf(run.getBatch())) {
                    if (!entry.isOver()) {
                        entry.over(StatusType.CANCELLED, entry.getSince() > 0 ? now - entry.getSince() : 0,
                                "not waited for");
                    }
                }
                end(run, now);
            } else {
                LOG.log(Level.INFO, "{0}: stopped by {1} in batch {2} of {3}",
                        new Object[] {describe(run), userName, run.getBatch(), run.getBatches()});
                boolean sleeping = run.getState() == State.SLEEPING;
                run.stopping(userName);
                if (sleeping) {
                    end(run, now);
                }
            }
            copy = publish(run);
        }
        changed(List.of(copy));
        return copy;
    }

    /* ------------------------------------------------------------------ the clock */

    /** Moves every run on as far as it can go; called every other second, and by tests. */
    void tick() {
        if (!anyActive) {
            return;
        }
        List<ConsolidatedRun> changed = new ArrayList<>();
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            synchronized (this) {
                long now = System.currentTimeMillis();
                for (ConsolidatedRun run : runs.values()) {
                    if (run.isActive() && advance(run, now)) {
                        changed.add(publish(run));
                    }
                }
                refreshActive();
            }
        }
        changed(changed);
    }

    /** Whether anything about the run changed that is kept or shown. */
    private boolean advance(ConsolidatedRun run, long now) {
        if (run.getState() == State.SLEEPING) {
            Jenkins jenkins = Jenkins.get();
            if (now < run.getNextBatchAt() || jenkins.isQuietingDown() || jenkins.isTerminating()) {
                return false;
            }
            startBatch(run, run.getBatch() + 1, now);
            return true;
        }
        boolean changed = false;
        boolean batchOver = true;
        for (Entry entry : run.entriesOf(run.getBatch())) {
            if (!entry.isOver()) {
                changed |= observe(run, entry, now);
                batchOver &= entry.isOver();
            }
        }
        if (!batchOver) {
            return changed;
        }
        if (run.getState() == State.STOPPING || run.getBatch() >= run.getBatches()) {
            end(run, now);
        } else if (run.getSleepSeconds() > 0) {
            run.sleeping(now + 1000L * run.getSleepSeconds());
            LOG.log(Level.FINE, "{0}: batch {1} of {2} finished, sleeping {3} s",
                    new Object[] {describe(run), run.getBatch(), run.getBatches(), run.getSleepSeconds()});
        } else {
            startBatch(run, run.getBatch() + 1, now);
        }
        return true;
    }

    private void end(ConsolidatedRun run, long now) {
        boolean stopped = run.getState() == State.STOPPING;
        for (Entry entry : run.getEntries()) {
            if (!entry.isOver()) {
                entry.over(StatusType.NOT_BUILT, 0, "not started");
            }
        }
        run.over(stopped ? State.STOPPED : State.FINISHED, now);
        int failed = 0;
        for (Entry entry : run.getEntries()) {
            if (entry.getStatus() == StatusType.FAILED || entry.getStatus() == StatusType.CANCELLED) {
                failed++;
            }
        }
        LOG.log(Level.INFO, "{0}: {1} after {2} s, {3} of {4} pipelines failed", new Object[] {describe(run),
                stopped ? "stopped" : "finished", (now - run.getStartedAt()) / 1000, failed, run.getEntries().size()});
    }

    private static String describe(ConsolidatedRun run) {
        return "Consolidated pipeline of view " + run.getViewName() + ", run #" + run.getNumber();
    }

    /* ------------------------------------------------------------------ starting pipelines */

    private void startBatch(ConsolidatedRun run, int batch, long now) {
        run.running(batch);
        List<String> names = new ArrayList<>();
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            for (Entry entry : run.entriesOf(batch)) {
                names.add(entry.getName());
                try {
                    entry.queued(schedule(run, entry), now);
                } catch (PipelineException e) {
                    entry.over(StatusType.NOT_BUILT, 0, e.getMessage());
                    LOG.log(Level.WARNING, "{0}: {1} was not started: {2}",
                            new Object[] {describe(run), entry.getName(), e.getMessage()});
                }
            }
        }
        anyActive = true;
        LOG.log(Level.FINE, "{0}: started batch {1} of {2}: {3}",
                new Object[] {describe(run), batch, run.getBatches(), names});
    }

    /** Puts the first job of the pipeline into the queue and returns the id of the queue item. */
    private static long schedule(ConsolidatedRun run, Entry entry) throws PipelineException {
        Job<?, ?> job = Jenkins.get().getItemByFullName(entry.getJobFullName(), Job.class);
        if (job == null) {
            throw new PipelineException("the job no longer exists");
        }
        if (!(job instanceof Queue.Task task) || !job.isBuildable()) {
            throw new PipelineException("the job is disabled");
        }
        List<Action> actions = new ArrayList<>();
        actions.add(new CauseAction(new ConsolidatedCause(run.getViewName(), run.getNumber()),
                new Cause.UserIdCause(run.getStartedById())));
        ParametersDefinitionProperty parameters = job.getProperty(ParametersDefinitionProperty.class);
        if (parameters != null) {
            List<ParameterValue> defaults = new ArrayList<>();
            for (ParameterDefinition definition : parameters.getParameterDefinitions()) {
                ParameterValue value = definition.getDefaultParameterValue();
                if (value != null) {
                    defaults.add(value);
                }
            }
            actions.add(new ParametersAction(defaults));
        }
        ScheduleResult result = Jenkins.get().getQueue().schedule2(task, 0, actions);
        Queue.Item item = result.isRefused() ? null : result.getItem();
        if (item == null) {
            throw new PipelineException("the queue refused the job");
        }
        return item.getId();
    }

    /* ------------------------------------------------------------------ finding out where a pipeline stands */

    /** Looks at the pipeline and moves its entry on; whether anything changed that is kept or shown. */
    private static boolean observe(ConsolidatedRun run, Entry entry, long now) {
        Job<?, ?> job = Jenkins.get().getItemByFullName(entry.getJobFullName(), Job.class);
        if (job == null) {
            entry.over(StatusType.NOT_BUILT, 0, "the job no longer exists");
            return true;
        }
        boolean changed = false;
        if (entry.getBuildNumber() == null) {
            Run<?, ?> build = buildOf(job, entry.queueId());
            if (build == null) {
                Queue.Item item = Jenkins.get().getQueue().getItem(entry.queueId());
                if (item == null || item instanceof Queue.LeftItem left && left.isCancelled()) {
                    entry.over(StatusType.CANCELLED, 0, "cancelled in the queue");
                    return true;
                }
                return false;
            }
            entry.running(build.getNumber(), build.getTimeInMillis());
            changed = true;
        }
        Run<?, ?> build = job.getBuildByNumber(entry.getBuildNumber());
        if (build == null) {
            entry.over(StatusType.NOT_BUILT, 0, "the build was deleted");
            return true;
        }
        StatusType outcome = build.isLogUpdated() ? null : outcomeOf(job, build, entry);
        if (outcome == null) {
            entry.quiet(0);
            return changed;
        }
        if (entry.quietSince() == 0) {
            entry.quiet(now);
        }
        if (now - entry.quietSince() < settleMillis()) {
            return changed;
        }
        long end = entry.quietSince();
        entry.over(outcome, end - entry.getSince(), null);
        LOG.log(Level.FINE, "{0}: {1} ended {2}", new Object[] {describe(run), entry.getName(), outcome});
        return true;
    }

    /** The build of the job that came from the queue item, among the newest ones. */
    private static Run<?, ?> buildOf(Job<?, ?> job, long queueId) {
        int searched = 0;
        for (Run<?, ?> build : job.getBuilds()) {
            if (build.getQueueId() == queueId) {
                return build;
            }
            if (++searched >= BUILDS_SEARCHED) {
                break;
            }
        }
        return null;
    }

    /**
     * The worst outcome among the tasks of the pipeline that the build started, or null while one of them is
     * running or queued, or is a build that has not completed: a build triggers its downstream jobs after its
     * result is known and before it completes.
     */
    private static StatusType outcomeOf(Job<?, ?> job, Run<?, ?> build, Entry entry) {
        Pipeline pipeline = null;
        try {
            Job<?, ?> last = entry.getLastJobFullName() == null ? null
                    : Jenkins.get().getItemByFullName(entry.getLastJobFullName(), Job.class);
            pipeline = ComponentSource.forJob(job).instance(job, last, build.getNumber(), TASKS_ONLY);
        } catch (PipelineException | RuntimeException e) {
            LOG.log(Level.FINE, "Cannot resolve the pipeline of " + build.getFullDisplayName(), e);
        }
        StatusType worst = Statuses.of(build).type();
        if (pipeline == null) {
            return worst;
        }
        worst = worse(worst, pipeline.status());
        Set<String> completed = new HashSet<>();
        for (Stage stage : pipeline.stages()) {
            worst = worse(worst, stage.status());
            for (Task task : stage.tasks()) {
                StatusType type = task.status().type();
                if (type == StatusType.QUEUED || type.isActive()) {
                    return null;
                }
                if (task.jobFullName() != null && task.buildNumber() != null
                        && completed.add(task.jobFullName() + "#" + task.buildNumber()) && !isCompleted(task)) {
                    return null;
                }
                worst = worse(worst, task.status());
            }
        }
        return worst;
    }

    private static boolean isCompleted(Task task) {
        Job<?, ?> job = Jenkins.get().getItemByFullName(task.jobFullName(), Job.class);
        Run<?, ?> build = job == null ? null : job.getBuildByNumber(task.buildNumber());
        return build == null || !build.isLogUpdated();
    }

    private static StatusType worse(StatusType worst, Status status) {
        return status != null && rank(status.type()) > rank(worst) ? status.type() : worst;
    }

    /** How bad an outcome is; what never ran does not count. */
    private static int rank(StatusType type) {
        return switch (type) {
            case FAILED -> 4;
            case CANCELLED -> 3;
            case UNSTABLE -> 2;
            case SUCCESS -> 1;
            default -> 0;
        };
    }

    /* ------------------------------------------------------------------ publishing and keeping */

    private ConsolidatedRun publish(ConsolidatedRun run) {
        ConsolidatedRun copy = run.copy();
        published.put(run.getViewKey(), copy);
        return copy;
    }

    private void refreshActive() {
        boolean active = false;
        for (ConsolidatedRun run : runs.values()) {
            active |= run.isActive();
        }
        anyActive = active;
    }

    /** Saves the runs and drops the cached models of the views whose runs changed; called without the lock. */
    private void changed(List<ConsolidatedRun> copies) {
        if (copies.isEmpty()) {
            return;
        }
        save();
        for (ConsolidatedRun copy : copies) {
            List<String> jobs = copy.jobs();
            if (!jobs.isEmpty()) {
                ModelCache.get().invalidate(jobs.get(0));
            }
        }
    }

    private static XmlFile file() {
        return new XmlFile(Jenkins.XSTREAM2, new File(Jenkins.get().getRootDir(), ConsolidatedRuns.class.getName() + ".xml"));
    }

    private synchronized void load() {
        XmlFile file = file();
        if (file.exists()) {
            try {
                file.unmarshal(this);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not read " + file + "; the consolidated pipelines start afresh", e);
            }
        }
        if (runs == null) {
            runs = new LinkedHashMap<>();
        }
        for (ConsolidatedRun run : runs.values()) {
            publish(run);
        }
        refreshActive();
    }

    @Override
    public synchronized void save() {
        try {
            file().write(this);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not save the runs of the consolidated pipelines", e);
        }
    }

    /** For tests: the runs as the file holds them, read back into a new instance. */
    static ConsolidatedRuns reloaded() {
        return new ConsolidatedRuns();
    }

    /** Moves the runs on every other second; a tick costs nothing while no run is going. */
    @Extension
    public static class Clock extends PeriodicWork {
        @Override
        public long getRecurrencePeriod() {
            return TICK_MILLIS;
        }

        @Override
        public long getInitialDelay() {
            return TICK_MILLIS;
        }

        @Override
        protected void doRun() {
            ConsolidatedRuns.get().tick();
        }
    }
}
