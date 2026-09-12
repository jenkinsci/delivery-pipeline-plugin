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

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.util.ListBoxModel;
import java.net.URL;
import java.util.List;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class DeliveryPipelineViewConfigTest {

    @Test
    void settingsSurviveTheConfigureForm(JenkinsRule jenkins) throws Exception {
        jenkins.createFreeStyleProject("build");
        jenkins.createFreeStyleProject("deploy");
        jenkins.getInstance().createProject(WorkflowJob.class, "wf");
        DeliveryPipelineView view = new DeliveryPipelineView("Pipeline");
        view.setDescription("Our pipelines");
        view.setComponentSpecs(List.of(
                new DeliveryPipelineView.ComponentSpec("Comp", "build", "deploy", true),
                new DeliveryPipelineView.ComponentSpec("Flow", "wf", null, false)));
        view.setRegexpFirstJobs(List.of(new DeliveryPipelineView.RegExpSpec("(build.*)", true)));
        view.setNoOfPipelines(4);
        view.setNoOfColumns(2);
        view.setSorting(Sorting.LATEST_ACTIVITY.getId());
        view.setUpdateInterval(7);
        view.setMaxNumberOfVisiblePipelines(5);
        view.setPagingEnabled(true);
        view.setAllowPipelineStart(true);
        view.setAllowManualTriggers(true);
        view.setAllowRebuild(true);
        view.setAllowAbort(true);
        view.setShowChanges(true);
        view.setShowAggregatedPipeline(true);
        view.setShowTotalBuildTime(true);
        view.setShowAbsoluteDateTime(true);
        view.setShowDescription(true);
        view.setShowPromotions(true);
        view.setShowTestResults(true);
        view.setShowStaticAnalysisResults(true);
        jenkins.getInstance().addView(view);
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlPage page = client.getPage(new URL(jenkins.getURL(), view.getViewUrl() + "configure"));
            HtmlForm form = page.getFormByName("viewConfig");
            jenkins.submit(form);
        }
        DeliveryPipelineView saved = (DeliveryPipelineView) jenkins.getInstance().getView("Pipeline");
        assertThat(saved.getDescription(), is("Our pipelines"));
        DeliveryPipelineView.ComponentSpec spec = saved.getComponentSpecs().get(0);
        assertThat(spec.getName(), is("Comp"));
        assertThat(spec.getFirstJob(), is("build"));
        assertThat(spec.getLastJob(), is("deploy"));
        assertThat(spec.isShowUpstream(), is(true));
        assertThat("a Pipeline job survives the job picker", saved.getComponentSpecs().get(1).getFirstJob(), is("wf"));
        assertThat(saved.getRegexpFirstJobs().get(0).getRegexp(), is("(build.*)"));
        assertThat(saved.getRegexpFirstJobs().get(0).isShowUpstream(), is(true));
        assertThat(saved.getNoOfPipelines(), is(4));
        assertThat(saved.getNoOfColumns(), is(2));
        assertThat(saved.getSorting(), is(Sorting.LATEST_ACTIVITY.getId()));
        assertThat(saved.getUpdateInterval(), is(7));
        assertThat(saved.getMaxNumberOfVisiblePipelines(), is(5));
        assertThat(saved.isPagingEnabled(), is(true));
        assertThat(saved.isAllowPipelineStart(), is(true));
        assertThat(saved.isAllowManualTriggers(), is(true));
        assertThat(saved.isAllowRebuild(), is(true));
        assertThat(saved.isAllowAbort(), is(true));
        assertThat(saved.isShowChanges(), is(true));
        assertThat(saved.isShowAggregatedPipeline(), is(true));
        assertThat(saved.isShowTotalBuildTime(), is(true));
        assertThat(saved.isShowAbsoluteDateTime(), is(true));
        assertThat(saved.isShowDescription(), is(true));
        assertThat(saved.isShowPromotions(), is(true));
        assertThat(saved.isShowTestResults(), is(true));
        assertThat(saved.isShowStaticAnalysisResults(), is(true));
    }

    @Test
    void theViewTypeIsOfferedAndTheOldPipelineOnlyTypeIsNot(JenkinsRule jenkins) throws Exception {
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            String html = client.goTo("newView").getWebResponse().getContentAsString();
            assertThat(html.contains("Delivery Pipeline View"), is(true));
            assertThat(html.contains("Delivery Pipeline View for Jenkins Pipelines"), is(false));
        }
    }

    /**
     * A folder laid out like a seeded controller: the jobs live in a subfolder, another job sorts before the one the
     * view shows, and the seed wrote the jobs' full names. The third component is spelled the way the 1.x form saved
     * it, relative to the folder.
     */
    private static DeliveryPipelineView seededFolderView(JenkinsRule jenkins, String firstJob) throws Exception {
        Folder ancestry = jenkins.getInstance().createProject(Folder.class, "Ancestry");
        Folder zero = ancestry.createProject(Folder.class, "0");
        zero.createProject(FreeStyleProject.class, "build_alpine");
        zero.createProject(FreeStyleProject.class, "build_golang");
        Folder apps = ancestry.createProject(Folder.class, "apps");
        apps.createProject(FreeStyleProject.class, "svc");
        DeliveryPipelineView view = new DeliveryPipelineView("golang");
        view.setComponentSpecs(List.of(
                new DeliveryPipelineView.ComponentSpec("0", firstJob, null, false),
                new DeliveryPipelineView.ComponentSpec("apps", "Ancestry/apps", null, false),
                new DeliveryPipelineView.ComponentSpec("legacy", "0/build_golang", "0/build_alpine", true)));
        view.setNoOfPipelines(4);
        view.setShowAggregatedPipeline(true);
        view.setPagingEnabled(true);
        view.setShowChanges(true);
        view.setShowDescription(true);
        view.setShowTotalBuildTime(true);
        view.setAllowPipelineStart(true);
        view.setAllowManualTriggers(true);
        view.setAllowRebuild(true);
        view.setUpdateInterval(10);
        view.setMaxNumberOfVisiblePipelines(0);
        view.setSorting(Sorting.LATEST_ACTIVITY.getId());
        ancestry.addView(view);
        return view;
    }

    /** The view as it is written to the folder's config.xml. */
    private static String configXml(DeliveryPipelineView view) throws Exception {
        String folder = ((Folder) view.getOwner()).getConfigFile().asString();
        String start = "<se.diabol.jenkins.pipeline.DeliveryPipelineView>";
        String end = "</se.diabol.jenkins.pipeline.DeliveryPipelineView>";
        int from = folder.indexOf(start);
        int to = folder.indexOf(end, from);
        assertThat("the view is in the folder's config.xml", from >= 0 && to > from, is(true));
        return folder.substring(from, to + end.length());
    }

    @Test
    void savingTheFormUnchangedWritesTheConfigurationBackAsItWas(JenkinsRule jenkins) throws Exception {
        DeliveryPipelineView view = seededFolderView(jenkins, "Ancestry/0/build_golang");
        String before = configXml(view);
        assertThat(before, containsString("<firstJob>Ancestry/0/build_golang</firstJob>"));
        assertThat(before, containsString("<firstJob>Ancestry/apps</firstJob>"));
        assertThat(before, containsString("<lastJob>0/build_alpine</lastJob>"));
        assertThat(before, containsString("<maxNumberOfVisiblePipelines>0</maxNumberOfVisiblePipelines>"));

        jenkins.configRoundtrip(view);

        assertThat(configXml(view), is(before));
        DeliveryPipelineView saved = (DeliveryPipelineView) view.getOwner().getView("golang");
        assertThat(saved.getComponentSpecs().get(0).getFirstJob(), is("Ancestry/0/build_golang"));
        assertThat(saved.getComponentSpecs().get(1).getFirstJob(), is("Ancestry/apps"));
        assertThat(saved.getComponentSpecs().get(2).getFirstJob(), is("0/build_golang"));
        assertThat(saved.getComponentSpecs().get(2).getLastJob(), is("0/build_alpine"));
        assertThat(saved.getNoOfPipelines(), is(4));
        assertThat(saved.getMaxNumberOfVisiblePipelines(), is(0));
        assertThat(saved.getSorting(), is(Sorting.LATEST_ACTIVITY.getId()));
    }

    @Test
    void changingOneValueInTheFormChangesThatValueAlone(JenkinsRule jenkins) throws Exception {
        DeliveryPipelineView view = seededFolderView(jenkins, "Ancestry/0/build_golang");
        String before = configXml(view);
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlForm form = client.getPage(view, "configure").getFormByName("viewConfig");
            form.getSelectByName("_.noOfPipelines").setSelectedAttribute("8", true);
            jenkins.submit(form);
        }
        assertThat(configXml(view),
                is(before.replace("<noOfPipelines>4</noOfPipelines>", "<noOfPipelines>8</noOfPipelines>")));
    }

    @Test
    void pickingAnotherInitialJobStoresThatJobAlone(JenkinsRule jenkins) throws Exception {
        DeliveryPipelineView view = seededFolderView(jenkins, "Ancestry/0/build_golang");
        String before = configXml(view);
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlForm form = client.getPage(view, "configure").getFormByName("viewConfig");
            form.getSelectsByName("_.firstJob").get(0).setSelectedAttribute("0/build_alpine", true);
            jenkins.submit(form);
        }
        assertThat(configXml(view), is(before.replace("<firstJob>Ancestry/0/build_golang</firstJob>",
                "<firstJob>0/build_alpine</firstJob>")));
    }

    @Test
    void aJobThatNoLongerExistsStaysInTheFormMarkedAsNotFound(JenkinsRule jenkins) throws Exception {
        DeliveryPipelineView view = seededFolderView(jenkins, "Ancestry/0/build_gone");
        String before = configXml(view);
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlForm form = client.getPage(view, "configure").getFormByName("viewConfig");
            HtmlSelect select = form.getSelectsByName("_.firstJob").get(0);
            assertThat(select.getSelectedOptions().get(0).getText(), is("Ancestry/0/build_gone (not found)"));
            assertThat(select.getSelectedOptions().get(0).getValueAttribute(), is("Ancestry/0/build_gone"));
            jenkins.submit(form);
        }
        assertThat(configXml(view), is(before));
    }

    @Test
    void theJobPickerSelectsTheStoredJobAndKeepsItsSpelling(JenkinsRule jenkins) throws Exception {
        DeliveryPipelineView view = seededFolderView(jenkins, "Ancestry/0/build_golang");
        Folder folder = (Folder) view.getOwner();
        DeliveryPipelineView.ComponentSpec.DescriptorImpl descriptor =
                new DeliveryPipelineView.ComponentSpec.DescriptorImpl();

        ListBoxModel full = descriptor.doFillFirstJobItems(view, folder, folder, "Ancestry/0/build_golang");
        ListBoxModel.Option selected = full.stream().filter(option -> option.selected).findFirst().orElseThrow();
        assertThat(selected.value, is("Ancestry/0/build_golang"));
        assertThat(selected.name, is("Ancestry » 0 » build_golang"));
        assertThat("other jobs are offered relative to the folder", full.get(0).value, is("0/build_alpine"));
        assertThat(full.stream().filter(option -> option.selected).count(), is(1L));

        ListBoxModel relative = descriptor.doFillFirstJobItems(view, folder, folder, "0/build_golang");
        assertThat(relative.stream().filter(option -> option.selected).findFirst().orElseThrow().value,
                is("0/build_golang"));

        ListBoxModel group = descriptor.doFillFirstJobItems(view, folder, folder, "Ancestry/apps");
        ListBoxModel.Option folderOption = group.stream().filter(option -> option.selected).findFirst().orElseThrow();
        assertThat(folderOption.value, is("Ancestry/apps"));
        assertThat(folderOption.name, is("Ancestry » apps (every job in it)"));

        ListBoxModel last = descriptor.doFillLastJobItems(view, folder, folder, "");
        assertThat("no final job selects the blank option", last.get(0).selected, is(true));
        ListBoxModel lastFull = descriptor.doFillLastJobItems(view, folder, folder, "Ancestry/0/build_alpine");
        assertThat(lastFull.stream().filter(option -> option.selected).findFirst().orElseThrow().value,
                is("Ancestry/0/build_alpine"));
    }
}
