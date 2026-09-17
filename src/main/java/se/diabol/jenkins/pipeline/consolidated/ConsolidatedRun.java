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

import java.util.ArrayList;
import java.util.List;
import se.diabol.jenkins.pipeline.model.StatusType;

/**
 * One run of a view's consolidated pipeline: the pipelines it was started with, in batches, and how far it got. It
 * is what {@link ConsolidatedRuns} keeps on disk, so it holds names and numbers only, and it is a class rather than
 * a record because XStream writes its fields.
 *
 * <p>The plan is fixed when the run starts: a view that is reconfigured, or replaced by a seed job, while its run is
 * going does not change what the run does. Instances handed out by {@link ConsolidatedRuns#of} are copies that
 * nothing changes any more.
 */
public final class ConsolidatedRun {

    /** Where a run stands. The first three are going on, the last two are over. */
    public enum State {
        /** The pipelines of the current batch are queued or running. */
        RUNNING,
        /** The current batch has finished and the next one starts at {@link ConsolidatedRun#getNextBatchAt()}. */
        SLEEPING,
        /** Someone stopped the run: no further batch starts, and the run ends when the current batch has. */
        STOPPING,
        /** Every batch ran. */
        FINISHED,
        /** Stopped before every batch ran. */
        STOPPED;

        public boolean isActive() {
            return this == RUNNING || this == SLEEPING || this == STOPPING;
        }
    }

    /** One pipeline of the run. */
    public static final class Entry {
        private final String name;
        private final String jobFullName;
        private final String lastJobFullName;
        private final int batch;
        private final long estimate;
        private StatusType status = StatusType.IDLE;
        private long queueId;
        private Integer buildNumber;
        private long since;
        private long duration;
        private String note;
        /** Since when nothing of the pipeline has been running or queued; 0 while something is. Not kept on disk. */
        private transient long quietSince;

        Entry(String name, String jobFullName, String lastJobFullName, int batch, long estimate) {
            this.name = name;
            this.jobFullName = jobFullName;
            this.lastJobFullName = lastJobFullName;
            this.batch = batch;
            this.estimate = estimate;
        }

        private Entry(Entry other) {
            this(other.name, other.jobFullName, other.lastJobFullName, other.batch, other.estimate);
            status = other.status;
            queueId = other.queueId;
            buildNumber = other.buildNumber;
            since = other.since;
            duration = other.duration;
            note = other.note;
            quietSince = other.quietSince;
        }

        /** The heading of the pipeline's component in the view. */
        public String getName() {
            return name;
        }

        /** The job the pipeline starts with, which the run builds. */
        public String getJobFullName() {
            return jobFullName;
        }

        /** The job the pipeline ends with as its component is configured, or null for the whole chain. */
        public String getLastJobFullName() {
            return lastJobFullName;
        }

        /** The 1-based batch the pipeline belongs to. */
        public int getBatch() {
            return batch;
        }

        /** How long the pipeline took in the run before this one, in milliseconds, or -1. */
        public long getEstimate() {
            return estimate;
        }

        /**
         * {@link StatusType#IDLE} until the batch starts, then {@link StatusType#QUEUED} and
         * {@link StatusType#RUNNING}, which holds until nothing of the pipeline is running or queued any more, and
         * at last the worst outcome among its tasks; {@link StatusType#NOT_BUILT} when it was never started.
         */
        public StatusType getStatus() {
            return status == null ? StatusType.IDLE : status;
        }

        /** The build of the first job the run started, once it has left the queue. */
        public Integer getBuildNumber() {
            return buildNumber;
        }

        /** Epoch milliseconds since when the pipeline is queued, or at which its first build started; 0 before. */
        public long getSince() {
            return since;
        }

        /** Milliseconds from the start of the first build until nothing was running or queued any more. */
        public long getDuration() {
            return duration;
        }

        /** Why the pipeline was not started or how it was lost, or null. */
        public String getNote() {
            return note;
        }

        public boolean isOver() {
            return getStatus().isFinished();
        }

        long queueId() {
            return queueId;
        }

        long quietSince() {
            return quietSince;
        }

        void queued(long queueId, long since) {
            this.status = StatusType.QUEUED;
            this.queueId = queueId;
            this.since = since;
        }

        void running(int buildNumber, long since) {
            this.status = StatusType.RUNNING;
            this.buildNumber = buildNumber;
            this.since = since;
        }

        void quiet(long quietSince) {
            this.quietSince = quietSince;
        }

        void over(StatusType outcome, long duration, String note) {
            this.status = outcome;
            this.duration = Math.max(0, duration);
            this.note = note;
            this.quietSince = 0;
        }
    }

