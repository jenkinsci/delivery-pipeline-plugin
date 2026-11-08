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
import static se.diabol.jenkins.pipeline.PageTestSupport.body;
import static se.diabol.jenkins.pipeline.PageTestSupport.staticClient;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.View;
import hudson.tasks.BuildTrigger;
import java.net.URL;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * JENKINS-34493: what the view shows to a user who may not read the jobs behind it. "insider" may read the jobs,
 * "outsider" may read Jenkins and the view but none of the jobs, and "stranger" may read Jenkins only.
 */
@WithJenkins
class ViewPermissionsTest {

    private JenkinsRule jenkins;
    private DeliveryPipelineView view;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        jenkins = rule;
        FreeStyleProject build = jenkins.createFreeStyleProject("secret-build");
        FreeStyleProject deploy = jenkins.createFreeStyleProject("secret-deploy");
        build.getPublishersList().add(new BuildTrigger(deploy.getName(), Result.SUCCESS));
        jenkins.getInstance().rebuildDependencyGraph();
        jenkins.buildAndAssertSuccess(build);
        jenkins.waitUntilNoActivity();
        view = new DeliveryPipelineView("Release");
        view.setComponentSpecs(List.of(new DeliveryPipelineView.ComponentSpec("Release", "secret-build", null, false)));
        view.setNoOfPipelines(1);
        jenkins.getInstance().addView(view);
        jenkins.getInstance().setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.getInstance().setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ).everywhere().to("insider", "outsider", "stranger")
                .grant(View.READ).everywhere().to("insider", "outsider")
                .grant(Item.READ).onItems(build, deploy).to("insider"));
    }

    private String json(String user) throws Exception {
        try (JenkinsRule.WebClient client = staticClient(jenkins).login(user)) {
            return body(jenkins, client, view.getViewUrl() + "api/json");
        }
    }

    private static int pipelinesIn(String json) {
        return JSONObject.fromObject(json).getJSONArray("components").getJSONObject(0).getJSONArray("pipelines").size();
    }

    @Test
    void anOutsiderSeesNoBuildsOfJobsTheyCannotRead() throws Exception {
        String json = json("outsider");
        assertThat(pipelinesIn(json), is(0));
        assertThat(json, not(containsString("secret-deploy")));
    }

    @Test
    void anOutsiderSeesNoBuildsEvenAfterAnInsiderHasLoadedTheView() throws Exception {
        assertThat("the insider sees the run", pipelinesIn(json("insider")), is(1));
        String json = json("outsider");
        assertThat("the model the insider loaded is not served to the outsider", pipelinesIn(json), is(0));
        assertThat(json, not(containsString("secret-deploy")));
    }

    @Test
    void anInsiderSeesTheBuildsEvenAfterAnOutsiderHasLoadedTheView() throws Exception {
        assertThat(pipelinesIn(json("outsider")), is(0));
        String json = json("insider");
        assertThat("the empty model the outsider loaded is not served to the insider", pipelinesIn(json), is(1));
        assertThat(json, containsString("secret-deploy"));
    }

    @Test
    void theViewIsHiddenFromAUserWhoCanReadNoneOfItsJobs() throws Exception {
        try (JenkinsRule.WebClient client = staticClient(jenkins).login("stranger")) {
            assertThat("the view is not listed", body(jenkins, client, "api/json?tree=views[name]"),
                    not(containsString("Release")));
            int status = client.getPage(new URL(jenkins.getURL(), view.getViewUrl() + "api/json"))
                    .getWebResponse().getStatusCode();
            assertThat("the view's model is refused", status, is(403));
        }
        try (JenkinsRule.WebClient client = staticClient(jenkins).login("insider")) {
            assertThat(body(jenkins, client, "api/json?tree=views[name]"), containsString("Release"));
        }
    }
}
