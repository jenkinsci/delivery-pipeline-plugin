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

import hudson.model.AbstractBuild;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import se.diabol.jenkins.pipeline.PipelineException;
import se.diabol.jenkins.pipeline.PipelineProperty;
import se.diabol.jenkins.pipeline.details.TaskDetailsContributor;
import se.diabol.jenkins.pipeline.freestyle.FreestyleComponentSource;
import se.diabol.jenkins.pipeline.freestyle.Statuses;
import se.diabol.jenkins.pipeline.freestyle.Templates;
import se.diabol.jenkins.pipeline.model.Pipeline;
import se.diabol.jenkins.pipeline.model.Stage;
import se.diabol.jenkins.pipeline.model.Status;
import se.diabol.jenkins.pipeline.model.StatusType;
import se.diabol.jenkins.pipeline.model.Task;
import se.diabol.jenkins.pipeline.model.ViewSettings;

/**
 * A run together with the runs it started, as one pipeline instance. The stages of a started run follow the stage
 * that started it, on the first rows with room for them, with an arrow from that stage and from the task that holds
 * the step; the runs they started follow in turn. Their stage and task ids carry the started run's id as a prefix,
 * their stage names its job's name. A started run still waiting in the queue is one queued task; one that was
 * cancelled before it started, or has been deleted since, is left out. A started job that is not a Pipeline brings
 * the chain of jobs downstream of it, laid out as a component of that job would show it.
 *
 * <p>Which runs a run started comes from {@link DownstreamRuns}. The jobs are looked up as the system, so that the
 * pipeline, which is cached and served to every viewer, does not depend on who computed it: as with chains of jobs,
 * everyone who can see the view sees every job the chain reaches, and acting on one still needs the permission on
 * that job.
 *
 * <p>The class also builds the aggregated row of a Pipeline job, in which every stage shows the newest run that ran
 * it.
 */
final class FlowChain {

    /** How far a chain is followed: runs that start runs that start runs, this many levels deep. */
    private static final int MAX_DEPTH = 8;

    /** How many started runs one pipeline instance shows at most, whatever the depth. */
    private static final int MAX_RUNS = 50;

    /** How many runs back the aggregated row looks for the newest run in which a stage ran. */
    private static final int AGGREGATED_RUNS = 20;

    /** The stages of one run as placed on the grid, with the prefix their ids carry. */
    private record Placed(Run<?, ?> run, List<Stage> stages, String prefix) {
    }

    private final ViewSettings settings;
    private final List<Stage> grid = new ArrayList<>();
    private final List<BitSet> rows = new ArrayList<>();
    private final Set<String> visited = new HashSet<>();
    private long latestEnd;
    private int runs;

    private FlowChain(ViewSettings settings) {
        this.settings = settings;
    }

    /** The pipeline instance of the run, with the runs it started. */
    static Pipeline of(WorkflowRun run, ViewSettings settings) {
        Pipeline own = FlowRuns.of(run).pipeline();
        if (own.stages().isEmpty()) {
            return own;
        }
        FlowChain chain = new FlowChain(settings);
        chain.visited.add(run.getExternalizableId());
        chain.latestEnd = endOf(run);
        Placed root = chain.place(run, own.stages(), 0, "", null);
        chain.follow(root, 1);
        if (chain.runs == 0) {
            return own;
        }
        chain.grid.sort(Comparator.comparingInt(Stage::row).thenComparingInt(Stage::column));
        long totalBuildTime = Math.max(own.totalBuildTime(), chain.latestEnd - run.getTimeInMillis());
        return own.withStages(chain.grid, totalBuildTime);
    }

