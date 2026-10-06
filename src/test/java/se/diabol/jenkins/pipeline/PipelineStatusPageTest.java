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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static se.diabol.jenkins.pipeline.PageTestSupport.body;
import static se.diabol.jenkins.pipeline.PageTestSupport.jsClient;
import static se.diabol.jenkins.pipeline.PageTestSupport.render;
import static se.diabol.jenkins.pipeline.PageTestSupport.taskNames;
import static se.diabol.jenkins.pipeline.PageTestSupport.texts;
import static se.diabol.jenkins.pipeline.PageTestSupport.view;

import hudson.model.Result;
import java.util.List;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Statuses of Pipeline runs and their stages, and the test results and warnings they carry, as the view renders them. */
@WithJenkins
class PipelineStatusPageTest {

    private JenkinsRule jenkins;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        jenkins = rule;
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
}
