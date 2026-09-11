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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static se.diabol.jenkins.pipeline.PageTestSupport.body;
import static se.diabol.jenkins.pipeline.PageTestSupport.jsClient;
import static se.diabol.jenkins.pipeline.PageTestSupport.render;
import static se.diabol.jenkins.pipeline.PageTestSupport.staticClient;
import static se.diabol.jenkins.pipeline.PageTestSupport.taskNames;
import static se.diabol.jenkins.pipeline.PageTestSupport.texts;
import static se.diabol.jenkins.pipeline.PageTestSupport.view;

import au.com.centrumsystems.hudson.plugin.buildpipeline.trigger.BuildPipelineTrigger;
import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.tasks.BuildTrigger;
import java.net.URL;
import java.util.List;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import hudson.model.Cause;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** The view as a browser sees it: the page, the JSON it polls and what the script renders and posts. */
@WithJenkins
class DeliveryPipelineViewPageTest {

    private static final String VIEW_NAME = "Pipeline";
    private static final String VIEW_URL = "view/" + VIEW_NAME + "/";

    private JenkinsRule jenkins;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        jenkins = rule;
        FreeStyleProject build = jenkins.createFreeStyleProject("build");
        jenkins.buildAndAssertSuccess(build);
        jenkins.buildAndAssertSuccess(build);
        DeliveryPipelineView view = new DeliveryPipelineView(VIEW_NAME);
        view.setComponentSpecs(List.of(new DeliveryPipelineView.ComponentSpec("Comp", "build", null, false)));
        view.setPagingEnabled(true);
        view.setNoOfPipelines(1);
        jenkins.getInstance().addView(view);
    }

    @Test
    void pageCarriesTheViewDataAndTheFullScreenPageIsBare() throws Exception {
        try (JenkinsRule.WebClient client = staticClient(jenkins)) {
            String html = body(jenkins, client, VIEW_URL);
            assertThat(html, containsString("class=\"dpp-view\""));
            assertThat(html, containsString("data-view-url=\"view/Pipeline/\""));
            assertThat(html, containsString("data-fullscreen=\"false\""));
            assertThat(html, containsString("data-crumb-field="));
            assertThat("no inline script or style", html, not(containsString("style=\"")));
            String fullscreen = body(jenkins, client, VIEW_URL + "?fullscreen=true");
            assertThat(fullscreen, containsString("class=\"dpp-fullscreen\""));
            assertThat(fullscreen, containsString("data-fullscreen=\"true\""));
            assertThat("no Jenkins chrome on the wall board page", fullscreen, not(containsString("id=\"side-panel\"")));
        }
    }

    @Test
    void apiAnswersWithComponentsAndSettingsAndToleratesOddParameters() throws Exception {
        try (JenkinsRule.WebClient client = staticClient(jenkins)) {
            String json = body(jenkins, client, VIEW_URL + "api/json?page=1&component=1&fullscreen=false");
            assertThat(json, containsString("\"name\":\"Comp\""));
            assertThat(json, containsString("\"settings\":{"));
            assertThat(json, containsString("\"pagingEnabled\":true"));
            assertThat(json, containsString("\"paging\":{"));
            assertThat(json, containsString("\"type\":\"SUCCESS\""));
            assertThat(body(jenkins, client, VIEW_URL + "api/json?page=null&component=null&fullscreen=null"),
                    containsString("\"name\":\"Comp\""));
        }
    }

    @Test
    void viewRendersPipelinesWithJavaScript() throws Exception {
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, VIEW_URL);
            assertThat(page.querySelectorAll(".pipeline-component").size(), is(1));
            assertThat(page.querySelector(".pipeline-title").asNormalizedText(), containsString("Comp"));
            assertThat(page.querySelectorAll(".stage-task.SUCCESS").size(), is(1));
            assertThat(page.querySelector(".pipeline-heading").asNormalizedText(), containsString("#2"));
            DomElement columns = page.querySelector(".dpp-columns");
            assertThat("the column count is applied by script, not markup", columns.getAttribute("style"), containsString("grid-template-columns"));
            assertThat(page.querySelectorAll(".dpp-column").size(), is(1));
        }
    }

    @Test
    void namesWithQuotesAreRenderedAsDataNotMarkup() throws Exception {
        FreeStyleProject odd = jenkins.createFreeStyleProject("job 'with' quotes (and parens)");
        odd.addProperty(new PipelineProperty("Task \"q\"", "Stage \"q\"", ""));
        jenkins.buildAndAssertSuccess(odd);
        DeliveryPipelineView view = new DeliveryPipelineView("View \"with\" 'quotes'");
        view.setComponentSpecs(List.of(new DeliveryPipelineView.ComponentSpec("Odd <b>", odd.getName(), null, false)));
        view.setAllowPipelineStart(true);
        jenkins.getInstance().addView(view);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            DomElement start = page.querySelector(".task-trigger-build");
            assertThat(start, notNullValue());
            assertThat(start.getAttribute("data-name"), is(odd.getName()));
            assertThat("only the attributes the script writes, nothing injected by the names",
                    start.getAttributes().getLength(), is(7));
            assertThat(page.querySelector(".pipeline-title").asNormalizedText(), containsString("Odd <b>"));
            assertThat(page.querySelectorAll(".pipeline-title b").size(), is(0));
            assertThat(taskNames(page), contains("Task \"q\""));
            assertThat(page.querySelector(".stage-name").asNormalizedText(), is("Stage \"q\""));
            DomElement stage = page.querySelector(".stage");
            assertThat(stage.getAttribute("class"), is("stage stage_Stage__q_"));
        }
    }

    @Test
    void connectorsAreDrawnBetweenChainedJobs() throws Exception {
        chain("build", "deploy");
        DeliveryPipelineView view = view(jenkins, "Chain", "build");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.querySelectorAll(".stage").size(), is(2));
            assertThat("one arrow between the two stages", page.querySelectorAll("path.relation").size(), is(1));
        }
    }

    @Test
    void paginationLinksBrowseOlderPipelines() throws Exception {
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, VIEW_URL);
            HtmlElement link = page.querySelector(".pagination a[data-page='2']");
            assertThat("two builds, one pipeline per page: a link to page 2", link, notNullValue());
            assertThat(page.querySelector(".pagination-total").asNormalizedText(), is("2 pipelines"));
            link.click();
            client.waitForBackgroundJavaScript(10000);
            assertThat("the second page shows the older build", page.querySelector(".pipeline-heading").asNormalizedText(),
                    containsString("#1"));
            assertThat(page.querySelectorAll(".pagination .active_link a").get(0).asNormalizedText(), is("2"));
        }
    }

    @Test
    void aggregatedRowAndDescriptionAreRendered() throws Exception {
        FreeStyleProject build = jenkins.getInstance().getItemByFullName("build", FreeStyleProject.class);
        build.addProperty(new PipelineProperty("Compile", "Build", "Deploys <b>everything</b> & more"));
        DeliveryPipelineView view = view(jenkins, "Described", "build");
        view.setShowAggregatedPipeline(true);
        view.setShowDescription(true);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.querySelectorAll(".pipeline-heading").size(), greaterThanOrEqualTo(2));
            assertThat(page.querySelector(".pipeline-aggregated .stage-version").asNormalizedText(), is("#2"));
            DomElement description = page.querySelector(".task-description");
            assertThat("the description goes through the markup formatter, which escapes markup by default",
                    description.asNormalizedText(), is("Deploys <b>everything</b> & more"));
            assertThat(description.querySelectorAll("b").size(), is(0));
        }
    }

    @Test
    void rebuildButtonPostsToTheView() throws Exception {
        FreeStyleProject deploy = chain("build", "deploy");
        int deployBuilds = deploy.getBuilds().size();
        DeliveryPipelineView view = view(jenkins, "Rebuild", "build");
        view.setAllowRebuild(true);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            HtmlElement rebuild = page.querySelector(".task-rebuild");
            assertThat(rebuild, notNullValue());
            assertThat(rebuild.getAttribute("data-project"), is("deploy"));
            assertThat("the first job cannot be rebuilt", page.querySelectorAll(".task-rebuild").size(), is(1));
            rebuild.click();
            client.waitForBackgroundJavaScript(5000);
        }
        jenkins.waitUntilNoActivity();
        assertThat("clicking rebuild queued a new build of deploy", deploy.getBuilds().size(), is(deployBuilds + 1));
    }

    @Test
    void startButtonPostsABuild() throws Exception {
        FreeStyleProject build = jenkins.getInstance().getItemByFullName("build", FreeStyleProject.class);
        int builds = build.getBuilds().size();
        DeliveryPipelineView view = view(jenkins, "Start", "build");
        view.setAllowPipelineStart(true);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            HtmlElement start = page.querySelector(".task-trigger-build");
            assertThat(start, notNullValue());
            start.click();
            client.waitForBackgroundJavaScript(5000);
        }
        jenkins.waitUntilNoActivity();
        assertThat("clicking Build now queued a new build", build.getBuilds().size(), is(builds + 1));
    }

    @Test
    void manualTriggerButtonPostsToTheView() throws Exception {
        FreeStyleProject build = jenkins.getInstance().getItemByFullName("build", FreeStyleProject.class);
        FreeStyleProject deploy = jenkins.createFreeStyleProject("deploy");
        build.getPublishersList().add(new BuildPipelineTrigger(deploy.getName(), null));
        jenkins.getInstance().rebuildDependencyGraph();
        jenkins.buildAndAssertSuccess(build);
        jenkins.waitUntilNoActivity();
        assertThat("a manual downstream job is not built automatically", deploy.getBuilds().size(), is(0));
        DeliveryPipelineView view = view(jenkins, "Manual", "build");
        view.setAllowManualTriggers(true);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            HtmlElement manual = page.querySelector(".task-manual");
            assertThat(manual, notNullValue());
            assertThat(manual.getAttribute("data-project"), is("deploy"));
            assertThat(manual.getAttribute("data-upstream"), is("build"));
            assertThat(page.querySelector(".stage-task.manual"), notNullValue());
            manual.click();
            client.waitForBackgroundJavaScript(5000);
        }
        jenkins.waitUntilNoActivity();
        assertThat("clicking the manual trigger built deploy", deploy.getBuilds().size(), is(1));
    }

    @Test
    void actionsAreRefusedWhenTheViewDoesNotAllowThem() throws Exception {
        chain("build", "deploy");
        DeliveryPipelineView view = view(jenkins, "Locked", "build");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.querySelector(".task-rebuild"), nullValue());
            assertThat(page.querySelector(".task-trigger-build"), nullValue());
            org.htmlunit.WebRequest request = new org.htmlunit.WebRequest(
                    new URL(jenkins.getURL(), view.getViewUrl() + "rebuild?project=deploy&buildId=1"), org.htmlunit.HttpMethod.POST);
            client.addCrumb(request);
            assertThat(client.getPage(request).getWebResponse().getStatusCode(), is(403));
        }
    }

    @Test
    void pipelineJobsRenderNextToFreestyleChains() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "wf");
        flow.setDefinition(new CpsFlowDefinition("node { stage('Compile') { echo 'compiling' } }", true));
        jenkins.buildAndAssertSuccess(flow);
        DeliveryPipelineView view = new DeliveryPipelineView("Mixed");
        view.setComponentSpecs(List.of(
                new DeliveryPipelineView.ComponentSpec("Comp", "build", null, false),
                new DeliveryPipelineView.ComponentSpec("Flow", "wf", null, false)));
        view.setNoOfPipelines(1);
        view.setAllowPipelineStart(true);
        jenkins.getInstance().addView(view);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            String json = body(jenkins, client, view.getViewUrl() + "api/json");
            assertThat(json, containsString("\"fullName\":\"wf\""));
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.querySelectorAll(".pipeline-component").size(), is(2));
            assertThat(taskNames(page), contains("build", "Compile"));
            DomElement start = page.getElementById("startpipeline-1");
            assertThat(start.getAttribute("data-url"), containsString("job/wf/"));
        }
        assertThat("the Pipeline job counts as an item of the view", view.contains(flow), is(true));
    }

    @Test
    void inputStepOfAPipelineJobCanBeProceededFromTheView() throws Exception {
        WorkflowJob gate = jenkins.getInstance().createProject(WorkflowJob.class, "gate");
        gate.setDefinition(new CpsFlowDefinition(
                "node { stage('Build') { echo 'built' }; stage('Deploy') { input 'Deploy?' } }", true));
        WorkflowRun run = gate.scheduleBuild2(0).waitForStart();
        waitForInput(run);
        DeliveryPipelineView view = view(jenkins, "Gate", "gate");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.querySelectorAll(".stage-task.PAUSED_PENDING_INPUT").size(), is(1));
            HtmlElement input = page.querySelector(".task-manual-specify");
            assertThat("the paused Deploy stage offers the input button", input, notNullValue());
            assertThat(input.getAttribute("data-project"), is("gate"));
            input.click();
            client.waitForBackgroundJavaScript(5000);
        }
        jenkins.waitUntilNoActivity();
        jenkins.assertBuildStatusSuccess(run);
    }

    @Test
    void abortButtonStopsARunningPipeline() throws Exception {
        WorkflowJob slow = jenkins.getInstance().createProject(WorkflowJob.class, "slow");
        slow.setDefinition(new CpsFlowDefinition("node { stage('Wait') { sleep 120 } }", true));
        WorkflowRun run = slow.scheduleBuild2(0).waitForStart();
        for (int i = 0; i < 100 && (run.getExecution() == null || run.getExecution().getCurrentHeads().size() < 1); i++) {
            Thread.sleep(100);
        }
        DeliveryPipelineView view = view(jenkins, "Slow", "slow");
        view.setAllowAbort(true);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            HtmlElement abort = page.querySelector(".task-abort");
            assertThat("a running task offers the abort button", abort, notNullValue());
            assertThat(page.querySelectorAll(".task-progress-running").size(), is(1));
            assertThat("a running stage links to the console",
                    page.<DomElement>querySelector(".stage-task .taskname a").getAttribute("href"), endsWith("/job/slow/1/console"));
            assertThat(abort.getAttribute("data-project"), is("slow"));
            abort.click();
            client.waitForBackgroundJavaScript(5000);
            assertThat(PageTestSupport.errorText(page), not(containsString("Could not")));
        }
        jenkins.waitUntilNoActivity();
        jenkins.assertBuildStatus(Result.ABORTED, run);
    }

    private static void waitForInput(WorkflowRun run) throws Exception {
        for (int i = 0; i < 150; i++) {
            InputAction action = run.getAction(InputAction.class);
            if (action != null && !action.getExecutions().isEmpty()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("build never reached the input step");
    }

    @Test
    void parallelStagesOfAPipelineBecomeTasksOfTheirStage() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "parallel");
        flow.setDefinition(new CpsFlowDefinition("node { stage('Test') { parallel("
                + "'Unit': { stage('Unit') { echo 'u' } }, "
                + "'Integration': { stage('Integration') { echo 'i' } }) } }", true));
        jenkins.buildAndAssertSuccess(flow);
        DeliveryPipelineView view = view(jenkins, "Parallel", "parallel");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat("nested stages are not stages of their own", page.querySelectorAll(".stage").size(), is(1));
            assertThat(page.querySelector(".stage-name").asNormalizedText(), is("Test"));
            assertThat(taskNames(page), containsInAnyOrder("Unit", "Integration"));
            assertThat(page.querySelectorAll(".stage-task.SUCCESS").size(), is(2));
        }
    }

    @Test
    void failedParallelBranchIsShownAsAFailedTask() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "branches");
        flow.setDefinition(new CpsFlowDefinition(
                "node { stage('Test') { parallel('ok': { echo 'fine' }, 'bad': { error 'boom' }) } }", true));
        jenkins.buildAndAssertStatus(Result.FAILURE, flow);
        DeliveryPipelineView view = view(jenkins, "Branches", "branches");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.querySelectorAll(".stage").size(), is(1));
            assertThat(taskNames(page), containsInAnyOrder("ok", "bad"));
            assertThat(page.querySelector(".stage-task.FAILED .taskname").asNormalizedText(), is("bad"));
            assertThat(page.querySelector(".stage-task.SUCCESS .taskname").asNormalizedText(), is("ok"));
        }
    }

    @Test
    void nestedSequentialStagesBecomeTasks() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "nested");
        flow.setDefinition(new CpsFlowDefinition(
                "node { stage('Build') { stage('Compile') { echo 'c' }; stage('Package') { echo 'p' } } }", true));
        jenkins.buildAndAssertSuccess(flow);
        DeliveryPipelineView view = view(jenkins, "Nested", "nested");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.querySelectorAll(".stage").size(), is(1));
            assertThat(page.querySelector(".stage-name").asNormalizedText(), is("Build"));
            assertThat(taskNames(page), contains("Compile", "Package"));
        }
    }

    @Test
    void deprecatedTaskStepStillRunsAndShowsAsTasks() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "tasks");
        flow.setDefinition(new CpsFlowDefinition(
                "node { stage('Build') { task('Compile') { echo 'c' }; task('Package') { echo 'p' } } }", true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(flow);
        jenkins.assertLogContains("task step is deprecated", run);
        DeliveryPipelineView view = view(jenkins, "Tasks", "tasks");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.querySelectorAll(".stage").size(), is(1));
            assertThat("the blocks of the old step are the tasks of their stage", taskNames(page), contains("Compile", "Package"));
            assertThat(page.querySelectorAll(".stage-task.SUCCESS").size(), is(2));
        }
    }

    @Test
    void placeholderStagesDoNotStretchRows() throws Exception {
        FreeStyleProject build = jenkins.getInstance().getItemByFullName("build", FreeStyleProject.class);
        jenkins.createFreeStyleProject("deploy");
        jenkins.createFreeStyleProject("deploy2");
        build.getPublishersList().add(new BuildTrigger("deploy, deploy2", Result.SUCCESS));
        jenkins.getInstance().rebuildDependencyGraph();
        jenkins.buildAndAssertSuccess(build);
        jenkins.waitUntilNoActivity();
        DeliveryPipelineView view = view(jenkins, "Fan", "build");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat("two downstream jobs are laid out in two rows", page.querySelectorAll(".pipeline-row").size(), is(2));
            DomElement placeholder = page.querySelector(".stage.hide");
            assertThat("the second row starts with an invisible placeholder", placeholder, notNullValue());
            assertThat("placeholders keep their natural height", placeholder.getAttribute("style"), is(""));
            Object verticalAlign = page.executeJavaScript(
                    "getComputedStyle(document.querySelector('.pipeline-cell')).verticalAlign").getJavaScriptResult();
            assertThat(String.valueOf(verticalAlign), is("top"));
            assertThat("arrows from build to both deploy stages", page.querySelectorAll("path.relation").size(), is(2));
        }
    }

    @Test
    void declarativeSkippedStageIsShownAsNotBuilt() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "declarative");
        flow.setDefinition(new CpsFlowDefinition(String.join("\n",
                "pipeline {",
                "  agent any",
                "  stages {",
                "    stage('Build') {",
                "      steps { echo 'b' }",
                "    }",
                "    stage('Deploy') {",
                "      when { expression { false } }",
                "      steps { echo 'd' }",
                "    }",
                "  }",
                "}"), true));
        jenkins.buildAndAssertSuccess(flow);
        DeliveryPipelineView view = view(jenkins, "Declarative", "declarative");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.querySelectorAll(".stage").size(), is(2));
            assertThat(taskNames(page), contains("Build", "Deploy"));
            DomElement built = page.querySelector(".stage_Build .stage-task");
            DomElement skipped = page.querySelector(".stage_Deploy .stage-task");
            assertThat(built.getAttribute("class"), containsString("SUCCESS"));
            assertThat("a stage skipped by a when condition is not built", skipped.getAttribute("class"), containsString("NOT_BUILT"));
        }
    }

    @Test
    void legacyStageStepsWithoutBlocksStillProduceStages() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "legacy");
        flow.setDefinition(new CpsFlowDefinition("node { stage 'Build'; echo 'b'; stage 'Test'; echo 't' }", true));
        jenkins.buildAndAssertSuccess(flow);
        DeliveryPipelineView view = view(jenkins, "Legacy", "legacy");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.querySelectorAll(".stage").size(), is(2));
            assertThat(taskNames(page), contains("Build", "Test"));
            assertThat(page.querySelectorAll(".stage-task.SUCCESS").size(), is(2));
        }
    }

    @Test
    void unstableStageIsShownAsUnstable() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "unstable");
        flow.setDefinition(new CpsFlowDefinition(
                "node { stage('Test') { catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') { error 'flaky' } } }", true));
        jenkins.buildAndAssertStatus(Result.UNSTABLE, flow);
        DeliveryPipelineView view = view(jenkins, "Unstable", "unstable");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(taskNames(page), contains("Test"));
            assertThat(page.querySelectorAll(".stage-task.UNSTABLE").size(), is(1));
        }
    }

    /** Chains build -> downstream, builds the chain once and returns the downstream job. */
    private FreeStyleProject chain(String upstreamName, String downstreamName) throws Exception {
        FreeStyleProject upstream = jenkins.getInstance().getItemByFullName(upstreamName, FreeStyleProject.class);
        FreeStyleProject downstream = jenkins.createFreeStyleProject(downstreamName);
        upstream.getPublishersList().add(new BuildTrigger(downstream.getName(), Result.SUCCESS));
        jenkins.getInstance().rebuildDependencyGraph();
        jenkins.buildAndAssertSuccess(upstream);
        jenkins.waitUntilNoActivity();
        return downstream;
    }

    @Test
    void pipelineRunCanBeRunAgainWithItsParametersFromTheView() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "parameterized");
        flow.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("TARGET", "staging")));
        flow.setDefinition(new CpsFlowDefinition("node { stage('Deploy') { echo \"deploying-to-${params.TARGET}\" } }", true));
        jenkins.assertBuildStatusSuccess(flow.scheduleBuild2(0,
                new ParametersAction(new StringParameterValue("TARGET", "production"))));
        DeliveryPipelineView view = view(jenkins, "Parameterized", "parameterized");
        view.setAllowRebuild(true);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat("a scripted Pipeline cannot be restarted from a stage", page.querySelector(".task-rebuild"), nullValue());
            HtmlElement rebuild = page.querySelector(".pipeline-rebuild");
            assertThat(rebuild, notNullValue());
            assertThat(rebuild.getAttribute("data-project"), is("parameterized"));
            assertThat(rebuild.getAttribute("data-build"), is("1"));
            rebuild.click();
            client.waitForBackgroundJavaScript(5000);
        }
        jenkins.waitUntilNoActivity();
        WorkflowRun again = flow.getBuildByNumber(2);
        assertThat("the button ran the Pipeline again", again, notNullValue());
        jenkins.assertBuildStatusSuccess(again);
        assertThat(again.getAction(ParametersAction.class).getParameter("TARGET").getValue(), is("production"));
        assertThat(again.getCause(Cause.UserIdCause.class), notNullValue());
        jenkins.assertLogContains("deploying-to-production", again);
    }

    @Test
    void declarativeRunCanBeRestartedFromAStageFromTheView() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "restartable");
        flow.setDefinition(new CpsFlowDefinition(String.join("\n",
                "pipeline {",
                "  agent any",
                "  stages {",
                "    stage('Build') { steps { echo 'compiling-now' } }",
                "    stage('Deploy') { steps { echo 'deploying-now' } }",
                "  }",
                "}"), true));
        jenkins.buildAndAssertSuccess(flow);
        DeliveryPipelineView view = view(jenkins, "Restart", "restartable");
        view.setAllowRebuild(true);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat("every stage of a finished Declarative run can be restarted from",
                    page.querySelectorAll(".task-rebuild").size(), is(2));
            HtmlElement restart = page.querySelector(".stage_Deploy .task-rebuild");
            assertThat(restart.getAttribute("data-stage"), is("Deploy"));
            assertThat(restart.getAttribute("title"), is("Restart from stage Deploy"));
            restart.click();
            client.waitForBackgroundJavaScript(5000);
        }
        jenkins.waitUntilNoActivity();
        WorkflowRun restarted = flow.getBuildByNumber(2);
        assertThat("the button scheduled a restarted run", restarted, notNullValue());
        jenkins.assertBuildStatusSuccess(restarted);
        jenkins.assertLogNotContains("compiling-now", restarted);
        jenkins.assertLogContains("deploying-now", restarted);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            DomElement build = page.querySelector(".stage_Build .stage-task");
            DomElement deploy = page.querySelector(".stage_Deploy .stage-task");
            assertThat("the stage before the restart point was skipped", build.getAttribute("class"), containsString("NOT_BUILT"));
            assertThat(deploy.getAttribute("class"), containsString("SUCCESS"));
        }
    }

    @Test
    void testResultsOfAPipelineRunAreShownOnTheStageThatRecordedThem() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "tested");
        flow.setDefinition(new CpsFlowDefinition(String.join("\n",
                "node {",
                "  stage('Unit') {",
                "    writeFile file: 'unit.xml', text: '<testsuite name=\"unit\" tests=\"2\" failures=\"1\">"
                        + "<testcase classname=\"A\" name=\"passes\"/>"
                        + "<testcase classname=\"A\" name=\"fails\"><failure message=\"boom\"/></testcase></testsuite>'",
                "    junit 'unit.xml'",
                "  }",
                "  stage('Integration') {",
                "    parallel fast: {",
                "      writeFile file: 'fast.xml', text: '<testsuite name=\"fast\" tests=\"1\">"
                        + "<testcase classname=\"B\" name=\"works\"/></testsuite>'",
                "      junit 'fast.xml'",
                "    }, slow: {",
                "      writeFile file: 'slow.xml', text: '<testsuite name=\"slow\" tests=\"3\" skipped=\"1\">"
                        + "<testcase classname=\"C\" name=\"one\"/><testcase classname=\"C\" name=\"two\"/>"
                        + "<testcase classname=\"C\" name=\"three\"><skipped/></testcase></testsuite>'",
                "      junit 'slow.xml'",
                "    }",
                "  }",
                "  stage('Package') { echo 'no tests here' }",
                "}"), true));
        jenkins.assertBuildStatus(Result.UNSTABLE, flow.scheduleBuild2(0));
        DeliveryPipelineView view = view(jenkins, "Tested", "tested");
        view.setShowTestResults(true);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(texts(page, ".stage_Unit .test-results td"), contains("2", "1", "0"));
            assertThat("each parallel branch shows its own results",
                    texts(page, ".stage_Integration .test-results td"), contains("1", "0", "0", "3", "0", "1"));
            assertThat(page.querySelectorAll(".stage_Package .test-results").size(), is(0));
            String json = body(jenkins, client, view.getViewUrl() + "api/json");
            assertThat(json, containsString("\"failed\":1,\"name\":\"Tests\",\"skipped\":0,\"total\":2"));
        }
        view.setShowTestResults(false);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            String json = body(jenkins, client, view.getViewUrl() + "api/json");
            assertThat("a view that hides test results does not serve them", json, not(containsString("\"total\":2")));
        }
    }

    @Test
    void warningsOfAPipelineRunAreShownOnThePipelineNotOnAStage() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "analysed");
        flow.setDefinition(new CpsFlowDefinition(String.join("\n",
                "node {",
                "  stage('Compile') {",
                "    writeFile file: 'javac.log', text: '[WARNING] /src/A.java:[3,5] [deprecation] foo() in A has been deprecated\\n"
                        + "[WARNING] /src/B.java:[7,1] [rawtypes] found raw type: List\\n'",
                "    recordIssues tool: java(pattern: 'javac.log')",
                "  }",
                "  stage('Test') { echo 'testing' }",
                "}"), true));
        jenkins.buildAndAssertSuccess(flow);
        DeliveryPipelineView view = view(jenkins, "Analysed", "analysed");
        view.setShowStaticAnalysisResults(true);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat("warning counts belong to the run, not to a stage",
                    page.querySelectorAll(".stage .analysis-results").size(), is(0));
            List<String> cells = texts(page, ".pipeline-analysis .analysis-results td");
            assertThat(cells.size(), is(4));
            assertThat(cells.subList(1, 4), contains("0", "2", "0"));
        }
    }

    @Test
    void inputStepWithParametersLinksToTheInputPage() throws Exception {
        WorkflowJob gate = jenkins.getInstance().createProject(WorkflowJob.class, "gate2");
        gate.setDefinition(new CpsFlowDefinition(
                "node { stage('Approve') { input message: 'Deploy?', parameters: [string(name: 'TARGET', defaultValue: 'staging')] } }",
                true));
        WorkflowRun run = gate.scheduleBuild2(0).waitForStart();
        waitForInput(run);
        DeliveryPipelineView view = view(jenkins, "Gate2", "gate2");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat("no proceed button when the input has parameters", page.querySelector("button.task-manual-specify"), nullValue());
            HtmlElement link = page.querySelector("a.task-manual-specify");
            assertThat(link, notNullValue());
            assertThat(link.getAttribute("href"), endsWith("/job/gate2/1/input/"));
            String json = body(jenkins, client, view.getViewUrl() + "api/json");
            assertThat(json, containsString("\"inputUrl\":\"job/gate2/1/input/\""));
            assertThat(json, containsString("\"requiresInput\":true"));
        }
        run.doStop();
        jenkins.waitUntilNoActivity();
    }

    @Test
    void syntheticDeclarativeStagesAreHiddenAndTheirTestsSitOnTheRun() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "posted");
        flow.setDefinition(new CpsFlowDefinition(String.join("\n",
                "pipeline {",
                "  agent any",
                "  stages {",
                "    stage('Build') { steps { echo 'b' } }",
                "    stage('Test') { steps { echo 't' } }",
                "  }",
                "  post {",
                "    always {",
                "      writeFile file: 'post.xml', text: '<testsuite name=\"post\" tests=\"2\" failures=\"0\">"
                        + "<testcase classname=\"P\" name=\"one\"/><testcase classname=\"P\" name=\"two\"/></testsuite>'",
                "      junit 'post.xml'",
                "    }",
                "  }",
                "}"), true));
        jenkins.buildAndAssertSuccess(flow);
        DeliveryPipelineView view = view(jenkins, "Posted", "posted");
        view.setShowTestResults(true);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat("only the stages the Jenkinsfile declares are shown", texts(page, ".stage-name"), contains("Build", "Test"));
            assertThat("tests recorded in the post section belong to the run",
                    texts(page, ".pipeline-tests .test-results td"), contains("2", "0", "0"));
            assertThat(page.querySelectorAll(".stage .test-results"), empty());
        }
    }

    @Test
    void queuedPipelineRunIsShownBeforeItStarts() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "waiting");
        flow.setDefinition(new CpsFlowDefinition("node { stage('Build') { echo 'b' } }", true));
        flow.scheduleBuild2(120);
        try {
            assertThat(flow.isInQueue(), is(true));
            DeliveryPipelineView view = view(jenkins, "Waiting", "waiting");
            try (JenkinsRule.WebClient client = jsClient(jenkins)) {
                HtmlPage page = render(jenkins, client, view.getViewUrl());
                assertThat(texts(page, ".pipeline-heading"), contains(containsString("#1")));
                DomElement task = page.querySelector(".stage-task");
                assertThat("the queued run shows a queued task", task.getAttribute("class"), containsString("QUEUED"));
                assertThat(taskNames(page), contains("Queued"));
            }
        } finally {
            jenkins.getInstance().getQueue().cancel(flow);
        }
    }

    @Test
    void stagesSkippedAfterAFailureAreNotBuilt() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "halted");
        flow.setDefinition(new CpsFlowDefinition(String.join("\n",
                "pipeline {",
                "  agent any",
                "  stages {",
                "    stage('Build') { steps { error 'compilation failed' } }",
                "    stage('Test') { steps { echo 't' } }",
                "    stage('Deploy') { steps { echo 'd' } }",
                "  }",
                "}"), true));
        jenkins.assertBuildStatus(Result.FAILURE, flow.scheduleBuild2(0));
        DeliveryPipelineView view = view(jenkins, "Halted", "halted");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.<DomElement>querySelector(".stage_Build .stage-task").getAttribute("class"), containsString("FAILED"));
            assertThat("a stage skipped because of the failure did not run",
                    page.<DomElement>querySelector(".stage_Test .stage-task").getAttribute("class"), containsString("NOT_BUILT"));
            assertThat(page.<DomElement>querySelector(".stage_Deploy .stage-task").getAttribute("class"), containsString("NOT_BUILT"));
        }
    }

    @Test
    void matrixCellsAreNamedByTheirAxes() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "matrixed");
        flow.setDefinition(new CpsFlowDefinition(String.join("\n",
                "pipeline {",
                "  agent none",
                "  stages {",
                "    stage('Test') {",
                "      matrix {",
                "        axes { axis { name 'OS'; values 'linux', 'mac' } }",
                "        agent any",
                "        stages { stage('Run') { steps { echo OS } } }",
                "      }",
                "    }",
                "  }",
                "}"), true));
        jenkins.buildAndAssertSuccess(flow);
        DeliveryPipelineView view = view(jenkins, "Matrixed", "matrixed");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(taskNames(page), containsInAnyOrder("OS = 'linux'", "OS = 'mac'"));
        }
    }

    @Test
    void stagesInsideScriptedBranchesCarryTheBranchName() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "branched");
        flow.setDefinition(new CpsFlowDefinition(String.join("\n",
                "node {",
                "  stage('Test') {",
                "    parallel(",
                "      linux: { stage('Compile') { echo 'c' }; stage('Unit') { echo 'u' } },",
                "      windows: { stage('Compile') { echo 'c' }; stage('Unit') { echo 'u' } })",
                "  }",
                "}"), true));
        jenkins.buildAndAssertSuccess(flow);
        DeliveryPipelineView view = view(jenkins, "Branched", "branched");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(taskNames(page), containsInAnyOrder("linux: Compile", "linux: Unit", "windows: Compile", "windows: Unit"));
        }
    }

    @Test
    void branchesOfAParallelNestedInABranchBecomeTasks() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "nested");
        flow.setDefinition(new CpsFlowDefinition(String.join("\n",
                "node {",
                "  stage('Test') {",
                "    parallel(",
                "      a: { parallel(a1: { echo '1' }, a2: { echo '2' }) },",
                "      b: { echo 'b' })",
                "  }",
                "}"), true));
        jenkins.buildAndAssertSuccess(flow);
        DeliveryPipelineView view = view(jenkins, "Nested", "nested");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.querySelectorAll(".stage").size(), is(1));
            assertThat("the leaves of the nested parallel are the tasks, named after both branches",
                    taskNames(page), containsInAnyOrder("a: a1", "a: a2", "b"));
            assertThat(page.querySelectorAll(".stage-task.SUCCESS").size(), is(3));
        }
    }

    @Test
    void pipelineRunWithoutStagesIsShownAsOneTaskNamedAfterTheJob() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "plain");
        flow.setDefinition(new CpsFlowDefinition(String.join("\n",
                "node {",
                "  writeFile file: 'unit.xml', text: '<testsuite name=\"unit\" tests=\"2\" failures=\"1\">"
                        + "<testcase classname=\"A\" name=\"passes\"/>"
                        + "<testcase classname=\"A\" name=\"fails\"><failure message=\"boom\"/></testcase></testsuite>'",
                "  junit 'unit.xml'",
                "}"), true));
        jenkins.assertBuildStatus(Result.UNSTABLE, flow.scheduleBuild2(0));
        DeliveryPipelineView view = view(jenkins, "Plain", "plain");
        view.setShowTestResults(true);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(texts(page, ".stage-name"), contains("plain"));
            assertThat("the run is one task named after the job", taskNames(page), contains("plain"));
            assertThat(page.<DomElement>querySelector(".stage-task").getAttribute("class"), containsString("UNSTABLE"));
            assertThat("the run's test results sit on the task, not on the run",
                    texts(page, ".stage_plain .test-results td"), contains("2", "1", "0"));
            assertThat(page.querySelectorAll(".pipeline-tests").size(), is(0));
            assertThat("a finished run links to its page",
                    page.<DomElement>querySelector(".stage-task .taskname a").getAttribute("href"), endsWith("/job/plain/1/"));
        }
    }

    @Test
    void pipelineRunShowsTheRunsItStartedAsAChain() throws Exception {
        jenkins.createFreeStyleProject("free");
        chain("free", "free-deploy");
        WorkflowJob down = jenkins.getInstance().createProject(WorkflowJob.class, "down");
        down.setDefinition(new CpsFlowDefinition("node { stage('Deploy') { echo 'd' }; stage('Verify') { echo 'v' } }", true));
        WorkflowJob up = jenkins.getInstance().createProject(WorkflowJob.class, "up");
        up.setDefinition(new CpsFlowDefinition(String.join("\n",
                "stage('Build') { node { echo 'b' } }",
                "stage('Trigger') { build job: 'down'; build job: 'free' }",
                "stage('Report') { echo 'r' }"), true));
        jenkins.buildAndAssertSuccess(up);
        jenkins.waitUntilNoActivity();
        DeliveryPipelineView view = view(jenkins, "Chain", "up");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat("the started runs follow the stage that started them, each on a row of its own, and a started "
                    + "job that is not a Pipeline brings the chain downstream of it",
                    texts(page, ".stage-name"),
                    contains("Build", "Trigger", "Report", "down: Deploy", "down: Verify", "free", "free: free-deploy"));
            assertThat(taskNames(page), contains("Build", "Trigger", "Report", "Deploy", "Verify", "free", "free-deploy"));
            assertThat(page.querySelectorAll(".stage-task.SUCCESS").size(), is(7));
            assertThat("two arrows along the run, one to each started run, one along the started Pipeline, one along "
                    + "the started chain of jobs", page.querySelectorAll("path.relation").size(), is(6));
            assertThat("a task of a started run links to that run",
                    hrefOfTask(page, "Deploy"), endsWith("/job/down/1/"));
            String json = body(jenkins, client, view.getViewUrl() + "api/json");
            assertThat(json, containsString("\"id\":\"down#1/"));
            assertThat(json, containsString("\"jobFullName\":\"free\""));
            assertThat(json, containsString("\"jobFullName\":\"free-deploy\""));
        }
        jenkins.buildAndAssertSuccess(down);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat("the chain shows the run that was started, not the latest one",
                    hrefOfTask(page, "Deploy"), endsWith("/job/down/1/"));
        }
    }

    @Test
    void aggregatedRowOfAPipelineJobShowsTheNewestRunThatRanEachStage() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "staged");
        flow.setDefinition(new CpsFlowDefinition("node { stage('Build') { echo 'b' }; stage('Deploy') { echo 'd' } }", true));
        jenkins.buildAndAssertSuccess(flow);
        flow.setDefinition(new CpsFlowDefinition("node { stage('Build') { error 'broken' }; stage('Deploy') { echo 'd' } }", true));
        jenkins.assertBuildStatus(Result.FAILURE, flow.scheduleBuild2(0));
        DeliveryPipelineView view = view(jenkins, "Staged", "staged");
        view.setShowAggregatedPipeline(true);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat("the aggregated row is laid out like the run that completed its stages",
                    texts(page, ".pipeline-aggregated .stage-name"), contains("Build", "Deploy"));
            assertThat("each stage shows the newest run in which it ran",
                    texts(page, ".pipeline-aggregated .stage-version"), contains("#2", "#1"));
            assertThat(page.querySelectorAll(".pipeline-aggregated .stage-task.FAILED").size(), is(1));
            assertThat(page.querySelectorAll(".pipeline-aggregated .stage-task.SUCCESS").size(), is(1));
            assertThat("the newest run itself is shown below the aggregated row, with its one stage",
                    texts(page, ".pipeline:not(.pipeline-aggregated) .stage-name"), contains("Build"));
        }
    }

    @Test
    void stagesNestedInsideBranchStagesAreTasksNamedAfterBoth() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "deep");
        flow.setDefinition(new CpsFlowDefinition(String.join("\n",
                "pipeline {",
                "  agent any",
                "  stages {",
                "    stage('Test') {",
                "      parallel {",
                "        stage('Linux') {",
                "          stages {",
                "            stage('Compile') { steps { echo 'c' } }",
                "            stage('Unit') { steps { echo 'u' } }",
                "          }",
                "        }",
                "        stage('Windows') {",
                "          stages {",
                "            stage('Compile') { steps { echo 'c' } }",
                "            stage('Unit') { steps { echo 'u' } }",
                "          }",
                "        }",
                "      }",
                "    }",
                "  }",
                "}"), true));
        jenkins.buildAndAssertSuccess(flow);
        DeliveryPipelineView view = view(jenkins, "Deep", "deep");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.querySelectorAll(".stage").size(), is(1));
            assertThat("the innermost stages are the tasks, named after the branch stage and themselves",
                    taskNames(page), containsInAnyOrder("Linux: Compile", "Linux: Unit", "Windows: Compile", "Windows: Unit"));
            assertThat(page.querySelectorAll(".stage-task.SUCCESS").size(), is(4));
        }
    }

    @Test
    void parallelBranchesOfARunWithoutStagesAreItsTasks() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "branchy");
        flow.setDefinition(new CpsFlowDefinition("node { parallel a: { echo 'a' }, b: { echo 'b' } }", true));
        jenkins.buildAndAssertSuccess(flow);
        DeliveryPipelineView view = view(jenkins, "Branchy", "branchy");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(texts(page, ".stage-name"), contains("branchy"));
            assertThat("the branches of a run without stages are the tasks of its one stage",
                    taskNames(page), contains("a", "b"));
            assertThat(page.querySelectorAll(".stage-task.SUCCESS").size(), is(2));
        }
    }

    @Test
    void folderComponentShowsOnePipelinePerJobInsideIt() throws Exception {
        Folder apps = jenkins.getInstance().createProject(Folder.class, "apps");
        for (String name : List.of("two", "one")) {
            WorkflowJob job = apps.createProject(WorkflowJob.class, name);
            job.setDefinition(new CpsFlowDefinition("node { stage('Build') { echo 'b' } }", true));
            jenkins.buildAndAssertSuccess(job);
        }
        DeliveryPipelineView view = view(jenkins, "Apps", "apps");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat("one component per job inside the folder, by name",
                    texts(page, "h1.pipeline-title"), contains(startsWith("Apps / one"), startsWith("Apps / two")));
            assertThat(page.querySelectorAll(".stage-task.SUCCESS").size(), is(2));
            String json = body(jenkins, client, view.getViewUrl() + "api/json");
            assertThat(json, containsString("\"name\":\"Apps / one\""));
        }
    }

    @Test
    void chainOfJobsFollowsIntoThePipelineJobItTriggers() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "flow");
        flow.setDefinition(new CpsFlowDefinition("node { stage('Deploy') { echo 'd' } }", true));
        FreeStyleProject trig = jenkins.createFreeStyleProject("trig");
        trig.getPublishersList().add(new BuildTrigger("flow", Result.SUCCESS));
        jenkins.getInstance().rebuildDependencyGraph();
        jenkins.buildAndAssertSuccess(trig);
        jenkins.waitUntilNoActivity();
        assertThat("the core build trigger started the Pipeline job", flow.getLastBuild(), notNullValue());
        DeliveryPipelineView view = view(jenkins, "Trig", "trig");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat("the triggered run follows the job that triggered it, on the same row",
                    texts(page, ".stage-name"), contains("trig", "flow: Deploy"));
            assertThat(taskNames(page), contains("trig", "Deploy"));
            assertThat(page.querySelectorAll("path.relation").size(), is(1));
            assertThat(hrefOfTask(page, "Deploy"), endsWith("/job/flow/1/"));
        }
    }

    @Test
    void stageStatusOutsideItsTasksShowsOnTheStageHeader() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "afterwards");
        flow.setDefinition(new CpsFlowDefinition(String.join("\n",
                "node {",
                "  stage('Test') {",
                "    parallel(a: { echo 'a' }, b: { echo 'b' })",
                "    writeFile file: 'unit.xml', text: '<testsuite name=\"unit\" tests=\"1\" failures=\"1\">"
                        + "<testcase classname=\"A\" name=\"fails\"><failure message=\"boom\"/></testcase></testsuite>'",
                "    junit 'unit.xml'",
                "  }",
                "}"), true));
        jenkins.assertBuildStatus(Result.UNSTABLE, flow.scheduleBuild2(0));
        DeliveryPipelineView view = view(jenkins, "Afterwards", "afterwards");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat("both branches passed", page.querySelectorAll(".stage-task.SUCCESS").size(), is(2));
            DomElement header = page.querySelector(".stage-header");
            assertThat("the stage header carries the status its tasks do not show",
                    header.getAttribute("class"), containsString("UNSTABLE"));
            assertThat(header.getAttribute("title"), containsString("outside its tasks"));
            assertThat("the run is no worse than its stage, so the heading stays quiet",
                    page.querySelectorAll(".pipeline-status").size(), is(0));
        }
    }

    @Test
    void runStatusOutsideItsStagesShowsOnTheRunHeading() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "trailing");
        flow.setDefinition(new CpsFlowDefinition("node { stage('Build') { echo 'b' } }; error 'broken after the stages'", true));
        jenkins.assertBuildStatus(Result.FAILURE, flow.scheduleBuild2(0));
        DeliveryPipelineView view = view(jenkins, "Trailing", "trailing");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.querySelectorAll(".stage-task.SUCCESS").size(), is(1));
            assertThat("the stage itself passed", page.querySelectorAll(".stage-header.FAILED").size(), is(0));
            DomElement badge = page.querySelector(".pipeline-heading .pipeline-status");
            assertThat("the heading says the run failed outside its stages", badge, notNullValue());
            assertThat(badge.getAttribute("class"), containsString("FAILED"));
            assertThat(badge.getTextContent(), is("Failed"));
            assertThat(badge.getAttribute("title"), containsString("outside its stages"));
        }
    }

    @Test
    void declarativeStagePostSectionStatusShowsOnTheStageHeader() throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "posted");
        flow.setDefinition(new CpsFlowDefinition(String.join("\n",
                "pipeline {",
                "  agent any",
                "  stages {",
                "    stage('Test') {",
                "      parallel {",
                "        stage('A') { steps { echo 'a' } }",
                "        stage('B') { steps { echo 'b' } }",
                "      }",
                "      post {",
                "        always {",
                "          writeFile file: 'unit.xml', text: '<testsuite name=\"unit\" tests=\"1\" failures=\"1\">"
                        + "<testcase classname=\"A\" name=\"fails\"><failure message=\"boom\"/></testcase></testsuite>'",
                "          junit 'unit.xml'",
                "        }",
                "      }",
                "    }",
                "  }",
                "}"), true));
        jenkins.assertBuildStatus(Result.UNSTABLE, flow.scheduleBuild2(0));
        DeliveryPipelineView view = view(jenkins, "Posted", "posted");
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(taskNames(page), contains("A", "B"));
            assertThat(page.querySelectorAll(".stage-task.SUCCESS").size(), is(2));
            assertThat("the stage's post section made it unstable, which its header shows",
                    page.querySelectorAll(".stage-header.UNSTABLE").size(), is(1));
        }
    }

    private static String hrefOfTask(HtmlPage page, String name) {
        for (DomNode link : page.querySelectorAll(".stage-task .taskname a")) {
            if (link.getTextContent().trim().equals(name)) {
                return ((DomElement) link).getAttribute("href");
            }
        }
        throw new AssertionError("no task named " + name);
    }

    @Test
    void runWithoutAStageYetIsTheRunItselfUnlessThePreviousRunHadStages() throws Exception {
        WorkflowJob fresh = jenkins.getInstance().createProject(WorkflowJob.class, "fresh");
        fresh.setDefinition(new CpsFlowDefinition("sleep 120; node { stage('Build') { echo 'b' } }", true));
        WorkflowJob late = jenkins.getInstance().createProject(WorkflowJob.class, "late");
        late.setDefinition(new CpsFlowDefinition("node { stage('Build') { echo 'b' } }", true));
        jenkins.buildAndAssertSuccess(late);
        late.setDefinition(new CpsFlowDefinition("sleep 120; node { stage('Build') { echo 'b' } }", true));
        WorkflowRun freshRun = fresh.scheduleBuild2(0).waitForStart();
        WorkflowRun lateRun = late.scheduleBuild2(0).waitForStart();
        try {
            DeliveryPipelineView freshView = view(jenkins, "Fresh", "fresh");
            DeliveryPipelineView lateView = view(jenkins, "Late", "late");
            try (JenkinsRule.WebClient client = jsClient(jenkins)) {
                HtmlPage page = render(jenkins, client, freshView.getViewUrl());
                assertThat("a first run with no stage yet is the run itself", taskNames(page), contains("fresh"));
                assertThat(page.querySelectorAll(".task-progress-running").size(), is(1));
                assertThat("a running run links to its console",
                        page.<DomElement>querySelector(".stage-task .taskname a").getAttribute("href"), endsWith("/job/fresh/1/console"));
                page = render(jenkins, client, lateView.getViewUrl());
                assertThat("a run whose previous run had stages is still starting", taskNames(page), contains("Starting"));
            }
        } finally {
            freshRun.doStop();
            lateRun.doStop();
            jenkins.waitUntilNoActivity();
        }
    }
}