    /**
     * The aggregated row of a Pipeline job: laid out like the newest run that completed its stages, since a failed
     * scripted run stops at the failing stage, each stage shows the newest run in which it ran, with that run's
     * display name as the version, or the layout run's stage as it is, without a version, when no recent run ran
     * it. Null for a job without runs.
     */
    static Pipeline aggregated(WorkflowJob job, ViewSettings settings) {
        List<WorkflowRun> recent = new ArrayList<>();
        WorkflowRun complete = null;
        for (WorkflowRun run : job.getBuilds()) {
            if (recent.size() == AGGREGATED_RUNS) {
                break;
            }
            recent.add(run);
            Result result = run.getResult();
            if (complete == null && !run.isBuilding() && result != null && result.isBetterOrEqualTo(Result.UNSTABLE)) {
                complete = run;
            }
        }
        if (recent.isEmpty()) {
            return null;
        }
        Map<String, Pipeline> instances = new HashMap<>();
        Pipeline layout = instanceOf(complete != null ? complete : recent.get(0), settings, instances);
        List<Stage> stages = new ArrayList<>();
        for (Stage stage : layout.stages()) {
            Stage source = stage;
            Pipeline from = layout;
            String version = null;
            for (WorkflowRun run : recent) {
                Pipeline instance = instanceOf(run, settings, instances);
                Stage candidate = stageNamed(instance, stage.name());
                if (candidate != null && reached(candidate)) {
                    source = candidate;
                    from = instance;
                    version = instance.version();
                    break;
                }
            }
            List<Task> tasks = new ArrayList<>();
            for (Task task : source.tasks()) {
                tasks.add(withIds(task, from.id() + "/"));
            }
            stages.add(new Stage(stage.id(), stage.name(), stage.row(), stage.column(), version, tasks,
                    stage.downstream()));
        }
        return new Pipeline("aggregated", null, 0, true, null, null, false, List.of(), List.of(), List.of(), 0, 0,
                List.of(), List.of(), stages);
    }

    private static Pipeline instanceOf(WorkflowRun run, ViewSettings settings, Map<String, Pipeline> instances) {
        return instances.computeIfAbsent(run.getExternalizableId(), id -> of(run, settings));
    }

    private static Stage stageNamed(Pipeline pipeline, String name) {
        for (Stage stage : pipeline.stages()) {
            if (stage.name().equals(name)) {
                return stage;
            }
        }
        return null;
    }

    /** Whether the stage ran, runs or waits in its run: any task that is not idle, disabled or not built. */
    private static boolean reached(Stage stage) {
        for (Task task : stage.tasks()) {
            StatusType type = task.status().type();
            if (type != StatusType.NOT_BUILT && type != StatusType.IDLE && type != StatusType.DISABLED) {
                return true;
            }
        }
        return false;
    }

    /** Adds the runs the placed run started, and theirs, to the grid. */
    private void follow(Placed placed, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (DownstreamRuns.Started started : DownstreamRuns.of(placed.run())) {
            if (runs >= MAX_RUNS) {
                return;
            }
            Job<?, ?> job = jobNamed(started.jobFullName());
            if (job == null) {
                continue;
            }
            Stage from = stageOf(placed, started.flowNodeId());
            Placed next;
            Run<?, ?> startedRun = started.buildNumber() == null ? null : job.getBuildByNumber(started.buildNumber());
            if (startedRun != null) {
                if (!visited.add(startedRun.getExternalizableId())) {
                    continue;
                }
                List<Stage> stages = stagesOf(startedRun);
                if (stages.isEmpty()) {
                    continue;
                }
                next = place(startedRun, stages, from.column() + 1, startedRun.getExternalizableId() + "/",
                        job.getDisplayName());
                latestEnd = Math.max(latestEnd, Math.max(endOf(startedRun), endOf(stages)));
            } else {
                Queue.Item item = started.buildNumber() == null && job instanceof Queue.Task task
                        ? Jenkins.get().getQueue().getItem(task) : null;
                if (item == null) {
                    continue;
                }
                next = place(null, List.of(queuedStage(job, item)), from.column() + 1,
                        job.getFullName() + "#queued-" + runs + "/", job.getDisplayName());
            }
            runs++;
            link(placed, from, started.flowNodeId(), firstOf(next.stages()));
            if (startedRun != null) {
                follow(next, depth + 1);
            }
        }
    }

    /**
     * The stages a started run contributes: a Pipeline run's own; for a job that is not a Pipeline, the chain of
     * jobs downstream of it as a component of that job lays it out, or failing that the run as one task.
     */
    private List<Stage> stagesOf(Run<?, ?> run) {
        if (run instanceof WorkflowRun flow) {
            return FlowRuns.of(flow).pipeline().stages();
        }
        if (run instanceof AbstractBuild<?, ?> build) {
            try {
                return FreestyleComponentSource.instanceOf(build, settings).stages();
            } catch (PipelineException e) {
                return List.of(buildStage(run));
            }
        }
        return List.of(buildStage(run));
    }

