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
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Configurations written by 1.x and by Job DSL keep loading. */
@WithJenkins
class LegacyConfigurationTest {

    private static final String ONE_X_VIEW = String.join("\n",
            "<se.diabol.jenkins.pipeline.DeliveryPipelineView>",
            "  <owner class=\"hudson\" reference=\"../../..\"/>",
            "  <name>Ancestry</name>",
            "  <filterExecutors>false</filterExecutors>",
            "  <filterQueue>false</filterQueue>",
            "  <properties class=\"hudson.model.View$PropertyList\"/>",
            "  <componentSpecs>",
            "    <se.diabol.jenkins.pipeline.DeliveryPipelineView_-ComponentSpec>",
            "      <name>0</name>",
            "      <firstJob>Ancestry_Automated/build</firstJob>",
            "    </se.diabol.jenkins.pipeline.DeliveryPipelineView_-ComponentSpec>",
            "  </componentSpecs>",
            "  <noOfPipelines>4</noOfPipelines>",
            "  <showAggregatedPipeline>true</showAggregatedPipeline>",
            "  <noOfColumns>1</noOfColumns>",
            "  <sorting>se.diabol.jenkins.pipeline.sort.NoOpComparator</sorting>",
            "  <showAvatars>true</showAvatars>",
            "  <updateInterval>45</updateInterval>",
            "  <showChanges>true</showChanges>",
            "  <allowManualTriggers>true</allowManualTriggers>",
            "  <showTotalBuildTime>true</showTotalBuildTime>",
            "  <allowRebuild>true</allowRebuild>",
            "  <allowPipelineStart>true</allowPipelineStart>",
            "  <showDescription>true</showDescription>",
            "  <showPromotions>false</showPromotions>",
            "  <showTestResults>false</showTestResults>",
            "  <pagingEnabled>true</pagingEnabled>",
            "  <linkRelative>true</linkRelative>",
            "  <linkToConsoleLog>true</linkToConsoleLog>",
            "  <showAggregatedChanges>true</showAggregatedChanges>",
            "  <aggregatedChangesGroupingPattern>JIRA-\\d+</aggregatedChangesGroupingPattern>",
            "  <embeddedCss>/userContent/pipeline.css</embeddedCss>",
            "  <fullScreenCss>/userContent/fullscreen.css</fullScreenCss>",
            "  <theme>default</theme>",
            "  <maxNumberOfVisiblePipelines>-1</maxNumberOfVisiblePipelines>",
            "  <regexpFirstJobs/>",
            "  <description defined-in=\"se.diabol.jenkins.pipeline.DeliveryPipelineView\">Our pipelines</description>",
            "</se.diabol.jenkins.pipeline.DeliveryPipelineView>");

    private static final String WORKFLOW_VIEW = String.join("\n",
            "<se.diabol.jenkins.workflow.WorkflowPipelineView>",
            "  <name>Flows</name>",
            "  <description>Pipeline jobs</description>",
            "  <filterExecutors>true</filterExecutors>",
            "  <filterQueue>false</filterQueue>",
            "  <properties class=\"hudson.model.View$PropertyList\"/>",
            "  <updateInterval>9</updateInterval>",
            "  <noOfPipelines>2</noOfPipelines>",
            "  <noOfColumns>2</noOfColumns>",
            "  <sorting>se.diabol.jenkins.pipeline.sort.NameComparator</sorting>",
            "  <allowPipelineStart>true</allowPipelineStart>",
            "  <allowAbort>true</allowAbort>",
            "  <showChanges>true</showChanges>",
            "  <showAbsoluteDateTime>true</showAbsoluteDateTime>",
            "  <maxNumberOfVisiblePipelines>3</maxNumberOfVisiblePipelines>",
            "  <componentSpecs>",
            "    <se.diabol.jenkins.workflow.WorkflowPipelineView_-ComponentSpec>",
            "      <name>Comp</name>",
            "      <job>wf</job>",
            "    </se.diabol.jenkins.workflow.WorkflowPipelineView_-ComponentSpec>",
            "  </componentSpecs>",
            "  <linkToConsoleLog>true</linkToConsoleLog>",
            "</se.diabol.jenkins.workflow.WorkflowPipelineView>");

