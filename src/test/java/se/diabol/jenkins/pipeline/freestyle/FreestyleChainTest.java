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
package se.diabol.jenkins.pipeline.freestyle;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import hudson.matrix.Axis;
import hudson.matrix.AxisList;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.tasks.BuildTrigger;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import se.diabol.jenkins.pipeline.PipelineProperty;
import se.diabol.jenkins.pipeline.model.Component;
import se.diabol.jenkins.pipeline.model.Pipeline;
import se.diabol.jenkins.pipeline.model.Stage;
import se.diabol.jenkins.pipeline.model.StatusType;
import se.diabol.jenkins.pipeline.model.Task;
import se.diabol.jenkins.pipeline.model.ViewSettings;
import se.diabol.jenkins.pipeline.source.ComponentRequest;

/** The model of chained jobs, straight from the source. */
@WithJenkins
class FreestyleChainTest {

    private JenkinsRule jenkins;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        jenkins = rule;
    }

    private static ViewSettings settings(int instances, boolean aggregated) {
        return new ViewSettings(instances, 1, 5, true, aggregated, true, true, true, true, true, true, false, true,
                true, true, true);
    }

    private Component resolve(String name, FreeStyleProject first, FreeStyleProject last, boolean showUpstream,
                              ViewSettings settings, int page) throws Exception {
        return new FreestyleComponentSource().resolve(new ComponentRequest(name, 1, first, last, showUpstream,
                Jenkins.get(), settings, page, true));
    }

    private FreeStyleProject chain(String upstream, String downstream) throws Exception {
        FreeStyleProject up = jenkins.getInstance().getItemByFullName(upstream, FreeStyleProject.class);
        if (up == null) {
            up = jenkins.createFreeStyleProject(upstream);
        }
        FreeStyleProject down = jenkins.getInstance().getItemByFullName(downstream, FreeStyleProject.class);
        if (down == null) {
            down = jenkins.createFreeStyleProject(downstream);
        }
        up.getPublishersList().add(new BuildTrigger(down.getName(), Result.SUCCESS));
        jenkins.getInstance().rebuildDependencyGraph();
        return down;
    }

    @Test
    void tasksAreTheBuildsTriggeredByTheFirstBuild() throws Exception {
        FreeStyleProject build = jenkins.createFreeStyleProject("build");
        build.addProperty(new PipelineProperty("Compile", "Build", null));
        FreeStyleProject test = chain("build", "test");
        test.addProperty(new PipelineProperty(null, "Build", null));
        FreeStyleProject deploy = chain("test", "deploy");
        deploy.getBuildersList().add(new FailureBuilder());
        jenkins.buildAndAssertSuccess(build);
        jenkins.waitUntilNoActivity();
        jenkins.buildAndAssertSuccess(build);
        jenkins.waitUntilNoActivity();

        Component component = resolve("Comp", build, null, false, settings(1, true), 1);
        assertThat(component.error(), nullValue());
        assertThat(component.firstJob().fullName(), is("build"));
        assertThat(component.paging().total(), is(2));
        assertThat(component.pipelines(), hasSize(2));

        Pipeline aggregated = component.pipelines().get(0);
        assertThat(aggregated.aggregated(), is(true));
        assertThat(aggregated.stages().get(0).version(), is("#2"));
        assertThat(aggregated.stages().get(1).version(), is("#2"));

        Pipeline latest = component.pipelines().get(1);
        assertThat(latest.version(), is("#2"));
        assertThat(latest.id(), is("build#2"));
        List<Stage> stages = latest.stages();
        assertThat(stages, hasSize(2));
        assertThat(stages.get(0).name(), is("Build"));
        assertThat("jobs with the same stage name share the stage", stages.get(0).tasks(), hasSize(2));
        assertThat(stages.get(0).tasks().get(0).name(), is("Compile"));
        assertThat(stages.get(0).tasks().get(1).name(), is("test"));
        assertThat(stages.get(0).downstream(), contains("deploy"));
        assertThat(stages.get(1).column(), is(1));
        Task deployTask = stages.get(1).tasks().get(0);
        assertThat(deployTask.status().type(), is(StatusType.FAILED));
        assertThat(deployTask.buildNumber(), is(2));
        assertThat(deployTask.url(), is("job/deploy/2/"));
        assertThat(deployTask.rebuildable(), is(true));
        assertThat("the first job is never rebuildable", stages.get(0).tasks().get(0).rebuildable(), is(false));
        assertThat(latest.totalBuildTime() >= 0, is(true));
        assertThat(latest.hasFailedTask(), is(true));
        assertThat(component.hasFailure(), is(true));

        Component secondPage = resolve("Comp", build, null, false, settings(1, false), 2);
        assertThat(secondPage.pipelines().get(0).version(), is("#1"));
    }

    @Test
    void theChainStopsAtTheLastJob() throws Exception {
        FreeStyleProject build = jenkins.createFreeStyleProject("build");
        FreeStyleProject test = chain("build", "test");
        chain("test", "deploy");
        Component component = resolve("Comp", build, test, false, settings(1, false), 1);
        assertThat(component.pipelines(), hasSize(0));
        assertThat(new FreestyleComponentSource().jobsOf(build, test), hasSize(2));
    }

    @Test
    void aTaskThatNeverRanIsIdleAndADisabledJobIsDisabled() throws Exception {
        FreeStyleProject build = jenkins.createFreeStyleProject("build");
        FreeStyleProject deploy = chain("build", "deploy");
        deploy.disable();
        jenkins.buildAndAssertSuccess(build);
        jenkins.waitUntilNoActivity();
        Component component = resolve("Comp", build, null, false, settings(1, false), 1);
        Task deployTask = component.pipelines().get(0).stages().get(1).tasks().get(0);
        assertThat(deployTask.status().type(), is(StatusType.DISABLED));
        assertThat(deployTask.buildNumber(), nullValue());
        assertThat(deployTask.url(), is("job/deploy/"));
    }

    @Test
    void matrixConfigurationsTakeTheirNamesFromTheParentProject() throws Exception {
        MatrixProject project = jenkins.createProject(MatrixProject.class, "Multi");
        project.setAxes(new AxisList(new Axis("axis", "foo", "bar")));
        project.addProperty(new PipelineProperty("task", "stage", ""));
        for (MatrixConfiguration configuration : project.getActiveConfigurations()) {
            assertThat(ChainGraph.stageNameOf(configuration), is("stage"));
            assertThat(ChainGraph.taskNameOf(configuration), is("task " + configuration.getName()));
        }
    }

    @Test
    void showUpstreamStartsFromTheRootOfTheChain() throws Exception {
        FreeStyleProject root = jenkins.createFreeStyleProject("root");
        FreeStyleProject middle = chain("root", "middle");
        chain("middle", "leaf");
        jenkins.buildAndAssertSuccess(root);
        jenkins.waitUntilNoActivity();
        Component component = resolve("Comp", middle, null, true, settings(1, false), 1);
        assertThat(component.pipelines().get(0).id(), is("root#1"));
        assertThat(component.pipelines().get(0).stages(), hasSize(3));
        assertThat(FreestyleComponentSource.roots(middle).get(0).getName(), is("root"));
    }

    @Test
    void aMissingChainIsReportedNotThrown() throws Exception {
        FreeStyleProject build = jenkins.createFreeStyleProject("build");
        se.diabol.jenkins.pipeline.DeliveryPipelineView view = new se.diabol.jenkins.pipeline.DeliveryPipelineView("V");
        view.setComponentSpecs(List.of(new se.diabol.jenkins.pipeline.DeliveryPipelineView.ComponentSpec("Gone", "nope", null, false),
                new se.diabol.jenkins.pipeline.DeliveryPipelineView.ComponentSpec("Here", "build", "nope", false)));
        jenkins.getInstance().addView(view);
        List<Component> components = view.getComponents();
        assertThat(components, hasSize(2));
        assertThat(components.get(0).error(), containsString("nope"));
        assertThat(components.get(1).error(), containsString("nope"));
        assertThat(components.get(0).pipelines(), hasSize(0));
        assertThat(components.get(1).firstJob(), nullValue());
    }
}
