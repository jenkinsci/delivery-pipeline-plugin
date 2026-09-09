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
package se.diabol.jenkins.workflow;

import org.htmlunit.Page;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.net.URL;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Checks that the workflow view page hands its request parameters to main-behaviour.js through the
 * data holder and renders the pipeline once the script has polled the JSON API (JENKINS-74082).
 */
@WithJenkins
class WorkflowPipelineViewPageTest {

    private static final String VIEW_NAME = "WorkflowPipeline";
    private static final String VIEW_URL = "view/" + VIEW_NAME + "/";

    private JenkinsRule jenkins;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        jenkins = rule;
        WorkflowJob job = jenkins.getInstance().createProject(WorkflowJob.class, "wf");
        job.setDefinition(new CpsFlowDefinition("""
                node {
                    stage('Build') {
                        echo 'building'
                    }
                }
                """.stripIndent(), true));
        jenkins.buildAndAssertSuccess(job);

        WorkflowPipelineView view = new WorkflowPipelineView(VIEW_NAME);
        view.setProject("wf");
        jenkins.getInstance().addView(view);
    }

    @Test
    void viewPageCarriesRequestParametersInDataHolder() throws Exception {
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            client.getOptions().setThrowExceptionOnFailingStatusCode(false);
            client.getOptions().setJavaScriptEnabled(false);

            Page page = client.getPage(new URL(jenkins.getURL(), VIEW_URL));
            assertThat(page.getWebResponse().getStatusCode(), is(200));
            String html = page.getWebResponse().getContentAsString();
            assertThat(html, containsString("data-page=\"1\""));
            assertThat(html, containsString("data-component=\"1\""));
            assertThat(html, containsString("data-fullscreen=\"false\""));
            assertThat(html, containsString("data-it-id=\"0\""));
            assertThat(html, not(containsString("bound/script/null?var=page")));
        }
    }

    @Test
    void deprecatedViewIsNotOfferedForNewViews() throws Exception {
        WorkflowPipelineView.DescriptorImpl descriptor =
                jenkins.getInstance().getDescriptorByType(WorkflowPipelineView.DescriptorImpl.class);
        assertThat(descriptor.isInstantiable(), is(false));
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            String newViewPage = client.goTo("newView").asNormalizedText();
            assertThat(newViewPage, containsString("Delivery Pipeline View"));
            assertThat(newViewPage, not(containsString("Jenkins Pipelines (deprecated)")));
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
            assertThat(page.getElementById("pipelines-1-0").asXml(), containsString("Build"));
        }
    }
}
