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

import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.TopLevelItem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.diabol.jenkins.pipeline.PipelineException;
import se.diabol.jenkins.pipeline.flow.FlowChain;
import se.diabol.jenkins.pipeline.details.TaskDetailsContributor;
import se.diabol.jenkins.pipeline.model.Change;
import se.diabol.jenkins.pipeline.model.Component;
import se.diabol.jenkins.pipeline.model.Contributor;
import se.diabol.jenkins.pipeline.model.JobRef;
import se.diabol.jenkins.pipeline.model.ManualStep;
import se.diabol.jenkins.pipeline.model.Paging;
import se.diabol.jenkins.pipeline.model.Pipeline;
import se.diabol.jenkins.pipeline.model.Stage;
import se.diabol.jenkins.pipeline.model.Status;
import se.diabol.jenkins.pipeline.model.StatusType;
import se.diabol.jenkins.pipeline.model.Task;
import se.diabol.jenkins.pipeline.model.ViewSettings;
import se.diabol.jenkins.pipeline.source.ComponentRequest;
import se.diabol.jenkins.pipeline.source.ComponentSource;

/**
 * Components made of chained jobs. The chain downstream of the first job is laid out once; each build of the first
 * job becomes a pipeline instance whose tasks are the builds it triggered.
 */
@Extension(ordinal = 100)
public class FreestyleComponentSource extends ComponentSource {

    /** A stage of the chain before any build is looked at. */
    private record TemplateStage(String id, int row, int column, List<ChainGraph.Node> nodes, List<String> downstream) {
    }

    /** The chain starting at one project, with the index that relates its builds. */
    private record Chain(ChainGraph graph, List<TemplateStage> stages, BuildIndex index) {
    }

    @Override
    public boolean supports(Job<?, ?> job) {
        return job instanceof AbstractProject;
    }

    @Override
    public Collection<? extends Job<?, ?>> jobsOf(Job<?, ?> firstJob, Job<?, ?> lastJob) {
        return ChainGraph.of((AbstractProject<?, ?>) firstJob, asProject(lastJob)).projects();
    }

    @Override
    public Component resolve(ComponentRequest request) throws PipelineException {
        AbstractProject<?, ?> first = (AbstractProject<?, ?>) request.firstJob();
        AbstractProject<?, ?> last = asProject(request.lastJob());
        ViewSettings settings = request.settings();
        Map<String, Chain> chains = new HashMap<>();
        Chain main = chainOf(chains, first, last);
        List<Pipeline> pipelines = new ArrayList<>();
        if (settings.showAggregatedPipeline()) {
            pipelines.add(aggregated(main, settings));
        }
        Iterable<? extends AbstractBuild<?, ?>> builds;
        int total;
        if (request.showUpstream()) {
            List<AbstractBuild<?, ?>> merged = new ArrayList<>();
            for (AbstractProject<?, ?> root : roots(first)) {
                merged.addAll(root.getBuilds());
            }
            merged.sort(Comparator.comparingLong((AbstractBuild<?, ?> build) -> build.getTimeInMillis()).reversed());
            builds = merged;
            total = merged.size();
        } else {
            if (first.isInQueue()) {
                pipelines.add(queued(main, settings));
            }
            builds = first.getBuilds();
            total = first.getBuilds().size();
        }
        Paging paging = request.paging() && settings.pagingEnabled()
                ? new Paging(Math.max(1, request.page()), settings.noOfPipelines(), total) : null;
        Iterator<? extends AbstractBuild<?, ?>> it = builds.iterator();
        for (int skip = paging == null ? 0 : paging.offset(); skip > 0 && it.hasNext(); skip--) {
            it.next();
        }
        for (int i = 0; i < settings.noOfPipelines() && it.hasNext(); i++) {
            AbstractBuild<?, ?> build = it.next();
            Chain chain = request.showUpstream() ? chainOf(chains, build.getProject(), last) : main;
            pipelines.add(FlowChain.expand(instance(chain, build, settings), settings));
        }
        return new Component(request.name(), request.index(), JobRef.of(first), paging, pipelines, null);
    }

