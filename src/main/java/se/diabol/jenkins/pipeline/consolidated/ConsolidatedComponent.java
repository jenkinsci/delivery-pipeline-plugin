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

import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import se.diabol.jenkins.pipeline.consolidated.ConsolidatedRun.Entry;
import se.diabol.jenkins.pipeline.consolidated.ConsolidatedRuns.Target;
import se.diabol.jenkins.pipeline.model.Component;
import se.diabol.jenkins.pipeline.model.Consolidated;
import se.diabol.jenkins.pipeline.model.Pipeline;
import se.diabol.jenkins.pipeline.model.Stage;
import se.diabol.jenkins.pipeline.model.Status;
import se.diabol.jenkins.pipeline.model.StatusType;
import se.diabol.jenkins.pipeline.model.Task;
import se.diabol.jenkins.pipeline.model.Trigger;

/**
 * Draws the consolidated pipeline of a view as a component like any other: one pipeline whose stages are the
 * batches, each leading to the next, and whose tasks are the pipelines of the view, so that the page shows it with
 * what it has for stages and tasks. A task links to the build that started its pipeline.
 *
 * <p>While a run is going the component shows that run, as it was planned when it started. Otherwise it shows what a
 * run started now would do, which follows the view's configuration, and every pipeline carries the outcome it had in
 * the last run.
 */
public final class ConsolidatedComponent {

    public static final String NAME = "Consolidated pipeline";

    /** Batches in a row of the grid at least, however narrow the view's pipelines are. */
    private static final int MIN_BATCHES_PER_ROW = 4;

    private ConsolidatedComponent() {
    }

    /**
     * The consolidated pipeline of a view.
     *
     * @param run the view's run that is going on, or its last one, or null
     * @param targets the pipelines a run started now would run, in order
     * @param concurrentPipelines how many of them a batch of such a run would hold
     * @param sleepSeconds how long such a run would sleep between two batches
     * @param columns how many columns the widest pipeline of the view takes; the batches wrap to stay within it
     */
    public static Component of(ConsolidatedRun run, List<Target> targets, int concurrentPipelines, int sleepSeconds,
                               int columns) {
        boolean active = run != null && run.isActive();
        List<Entry> entries = active ? run.getEntries() : planned(run, targets, Math.max(1, concurrentPipelines));
        int batches = 0;
        int finished = 0;
        int failed = 0;
        for (Entry entry : entries) {
            batches = Math.max(batches, entry.getBatch());
        }
        for (Entry entry : run == null ? List.<Entry>of() : run.getEntries()) {
            // what a stopped run never started is over too, but did not finish
            if (entry.isOver() && entry.getBatch() <= run.getBatch()) {
                finished++;
            }
            if (entry.getStatus() == StatusType.FAILED || entry.getStatus() == StatusType.CANCELLED) {
                failed++;
            }
        }
        List<String> jobs = new ArrayList<>();
        for (Target target : targets) {
            jobs.add(target.jobFullName());
        }
        long now = System.currentTimeMillis();
        long left = active ? remaining(run, now) : -1;
        // what a run started now would take, going by the plan, which is what the entries are between two runs
        long whole = expected(active ? planned(run, targets, Math.max(1, concurrentPipelines)) : entries, 1,
                Math.max(0, sleepSeconds));
        Consolidated consolidated = new Consolidated(run == null ? Consolidated.IDLE : run.getState().name(),
                run == null ? 0 : run.getNumber(), run == null ? 0 : run.getBatch(),
                run == null ? batches : run.getBatches(), finished, failed,
                run == null ? entries.size() : run.getEntries().size(),
                active ? run.getConcurrentPipelines() : Math.max(1, concurrentPipelines),
                active ? run.getSleepSeconds() : Math.max(0, sleepSeconds),
                run != null && run.getNextBatchAt() > 0 ? run.getNextBatchAt() : null,
                run == null ? null : run.getStartedAt(),
                run != null && run.getFinishedAt() > 0 ? run.getFinishedAt() : null,
                left < 0 ? null : now + left, whole < 0 ? null : whole,
                run == null ? null : run.getStartedBy(), run == null ? null : run.getStoppedBy(),
                active ? run.jobs() : jobs);
        Pipeline pipeline = new Pipeline("consolidated", run == null ? null : "#" + run.getNumber(),
                run == null ? 0 : run.getStartedAt(), false, null, null, false,
                run == null || run.getStartedBy() == null ? List.of()
                        : List.of(new Trigger(Trigger.MANUAL, "user " + run.getStartedBy())),
                List.of(), List.of(), 0, elapsed(run), List.of(), List.of(),
                stages(entries, Math.max(MIN_BATCHES_PER_ROW, columns)), statusOf(run));
        return new Component(NAME, 0, null, null, List.of(pipeline), null, consolidated);
    }

