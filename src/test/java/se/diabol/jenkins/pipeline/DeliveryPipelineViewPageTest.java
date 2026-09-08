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

import hudson.model.FreeStyleProject;
import org.htmlunit.Page;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.net.URL;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

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
        }
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