    private static AbstractProject<?, ?> asProject(Job<?, ?> job) {
        return job instanceof AbstractProject<?, ?> project ? project : null;
    }

    private static Chain chainOf(Map<String, Chain> chains, AbstractProject<?, ?> first, AbstractProject<?, ?> last)
            throws PipelineException {
        Chain chain = chains.get(first.getFullName());
        if (chain == null) {
            chain = chain(first, last);
            chains.put(first.getFullName(), chain);
        }
        return chain;
    }

    private static Chain chain(AbstractProject<?, ?> first, AbstractProject<?, ?> last) throws PipelineException {
        ChainGraph graph = ChainGraph.of(first, last);
        Map<String, List<ChainGraph.Node>> byStage = graph.stages();
        List<String> ids = new ArrayList<>(byStage.keySet());
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (Map.Entry<String, List<ChainGraph.Node>> entry : byStage.entrySet()) {
            Set<String> targets = new LinkedHashSet<>();
            for (ChainGraph.Node node : entry.getValue()) {
                for (String downstream : node.downstream()) {
                    String target = graph.stageOf(downstream);
                    if (target != null && !target.equals(entry.getKey())) {
                        targets.add(target);
                    }
                }
            }
            edges.put(entry.getKey(), new ArrayList<>(targets));
        }
        Map<String, StageLayout.Position> positions =
                StageLayout.place(ids, edges, graph.stageOf(first.getFullName()));
        List<TemplateStage> stages = new ArrayList<>();
        for (Map.Entry<String, StageLayout.Position> entry : positions.entrySet()) {
            String id = entry.getKey();
            stages.add(new TemplateStage(id, entry.getValue().row(), entry.getValue().column(), byStage.get(id),
                    edges.get(id)));
        }
        return new Chain(graph, stages, new BuildIndex(first));
    }

    /** The projects without upstream projects that the chain leading to the project starts from. */
    static List<AbstractProject<?, ?>> roots(AbstractProject<?, ?> project) {
        List<AbstractProject<?, ?>> result = new ArrayList<>();
        collectRoots(project, result, new HashSet<>());
        return result.isEmpty() ? List.of(project) : result;
    }

    private static void collectRoots(AbstractProject<?, ?> project, List<AbstractProject<?, ?>> into,
                                     Set<String> visited) {
        if (!visited.add(project.getFullName())) {
            return;
        }
        List<AbstractProject> upstreams = project.getUpstreamProjects();
        if (upstreams.isEmpty()) {
            into.add(project);
            return;
        }
        for (AbstractProject<?, ?> upstream : upstreams) {
            collectRoots(upstream, into, visited);
        }
    }

    /**
     * The pipeline instance that starts at the given build: the chain downstream of its project laid out as a
     * component of that project shows it, with the builds the given one triggered. For a run of another kind of job
     * that started the build.
     */
    public static Pipeline instanceOf(AbstractBuild<?, ?> build, ViewSettings settings) throws PipelineException {
        return instance(chain(build.getProject(), null), build, settings);
    }

    private static Pipeline instance(Chain chain, AbstractBuild<?, ?> firstBuild, ViewSettings settings) {
        List<Stage> stages = stagesFor(chain, firstBuild, settings);
        List<Change> changes = Changes.of(firstBuild);
        List<Contributor> contributors = Changes.contributorsOf(changes);
        long totalBuildTime = settings.showTotalBuildTime() ? totalBuildTime(stages) : 0;
        return new Pipeline(chain.index().first().getFullName() + "#" + firstBuild.getNumber(),
                firstBuild.getDisplayName(), firstBuild.getTimeInMillis(), false,
                chain.index().first().getFullName(), firstBuild.getNumber(), false,
                Triggers.of(firstBuild.getCauses()), contributors, settings.showChanges() ? changes : List.of(),
                changes.size(), totalBuildTime, List.of(), List.of(), stages, Statuses.of(firstBuild));
    }

