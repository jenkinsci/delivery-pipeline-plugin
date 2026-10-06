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
import static org.hamcrest.Matchers.is;
import static se.diabol.jenkins.pipeline.PageTestSupport.jsClient;
import static se.diabol.jenkins.pipeline.PageTestSupport.render;
import static se.diabol.jenkins.pipeline.PageTestSupport.taskNames;
import static se.diabol.jenkins.pipeline.PageTestSupport.texts;
import static se.diabol.jenkins.pipeline.PageTestSupport.view;

import hudson.model.Result;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Parallel branches, nested stages and matrix cells of Pipeline runs, as the view renders them. */
@WithJenkins
class PipelineBranchesPageTest {

    private JenkinsRule jenkins;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        jenkins = rule;
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
}
