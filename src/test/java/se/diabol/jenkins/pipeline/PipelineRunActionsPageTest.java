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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static se.diabol.jenkins.pipeline.PageTestSupport.body;
import static se.diabol.jenkins.pipeline.PageTestSupport.jsClient;
import static se.diabol.jenkins.pipeline.PageTestSupport.render;
import static se.diabol.jenkins.pipeline.PageTestSupport.view;
import static se.diabol.jenkins.pipeline.PageTestSupport.waitFor;

import hudson.model.Result;
import org.htmlunit.html.DomElement;
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

/** Pipeline jobs acted on from the view: input steps, abort, running again and restarting from a stage. */
@WithJenkins
class PipelineRunActionsPageTest {

    private JenkinsRule jenkins;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        jenkins = rule;
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
            waitFor(() -> !run.isBuilding());
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
            waitFor(() -> !run.isBuilding());
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
            waitFor(() -> flow.getLastBuild().getNumber() > 1 || !jenkins.getInstance().getQueue().isEmpty());
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
            waitFor(() -> flow.getLastBuild().getNumber() > 1 || !jenkins.getInstance().getQueue().isEmpty());
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
}
