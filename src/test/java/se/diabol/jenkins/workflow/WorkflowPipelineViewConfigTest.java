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
 * Submitting the configure form unchanged must keep every setting of the workflow view.
 */
@WithJenkins
class WorkflowPipelineViewConfigTest {

    @Test
    void settingsSurviveTheConfigureForm(JenkinsRule jenkins) throws Exception {
        jenkins.getInstance().createProject(WorkflowJob.class, "wf");

        WorkflowPipelineView view = new WorkflowPipelineView("Workflow");
        view.setComponentSpecs(List.of(new WorkflowPipelineView.ComponentSpec("Comp", "wf")));
        view.setNoOfPipelines(2);
        view.setNoOfColumns(2);
        view.setUpdateInterval(9);
        view.setMaxNumberOfVisiblePipelines(3);
        view.setAllowPipelineStart(true);
        view.setAllowAbort(true);
        view.setShowChanges(true);
        view.setShowAbsoluteDateTime(true);
        view.setLinkToConsoleLog(true);
        jenkins.getInstance().addView(view);

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlPage page = client.getPage(new URL(jenkins.getURL(), view.getViewUrl() + "configure"));
            HtmlForm form = page.getFormByName("viewConfig");
            jenkins.submit(form);
        }

        WorkflowPipelineView saved = (WorkflowPipelineView) jenkins.getInstance().getView("Workflow");
        assertThat(saved.getComponentSpecs().get(0).getName(), is("Comp"));
        assertThat(saved.getComponentSpecs().get(0).getJob(), is("wf"));
        assertThat(saved.getNoOfPipelines(), is(2));
        assertThat(saved.getNoOfColumns(), is(2));
        assertThat(saved.getUpdateInterval(), is(9));
        assertThat(saved.getMaxNumberOfVisiblePipelines(), is(3));
        assertThat(saved.isAllowPipelineStart(), is(true));
        assertThat(saved.isAllowAbort(), is(true));
        assertThat(saved.isShowChanges(), is(true));
        assertThat(saved.isShowAbsoluteDateTime(), is(true));
        assertThat(saved.isLinkToConsoleLog(), is(true));
    }
}
