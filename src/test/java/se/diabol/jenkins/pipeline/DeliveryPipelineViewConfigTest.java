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

import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.net.URL;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Submitting the configure form unchanged must keep every setting: the form data is bound back
 * onto the view in {@code submit()} and the component / regexp lists are bound separately.
 */
@WithJenkins
class DeliveryPipelineViewConfigTest {

    @Test
    void settingsSurviveTheConfigureForm(JenkinsRule jenkins) throws Exception {
        jenkins.createFreeStyleProject("build");
        jenkins.createFreeStyleProject("deploy");
        jenkins.getInstance().createProject(WorkflowJob.class, "wf");

        DeliveryPipelineView view = new DeliveryPipelineView("Pipeline");
        view.setComponentSpecs(List.of(
                new DeliveryPipelineView.ComponentSpec("Comp", "build", "deploy", true),
                new DeliveryPipelineView.ComponentSpec("Flow", "wf", null, false)));
        view.setRegexpFirstJobs(List.of(new DeliveryPipelineView.RegExpSpec("build.*", true)));
        view.setNoOfPipelines(4);
        view.setNoOfColumns(2);
        view.setUpdateInterval(7);
        view.setMaxNumberOfVisiblePipelines(5);
        view.setPagingEnabled(true);
        view.setAllowPipelineStart(true);
        view.setAllowManualTriggers(true);
        view.setAllowRebuild(true);
        view.setAllowAbort(true);
        view.setShowChanges(true);
        view.setShowAggregatedPipeline(true);
        view.setShowAggregatedChanges(true);
        view.setAggregatedChangesGroupingPattern("JIRA-\\d+");
        view.setShowTotalBuildTime(true);
        view.setShowAbsoluteDateTime(true);
        view.setShowDescription(true);
        view.setShowPromotions(true);
        view.setShowTestResults(true);
        view.setShowStaticAnalysisResults(true);
        view.setLinkRelative(true);
        view.setLinkToConsoleLog(true);
        view.setEmbeddedCss("/userContent/pipeline.css");
        view.setFullScreenCss("/userContent/fullscreen.css");
        jenkins.getInstance().addView(view);

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlPage page = client.getPage(new URL(jenkins.getURL(), view.getViewUrl() + "configure"));
            HtmlForm form = page.getFormByName("viewConfig");
            jenkins.submit(form);
        }

        DeliveryPipelineView saved = (DeliveryPipelineView) jenkins.getInstance().getView("Pipeline");
        DeliveryPipelineView.ComponentSpec spec = saved.getComponentSpecs().get(0);
        assertThat(spec.getName(), is("Comp"));
        assertThat(spec.getFirstJob(), is("build"));
        assertThat(spec.getLastJob(), is("deploy"));
        assertThat(spec.isShowUpstream(), is(true));
        assertThat("a Pipeline job survives the job picker", saved.getComponentSpecs().get(1).getFirstJob(), is("wf"));
        assertThat(saved.getRegexpFirstJobs().get(0).getRegexp(), is("build.*"));
        assertThat(saved.getRegexpFirstJobs().get(0).isShowUpstream(), is(true));
        assertThat(saved.getNoOfPipelines(), is(4));
        assertThat(saved.getNoOfColumns(), is(2));
        assertThat(saved.getUpdateInterval(), is(7));
        assertThat(saved.getMaxNumberOfVisiblePipelines(), is(5));
        assertThat(saved.getPagingEnabled(), is(true));
        assertThat(saved.isAllowPipelineStart(), is(true));
        assertThat(saved.isAllowManualTriggers(), is(true));
        assertThat(saved.isAllowRebuild(), is(true));
        assertThat(saved.isAllowAbort(), is(true));
        assertThat(saved.isShowChanges(), is(true));
        assertThat(saved.isShowAggregatedPipeline(), is(true));
        assertThat(saved.isShowAggregatedChanges(), is(true));
        assertThat(saved.getAggregatedChangesGroupingPattern(), is("JIRA-\\d+"));
        assertThat(saved.isShowTotalBuildTime(), is(true));
        assertThat(saved.isShowAbsoluteDateTime(), is(true));
        assertThat(saved.isShowDescription(), is(true));
        assertThat(saved.isShowPromotions(), is(true));
        assertThat(saved.isShowTestResults(), is(true));
        assertThat(saved.isShowStaticAnalysisResults(), is(true));
        assertThat(saved.isLinkRelative(), is(true));
        assertThat(saved.isLinkToConsoleLog(), is(true));
        assertThat(saved.getEmbeddedCss(), is("/userContent/pipeline.css"));
        assertThat(saved.getFullScreenCss(), is("/userContent/fullscreen.css"));
    }
}
