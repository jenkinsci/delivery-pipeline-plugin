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

import au.com.centrumsystems.hudson.plugin.buildpipeline.trigger.BuildPipelineTrigger;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.tasks.BuildTrigger;
import org.htmlunit.Page;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Regression tests for the view page and its JSON API after the inline scripts were moved to
 * main-behaviour.js (JENKINS-74082). The page used to hand the request parameters to the script
 * through {@code <st:bind>} globals, which are {@code null} unless the parameters are present in
 * the URL, so the script requested {@code api/json?page=null&component=null&fullscreen=null} and
 * every paged view showed "Error communicating to server!".
 */
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
    void viewPageCarriesRequestParametersInDataHolder() throws Exception {
        try (JenkinsRule.WebClient client = staticClient()) {
            String html = body(client, VIEW_URL);
            assertThat(html, containsString("data-page=\"1\""));
            assertThat(html, containsString("data-component=\"1\""));
            assertThat(html, containsString("data-fullscreen=\"false\""));
            assertThat(html, containsString("data-it-id=\"0\""));
            assertThat(html, not(containsString("bound/script/null?var=page")));
            assertThat(html, not(containsString("bound/script/null?var=component")));
            assertThat(html, not(containsString("bound/script/null?var=fullscreen")));
            assertThat("column width is applied by script, not an inline style",
                    html, containsString("id=\"pipelines-1-0\" class=\"left\"></div>"));

            String secondPage = body(client, VIEW_URL + "?page=2&component=1");
            assertThat(secondPage, containsString("data-page=\"2\""));

            String fullscreen = body(client, VIEW_URL + "?fullscreen=true");
            assertThat(fullscreen, containsString("data-fullscreen=\"true\""));
        }
    }

    @Test
    void apiAnswersRequestIssuedByViewScript() throws Exception {
        try (JenkinsRule.WebClient client = staticClient()) {
            String json = body(client, VIEW_URL + "api/json?page=1&component=1&fullscreen=false");
            assertThat(json, containsString("\"Comp\""));
        }
    }

    @Test
    void apiToleratesNonNumericPagingParameters() throws Exception {
        try (JenkinsRule.WebClient client = staticClient()) {
            String json = body(client, VIEW_URL + "api/json?page=null&component=null&fullscreen=null");
            assertThat(json, containsString("\"Comp\""));
        }
    }

    @Test
    void namesWithQuotesAreRenderedAsDataNotMarkup() throws Exception {
        FreeStyleProject odd = jenkins.createFreeStyleProject("job \"with\" 'quotes' (and parens)");
        odd.addProperty(new PipelineProperty("Task \"q\"", "Stage \"q\"", ""));
        jenkins.buildAndAssertSuccess(odd);

        DeliveryPipelineView view = new DeliveryPipelineView("View \"with\" 'quotes'");
        view.setComponentSpecs(List.of(new DeliveryPipelineView.ComponentSpec("Odd", odd.getName(), null, false)));
        view.setAllowPipelineStart(true);
        jenkins.getInstance().addView(view);

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            client.getOptions().setThrowExceptionOnFailingStatusCode(false);
            client.getOptions().setThrowExceptionOnScriptError(false);
            HtmlPage page = client.getPage(new URL(jenkins.getURL(), view.getViewUrl()));
            client.waitForBackgroundJavaScript(15000);

            assertThat(page.getElementById("pipelineerror-0").getTextContent(), not(containsString("Error")));
            DomElement start = page.querySelector(".task-trigger-build");
            assertThat(start, notNullValue());
            assertThat(start.getAttribute("data-task-id"), is(view.getViewName()));
            assertThat("only the attributes pipe.js writes, nothing injected by the name",
                    start.getAttributes().getLength(), is(5));

            DomElement pipelines = page.getElementById("pipelines-1-0");
            assertThat(pipelines.asNormalizedText(), containsString("Task \"q\""));
            assertThat(pipelines.asNormalizedText(), containsString("Stage \"q\""));
            DomElement task = page.querySelector(".stage-task");
            assertThat(task.getId(), is("task-job__with___quotes___and_parens_0"));
            DomElement stage = page.querySelector(".stage");
            assertThat(stage.getAttribute("class"), is("stage stage_Stage__q_"));
        }
    }

    @Test
    void viewRendersPipelinesWithJavaScript() throws Exception {
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            client.getOptions().setThrowExceptionOnFailingStatusCode(false);
            client.getOptions().setThrowExceptionOnScriptError(false);
            client.getOptions().setJavaScriptEnabled(true);
            HtmlPage page = client.getPage(new URL(jenkins.getURL(), VIEW_URL));
            client.waitForBackgroundJavaScript(15000);

            String error = page.getElementById("pipelineerror-0").getTextContent();
            assertThat(error, not(containsString("Error communicating")));
            assertThat(page.getElementById("pipelines-1-0").asXml(), containsString("Comp"));
            assertThat(page.getElementById("pipelines-1-0").getAttribute("style"), containsString("width"));
        }
    }

    @Test
    void connectorsAreDrawnBetweenChainedJobs() throws Exception {
        FreeStyleProject build = jenkins.getInstance().getItemByFullName("build", FreeStyleProject.class);
        FreeStyleProject deploy = jenkins.createFreeStyleProject("deploy");
        build.getPublishersList().add(new BuildTrigger(deploy.getName(), Result.SUCCESS));
        jenkins.getInstance().rebuildDependencyGraph();
        jenkins.buildAndAssertSuccess(build);
        jenkins.waitUntilNoActivity();

        DeliveryPipelineView view = new DeliveryPipelineView("Chain");
        view.setComponentSpecs(List.of(new DeliveryPipelineView.ComponentSpec("Chain", "build", null, false)));
        view.setNoOfPipelines(1);
        jenkins.getInstance().addView(view);

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            client.getOptions().setThrowExceptionOnFailingStatusCode(false);
            client.getOptions().setThrowExceptionOnScriptError(false);
            HtmlPage page = client.getPage(new URL(jenkins.getURL(), view.getViewUrl()));
            client.waitForBackgroundJavaScript(15000);

            assertThat(page.getElementById("pipelineerror-0").getTextContent(), not(containsString("Error")));
            assertThat(page.querySelectorAll(".stage").size(), is(2));
            assertThat("jsPlumb drew a connector between the two stages",
                    page.querySelectorAll("._jsPlumb_connector, .relation").size(), is(1));
        }
    }

    @Test
    void paginationLinksAreRenderedForOlderPipelines() throws Exception {
        try (JenkinsRule.WebClient client = jsClient()) {
            HtmlPage page = render(client, VIEW_URL);
            assertThat("two builds, one pipeline per page: a link to page 2",
                    page.querySelectorAll(".pagination a[href*='page=2']").size(), greaterThanOrEqualTo(1));
        }
    }

    @Test
    void aggregatedRowAndHtmlDescriptionAreRendered() throws Exception {
        FreeStyleProject build = jenkins.getInstance().getItemByFullName("build", FreeStyleProject.class);
        build.addProperty(new PipelineProperty("Compile", "Build", "Deploys <b>everything</b> & more"));

        DeliveryPipelineView view = new DeliveryPipelineView("Described");
        view.setComponentSpecs(List.of(new DeliveryPipelineView.ComponentSpec("Comp", "build", null, false)));
        view.setShowAggregatedPipeline(true);
        view.setShowDescription(true);
        view.setNoOfPipelines(1);
        jenkins.getInstance().addView(view);

        try (JenkinsRule.WebClient client = jsClient()) {
            HtmlPage page = render(client, view.getViewUrl());
            DomElement pipelines = page.getElementById("pipelines-1-0");
            assertThat(pipelines.asNormalizedText(), containsString("Aggregated view"));
            assertThat("the description template is rendered as HTML on purpose",
                    pipelines.querySelector(".infoPanelInner").asXml(), containsString("<b>"));
            assertThat(pipelines.querySelector(".infoPanelInner").asNormalizedText(), containsString("everything & more"));
        }
    }

    @Test
    void rebuildButtonPostsToTheApi() throws Exception {
        FreeStyleProject build = jenkins.getInstance().getItemByFullName("build", FreeStyleProject.class);
        FreeStyleProject deploy = jenkins.createFreeStyleProject("deploy");
        build.getPublishersList().add(new BuildTrigger(deploy.getName(), Result.SUCCESS));
        jenkins.getInstance().rebuildDependencyGraph();
        jenkins.buildAndAssertSuccess(build);
        jenkins.waitUntilNoActivity();
        int deployBuilds = deploy.getBuilds().size();

        DeliveryPipelineView view = new DeliveryPipelineView("Rebuild");
        view.setComponentSpecs(List.of(new DeliveryPipelineView.ComponentSpec("Chain", "build", null, false)));
        view.setNoOfPipelines(1);
        view.setAllowRebuild(true);
        jenkins.getInstance().addView(view);

        try (JenkinsRule.WebClient client = jsClient()) {
            HtmlPage page = render(client, view.getViewUrl());
            HtmlElement rebuild = page.querySelector(".task-rebuild");
            assertThat(rebuild, notNullValue());
            assertThat(rebuild.getAttribute("data-project"), is("deploy"));
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

        DeliveryPipelineView view = new DeliveryPipelineView("Start");
        view.setComponentSpecs(List.of(new DeliveryPipelineView.ComponentSpec("Comp", "build", null, false)));
        view.setAllowPipelineStart(true);
        jenkins.getInstance().addView(view);

        try (JenkinsRule.WebClient client = jsClient()) {
            HtmlPage page = render(client, view.getViewUrl());
            HtmlElement start = page.querySelector(".task-trigger-build");
            assertThat(start, notNullValue());
            start.click();
            client.waitForBackgroundJavaScript(5000);
        }
        jenkins.waitUntilNoActivity();
        assertThat("clicking Build now queued a new build", build.getBuilds().size(), is(builds + 1));
    }

    @Test
    void manualTriggerButtonPostsToTheApi() throws Exception {
        FreeStyleProject build = jenkins.getInstance().getItemByFullName("build", FreeStyleProject.class);
        FreeStyleProject deploy = jenkins.createFreeStyleProject("deploy");
        build.getPublishersList().add(new BuildPipelineTrigger(deploy.getName(), null));
        jenkins.getInstance().rebuildDependencyGraph();
        jenkins.buildAndAssertSuccess(build);
        jenkins.waitUntilNoActivity();
        assertThat("a manual downstream job is not built automatically", deploy.getBuilds().size(), is(0));

        DeliveryPipelineView view = new DeliveryPipelineView("Manual");
        view.setComponentSpecs(List.of(new DeliveryPipelineView.ComponentSpec("Chain", "build", null, false)));
        view.setNoOfPipelines(1);
        view.setAllowManualTriggers(true);
        jenkins.getInstance().addView(view);

        try (JenkinsRule.WebClient client = jsClient()) {
            HtmlPage page = render(client, view.getViewUrl());
            HtmlElement manual = page.querySelector(".task-manual");
            assertThat(manual, notNullValue());
            assertThat(manual.getAttribute("data-downstream-project"), is("deploy"));
            assertThat(manual.getAttribute("data-upstream-project"), is("build"));
            manual.click();
            client.waitForBackgroundJavaScript(5000);
        }
        jenkins.waitUntilNoActivity();
        assertThat("clicking the manual trigger built deploy", deploy.getBuilds().size(), is(1));
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

        try (JenkinsRule.WebClient client = jsClient()) {
            String json = client.getPage(new URL(jenkins.getURL(), view.getViewUrl() + "api/json"))
                    .getWebResponse().getContentAsString();
            assertThat(json, containsString("\"workflowComponent\":true"));

            HtmlPage page = render(client, view.getViewUrl());
            assertThat(page.querySelectorAll(".pipeline-component").size(), is(2));
            DomElement pipelines = page.getElementById("pipelines-1-0");
            assertThat(pipelines.asNormalizedText(), containsString("Flow"));
            assertThat(pipelines.asNormalizedText(), containsString("Compile"));
            DomElement start = page.getElementById("startpipeline-1");
            assertThat(start.getAttribute("data-workflow-url"), containsString("job/wf/"));
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

        DeliveryPipelineView view = new DeliveryPipelineView("Gate");
        view.setComponentSpecs(List.of(new DeliveryPipelineView.ComponentSpec("Gate", "gate", null, false)));
        view.setNoOfPipelines(1);
        jenkins.getInstance().addView(view);

        try (JenkinsRule.WebClient client = jsClient()) {
            HtmlPage page = render(client, view.getViewUrl());
            HtmlElement input = page.querySelector(".task-manual-specify");
            assertThat("the paused Deploy stage offers the input button", input, notNullValue());
            assertThat(input.getAttribute("data-project"), is("gate"));
            input.click();
            client.waitForBackgroundJavaScript(5000);
        }
        jenkins.waitUntilNoActivity();
        jenkins.assertBuildStatusSuccess(run);
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
        DeliveryPipelineView view = pipelineView("Parallel", "parallel");

        try (JenkinsRule.WebClient client = jsClient()) {
            HtmlPage page = render(client, view.getViewUrl());
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
        DeliveryPipelineView view = pipelineView("Branches", "branches");

        try (JenkinsRule.WebClient client = jsClient()) {
            HtmlPage page = render(client, view.getViewUrl());
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
        DeliveryPipelineView view = pipelineView("Nested", "nested");

        try (JenkinsRule.WebClient client = jsClient()) {
            HtmlPage page = render(client, view.getViewUrl());
            assertThat(page.querySelectorAll(".stage").size(), is(1));
            assertThat(page.querySelector(".stage-name").asNormalizedText(), is("Build"));
            assertThat(taskNames(page), contains("Compile", "Package"));
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
        DeliveryPipelineView view = pipelineView("Fan", "build");

        try (JenkinsRule.WebClient client = jsClient()) {
            HtmlPage page = render(client, view.getViewUrl());
            assertThat("two downstream jobs are laid out in two rows", page.querySelectorAll(".pipeline-row").size(), is(2));
            DomElement placeholder = page.querySelector(".stage.hide");
            assertThat("the second row starts with an invisible placeholder", placeholder, notNullValue());
            assertThat("placeholders keep their natural height", placeholder.getAttribute("style"), is(""));
            Object verticalAlign = page.executeJavaScript(
                    "getComputedStyle(document.querySelector('.pipeline-cell')).verticalAlign").getJavaScriptResult();
            assertThat(String.valueOf(verticalAlign), is("top"));
        }
    }

    private DeliveryPipelineView pipelineView(String name, String job) throws IOException {
        DeliveryPipelineView view = new DeliveryPipelineView(name);
        view.setComponentSpecs(List.of(new DeliveryPipelineView.ComponentSpec(name, job, null, false)));
        view.setNoOfPipelines(1);
        jenkins.getInstance().addView(view);
        return view;
    }

    private static List<String> taskNames(HtmlPage page) {
        List<String> names = new ArrayList<>();
        for (DomNode node : page.querySelectorAll(".stage-task .taskname")) {
            names.add(node.asNormalizedText());
        }
        return names;
    }

    private JenkinsRule.WebClient jsClient() {
        JenkinsRule.WebClient client = jenkins.createWebClient();
        client.getOptions().setThrowExceptionOnFailingStatusCode(false);
        client.getOptions().setThrowExceptionOnScriptError(false);
        return client;
    }

    private HtmlPage render(JenkinsRule.WebClient client, String relativeUrl) throws Exception {
        HtmlPage page = client.getPage(new URL(jenkins.getURL(), relativeUrl));
        client.waitForBackgroundJavaScript(15000);
        assertThat(page.getElementById("pipelineerror-0").getTextContent(), not(containsString("Error")));
        return page;
    }

    private JenkinsRule.WebClient staticClient() {
        JenkinsRule.WebClient client = jenkins.createWebClient();
        client.getOptions().setThrowExceptionOnFailingStatusCode(false);
        client.getOptions().setJavaScriptEnabled(false);
        return client;
    }

    private String body(JenkinsRule.WebClient client, String relativeUrl) throws Exception {
        Page page = client.getPage(new URL(jenkins.getURL(), relativeUrl));
        assertThat(relativeUrl, page.getWebResponse().getStatusCode(), is(200));
        return page.getWebResponse().getContentAsString();
    }
}