    @Test
    void aViewSavedByOneXLoadsWithItsOptionsAndWithoutTheRemovedOnes(JenkinsRule jenkins) {
        Object loaded = Jenkins.XSTREAM2.fromXML(ONE_X_VIEW);
        assertThat(loaded, instanceOf(DeliveryPipelineView.class));
        DeliveryPipelineView view = (DeliveryPipelineView) loaded;
        assertThat(view.getViewName(), is("Ancestry"));
        assertThat(view.getComponentSpecs().get(0).getName(), is("0"));
        assertThat(view.getComponentSpecs().get(0).getFirstJob(), is("Ancestry_Automated/build"));
        assertThat(view.getComponentSpecs().get(0).getLastJob(), nullValue());
        assertThat(view.getNoOfPipelines(), is(4));
        assertThat(view.isShowAggregatedPipeline(), is(true));
        assertThat("the 1.x no-op sorter means none", view.getSorting(), is(Sorting.NONE.getId()));
        assertThat(view.getUpdateInterval(), is(45));
        assertThat(view.isShowChanges(), is(true));
        assertThat(view.isAllowManualTriggers(), is(true));
        assertThat(view.isShowTotalBuildTime(), is(true));
        assertThat(view.isAllowRebuild(), is(true));
        assertThat(view.isAllowPipelineStart(), is(true));
        assertThat(view.isShowDescription(), is(true));
        assertThat(view.isPagingEnabled(), is(true));
        assertThat("the description 1.x kept on the subclass becomes the view's", view.getDescription(), is("Our pipelines"));
        String saved = Jenkins.XSTREAM2.toXML(view);
        assertThat("removed options are not written back", saved.contains("showAvatars"), is(false));
        assertThat(saved.contains("embeddedCss"), is(false));
        assertThat(saved.contains("<sorting>none</sorting>"), is(true));
        assertThat(saved.contains("<description>Our pipelines</description>"), is(true));
    }

    /** The old view inside a folder, as 1.x saved it: with a reference to the folder that owns it. */
    @Test
    void aPipelineOnlyViewInsideAFolderKeepsItsOwner(JenkinsRule jenkins) throws Exception {
        com.cloudbees.hudson.plugins.folder.Folder folder =
                jenkins.getInstance().createProject(com.cloudbees.hudson.plugins.folder.Folder.class, "legacy");
        String xml = String.join("\n",
                "<com.cloudbees.hudson.plugins.folder.Folder>",
                "  <properties/>",
                "  <folderViews class=\"com.cloudbees.hudson.plugins.folder.views.DefaultFolderViewHolder\">",
                "    <views>",
                "      <hudson.model.AllView>",
                "        <owner class=\"com.cloudbees.hudson.plugins.folder.Folder\" reference=\"../../../..\"/>",
                "        <name>all</name>",
                "        <filterExecutors>false</filterExecutors>",
                "        <filterQueue>false</filterQueue>",
                "        <properties class=\"hudson.model.View$PropertyList\"/>",
                "      </hudson.model.AllView>",
                WORKFLOW_VIEW.replace("<name>Flows</name>",
                        "<owner class=\"com.cloudbees.hudson.plugins.folder.Folder\" reference=\"../../../..\"/>\n  <name>Flows</name>"),
                "    </views>",
                "    <tabBar class=\"hudson.views.DefaultViewsTabBar\"/>",
                "  </folderViews>",
                "  <healthMetrics/>",
                "</com.cloudbees.hudson.plugins.folder.Folder>");
        folder.updateByXml(new javax.xml.transform.stream.StreamSource(new java.io.StringReader(xml)));
        hudson.model.View flows = folder.getView("Flows");
        assertThat(flows, instanceOf(DeliveryPipelineView.class));
        assertThat("the migrated view knows its folder", flows.getOwner(), is(folder));
        assertThat(((DeliveryPipelineView) flows).getComponentSpecs().get(0).getFirstJob(), is("wf"));
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            String json = client.goTo("job/legacy/api/json?tree=views[name,_class]", "application/json")
                    .getWebResponse().getContentAsString();
            assertThat("the folder can list its views again", json, containsString("\"Flows\""));
        }
    }

    @Test
    void aPipelineOnlyViewOfOneXBecomesADeliveryPipelineView(JenkinsRule jenkins) {
        Object loaded = Jenkins.XSTREAM2.fromXML(WORKFLOW_VIEW);
        assertThat(loaded, instanceOf(DeliveryPipelineView.class));
        DeliveryPipelineView view = (DeliveryPipelineView) loaded;
        assertThat(view.getViewName(), is("Flows"));
        assertThat(view.getDescription(), is("Pipeline jobs"));
        assertThat(view.isFilterExecutors(), is(true));
        assertThat(view.getComponentSpecs().size(), is(1));
        assertThat(view.getComponentSpecs().get(0).getName(), is("Comp"));
        assertThat(view.getComponentSpecs().get(0).getFirstJob(), is("wf"));
        assertThat(view.getUpdateInterval(), is(9));
        assertThat(view.getNoOfPipelines(), is(2));
        assertThat(view.getNoOfColumns(), is(2));
        assertThat(view.getSorting(), is(Sorting.NAME.getId()));
        assertThat(view.isAllowPipelineStart(), is(true));
        assertThat(view.isAllowAbort(), is(true));
        assertThat(view.isShowChanges(), is(true));
        assertThat(view.isShowAbsoluteDateTime(), is(true));
        assertThat(view.getMaxNumberOfVisiblePipelines(), is(3));
    }
}
