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
package se.diabol.jenkins.pipeline;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Api;
import hudson.model.Descriptor;
import hudson.model.Describable;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.TopLevelItem;
import hudson.model.View;
import hudson.model.ViewDescriptor;
import hudson.model.ViewGroup;
import hudson.security.AccessControlled;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.interceptor.RequirePOST;
import se.diabol.jenkins.pipeline.cache.ModelCache;
import se.diabol.jenkins.pipeline.model.Component;
import se.diabol.jenkins.pipeline.model.ViewSettings;
import se.diabol.jenkins.pipeline.source.ComponentRequest;
import se.diabol.jenkins.pipeline.source.ComponentSource;

/**
 * A view that shows one or more delivery pipelines: chains of jobs with downstream dependencies, or Pipeline
 * (Jenkinsfile) jobs. The page polls {@code api/json} and renders the {@link Component} model it gets back.
 *
 * <p>Field names are the persisted format of 1.x, which Job DSL's {@code deliveryPipelineView} also writes.
 */
public class DeliveryPipelineView extends View {

    private static final Logger LOG = Logger.getLogger(DeliveryPipelineView.class.getName());

    static final int DEFAULT_INTERVAL = 5;
    static final int DEFAULT_NO_OF_PIPELINES = 3;
    static final int MAX_NO_OF_PIPELINES = 50;

    private List<ComponentSpec> componentSpecs;
    private List<RegExpSpec> regexpFirstJobs;
    private int noOfPipelines = DEFAULT_NO_OF_PIPELINES;
    private int noOfColumns = 1;
    private String sorting = Sorting.NONE.getId();
    private int updateInterval = DEFAULT_INTERVAL;
    private int maxNumberOfVisiblePipelines = -1;
    private boolean pagingEnabled;
    private boolean showAggregatedPipeline;
    private boolean showChanges;
    private boolean showDescription;
    private boolean showTotalBuildTime;
    private boolean showTestResults;
    private boolean showStaticAnalysisResults;
    private boolean showPromotions;
    private boolean showAbsoluteDateTime;
    private boolean allowPipelineStart;
    private boolean allowManualTriggers;
    private boolean allowRebuild;
    private boolean allowAbort;

    // Options of 1.x that 2.0 does not have. Jenkins reads transient fields from disk, so old configurations still
    // load; they are never written back. The description used to be kept twice and is moved to the view's own.
    private transient String description;
    private transient boolean showAvatars;
    private transient boolean linkRelative;
    private transient boolean linkToConsoleLog;
    private transient boolean showAggregatedChanges;
    private transient String aggregatedChangesGroupingPattern;
    private transient String fullScreenCss;
    private transient String embeddedCss;
    private transient String theme;

    @DataBoundConstructor
    public DeliveryPipelineView(String name) {
        super(name);
    }

    public DeliveryPipelineView(String name, ViewGroup owner) {
        super(name, owner);
    }

    protected Object readResolve() {
        if (super.description == null && description != null) {
            super.description = description;
        }
        description = null;
        sorting = Sorting.fromId(sorting).getId();
        if (updateInterval <= 0) {
            updateInterval = DEFAULT_INTERVAL;
        }
        return this;
    }

    /* ------------------------------------------------------------------ configuration */

    public List<ComponentSpec> getComponentSpecs() {
        return componentSpecs == null ? List.of() : componentSpecs;
    }

    public void setComponentSpecs(List<ComponentSpec> componentSpecs) {
        this.componentSpecs = componentSpecs == null ? null : new ArrayList<>(componentSpecs);
    }

    public List<RegExpSpec> getRegexpFirstJobs() {
        return regexpFirstJobs == null ? List.of() : regexpFirstJobs;
    }

    public void setRegexpFirstJobs(List<RegExpSpec> regexpFirstJobs) {
        this.regexpFirstJobs = regexpFirstJobs == null ? null : new ArrayList<>(regexpFirstJobs);
    }

    public int getNoOfPipelines() {
        return noOfPipelines;
    }

    public void setNoOfPipelines(int noOfPipelines) {
        this.noOfPipelines = Math.max(0, Math.min(MAX_NO_OF_PIPELINES, noOfPipelines));
    }

    public int getNoOfColumns() {
        return noOfColumns;
    }

