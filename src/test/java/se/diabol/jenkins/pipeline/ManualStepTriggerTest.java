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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static se.diabol.jenkins.pipeline.PageTestSupport.jsClient;
import static se.diabol.jenkins.pipeline.PageTestSupport.render;
import static se.diabol.jenkins.pipeline.PageTestSupport.view;

import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import java.net.URL;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import se.diabol.jenkins.pipeline.freestyle.FreestyleComponentSource;
import se.diabol.jenkins.pipeline.model.Component;
import se.diabol.jenkins.pipeline.model.StatusType;
import se.diabol.jenkins.pipeline.model.Task;
import se.diabol.jenkins.pipeline.model.ViewSettings;
import se.diabol.jenkins.pipeline.source.ComponentRequest;

/** The plugin's own manual step: listed in the chain, never started automatically, started from the view. */
@WithJenkins
class ManualStepTriggerTest {

    private JenkinsRule jenkins;
    private FreeStyleProject build;
    private FreeStyleProject deploy;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        jenkins = rule;
        build = jenkins.createFreeStyleProject("build");
        deploy = jenkins.createFreeStyleProject("deploy");
        build.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("VERSION", "1.0")));
        deploy.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("VERSION", "unset"), new StringParameterDefinition("TARGET", "production")));
        build.getPublishersList().add(new ManualStepTrigger("deploy"));
        jenkins.getInstance().rebuildDependencyGraph();
    }

    private Task deployTask() throws Exception {
        ViewSettings settings = new ViewSettings(1, 1, 5, false, false, false, false, false, false, false, false,
                false, true, true, true, true);
        Component component = new FreestyleComponentSource().resolve(new ComponentRequest("Comp", 1, build, null,
                false, Jenkins.get(), settings, 1, false));
        assertThat(component.error(), nullValue());
        return component.pipelines().get(0).stages().get(1).tasks().get(0);
    }

    @Test
    void manualStepIsPartOfTheChainButNeverStartedAutomatically() throws Exception {
        assertThat("the dependency graph lists the manual step downstream", build.getDownstreamProjects(), hasItem(deploy));
        jenkins.buildAndAssertSuccess(build);
        jenkins.waitUntilNoActivity();
        assertThat("a finished upstream build does not start the manual step", deploy.getBuilds().size(), is(0));
        assertThat("the manual step is one of the chain's jobs", new FreestyleComponentSource().jobsOf(build, null).contains(deploy), is(true));
        Task task = deployTask();
        assertThat(task.status().type(), is(StatusType.IDLE));
        assertThat(task.manual(), notNullValue());
        assertThat(task.manual().upstreamJob(), is("build"));
        assertThat(task.manual().upstreamBuild(), is(1));
        assertThat("the step can be triggered once the upstream build has finished", task.manual().enabled(), is(true));
    }

    @Test
    void triggeringFromTheViewHandsTheUpstreamParametersDown() throws Exception {
        FreeStyleBuild upstream = jenkins.assertBuildStatusSuccess(build.scheduleBuild2(0,
                new ParametersAction(new StringParameterValue("VERSION", "2.0"))));
        jenkins.waitUntilNoActivity();
        DeliveryPipelineView view = view(jenkins, "Manual", "build");
        view.setAllowManualTriggers(true);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            HtmlElement manual = page.querySelector(".task-manual");
            assertThat(manual, notNullValue());
            assertThat(manual.getAttribute("data-project"), is("deploy"));
            assertThat(manual.getAttribute("data-upstream"), is("build"));
            assertThat(manual.getAttribute("data-build"), is("1"));
            manual.click();
            client.waitForBackgroundJavaScript(5000);
            assertThat(PageTestSupport.errorText(page), not(containsString("Could not")));
        }
        jenkins.waitUntilNoActivity();
        FreeStyleBuild downstream = deploy.getLastBuild();
        assertThat("clicking the button built the manual step", downstream, notNullValue());
        jenkins.assertBuildStatusSuccess(downstream);
        ParametersAction parameters = downstream.getAction(ParametersAction.class);
        assertThat("the upstream value is handed down", parameters.getParameter("VERSION").getValue(), is("2.0"));
        assertThat("a parameter the upstream build lacks keeps its default", parameters.getParameter("TARGET").getValue(), is("production"));
        Cause.UpstreamCause cause = downstream.getCause(Cause.UpstreamCause.class);
        assertThat(cause, notNullValue());
        assertThat(cause.getUpstreamBuild(), is(upstream.getNumber()));
        assertThat(downstream.getCause(Cause.UserIdCause.class), notNullValue());
        Task task = deployTask();
        assertThat("the view now shows the build in the same pipeline instance", task.buildNumber(), is(1));
        assertThat(task.status().type(), is(StatusType.SUCCESS));
        assertThat("a successful manual step is not offered again", task.manual().enabled(), is(false));
    }

    @Test
    void theStepSurvivesTheJobConfigureFormAndFollowsRenames() throws Exception {
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlPage page = client.getPage(new URL(jenkins.getURL(), build.getUrl() + "configure"));
            HtmlForm form = page.getFormByName("config");
            jenkins.submit(form);
        }
        assertThat(build.getPublishersList().get(ManualStepTrigger.class).getDownstreamProjectNames(), is("deploy"));
        deploy.renameTo("deploy2");
        assertThat(build.getPublishersList().get(ManualStepTrigger.class).getDownstreamProjectNames(), is("deploy2"));
        assertThat(build.getPublishersList().get(ManualStepTrigger.class).getDownstreamProjects(build.getParent()),
                contains(deploy));
    }

    @Test
    void validationReportsUnknownJobsAndSelfReferences() {
        ManualStepTrigger.DescriptorImpl descriptor = jenkins.getInstance().getDescriptorByType(ManualStepTrigger.DescriptorImpl.class);
        assertThat(descriptor.doCheckDownstreamProjectNames(build, "deploy").kind, is(hudson.util.FormValidation.Kind.OK));
        assertThat(descriptor.doCheckDownstreamProjectNames(build, "deploy, nope").renderHtml(), containsString("No such job: nope"));
        assertThat(descriptor.doCheckDownstreamProjectNames(build, "build").renderHtml(), containsString("own manual step"));
    }

    private static org.hamcrest.Matcher<String> not(org.hamcrest.Matcher<String> matcher) {
        return org.hamcrest.Matchers.not(matcher);
    }
}