    private final String viewKey;
    private final String viewName;
    private final int number;
    private final int concurrentPipelines;
    private final int sleepSeconds;
    private final String startedById;
    private final String startedBy;
    private final long startedAt;
    private final long estimate;
    private final List<Entry> entries;
    private State state = State.RUNNING;
    private int batch;
    private long nextBatchAt;
    private long finishedAt;
    private String stoppedBy;

    ConsolidatedRun(String viewKey, String viewName, int number, int concurrentPipelines, int sleepSeconds,
                    String startedById, String startedBy, long startedAt, long estimate, List<Entry> entries) {
        this.viewKey = viewKey;
        this.viewName = viewName;
        this.number = number;
        this.concurrentPipelines = concurrentPipelines;
        this.sleepSeconds = sleepSeconds;
        this.startedById = startedById;
        this.startedBy = startedBy;
        this.startedAt = startedAt;
        this.estimate = estimate;
        this.entries = entries;
    }

    /** A copy that shares nothing with this run. */
    ConsolidatedRun copy() {
        List<Entry> copies = new ArrayList<>();
        for (Entry entry : getEntries()) {
            copies.add(new Entry(entry));
        }
        ConsolidatedRun copy = new ConsolidatedRun(viewKey, viewName, number, concurrentPipelines, sleepSeconds,
                startedById, startedBy, startedAt, estimate, copies);
        copy.state = state;
        copy.batch = batch;
        copy.nextBatchAt = nextBatchAt;
        copy.finishedAt = finishedAt;
        copy.stoppedBy = stoppedBy;
        return copy;
    }

    /** What identifies the view: the URL of the view relative to the Jenkins root. */
    public String getViewKey() {
        return viewKey;
    }

    public String getViewName() {
        return viewName;
    }

    /** The number of the run within its view, starting at 1. */
    public int getNumber() {
        return number;
    }

    public int getConcurrentPipelines() {
        return concurrentPipelines;
    }

    public int getSleepSeconds() {
        return sleepSeconds;
    }

    /** The id of the user who started the run, or null for an anonymous one. */
    public String getStartedById() {
        return startedById;
    }

    /** The display name of the user who started the run. */
    public String getStartedBy() {
        return startedBy;
    }

    public long getStartedAt() {
        return startedAt;
    }

    /** How long the run before this one took when it ran to its end, in milliseconds, or -1. */
    public long getEstimate() {
        return estimate;
    }

    public List<Entry> getEntries() {
        return entries == null ? List.of() : List.copyOf(entries);
    }

    public State getState() {
        return state == null ? State.FINISHED : state;
    }

    public boolean isActive() {
        return getState().isActive();
    }

    /** The 1-based batch the run is at, or ended with. */
    public int getBatch() {
        return batch;
    }

    public int getBatches() {
        int last = 0;
        for (Entry entry : getEntries()) {
            last = Math.max(last, entry.getBatch());
        }
        return last;
    }

    /** Epoch milliseconds at which the next batch starts while {@link State#SLEEPING}, else 0. */
    public long getNextBatchAt() {
        return nextBatchAt;
    }

    /** Epoch milliseconds at which the run ended, 0 while it is going. */
    public long getFinishedAt() {
        return finishedAt;
    }

    /** The display name of the user who stopped the run, or null. */
    public String getStoppedBy() {
        return stoppedBy;
    }

    /** The first jobs of the run's pipelines. */
    public List<String> jobs() {
        List<String> jobs = new ArrayList<>();
        for (Entry entry : getEntries()) {
            jobs.add(entry.getJobFullName());
        }
        return jobs;
    }

    List<Entry> entriesOf(int batch) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : getEntries()) {
            if (entry.getBatch() == batch) {
                result.add(entry);
            }
        }
        return result;
    }

    Entry entryOf(String jobFullName) {
        for (Entry entry : getEntries()) {
            if (entry.getJobFullName().equals(jobFullName)) {
                return entry;
            }
        }
        return null;
    }

    void running(int batch) {
        this.state = State.RUNNING;
        this.batch = batch;
        this.nextBatchAt = 0;
    }

    void sleeping(long nextBatchAt) {
        this.state = State.SLEEPING;
        this.nextBatchAt = nextBatchAt;
    }

    void stopping(String stoppedBy) {
        this.state = State.STOPPING;
        this.stoppedBy = stoppedBy;
        this.nextBatchAt = 0;
    }

    void over(State state, long finishedAt) {
        this.state = state;
        this.finishedAt = finishedAt;
        this.nextBatchAt = 0;
    }
}