    /**
     * Puts the stages of a run on the grid from the given column on, keeping their rows and columns relative to
     * each other, on the first rows with room for them; prefixes their ids and gives their names the job's name
     * unless that is the name already.
     */
    private Placed place(Run<?, ?> run, List<Stage> stages, int firstColumn, String prefix, String jobName) {
        List<BitSet> shape = new ArrayList<>();
        for (Stage stage : stages) {
            while (shape.size() <= stage.row()) {
                shape.add(new BitSet());
            }
            shape.get(stage.row()).set(firstColumn + stage.column());
        }
        int firstRow = rowWithRoom(shape);
        List<Stage> placed = new ArrayList<>();
        for (Stage stage : stages) {
            List<Task> tasks = new ArrayList<>();
            for (Task task : stage.tasks()) {
                tasks.add(withIds(task, prefix));
            }
            String name = jobName == null || jobName.equals(stage.name()) ? stage.name() : jobName + ": " + stage.name();
            placed.add(new Stage(prefix + stage.id(), name, firstRow + stage.row(), firstColumn + stage.column(),
                    stage.version(), tasks, prefixed(stage.downstream(), prefix)));
        }
        grid.addAll(placed);
        return new Placed(run, placed, prefix);
    }

    /** The first row from which the shape, one set of columns per row, fits into free cells of the grid; takes them. */
    private int rowWithRoom(List<BitSet> shape) {
        for (int first = 0; ; first++) {
            while (rows.size() < first + shape.size()) {
                rows.add(new BitSet());
            }
            boolean free = true;
            for (int i = 0; i < shape.size() && free; i++) {
                free = !rows.get(first + i).intersects(shape.get(i));
            }
            if (free) {
                for (int i = 0; i < shape.size(); i++) {
                    rows.get(first + i).or(shape.get(i));
                }
                return first;
            }
        }
    }

    /** The stage a chain enters by: the first one by row and column. */
    private static Stage firstOf(List<Stage> stages) {
        Stage first = stages.get(0);
        for (Stage stage : stages) {
            if (stage.row() < first.row() || stage.row() == first.row() && stage.column() < first.column()) {
                first = stage;
            }
        }
        return first;
    }

    /** Draws the arrows from the stage, and from its task that holds the step, to the first stage of a started run. */
    private void link(Placed placed, Stage from, String nodeId, Stage to) {
        Stage current = from;
        int index = -1;
        for (int i = 0; i < grid.size(); i++) {
            if (grid.get(i).id().equals(from.id())) {
                current = grid.get(i);
                index = i;
            }
        }
        if (index < 0) {
            return;
        }
        FlowNode node = nodeOf(placed.run(), nodeId);
        List<String> enclosing = node == null ? List.of() : node.getAllEnclosingIds();
        String target = to.tasks().get(0).id();
        List<Task> tasks = new ArrayList<>(current.tasks());
        int holder = 0;
        for (int i = 0; i < tasks.size(); i++) {
            String raw = rawId(tasks.get(i).id(), placed.prefix());
            if (raw.equals(nodeId) || enclosing.contains(raw)) {
                holder = i;
                break;
            }
        }
        if (!tasks.isEmpty()) {
            tasks.set(holder, withDownstream(tasks.get(holder), target));
        }
        List<String> downstream = new ArrayList<>(current.downstream());
        downstream.add(to.id());
        grid.set(index, new Stage(current.id(), current.name(), current.row(), current.column(), current.version(),
                tasks, downstream));
    }

    /**
     * The placed stage that holds the flow node: the block stage enclosing it, else the nearest legacy stage step
     * started before it, else the last stage, which is the whole run when the run has no stages.
     */
    private static Stage stageOf(Placed placed, String nodeId) {
        List<Stage> stages = placed.stages();
        Stage last = stages.get(stages.size() - 1);
        FlowNode node = nodeOf(placed.run(), nodeId);
        if (node == null) {
            return last;
        }
        List<String> enclosing = node.getAllEnclosingIds();
        for (Stage stage : stages) {
            if (enclosing.contains(rawId(stage.id(), placed.prefix()))) {
                return stage;
            }
        }
        long position = FlowGraph.order(node);
        Stage best = null;
        long bestStart = -1;
        for (Stage stage : stages) {
            long start = orderOf(rawId(stage.id(), placed.prefix()));
            if (start < position && start > bestStart) {
                best = stage;
                bestStart = start;
            }
        }
        return best != null ? best : last;
    }

