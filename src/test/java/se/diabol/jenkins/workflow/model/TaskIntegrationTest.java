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
package se.diabol.jenkins.workflow.model;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

import hudson.cli.BuildCommand;
import hudson.model.Result;
import org.htmlunit.Page;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import se.diabol.jenkins.workflow.WorkflowPipelineView;

import java.net.URL;

public class TaskIntegrationTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void shouldHandleClosureTaskInClosureStage() throws Exception {
        shouldCreatePipelineAndViewAndSuccessfullyBuildDefinition("""
                node {
                    stage('Stage1') {
                        task('Task1') {
                            echo 'Task1'
                        }
                    }
                    stage('Stage2') {
                        task('Task2') {
                            echo 'Task2'
                        }
                    }
                }
                """.stripIndent()
        );
    }

    @Test
    public void shouldHandleClosureTaskInNonClosureStage() throws Exception {
        shouldCreatePipelineAndViewAndSuccessfullyBuildDefinition("""
                node {
                    stage 'Stage1'
                        task('Task1') {
                            echo 'Task1'
                        }
                    stage 'Stage2'
                        task('Task2') {
                            echo 'Task2'
                        }
                }
                """.stripIndent()
        );
    }

    private void shouldCreatePipelineAndViewAndSuccessfullyBuildDefinition(String script) throws Exception {
        String projectName = "TaskPipeline";
        WorkflowJob pipeline = jenkins.getInstance().createProject(WorkflowJob.class, projectName);
        pipeline.setDefinition(new CpsFlowDefinition(script, true));

        pipeline.scheduleBuild(0, new BuildCommand.CLICause());
        jenkins.waitUntilNoActivity();
        assertThat(pipeline.getLastBuild().getResult(), is(Result.SUCCESS));

        String viewName = "TaskPipelineView";
        WorkflowPipelineView view = new WorkflowPipelineView(viewName);
        view.setProject(projectName);

        jenkins.getInstance().addView(view);

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {

            Page viewPage = client.getPage(new URL(jenkins.getURL(), "/jenkins/view/" + viewName));
            assertThat(viewPage.getWebResponse().getStatusCode(), is(200));

            Page apiPage = client.getPage(new URL(jenkins.getURL(), "/jenkins/view/" + viewName + "/api/json"));
            assertThat(apiPage.getWebResponse().getStatusCode(), is(200));
        }
    }
}
