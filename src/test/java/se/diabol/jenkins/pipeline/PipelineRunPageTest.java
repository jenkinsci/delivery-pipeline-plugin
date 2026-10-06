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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static se.diabol.jenkins.pipeline.PageTestSupport.body;
import static se.diabol.jenkins.pipeline.PageTestSupport.jsClient;
import static se.diabol.jenkins.pipeline.PageTestSupport.render;
import static se.diabol.jenkins.pipeline.PageTestSupport.taskNames;
import static se.diabol.jenkins.pipeline.PageTestSupport.texts;
import static se.diabol.jenkins.pipeline.PageTestSupport.view;

import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.tasks.BuildTrigger;
import java.util.List;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Pipeline jobs as the view renders them: next to freestyle chains, queued, without stages, and the runs they start. */
@WithJenkins
class PipelineRunPageTest {

    private static final String VIEW_NAME = "Pipeline";

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