    private static FlowNode nodeOf(Run<?, ?> run, String nodeId) {
        if (nodeId == null || !(run instanceof WorkflowRun flow)) {
            return null;
        }
        FlowExecution execution = flow.getExecution();
        if (execution == null) {
            return null;
        }
        try {
            return execution.getNode(nodeId);
        } catch (IOException e) {
            return null;
        }
    }

    /** A run of a job that is not a Pipeline, as one task named as a chained job would be, with the run's details. */
    private static Stage buildStage(Run<?, ?> run) {
        Job<?, ?> job = run.getParent();
        PipelineProperty property = job.getProperty(PipelineProperty.class);
        String taskName = property == null || isBlank(property.getTaskName())
                ? "" : Templates.expand(run, property.getTaskName());
        if (taskName.isBlank()) {
            taskName = job.getDisplayName();
        }
        String stageName = property == null || isBlank(property.getStageName())
                ? job.getDisplayName() : property.getStageName();
        Status status = Statuses.of(run);
        String url = status.type() == StatusType.RUNNING ? run.getUrl() + "console" : run.getUrl();
        Task task = new Task("build", taskName, url, job.getFullName(), run.getNumber(), status, null,
                !run.isBuilding(), null, false, null, null, TaskDetailsContributor.testsOf(run),
                TaskDetailsContributor.analysisOf(run), TaskDetailsContributor.promotionsOf(run), List.of());
        return new Stage("build", stageName, 0, 0, null, List.of(task), List.of());
    }

    private static Stage queuedStage(Job<?, ?> job, Queue.Item item) {
        Task task = new Task("queued", job.getDisplayName(), job.getUrl(), job.getFullName(), null,
                Status.queued(item.getInQueueSince()), null, false, null, false, null, null, List.of(), List.of(),
                List.of(), List.of());
        return new Stage("queued", job.getDisplayName(), 0, 0, null, List.of(task), List.of());
    }

    private static Job<?, ?> jobNamed(String fullName) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            return Jenkins.get().getItemByFullName(fullName, Job.class);
        }
    }

    private static Task withIds(Task task, String prefix) {
        if (prefix.isEmpty()) {
            return task;
        }
        return new Task(prefix + task.id(), task.name(), task.url(), task.jobFullName(), task.buildNumber(),
                task.status(), task.description(), task.rebuildable(), task.restart(), task.requiresInput(),
                task.inputUrl(), task.manual(), task.tests(), task.analysis(), task.promotions(),
                prefixed(task.downstream(), prefix));
    }

    private static Task withDownstream(Task task, String id) {
        List<String> downstream = new ArrayList<>(task.downstream());
        downstream.add(id);
        return new Task(task.id(), task.name(), task.url(), task.jobFullName(), task.buildNumber(), task.status(),
                task.description(), task.rebuildable(), task.restart(), task.requiresInput(), task.inputUrl(),
                task.manual(), task.tests(), task.analysis(), task.promotions(), downstream);
    }

    private static List<String> prefixed(List<String> ids, String prefix) {
        if (prefix.isEmpty()) {
            return ids;
        }
        List<String> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            result.add(prefix + id);
        }
        return result;
    }

    /** The id without the prefix of its run: the flow node id of a Pipeline task or stage. */
    static String rawId(String id, String prefix) {
        return id.startsWith(prefix) ? id.substring(prefix.length()) : id;
    }

    private static long orderOf(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private static long endOf(Run<?, ?> run) {
        return run.isBuilding() ? System.currentTimeMillis() : run.getTimeInMillis() + run.getDuration();
    }

    /** When the last of the builds shown by the stages ended, or now while one still runs; 0 when none ran. */
    private static long endOf(List<Stage> stages) {
        long end = 0;
        for (Stage stage : stages) {
            for (Task task : stage.tasks()) {
                Status status = task.status();
                switch (status.type()) {
                    case RUNNING, PAUSED_PENDING_INPUT -> end = Math.max(end, System.currentTimeMillis());
                    case SUCCESS, UNSTABLE, FAILED, CANCELLED -> end = Math.max(end, status.timestamp() + status.duration());
                    default -> { }
                }
            }
        }
        return end;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
