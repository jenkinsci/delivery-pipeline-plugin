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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.htmlunit.Page;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.jvnet.hudson.test.JenkinsRule;

/** Shared helpers for tests that render the view in HtmlUnit. */
final class PageTestSupport {

    private PageTestSupport() {
    }

    static DeliveryPipelineView view(JenkinsRule jenkins, String name, String job) throws IOException {
        DeliveryPipelineView view = new DeliveryPipelineView(name);
        view.setComponentSpecs(List.of(new DeliveryPipelineView.ComponentSpec(name, job, null, false)));
        view.setNoOfPipelines(1);
        jenkins.getInstance().addView(view);
        return view;
    }

    static JenkinsRule.WebClient jsClient(JenkinsRule jenkins) {
        JenkinsRule.WebClient client = jenkins.createWebClient();
        client.getOptions().setThrowExceptionOnFailingStatusCode(false);
        client.getOptions().setThrowExceptionOnScriptError(false);
        return client;
    }

    static JenkinsRule.WebClient staticClient(JenkinsRule jenkins) {
        JenkinsRule.WebClient client = jenkins.createWebClient();
        client.getOptions().setThrowExceptionOnFailingStatusCode(false);
        client.getOptions().setJavaScriptEnabled(false);
        return client;
    }

    /** Opens the page, lets the script poll and render, and checks that no error is shown. */
    static HtmlPage render(JenkinsRule jenkins, JenkinsRule.WebClient client, String relativeUrl) throws Exception {
        HtmlPage page = client.getPage(new URL(jenkins.getURL(), relativeUrl));
        client.waitForBackgroundJavaScript(15000);
        assertThat(errorText(page), not(containsString("Error")));
        return page;
    }

    static String errorText(HtmlPage page) {
        DomNode error = page.querySelector(".dpp-error");
        return error == null ? "" : error.getTextContent();
    }

    static String body(JenkinsRule jenkins, JenkinsRule.WebClient client, String relativeUrl) throws Exception {
        Page page = client.getPage(new URL(jenkins.getURL(), relativeUrl));
        assertThat(relativeUrl, page.getWebResponse().getStatusCode(), is(200));
        return page.getWebResponse().getContentAsString();
    }

    static List<String> texts(HtmlPage page, String selector) {
        List<String> result = new ArrayList<>();
        for (DomNode node : page.querySelectorAll(selector)) {
            result.add(node.asNormalizedText());
        }
        return result;
    }

    static List<String> taskNames(HtmlPage page) {
        return texts(page, ".stage-task .taskname");
    }
}