    /** The pipeline instance of a first job that is waiting in the queue. */
    private Pipeline queued(Chain chain, ViewSettings settings) {
        AbstractProject<?, ?> first = chain.index().first();
        Queue.Item item = first.getQueueItem();
        return new Pipeline(first.getFullName() + "#queued", "#" + first.getNextBuildNumber(),
                item == null ? 0 : item.getInQueueSince(), false, first.getFullName(), null, false,
                item == null ? List.of() : Triggers.of(item.getCauses()), List.of(), List.of(), 0, 0, List.of(),
                List.of(), stagesFor(chain, null, settings),
                item == null ? Status.idle() : Status.queued(item.getInQueueSince()));
    }

    private static List<Stage> stagesFor(Chain chain, AbstractBuild<?, ?> firstBuild, ViewSettings settings) {
        List<Stage> stages = new ArrayList<>();
        for (TemplateStage template : chain.stages()) {
            List<Task> tasks = new ArrayList<>();
            for (ChainGraph.Node node : template.nodes()) {
                tasks.add(task(chain, node, firstBuild, settings));
            }
            stages.add(new Stage(template.id(), template.id(), template.row(), template.column(), null, tasks,
                    template.downstream(), null));
        }
        return stages;
    }

    private static Task task(Chain chain, ChainGraph.Node node, AbstractBuild<?, ?> firstBuild, ViewSettings settings) {
        AbstractProject<?, ?> project = node.project();
        boolean queued = chain.index().isQueued(project, firstBuild);
        AbstractBuild<?, ?> build = queued ? null : chain.index().buildOf(project, firstBuild);
        Status status = Statuses.of(project, build, queued);
        ManualStep manual = ManualSteps.of(chain.index(), project, build, firstBuild, queued);
        boolean rebuildable = !node.initial() && isRebuildable(status.type());
        return taskOf(node, build, status, settings, manual, rebuildable);
    }

    /** The aggregated pipeline: each stage shows the newest version that reached it. */
    private Pipeline aggregated(Chain chain, ViewSettings settings) {
        List<Stage> stages = new ArrayList<>();
        for (TemplateStage template : chain.stages()) {
            AbstractBuild<?, ?> version = null;
            for (ChainGraph.Node node : template.nodes()) {
                AbstractBuild<?, ?> candidate = chain.index().newestFirstBuildReaching(node.project());
                if (candidate != null && (version == null || candidate.getNumber() > version.getNumber())) {
                    version = candidate;
                }
            }
            List<Task> tasks = new ArrayList<>();
            for (ChainGraph.Node node : template.nodes()) {
                AbstractBuild<?, ?> build = version == null ? null : chain.index().buildOf(node.project(), version);
                boolean queued = build == null && node.project().isInQueue();
                Status status = Statuses.of(node.project(), build, queued);
                tasks.add(taskOf(node, build, status, settings, null, false));
            }
            stages.add(new Stage(template.id(), template.id(), template.row(), template.column(),
                    version == null ? null : version.getDisplayName(), tasks, template.downstream(), null));
        }
        return new Pipeline("aggregated", null, 0, true, null, null, false, List.of(), List.of(), List.of(), 0, 0,
                List.of(), List.of(), stages, null);
    }

    private static Task taskOf(ChainGraph.Node node, AbstractBuild<?, ?> build, Status status, ViewSettings settings,
                               ManualStep manual, boolean rebuildable) {
        AbstractProject<?, ?> project = node.project();
        String name = Templates.expand(build, node.taskName());
        if (name.isBlank()) {
            name = project.getDisplayName();
        }
        String url = project.getUrl();
        if (build != null) {
            url = status.type() == StatusType.RUNNING ? build.getUrl() + "console" : build.getUrl();
        }
        String description = null;
        if (settings.showDescription()) {
            String template = node.descriptionTemplate();
            if (template.isBlank() && build != null) {
                template = build.getDescription();
            }
            description = Templates.toHtml(Templates.expand(build, template));
        }
        boolean details = build != null;
        return new Task(node.id(), name, url, node.id(), build == null ? null : build.getNumber(), status,
                description, rebuildable, null, false, null, manual,
                details && settings.showTestResults() ? TaskDetailsContributor.testsOf(build) : List.of(),
                details && settings.showStaticAnalysisResults() ? TaskDetailsContributor.analysisOf(build) : List.of(),
                details && settings.showPromotions() ? TaskDetailsContributor.promotionsOf(build) : List.of(),
                node.downstream());
    }

