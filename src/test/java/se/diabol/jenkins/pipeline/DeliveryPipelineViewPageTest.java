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
import static org.hamcrest.Matchers.containsString;
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
import static se.diabol.jenkins.pipeline.PageTestSupport.waitFor;

import au.com.centrumsystems.hudson.plugin.buildpipeline.trigger.BuildPipelineTrigger;
import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.tasks.BuildTrigger;
import java.net.URL;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** The view as a browser sees it: the page, the JSON it polls, and freestyle chains rendered and acted on by the script. */
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
            waitFor(() -> page.querySelector(".pipeline-heading").asNormalizedText().contains("#1"));
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
            waitFor(() -> deploy.getBuilds().size() > deployBuilds || !jenkins.getInstance().getQueue().isEmpty());
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
            waitFor(() -> build.getBuilds().size() > builds || !jenkins.getInstance().getQueue().isEmpty());
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
            waitFor(() -> !deploy.getBuilds().isEmpty() || !jenkins.getInstance().getQueue().isEmpty());
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
    void apiAnswersUnchangedPollsWith304AndWritesTheServerTimeAfresh() throws Exception {
        try (JenkinsRule.WebClient client = staticClient(jenkins)) {
            URL url = new URL(jenkins.getURL(), VIEW_URL + "api/json?page=1&component=1&fullscreen=false");
            Page first = client.getPage(url);
            String etag = first.getWebResponse().getResponseHeaderValue("ETag");
            assertThat("the JSON carries an ETag", etag, notNullValue());
            assertThat(first.getWebResponse().getContentAsString(), containsString("\"components\":["));
            WebRequest conditional = new WebRequest(url);
            conditional.setAdditionalHeader("If-None-Match", etag);
            Page unchanged = client.getPage(conditional);
            assertThat("nothing changed, so the poll is answered without a body",
                    unchanged.getWebResponse().getStatusCode(), is(304));
            assertThat(unchanged.getWebResponse().getContentAsString(), is(""));
            Page again = client.getPage(url);
            assertThat("the exported bytes are reused, the server time is not",
                    serverTimeOf(again) >= serverTimeOf(first), is(true));
            assertThat(again.getWebResponse().getResponseHeaderValue("ETag"), is(etag));
            jenkins.buildAndAssertSuccess(jenkins.getInstance().getItemByFullName("build", FreeStyleProject.class));
            Page changed = client.getPage(conditional);
            assertThat("a build changed the model, so the ETag no longer matches",
                    changed.getWebResponse().getStatusCode(), is(200));
            assertThat(changed.getWebResponse().getResponseHeaderValue("ETag"), not(is(etag)));
            assertThat("Stapler's own parameters still export on the spot",
                    body(jenkins, client, VIEW_URL + "api/json?tree=components[name]"), containsString("\"name\":\"Comp\""));
        }
    }

    private static long serverTimeOf(Page page) {
        Matcher matcher = Pattern.compile("\"serverTime\":(\\d+)").matcher(page.getWebResponse().getContentAsString());
        assertThat("the response carries a server time", matcher.find(), is(true));
        return Long.parseLong(matcher.group(1));
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

    private static String hrefOfTask(HtmlPage page, String name) {
        for (DomNode link : page.querySelectorAll(".stage-task .taskname a")) {
            if (link.getTextContent().trim().equals(name)) {
                return ((DomElement) link).getAttribute("href");
            }
        }
        throw new AssertionError("no task named " + name);
    }
}