    public void setNoOfColumns(int noOfColumns) {
        this.noOfColumns = Math.max(1, Math.min(3, noOfColumns));
    }

    public String getSorting() {
        return Sorting.fromId(sorting).getId();
    }

    public void setSorting(String sorting) {
        this.sorting = Sorting.fromId(sorting).getId();
    }

    public int getUpdateInterval() {
        return updateInterval <= 0 ? DEFAULT_INTERVAL : updateInterval;
    }

    public void setUpdateInterval(int updateInterval) {
        this.updateInterval = updateInterval <= 0 ? DEFAULT_INTERVAL : updateInterval;
    }

    public int getMaxNumberOfVisiblePipelines() {
        return maxNumberOfVisiblePipelines;
    }

    public void setMaxNumberOfVisiblePipelines(int maxNumberOfVisiblePipelines) {
        this.maxNumberOfVisiblePipelines = maxNumberOfVisiblePipelines;
    }

    public boolean isPagingEnabled() {
        return pagingEnabled;
    }

    /** The 1.x name of {@link #isPagingEnabled()}. */
    public boolean getPagingEnabled() {
        return pagingEnabled;
    }

    public void setPagingEnabled(boolean pagingEnabled) {
        this.pagingEnabled = pagingEnabled;
    }

    public boolean isShowAggregatedPipeline() {
        return showAggregatedPipeline;
    }

    public void setShowAggregatedPipeline(boolean showAggregatedPipeline) {
        this.showAggregatedPipeline = showAggregatedPipeline;
    }

    public boolean isShowChanges() {
        return showChanges;
    }

    public void setShowChanges(boolean showChanges) {
        this.showChanges = showChanges;
    }

    public boolean isShowDescription() {
        return showDescription;
    }

    public void setShowDescription(boolean showDescription) {
        this.showDescription = showDescription;
    }

    public boolean isShowTotalBuildTime() {
        return showTotalBuildTime;
    }

    public void setShowTotalBuildTime(boolean showTotalBuildTime) {
        this.showTotalBuildTime = showTotalBuildTime;
    }

    public boolean isShowTestResults() {
        return showTestResults;
    }

    public void setShowTestResults(boolean showTestResults) {
        this.showTestResults = showTestResults;
    }

    public boolean isShowStaticAnalysisResults() {
        return showStaticAnalysisResults;
    }

    public void setShowStaticAnalysisResults(boolean showStaticAnalysisResults) {
        this.showStaticAnalysisResults = showStaticAnalysisResults;
    }

    public boolean isShowPromotions() {
        return showPromotions;
    }

    public void setShowPromotions(boolean showPromotions) {
        this.showPromotions = showPromotions;
    }

    public boolean isShowAbsoluteDateTime() {
        return showAbsoluteDateTime;
    }

    public void setShowAbsoluteDateTime(boolean showAbsoluteDateTime) {
        this.showAbsoluteDateTime = showAbsoluteDateTime;
    }

    public boolean isAllowPipelineStart() {
        return allowPipelineStart;
    }

    public void setAllowPipelineStart(boolean allowPipelineStart) {
        this.allowPipelineStart = allowPipelineStart;
    }

    public boolean isAllowManualTriggers() {
        return allowManualTriggers;
    }

    public void setAllowManualTriggers(boolean allowManualTriggers) {
        this.allowManualTriggers = allowManualTriggers;
    }

    public boolean isAllowRebuild() {
        return allowRebuild;
    }

    public void setAllowRebuild(boolean allowRebuild) {
        this.allowRebuild = allowRebuild;
    }

    public boolean isAllowAbort() {
        return allowAbort;
    }

    public void setAllowAbort(boolean allowAbort) {
        this.allowAbort = allowAbort;
    }

    /* ------------------------------------------------------------------ the JSON the page polls */

    @Exported
    public ViewSettings getSettings() {
        return new ViewSettings(noOfPipelines, noOfColumns, getUpdateInterval(), pagingEnabled,
                showAggregatedPipeline, showChanges, showDescription, showTotalBuildTime, showTestResults,
                showStaticAnalysisResults, showPromotions, showAbsoluteDateTime, allowPipelineStart,
                allowManualTriggers, allowRebuild, allowAbort);
    }

    @Exported
    public long getServerTime() {
        return System.currentTimeMillis();
    }