    private static boolean isRebuildable(StatusType type) {
        return type == StatusType.SUCCESS || type == StatusType.UNSTABLE || type == StatusType.FAILED
                || type == StatusType.CANCELLED;
    }

    /** The longest route from the first task to the end of the pipeline, in build time. */
    static long totalBuildTime(List<Stage> stages) {
        Map<String, Task> tasks = new HashMap<>();
        for (Stage stage : stages) {
            for (Task task : stage.tasks()) {
                tasks.put(task.id(), task);
            }
        }
        long result = 0;
        Map<String, Long> memo = new HashMap<>();
        for (Stage stage : stages) {
            for (Task task : stage.tasks()) {
                result = Math.max(result, routeTime(task.id(), tasks, memo, new HashSet<>()));
            }
        }
        return result;
    }

    private static long routeTime(String id, Map<String, Task> tasks, Map<String, Long> memo, Set<String> visiting) {
        Long known = memo.get(id);
        if (known != null) {
            return known;
        }
        Task task = tasks.get(id);
        if (task == null || !visiting.add(id)) {
            return 0;
        }
        long longest = 0;
        for (String downstream : task.downstream()) {
            longest = Math.max(longest, routeTime(downstream, tasks, memo, visiting));
        }
        visiting.remove(id);
        long result = task.status().exportedDuration() + longest;
        memo.put(id, result);
        return result;
    }

    @Override
    public void rebuild(Job<?, ?> job, int buildNumber) throws PipelineException {
        AbstractProject<?, ?> project = (AbstractProject<?, ?>) job;
        project.checkPermission(Item.BUILD);
        AbstractBuild<?, ?> build = project.getBuildByNumber(buildNumber);
        if (build == null) {
            throw new PipelineException("Build " + buildNumber + " of " + job.getFullName() + " does not exist");
        }
        List<Cause> causes = new ArrayList<>();
        for (Cause cause : build.getCauses()) {
            if (!(cause instanceof Cause.UserIdCause)) {
                causes.add(cause);
            }
        }
        causes.add(new Cause.UserIdCause());
        List<Action> actions = new ArrayList<>();
        actions.add(new CauseAction(causes));
        ParametersAction parameters = build.getAction(ParametersAction.class);
        if (parameters != null) {
            actions.add(parameters);
        }
        if (project.scheduleBuild2(project.getQuietPeriod(), null, actions) == null) {
            throw new PipelineException("Could not schedule a build of " + job.getFullName());
        }
    }

    @Override
    public void abort(Job<?, ?> job, int buildNumber) throws PipelineException {
        AbstractProject<?, ?> project = (AbstractProject<?, ?>) job;
        project.checkPermission(Item.CANCEL);
        AbstractBuild<?, ?> build = project.getBuildByNumber(buildNumber);
        if (build == null || !build.isBuilding()) {
            throw new PipelineException("Build " + buildNumber + " of " + job.getFullName() + " is not running");
        }
        Executor executor = build.getExecutor();
        if (executor == null) {
            executor = build.getOneOffExecutor();
        }
        if (executor == null) {
            throw new PipelineException("Build " + buildNumber + " of " + job.getFullName() + " has no executor");
        }
        executor.interrupt();
    }

    @Override
    public void triggerManual(Job<?, ?> job, Job<?, ?> upstream, int upstreamBuild,
                              ItemGroup<? extends TopLevelItem> context) throws PipelineException {
        AbstractProject<?, ?> project = (AbstractProject<?, ?>) job;
        AbstractProject<?, ?> upstreamProject = asProject(upstream);
        if (upstreamProject == null) {
            throw new PipelineException("The upstream job of " + job.getFullName() + " is not a chained job");
        }
        project.checkPermission(Item.BUILD);
        for (ManualTriggerProvider provider : ManualTriggerProvider.all()) {
            if (provider.trigger(project, upstreamProject, upstreamBuild, context)) {
                return;
            }
        }
        throw new PipelineException("No manual trigger leads from " + upstreamProject.getFullName() + " to "
                + job.getFullName());
    }
}