    /** What a run started now would do, each pipeline with the outcome it had in the last run. */
    private static List<Entry> planned(ConsolidatedRun last, List<Target> targets, int size) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            Entry before = last == null ? null : last.entryOf(target.jobFullName());
            Entry entry = new Entry(target.name(), target.jobFullName(), target.lastJobFullName(), i / size + 1,
                    took(before) > 0 ? took(before) : target.estimate());
            if (before != null && before.isOver()) {
                if (before.getBuildNumber() != null) {
                    entry.running(before.getBuildNumber(), before.getSince());
                }
                entry.over(before.getStatus(), before.getDuration(), before.getNote());
            }
            entries.add(entry);
        }
        return entries;
    }

    /* ------------------------------------------------------------------ how long a run takes */

    /** How long the pipeline took in the run, when it ran to a good end there; otherwise -1. */
    private static long took(Entry entry) {
        boolean good = entry != null
                && (entry.getStatus() == StatusType.SUCCESS || entry.getStatus() == StatusType.UNSTABLE);
        return good && entry.getDuration() > 0 ? entry.getDuration() : -1;
    }

    /**
     * How long a pipeline instance took from its start to the end of its last task, waiting included, in
     * milliseconds; -1 for one that is still going, that failed or was aborted, which usually means it stopped
     * early, or that never ran. A view hands this over for the newest instance of each of its pipelines, so that the
     * first run of its consolidated pipeline has something to go by.
     */
    public static long wallDuration(Pipeline pipeline) {
        if (pipeline.aggregated() || pipeline.timestamp() <= 0) {
            return -1;
        }
        long end = 0;
        for (Stage stage : pipeline.stages()) {
            for (Task task : stage.tasks()) {
                StatusType type = task.status().type();
                if (type == StatusType.QUEUED || type.isActive() || type == StatusType.FAILED
                        || type == StatusType.CANCELLED) {
                    return -1;
                }
                if (type == StatusType.SUCCESS || type == StatusType.UNSTABLE) {
                    end = Math.max(end, task.status().timestamp() + task.status().duration());
                }
            }
        }
        return end > pipeline.timestamp() ? end - pipeline.timestamp() : -1;
    }

    /**
     * Milliseconds the batches from the given one on are expected to take: for each its slowest pipeline and the
     * quiet time that ends it, and the sleep between two of them. A pipeline nothing is known of counts as the
     * average of the others; -1 when nothing is known of any.
     */
    private static long expected(List<Entry> entries, int fromBatch, int sleepSeconds) {
        long fallback = average(entries);
        if (fallback < 0) {
            return -1;
        }
        int last = 0;
        for (Entry entry : entries) {
            last = Math.max(last, entry.getBatch());
        }
        long total = 0;
        for (int batch = fromBatch; batch <= last; batch++) {
            long slowest = 0;
            for (Entry entry : entries) {
                if (entry.getBatch() == batch) {
                    slowest = Math.max(slowest, entry.getEstimate() > 0 ? entry.getEstimate() : fallback);
                }
            }
            total += slowest + ConsolidatedRuns.settleMillis() + (batch < last ? 1000L * sleepSeconds : 0);
        }
        return total;
    }

    private static long average(List<Entry> entries) {
        long sum = 0;
        int known = 0;
        for (Entry entry : entries) {
            if (entry.getEstimate() > 0) {
                sum += entry.getEstimate();
                known++;
            }
        }
        return known == 0 ? -1 : sum / known;
    }

    /** Milliseconds until the run going on is expected to end, or -1 when nothing is known of its pipelines. */
    private static long remaining(ConsolidatedRun run, long now) {
        List<Entry> entries = run.getEntries();
        long fallback = average(entries);
        if (fallback < 0) {
            return -1;
        }
        boolean stopping = run.getState() == ConsolidatedRun.State.STOPPING;
        long later = stopping ? 0 : expected(entries, run.getBatch() + 1, run.getSleepSeconds());
        if (run.getState() == ConsolidatedRun.State.SLEEPING) {
            return Math.max(0, run.getNextBatchAt() - now) + later;
        }
        long current = 0;
        boolean going = false;
        for (Entry entry : entries) {
            if (entry.getBatch() != run.getBatch() || entry.isOver()) {
                continue;
            }
            going = true;
            long estimate = entry.getEstimate() > 0 ? entry.getEstimate() : fallback;
            // a pipeline in the queue has all of its time before it; one that is overdue may end any moment
            long spent = entry.getStatus() == StatusType.RUNNING ? now - entry.getSince() : 0;
            current = Math.max(current, Math.max(0, estimate - spent));
        }
        boolean more = !stopping && run.getBatch() < run.getBatches();
        return current + (going ? ConsolidatedRuns.settleMillis() : 0) + (more ? 1000L * run.getSleepSeconds() : 0)
                + later;
    }

    private static long elapsed(ConsolidatedRun run) {
        if (run == null) {
            return 0;
        }
        long end = run.getFinishedAt() > 0 ? run.getFinishedAt() : System.currentTimeMillis();
        return Math.max(0, end - run.getStartedAt());
    }

    private static Status statusOf(ConsolidatedRun run) {
        if (run == null) {
            return Status.idle();
        }
        if (run.isActive()) {
            return Status.running(run.getStartedAt(), run.getEstimate());
        }
        StatusType worst = StatusType.SUCCESS;
        for (Entry entry : run.getEntries()) {
            if (rank(entry.getStatus()) > rank(worst)) {
                worst = entry.getStatus();
            }
        }
        return Status.finished(worst, run.getStartedAt(), run.getFinishedAt() - run.getStartedAt());
    }

    private static int rank(StatusType type) {
        return switch (type) {
            case FAILED -> 4;
            case CANCELLED -> 3;
            case UNSTABLE -> 2;
            default -> 1;
        };
    }

    /** A stage per batch, left to right and then on the next row, each leading to the next. */
    private static List<Stage> stages(List<Entry> entries, int perRow) {
        int batches = 0;
        for (Entry entry : entries) {
            batches = Math.max(batches, entry.getBatch());
        }
        List<Stage> stages = new ArrayList<>();
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            for (int batch = 1; batch <= batches; batch++) {
                List<Task> tasks = new ArrayList<>();
                for (int i = 0; i < entries.size(); i++) {
                    if (entries.get(i).getBatch() == batch) {
                        tasks.add(task(entries.get(i), i));
                    }
                }
                stages.add(new Stage("batch-" + batch, "Batch " + batch, (batch - 1) / perRow, (batch - 1) % perRow,
                        null, tasks, batch < batches ? List.of("batch-" + (batch + 1)) : List.of(), null));
            }
        }
        return stages;
    }

    /**
     * The task of a pipeline. The job is looked up as the system, as the jobs of a chain are, so that the model,
     * which is cached and served to every viewer, does not depend on who computed it.
     */
    private static Task task(Entry entry, int position) {
        Job<?, ?> job = Jenkins.get().getItemByFullName(entry.getJobFullName(), Job.class);
        String url = job == null ? "" : job.getUrl();
        if (job != null && entry.getBuildNumber() != null && job.getBuildByNumber(entry.getBuildNumber()) != null) {
            url += entry.getBuildNumber() + "/";
        }
        String name = entry.getNote() == null ? entry.getName() : entry.getName() + " (" + entry.getNote() + ")";
        return new Task("pipeline-" + (position + 1), name, url, entry.getJobFullName(), entry.getBuildNumber(),
                statusOf(entry), null, false, null, false, null, null, List.of(), List.of(), List.of(), List.of());
    }

    private static Status statusOf(Entry entry) {
        StatusType type = entry.getStatus();
        if (type == StatusType.QUEUED) {
            return Status.queued(entry.getSince());
        }
        if (type == StatusType.RUNNING) {
            return Status.running(entry.getSince(), entry.getEstimate());
        }
        if (type.isFinished()) {
            return Status.finished(type, entry.getSince(), entry.getDuration());
        }
        return Status.idle();
    }
}