    /**
     * The components with their pipelines. The request may carry {@code page} and {@code component} to page one
     * component's instances, and {@code fullscreen=true} to switch paging off.
     */
    @Exported
    public List<Component> getComponents() {
        StaplerRequest2 request = Stapler.getCurrentRequest2();
        int page = Math.max(1, intParameter(request, "page", 1));
        int pagedComponent = intParameter(request, "component", 1);
        boolean fullscreen = request != null && Boolean.parseBoolean(request.getParameter("fullscreen"));
        boolean paging = pagingEnabled && !fullscreen;
        return ModelCache.get().get(cacheKey(page, pagedComponent, paging),
                () -> resolveComponents(page, pagedComponent, paging));
    }

    static int intParameter(StaplerRequest2 request, String name, int defaultValue) {
        String value = request == null ? null : request.getParameter(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String cacheKey(int page, int pagedComponent, boolean paging) {
        return getViewUrl() + "|" + getSettings().hashCode() + "|" + getComponentSpecs().hashCode() + "|"
                + getRegexpFirstJobs().hashCode() + "|" + sorting + "|" + maxNumberOfVisiblePipelines + "|"
                + (paging ? pagedComponent + "/" + page : "-");
    }

    List<Component> resolveComponents(int page, int pagedComponent, boolean paging) {
        List<Component> components = new ArrayList<>();
        ViewSettings settings = getSettings();
        int index = 1;
        for (ComponentSpec spec : getComponentSpecs()) {
            components.add(resolve(spec.getName(), spec.getFirstJob(), spec.getLastJob(), spec.isShowUpstream(),
                    index, index == pagedComponent ? page : 1, paging, settings));
            index++;
        }
        for (RegExpSpec spec : getRegexpFirstJobs()) {
            for (Map.Entry<String, Job<?, ?>> match : matches(spec.getRegexp()).entrySet()) {
                components.add(resolve(match.getKey(), match.getValue(), null, spec.isShowUpstream(), index,
                        index == pagedComponent ? page : 1, paging, settings));
                index++;
            }
        }
        components.sort(Sorting.fromId(sorting).comparator());
        if (maxNumberOfVisiblePipelines > 0 && components.size() > maxNumberOfVisiblePipelines) {
            components = new ArrayList<>(components.subList(0, maxNumberOfVisiblePipelines));
        }
        return components;
    }

    private Component resolve(String name, String firstJobName, String lastJobName, boolean showUpstream, int index,
                              int page, boolean paging, ViewSettings settings) {
        Job<?, ?> first = findJob(firstJobName);
        if (first == null) {
            return Component.failed(name, index, "Could not find job " + firstJobName);
        }
        return resolve(name, first, lastJobName, showUpstream, index, page, paging, settings);
    }

    private Component resolve(String name, Job<?, ?> first, String lastJobName, boolean showUpstream, int index,
                              int page, boolean paging, ViewSettings settings) {
        Job<?, ?> last = null;
        if (lastJobName != null && !lastJobName.isBlank()) {
            last = findJob(lastJobName);
            if (last == null) {
                return Component.failed(name, index, "Could not find job " + lastJobName);
            }
        }
        try {
            ComponentSource source = ComponentSource.forJob(first);
            return source.resolve(new ComponentRequest(name, index, first, last, showUpstream, getOwnerItemGroup(),
                    settings, page, paging));
        } catch (PipelineException e) {
            return Component.failed(name, index, e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not resolve pipeline " + name + " of view " + getViewName(), e);
            return Component.failed(name, index, "Could not resolve the pipeline: " + e);
        }
    }

    /** The job with the given name relative to the view's item group, or null. */
    Job<?, ?> findJob(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        ItemGroup<?> context = getOwnerItemGroup();
        return Jenkins.get().getItem(name.trim(), context == null ? Jenkins.get() : context, Job.class);
    }

    /** Jobs whose full name matches the expression, keyed by its first capture group, in item order. */
    static Map<String, Job<?, ?>> matches(String regexp) {
        Map<String, Job<?, ?>> result = new LinkedHashMap<>();
        if (regexp == null || regexp.isBlank()) {
            return result;
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(regexp);
        } catch (PatternSyntaxException e) {
            LOG.log(Level.WARNING, "Ignoring invalid pipeline expression " + regexp, e);
            return result;
        }
        for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
            Matcher matcher = pattern.matcher(job.getFullName());
            if (matcher.find() && matcher.groupCount() >= 1 && isPipelineStart(job)) {
                result.putIfAbsent(matcher.group(1), job);
            }
        }
        return result;
    }

    static boolean isPipelineStart(Job<?, ?> job) {
        for (ComponentSource source : ComponentSource.all()) {
            if (source.supports(job)) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ actions the page posts */

    /** Triggers a manually triggered task, continuing from a build of its upstream job. */
    @RequirePOST
    public HttpResponse doManualStep(@QueryParameter String project, @QueryParameter String upstream,
                                     @QueryParameter String buildId) {
        if (!allowManualTriggers) {
            return HttpResponses.errorWithoutStack(403, "Manual triggers are not enabled for this view");
        }
        return action(project, buildId, (source, job, number) ->
                source.triggerManual(job, findJobByFullName(upstream), number, getOwnerItemGroup()));
    }

    /**
     * Schedules a build of the job like the one with the given number, or when a stage is named, restarts that
     * build from the stage.
     */
    @RequirePOST
    public HttpResponse doRebuild(@QueryParameter String project, @QueryParameter String buildId,
                                  @QueryParameter String stage) {
        if (!allowRebuild) {
            return HttpResponses.errorWithoutStack(403, "Rebuilding is not enabled for this view");
        }
        return action(project, buildId, (source, job, number) -> source.rebuild(job, number, stage));
    }

    /** Stops the build with the given number. */
    @RequirePOST
    public HttpResponse doAbort(@QueryParameter String project, @QueryParameter String buildId) {
        if (!allowAbort) {
            return HttpResponses.errorWithoutStack(403, "Aborting builds is not enabled for this view");
        }
        return action(project, buildId, ComponentSource::abort);
    }

    /** Lets a Pipeline run continue past the input step the given task, or else the run, is waiting at. */
    @RequirePOST
    public HttpResponse doProceedInput(@QueryParameter String project, @QueryParameter String buildId,
                                       @QueryParameter String task) {
        return action(project, buildId, (source, job, number) -> source.proceedInput(job, number, task));
    }

    private interface Action {
        void perform(ComponentSource source, Job<?, ?> job, int buildNumber) throws PipelineException;
    }

    private HttpResponse action(String project, String buildId, Action action) {
        Job<?, ?> job = findJobByFullName(project);
        if (job == null) {
            return HttpResponses.errorWithoutStack(404, "No such job: " + project);
        }
        int number;
        try {
            number = Integer.parseInt(buildId == null ? "" : buildId.trim());
        } catch (NumberFormatException e) {
            return HttpResponses.errorWithoutStack(400, "Not a build number: " + buildId);
        }
        try {
            action.perform(ComponentSource.forJob(job), job, number);
            return HttpResponses.ok();
        } catch (PipelineException e) {
            LOG.log(Level.FINE, "Action on " + project + " #" + buildId + " failed", e);
            return HttpResponses.errorWithoutStack(400, e.getMessage());
        }
    }

    private static Job<?, ?> findJobByFullName(String fullName) {
        return fullName == null ? null : Jenkins.get().getItemByFullName(fullName, Job.class);
    }

    /* ------------------------------------------------------------------ View */

    @Override
    public Api getApi() {
        return new PipelineApi(this);
    }

    @Override
    public Collection<TopLevelItem> getItems() {
        Set<TopLevelItem> items = new LinkedHashSet<>();
        for (ComponentSpec spec : getComponentSpecs()) {
            addJobs(items, findJob(spec.getFirstJob()), findJob(spec.getLastJob()));
        }
        for (RegExpSpec spec : getRegexpFirstJobs()) {
            for (Job<?, ?> job : matches(spec.getRegexp()).values()) {
                addJobs(items, job, null);
            }
        }
        return items;
    }

    private static void addJobs(Set<TopLevelItem> into, Job<?, ?> first, Job<?, ?> last) {
        if (first == null) {
            return;
        }
        try {
            for (Job<?, ?> job : ComponentSource.forJob(first).jobsOf(first, last)) {
                if (job instanceof TopLevelItem item) {
                    into.add(item);
                }
            }
        } catch (PipelineException e) {
            LOG.log(Level.FINE, "Cannot list the jobs of " + first.getFullName(), e);
        }
    }

    @Override
    public boolean contains(TopLevelItem item) {
        return getItems().contains(item);
    }

    @Override
    public void onJobRenamed(Item item, String oldName, String newName) {
        if (componentSpecs == null || oldName == null) {
            return;
        }
        Iterator<ComponentSpec> it = componentSpecs.iterator();
        while (it.hasNext()) {
            ComponentSpec spec = it.next();
            if (oldName.equals(spec.getFirstJob())) {
                if (newName == null) {
                    it.remove();
                    continue;
                }
                spec.setFirstJob(newName);
            }
            if (oldName.equals(spec.getLastJob())) {
                if (newName == null) {
                    it.remove();
                    continue;
                }
                spec.setLastJob(newName);
            }
        }
    }

    @Override
    protected void submit(StaplerRequest2 req) throws IOException, ServletException, Descriptor.FormException {
        req.bindJSON(this, req.getSubmittedForm());
        componentSpecs = req.bindJSONToList(ComponentSpec.class, req.getSubmittedForm().get("componentSpecs"));
        regexpFirstJobs = req.bindJSONToList(RegExpSpec.class, req.getSubmittedForm().get("regexpFirstJobs"));
    }

    @Override
    @RequirePOST
    public Item doCreateItem(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        ItemGroup<? extends TopLevelItem> owner = getOwnerItemGroup();
        (owner instanceof AccessControlled controlled ? controlled : Jenkins.get()).checkPermission(Item.CREATE);
        if (owner instanceof jenkins.model.ModifiableTopLevelItemGroup group) {
            return group.doCreateItem(req, rsp);
        }
        return Jenkins.get().doCreateItem(req, rsp);
    }

    /* ------------------------------------------------------------------ descriptors and specs */

    /**
     * Form validation and list filling happen on the view's configuration page: the caller must be allowed to
     * configure the view, or to create one where there is none yet.
     */
    static void checkConfigure(View view, ViewGroup owner) {
        if (view != null) {
            view.checkPermission(View.CONFIGURE);
        } else if (owner != null) {
            owner.checkPermission(View.CREATE);
        } else {
            Jenkins.get().checkPermission(View.CREATE);
        }
    }

    @Extension
    @Symbol("deliveryPipelineView")
    public static class DescriptorImpl extends ViewDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Delivery Pipeline View";
        }

        // The validators below are cheap, change nothing and reveal nothing, so they need no POST protection.

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public ListBoxModel doFillNoOfColumnsItems(@AncestorInPath View view, @AncestorInPath ViewGroup owner) {
            checkConfigure(view, owner);
            ListBoxModel options = new ListBoxModel();
            for (int i = 1; i <= 3; i++) {
                options.add(String.valueOf(i), String.valueOf(i));
            }
            return options;
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public ListBoxModel doFillNoOfPipelinesItems(@AncestorInPath View view, @AncestorInPath ViewGroup owner) {
            checkConfigure(view, owner);
            ListBoxModel options = new ListBoxModel();
            for (int i = 0; i <= MAX_NO_OF_PIPELINES; i++) {
                options.add(String.valueOf(i), String.valueOf(i));
            }
            return options;
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public ListBoxModel doFillSortingItems(@AncestorInPath View view, @AncestorInPath ViewGroup owner) {
            checkConfigure(view, owner);
            ListBoxModel options = new ListBoxModel();
            for (Sorting sorting : Sorting.values()) {
                options.add(sorting.getDisplayName(), sorting.getId());
            }
            return options;
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public FormValidation doCheckUpdateInterval(@AncestorInPath View view, @AncestorInPath ViewGroup owner,
                                                    @QueryParameter String value) {
            checkConfigure(view, owner);
            try {
                return Integer.parseInt(value) > 0 ? FormValidation.ok()
                        : FormValidation.error("The update interval must be at least one second");
            } catch (NumberFormatException e) {
                return FormValidation.error("The update interval must be a whole number of seconds");
            }
        }
    }

    /** One pipeline of the view: the job it starts with and, optionally, the one it ends with. */
    public static class ComponentSpec implements Describable<ComponentSpec> {
        private String name;
        private String firstJob;
        private String lastJob;
        private boolean showUpstream;

        @DataBoundConstructor
        public ComponentSpec(String name, String firstJob, String lastJob, boolean showUpstream) {
            this.name = name;
            this.firstJob = firstJob;
            this.lastJob = lastJob == null || lastJob.isBlank() ? null : lastJob;
            this.showUpstream = showUpstream;
        }

        public String getName() {
            return name;
        }

        public String getFirstJob() {
            return firstJob;
        }

        public void setFirstJob(String firstJob) {
            this.firstJob = firstJob;
        }

        public String getLastJob() {
            return lastJob;
        }

        public void setLastJob(String lastJob) {
            this.lastJob = lastJob;
        }

        public boolean isShowUpstream() {
            return showUpstream;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(name, firstJob, lastJob, showUpstream);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ComponentSpec spec && java.util.Objects.equals(name, spec.name)
                    && java.util.Objects.equals(firstJob, spec.firstJob) && java.util.Objects.equals(lastJob, spec.lastJob)
                    && showUpstream == spec.showUpstream;
        }

        @Extension
        public static class DescriptorImpl extends Descriptor<ComponentSpec> {
            @NonNull
            @Override
            public String getDisplayName() {
                return "";
            }

            /** Lists the jobs the caller may read, as {@code getAllItems} filters them. */
            @SuppressWarnings("lgtm[jenkins/csrf]")
            public ListBoxModel doFillFirstJobItems(@AncestorInPath View view, @AncestorInPath ViewGroup owner,
                                                    @AncestorInPath ItemGroup<?> context) {
                checkConfigure(view, owner);
                ListBoxModel options = new ListBoxModel();
                for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
                    if (isPipelineStart(job)) {
                        options.add(job.getFullDisplayName(), job.getRelativeNameFrom(context));
                    }
                }
                return options;
            }

            @SuppressWarnings("lgtm[jenkins/csrf]")
            public ListBoxModel doFillLastJobItems(@AncestorInPath View view, @AncestorInPath ViewGroup owner,
                                                   @AncestorInPath ItemGroup<?> context) {
                checkConfigure(view, owner);
                ListBoxModel options = new ListBoxModel();
                options.add("", "");
                for (AbstractProject<?, ?> job : Jenkins.get().getAllItems(AbstractProject.class)) {
                    options.add(job.getFullDisplayName(), job.getRelativeNameFrom(context));
                }
                return options;
            }

            @SuppressWarnings("lgtm[jenkins/csrf]")
            public FormValidation doCheckName(@AncestorInPath View view, @AncestorInPath ViewGroup owner,
                                              @QueryParameter String value) {
                checkConfigure(view, owner);
                return value == null || value.isBlank() ? FormValidation.error("Please supply a title")
                        : FormValidation.ok();
            }
        }
    }

    /** Pipelines found by a regular expression over job names; the capture group names the pipeline. */
    public static class RegExpSpec implements Describable<RegExpSpec> {
        private String regexp;
        private boolean showUpstream;

        @DataBoundConstructor
        public RegExpSpec(String regexp, boolean showUpstream) {
            this.regexp = regexp == null ? null : regexp.trim();
            this.showUpstream = showUpstream;
        }

        public String getRegexp() {
            return regexp;
        }

        public boolean isShowUpstream() {
            return showUpstream;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(regexp, showUpstream);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RegExpSpec spec && java.util.Objects.equals(regexp, spec.regexp)
                    && showUpstream == spec.showUpstream;
        }

        @Extension
        public static class DescriptorImpl extends Descriptor<RegExpSpec> {
            @NonNull
            @Override
            public String getDisplayName() {
                return "RegExp";
            }

            @SuppressWarnings("lgtm[jenkins/csrf]")
            public FormValidation doCheckRegexp(@AncestorInPath View view, @AncestorInPath ViewGroup owner,
                                                @QueryParameter String value) {
                checkConfigure(view, owner);
                if (value == null || value.isBlank()) {
                    return FormValidation.error("The regular expression cannot be blank");
                }
                try {
                    int groups = Pattern.compile(value).matcher("").groupCount();
                    if (groups == 0) {
                        return FormValidation.error("No capture group defined");
                    }
                    if (groups > 1) {
                        return FormValidation.error("Too many capture groups defined");
                    }
                    return FormValidation.ok();
                } catch (PatternSyntaxException e) {
                    return FormValidation.error(e, "Syntax error in the regular expression");
                }
            }
        }
    }
}
