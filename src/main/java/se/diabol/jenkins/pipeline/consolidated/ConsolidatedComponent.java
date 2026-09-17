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
        Consolidated consolidated = new Consolidated(run == null ? Consolidated.IDLE : run.getState().name(),
                run == null ? 0 : run.getNumber(), run == null ? 0 : run.getBatch(),
                run == null ? batches : run.getBatches(), finished, failed,
                run == null ? entries.size() : run.getEntries().size(),
                active ? run.getConcurrentPipelines() : Math.max(1, concurrentPipelines),
                active ? run.getSleepSeconds() : Math.max(0, sleepSeconds),
                run != null && run.getNextBatchAt() > 0 ? run.getNextBatchAt() : null,
                run == null ? null : run.getStartedAt(),
                run != null && run.getFinishedAt() > 0 ? run.getFinishedAt() : null,
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
            Entry entry = new Entry(target.name(), target.jobFullName(), target.lastJobFullName(), i / size + 1, -1);
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
