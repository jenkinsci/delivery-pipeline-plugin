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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.util.FormValidation;
import java.net.URL;
import java.util.List;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
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
        view.setShowConsolidatedPipeline(true);
        view.setNoOfConcurrentPipelines(5);
        view.setSleepBetweenConcurrentPipelines(42);
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
        assertThat(saved.isShowConsolidatedPipeline(), is(true));
        assertThat(saved.getNoOfConcurrentPipelines(), is(5));
        assertThat(saved.getSleepBetweenConcurrentPipelines(), is(42));
    }

    @Test
    void theConsolidatedPipelineIsOffByDefaultWithThreePipelinesAtATimeAndTenSecondsBetweenBatches(JenkinsRule jenkins)
            throws Exception {
        DeliveryPipelineView view = new DeliveryPipelineView("Defaults");
        jenkins.getInstance().addView(view);
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlForm form = client.getPage(view, "configure").getFormByName("viewConfig");
            assertThat(form.getInputByName("_.noOfConcurrentPipelines").getValue(), is("3"));
            assertThat(form.getInputByName("_.sleepBetweenConcurrentPipelines").getValue(), is("10"));
            form.getInputByName("_.showConsolidatedPipeline").setChecked(true);
            form.getInputByName("_.sleepBetweenConcurrentPipelines").setValue("0");
            jenkins.submit(form);
        }
        DeliveryPipelineView saved = (DeliveryPipelineView) jenkins.getInstance().getView("Defaults");
        assertThat(saved.isShowConsolidatedPipeline(), is(true));
        assertThat(saved.getNoOfConcurrentPipelines(), is(3));
        assertThat("no sleep at all is a setting, not a missing one", saved.getSleepBetweenConcurrentPipelines(), is(0));
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
    void typingAnotherInitialJobStoresThatJobAlone(JenkinsRule jenkins) throws Exception {
        DeliveryPipelineView view = seededFolderView(jenkins, "Ancestry/0/build_golang");
        String before = configXml(view);
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlForm form = client.getPage(view, "configure").getFormByName("viewConfig");
            form.getInputsByName("_.firstJob").get(0).setValue("0/build_alpine");
            jenkins.submit(form);
        }
        assertThat(configXml(view), is(before.replace("<firstJob>Ancestry/0/build_golang</firstJob>",
                "<firstJob>0/build_alpine</firstJob>")));
    }

    @Test
    void aJobThatNoLongerExistsStaysInTheFormAndIsReportedAsMissing(JenkinsRule jenkins) throws Exception {
        DeliveryPipelineView view = seededFolderView(jenkins, "Ancestry/0/build_gone");
        Folder folder = (Folder) view.getOwner();
        String before = configXml(view);
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlForm form = client.getPage(view, "configure").getFormByName("viewConfig");
            assertThat(form.getInputsByName("_.firstJob").get(0).getValue(), is("Ancestry/0/build_gone"));
            jenkins.submit(form);
        }
        assertThat(configXml(view), is(before));
        FormValidation missing = new DeliveryPipelineView.ComponentSpec.DescriptorImpl()
                .doCheckFirstJob(view, folder, folder, "Ancestry/0/build_gone");
        assertThat(missing.kind, is(FormValidation.Kind.ERROR));
        assertThat(missing.getMessage(), containsString("No such job: Ancestry/0/build_gone"));
        assertThat("with the nearest job there is", missing.getMessage(), containsString("Did you mean 0/build_golang?"));
    }

    /**
     * The jobs are typed, completed and checked, not picked from a list of every job of the controller: such a list,
     * once per field and component, is what made the form of a view with many components take minutes to open.
     */
    @Test
    void theJobsOfAComponentAreTextBoxesWithTheStoredSpelling(JenkinsRule jenkins) throws Exception {
        DeliveryPipelineView view = seededFolderView(jenkins, "Ancestry/0/build_golang");
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlForm form = client.getPage(view, "configure").getFormByName("viewConfig");
            assertThat(form.getSelectsByName("_.firstJob").size(), is(0));
            assertThat(form.getSelectsByName("_.lastJob").size(), is(0));
            assertThat(form.getInputsByName("_.firstJob").size(), is(3));
            assertThat(form.getInputsByName("_.firstJob").get(0).getValue(), is("Ancestry/0/build_golang"));
            assertThat(form.getInputsByName("_.firstJob").get(2).getValue(), is("0/build_golang"));
            assertThat(form.getInputsByName("_.lastJob").get(0).getValue(), is(""));
            assertThat(form.getInputsByName("_.lastJob").get(2).getValue(), is("0/build_alpine"));
            assertThat("the box completes what is typed", form.getInputsByName("_.firstJob").get(0)
                    .getAttribute("autoCompleteUrl"), containsString("autoCompleteFirstJob"));
            assertThat("and checks it", form.getInputsByName("_.lastJob").get(0).getAttribute("checkUrl"),
                    containsString("checkLastJob"));
        }
    }

    @Test
    void jobNamesAreCompletedRelativeToTheFolderAndAsFullNames(JenkinsRule jenkins) throws Exception {
        DeliveryPipelineView view = seededFolderView(jenkins, "Ancestry/0/build_golang");
        Folder folder = (Folder) view.getOwner();
        DeliveryPipelineView.ComponentSpec.DescriptorImpl descriptor =
                new DeliveryPipelineView.ComponentSpec.DescriptorImpl();

        assertThat(descriptor.doAutoCompleteFirstJob(view, folder, folder, "0/build_").getValues(),
                containsInAnyOrder("0/build_alpine", "0/build_golang"));
        assertThat("the spelling of a seed job", descriptor.doAutoCompleteFirstJob(view, folder, folder,
                "Ancestry/0/build_g").getValues(), contains("Ancestry/0/build_golang"));
        assertThat("a folder can be a component", descriptor.doAutoCompleteFirstJob(view, folder, folder, "ap")
                .getValues(), contains("apps"));
        assertThat("one level at a time", descriptor.doAutoCompleteFirstJob(view, folder, folder, "").getValues(),
                containsInAnyOrder("0", "apps", "Ancestry"));
        assertThat(descriptor.doAutoCompleteFirstJob(view, folder, folder, "/Ancestry/apps/s").getValues(),
                contains("/Ancestry/apps/svc"));
        assertThat("only chained jobs end a pipeline", descriptor.doAutoCompleteLastJob(view, folder, folder,
                "0/build_a").getValues(), contains("0/build_alpine"));
        assertThat(descriptor.doAutoCompleteLastJob(view, folder, folder, "ap").getValues(), empty());
    }

    @Test
    void jobNamesAreCheckedTheWayTheViewResolvesThem(JenkinsRule jenkins) throws Exception {
        DeliveryPipelineView view = seededFolderView(jenkins, "Ancestry/0/build_golang");
        Folder folder = (Folder) view.getOwner();
        DeliveryPipelineView.ComponentSpec.DescriptorImpl descriptor =
                new DeliveryPipelineView.ComponentSpec.DescriptorImpl();

        assertThat(descriptor.doCheckFirstJob(view, folder, folder, "Ancestry/0/build_golang").kind,
                is(FormValidation.Kind.OK));
        assertThat(descriptor.doCheckFirstJob(view, folder, folder, " 0/build_golang ").kind, is(FormValidation.Kind.OK));
        assertThat(descriptor.doCheckFirstJob(view, folder, folder, "/Ancestry/0/build_golang").kind,
                is(FormValidation.Kind.OK));
        FormValidation group = descriptor.doCheckFirstJob(view, folder, folder, "Ancestry/apps");
        assertThat(group.kind, is(FormValidation.Kind.OK));
        assertThat(group.getMessage(), containsString("One pipeline for each of the 1 jobs in it"));
        assertThat(descriptor.doCheckFirstJob(view, folder, folder, "").kind, is(FormValidation.Kind.ERROR));

        assertThat("no final job follows the chain to its ends", descriptor.doCheckLastJob(view, folder, folder, "").kind,
                is(FormValidation.Kind.OK));
        assertThat(descriptor.doCheckLastJob(view, folder, folder, "Ancestry/0/build_alpine").kind,
                is(FormValidation.Kind.OK));
        assertThat(descriptor.doCheckLastJob(view, folder, folder, "0/build_gone").kind, is(FormValidation.Kind.ERROR));
        FormValidation notAJob = descriptor.doCheckLastJob(view, folder, folder, "apps");
        assertThat(notAJob.kind, is(FormValidation.Kind.ERROR));
        assertThat(notAJob.getMessage(), containsString("Only a chained job can end a pipeline"));
    }
}
