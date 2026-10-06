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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.fail;
import static se.diabol.jenkins.pipeline.PageTestSupport.body;
import static se.diabol.jenkins.pipeline.PageTestSupport.jsClient;
import static se.diabol.jenkins.pipeline.PageTestSupport.render;
import static se.diabol.jenkins.pipeline.PageTestSupport.staticClient;
import static se.diabol.jenkins.pipeline.PageTestSupport.texts;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.View;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import se.diabol.jenkins.pipeline.model.Component;
import se.diabol.jenkins.pipeline.model.Consolidated;
import se.diabol.jenkins.pipeline.model.Stage;
import se.diabol.jenkins.pipeline.model.StatusType;
import se.diabol.jenkins.pipeline.model.Task;

/** The consolidated pipeline as a view shows it: its component, the actions, who may use them, and the page. */
@WithJenkins
class ConsolidatedPipelineViewTest {

    private static final String SETTLE_SECONDS = "se.diabol.jenkins.pipeline.consolidated.ConsolidatedRuns.settleSeconds";

    private JenkinsRule jenkins;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        jenkins = rule;
        System.setProperty(SETTLE_SECONDS, "0");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SETTLE_SECONDS);
    }

    private DeliveryPipelineView view(String name, int concurrent, int sleep, String... jobs) throws Exception {
        List<DeliveryPipelineView.ComponentSpec> specs = new ArrayList<>();
        for (String job : jobs) {
            if (jenkins.getInstance().getItem(job) == null) {
                jenkins.createFreeStyleProject(job);
            }
            specs.add(new DeliveryPipelineView.ComponentSpec("Pipeline " + job, job, null, false));
        }
        DeliveryPipelineView view = new DeliveryPipelineView(name);
        view.setComponentSpecs(specs);
        view.setNoOfPipelines(1);
        view.setShowConsolidatedPipeline(true);
        view.setAllowPipelineStart(true);
        view.setNoOfConcurrentPipelines(concurrent);
        view.setSleepBetweenConcurrentPipelines(sleep);
        jenkins.getInstance().addView(view);
        return view;
    }

    private static Component consolidatedOf(DeliveryPipelineView view) {
        Component first = view.getComponents().get(0);
        assertThat("the consolidated pipeline comes first", first.consolidated(), notNullValue());
        return first;
    }

    /** Waits for the clock to move the view's run on. */
    private static Consolidated await(DeliveryPipelineView view, String what, Predicate<Consolidated> condition)
            throws Exception {
        long deadline = System.currentTimeMillis() + 90_000;
        while (true) {
            Consolidated consolidated = consolidatedOf(view).consolidated();
            if (condition.test(consolidated)) {
                return consolidated;
            }
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting until " + what + "; the run is " + consolidated);
            }
            Thread.sleep(250);
        }
    }

    private static List<String> taskNames(Stage stage) {
        List<String> names = new ArrayList<>();
        for (Task task : stage.tasks()) {
            names.add(task.name());
        }
        return names;
    }

    @Test
    void theConsolidatedPipelineComesFirstWithAStagePerBatchAndATaskPerPipeline() throws Exception {
        DeliveryPipelineView view = view("All", 2, 10, "a", "b", "c", "d", "e", "f", "g", "h", "i");
        List<Component> components = view.getComponents();
        assertThat(components, hasSize(10));
        Component component = components.get(0);
        assertThat(component.name(), is("Consolidated pipeline"));
        assertThat(component.index(), is(0));
        assertThat("the components keep their positions for paging", components.get(1).index(), is(1));
        assertThat(component.firstJob(), nullValue());

        Consolidated consolidated = component.consolidated();
        assertThat(consolidated.state(), is(Consolidated.IDLE));
        assertThat(consolidated.active(), is(false));
        assertThat(consolidated.permitted(), is(true));
        assertThat(consolidated.total(), is(9));
        assertThat(consolidated.batches(), is(5));
        assertThat(consolidated.concurrentPipelines(), is(2));
        assertThat(consolidated.sleepSeconds(), is(10));
        assertThat(consolidated.jobs(), contains("a", "b", "c", "d", "e", "f", "g", "h", "i"));

        List<Stage> stages = component.pipelines().get(0).stages();
        assertThat(stages, hasSize(5));
        assertThat(stages.get(0).name(), is("Batch 1"));
        assertThat(taskNames(stages.get(0)), contains("Pipeline a", "Pipeline b"));
        assertThat(taskNames(stages.get(4)), contains("Pipeline i"));
        assertThat(stages.get(0).downstream(), contains("batch-2"));
        assertThat(stages.get(4).downstream(), empty());
        assertThat("four batches to a row", stages.get(3).row() + "/" + stages.get(3).column(), is("0/3"));
        assertThat("then on to the next row", stages.get(4).row() + "/" + stages.get(4).column(), is("1/0"));
        Task task = stages.get(0).tasks().get(0);
        assertThat(task.status().type(), is(StatusType.IDLE));
        assertThat(task.jobFullName(), is("a"));
        assertThat(task.url(), is("job/a/"));

        view.setShowConsolidatedPipeline(false);
        assertThat(view.getComponents(), hasSize(9));
        assertThat(view.getComponents().get(0).consolidated(), nullValue());
    }

    @Test
    void aRunGoesThroughTheBatchesAndTheViewShowsItsOutcomeAfterwards() throws Exception {
        DeliveryPipelineView view = view("All", 2, 0, "a", "b", "c");
        view.doStartConsolidated();
        Consolidated running = consolidatedOf(view).consolidated();
        assertThat(running.state(), is(Consolidated.RUNNING));
        assertThat(running.number(), is(1));
        assertThat(running.batch(), is(1));
        assertThat("a test calls the action as the system", running.startedBy(), is("SYSTEM"));

        Consolidated finished = await(view, "the run has finished", c -> Consolidated.FINISHED.equals(c.state()));
        assertThat(finished.finished(), is(3));
        assertThat(finished.failed(), is(0));
        assertThat(finished.batch(), is(2));
        assertThat(finished.finishedAt(), notNullValue());
        Component component = consolidatedOf(view);
        Task last = component.pipelines().get(0).stages().get(1).tasks().get(0);
        assertThat(last.name(), is("Pipeline c"));
        assertThat(last.status().type(), is(StatusType.SUCCESS));
        assertThat(last.buildNumber(), is(1));
        assertThat("a task leads to the build that started its pipeline", last.url(), is("job/c/1/"));
        assertThat(component.pipelines().get(0).version(), is("#1"));
        assertThat("the pipeline itself says what ran it", view.getComponents().get(3).pipelines().get(0).triggers()
                .get(0).description(), is("run #1 of the consolidated pipeline"));

        // what a run started now would do, each pipeline with the outcome it had
        jenkins.createFreeStyleProject("d");
        List<DeliveryPipelineView.ComponentSpec> specs = new ArrayList<>(view.getComponentSpecs());
        specs.add(0, new DeliveryPipelineView.ComponentSpec("Pipeline d", "d", null, false));
        view.setComponentSpecs(specs);
        List<Stage> stages = consolidatedOf(view).pipelines().get(0).stages();
        assertThat(taskNames(stages.get(0)), contains("Pipeline d", "Pipeline a"));
        assertThat(stages.get(0).tasks().get(0).status().type(), is(StatusType.IDLE));
        assertThat(stages.get(0).tasks().get(1).status().type(), is(StatusType.SUCCESS));
        assertThat(consolidatedOf(view).consolidated().jobs(), contains("d", "a", "b", "c"));
    }

    @Test
    void theExpectedLengthOfARunGoesByTheLastInstanceOfEachPipelineUntilThereWasARun() throws Exception {
        DeliveryPipelineView view = view("All", 1, 10, "a", "b");
        assertThat("nothing ran yet", consolidatedOf(view).consolidated().estimatedDuration(), nullValue());

        FreeStyleProject a = jenkins.getInstance().getItemByFullName("a", FreeStyleProject.class);
        a.getBuildersList().add(new SleepBuilder(300));
        jenkins.buildAndAssertSuccess(a);
        jenkins.waitUntilNoActivity();
        Consolidated idle = consolidatedOf(view).consolidated();
        assertThat("a took a while, b counts as the average, and a sleep lies between them",
                idle.estimatedDuration() >= 2 * 300L + 10_000L, is(true));
        assertThat(idle.estimatedEnd(), nullValue());

        view.doStartConsolidated();
        Consolidated running = consolidatedOf(view).consolidated();
        assertThat(running.estimatedEnd(), notNullValue());
        assertThat(running.estimatedEnd() > System.currentTimeMillis(), is(true));
        view.doStopConsolidated();
        await(view, "the run has stopped", c -> Consolidated.STOPPED.equals(c.state()));
        assertThat(consolidatedOf(view).consolidated().estimatedEnd(), nullValue());
    }

    @Test
    void theActionsNeedTheViewToAllowThem() throws Exception {
        DeliveryPipelineView view = view("All", 2, 0, "a");
        view.setAllowPipelineStart(false);
        try (JenkinsRule.WebClient client = staticClient(jenkins)) {
            assertThat(post(client, view, "startConsolidated"), is(403));
            assertThat(post(client, view, "stopConsolidated"), is(403));
            view.setAllowPipelineStart(true);
            view.setShowConsolidatedPipeline(false);
            assertThat(post(client, view, "startConsolidated"), is(403));
            view.setShowConsolidatedPipeline(true);
            assertThat("nothing to stop", post(client, view, "stopConsolidated"), is(409));
            assertThat("a GET starts nothing", client.getPage(new URL(jenkins.getURL(),
                    view.getViewUrl() + "startConsolidated")).getWebResponse().getStatusCode(), is(405));
            assertThat(jenkins.getInstance().getItemByFullName("a", FreeStyleProject.class).getLastBuild(), nullValue());
            assertThat(post(client, view, "startConsolidated"), is(200));
        }
        await(view, "the run has finished", c -> Consolidated.FINISHED.equals(c.state()));
    }

    @Test
    void runningThePipelinesNeedsThePermissionToBuildEveryOne() throws Exception {
        DeliveryPipelineView view = view("All", 2, 3600, "a", "b", "c");
        jenkins.getInstance().setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.getInstance().setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, View.READ, Item.READ).everywhere().to("viewer", "partial", "builder")
                .grant(Item.BUILD).onItems(jenkins.getInstance().getItem("a")).to("partial")
                .grant(Item.BUILD).everywhere().to("builder"));
        try (JenkinsRule.WebClient client = staticClient(jenkins).login("partial")) {
            assertThat(body(jenkins, client, view.getViewUrl() + "api/json"), containsString("\"permitted\":false"));
            assertThat(post(client, view, "startConsolidated"), is(403));
        }
        try (JenkinsRule.WebClient client = staticClient(jenkins).login("builder")) {
            assertThat(body(jenkins, client, view.getViewUrl() + "api/json"), containsString("\"permitted\":true"));
            assertThat(post(client, view, "startConsolidated"), is(200));
            assertThat("one run at a time", post(client, view, "startConsolidated"), is(409));
            String json = body(jenkins, client, view.getViewUrl() + "api/json");
            assertThat(json, containsString("\"startedBy\":\"builder\""));
            assertThat(json, containsString("\"active\":true"));
        }
        try (JenkinsRule.WebClient client = staticClient(jenkins).login("viewer")) {
            assertThat(post(client, view, "stopConsolidated"), is(403));
        }
        try (JenkinsRule.WebClient client = staticClient(jenkins).login("builder")) {
            assertThat(post(client, view, "stopConsolidated"), is(200));
        }
        await(view, "the run has stopped", c -> Consolidated.STOPPED.equals(c.state()));
        assertThat(consolidatedOf(view).consolidated().stoppedBy(), is("builder"));
    }

    private int post(JenkinsRule.WebClient client, DeliveryPipelineView view, String action) throws Exception {
        WebRequest request = new WebRequest(new URL(jenkins.getURL(), view.getViewUrl() + action), HttpMethod.POST);
        return client.getPage(client.addCrumb(request)).getWebResponse().getStatusCode();
    }

    @Test
    void thePageShowsTheConsolidatedPipelineAboveTheComponentsAndRunsItFromItsButton() throws Exception {
        DeliveryPipelineView view = view("All", 2, 0, "a", "b", "c");
        view.setNoOfColumns(2);
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            DomElement section = page.querySelector(".dpp-consolidated section.pipeline-consolidated");
            assertThat(section, notNullValue());
            assertThat(section.getAttribute("class"), containsString("consolidated-IDLE"));
            assertThat(page.querySelector(".pipeline-consolidated .pipeline-title").asNormalizedText(),
                    containsString("Consolidated pipeline"));
            assertThat(page.querySelector(".consolidated-summary").asNormalizedText(),
                    is("Runs the 3 pipelines of this view, 2 at a time, and sleeps 0 seconds between two batches."));
            assertThat(texts(page, ".pipeline-consolidated .stage-name"), contains("Batch 1", "Batch 2"));
            assertThat(texts(page, ".pipeline-consolidated .stage-task .taskname"),
                    contains("Pipeline a", "Pipeline b", "Pipeline c"));
            assertThat("the components share the columns without it", page.querySelectorAll(".dpp-column").size(), is(2));
            assertThat(page.querySelectorAll(".dpp-columns .pipeline-component").size(), is(3));
            assertThat("the first component keeps the first start button", page.querySelector("#startpipeline-0"),
                    notNullValue());
            assertThat("a task of the consolidated pipeline has no actions of its own",
                    page.querySelectorAll(".pipeline-consolidated .task-actions").size(), is(0));

            HtmlElement start = page.querySelector("button.consolidated-start");
            assertThat(start, notNullValue());
            assertThat(start.getAttribute("title"), is("Run the 3 pipelines, 2 at a time"));
            start.click();
            await(view, "the run has started", c -> c.number() == 1);
        }
        await(view, "the run has finished", c -> Consolidated.FINISHED.equals(c.state()));
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.querySelector(".consolidated-summary").asNormalizedText(), containsString("Run #1 finished"));
            assertThat(page.querySelector(".consolidated-summary").asNormalizedText(),
                    containsString("3 pipelines, none failed"));
            assertThat(page.querySelectorAll(".pipeline-consolidated .stage-task.SUCCESS").size(), is(3));
            DomElement link = page.querySelector(".pipeline-consolidated .stage-task .taskname a");
            assertThat(link.getAttribute("href"), containsString("job/a/1/"));
            assertThat(page.querySelector("button.consolidated-start"), notNullValue());
            assertThat(page.querySelector("button.consolidated-stop"), nullValue());
            DomElement eta = page.querySelector(".consolidated-eta");
            assertThat("no run is going, so none is expected to end", eta.hasAttribute("hidden"), is(true));
        }
    }

    @Test
    void aRunIsStoppedFromThePageAndStoppedAgainToEndItWithoutWaiting() throws Exception {
        DeliveryPipelineView view = view("All", 1, 0, "a", "b");
        FreeStyleProject a = jenkins.getInstance().getItemByFullName("a", FreeStyleProject.class);
        a.getBuildersList().add(new SleepBuilder(300));
        jenkins.buildAndAssertSuccess(a);  // so that there is something to expect the end of the run by
        jenkins.waitUntilNoActivity();
        a.setAssignedLabel(Label.get("nowhere"));  // its next build waits in the queue for an agent that never comes
        view.doStartConsolidated();
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.querySelector("button.consolidated-start"), nullValue());
            assertThat(page.querySelector(".consolidated-summary").asNormalizedText(),
                    containsString("Run #1: batch 1 of 2 is running, 0 of 2 pipelines finished"));
            DomElement eta = page.querySelector(".consolidated-eta");
            assertThat(eta.hasAttribute("hidden"), is(false));
            assertThat(eta.asNormalizedText(), containsString("Expected to finish"));
            HtmlElement stop = page.querySelector("button.consolidated-stop");
            assertThat(stop, notNullValue());
            stop.click();
            await(view, "the run is stopping", c -> Consolidated.STOPPING.equals(c.state()));
        }
        try (JenkinsRule.WebClient client = jsClient(jenkins)) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.querySelector(".consolidated-summary").asNormalizedText(),
                    containsString("waiting for the pipelines of batch 1 to finish"));
            HtmlElement abandon = page.querySelector("button.consolidated-abandon");
            assertThat(abandon, notNullValue());
            abandon.click();
            await(view, "the run has ended", c -> Consolidated.STOPPED.equals(c.state()));
        }
        List<Stage> stages = consolidatedOf(view).pipelines().get(0).stages();
        assertThat(taskNames(stages.get(0)), contains("Pipeline a (not waited for)"));
        assertThat(stages.get(0).tasks().get(0).status().type(), is(StatusType.CANCELLED));
        assertThat(taskNames(stages.get(1)), contains("Pipeline b (not started)"));
        assertThat(stages.get(1).tasks().get(0).status().type(), is(StatusType.NOT_BUILT));
        jenkins.getInstance().getQueue().cancel(a);
    }

    @Test
    void aViewerWhoMayNotBuildGetsNoButton() throws Exception {
        DeliveryPipelineView view = view("All", 2, 0, "a");
        jenkins.getInstance().setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.getInstance().setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, View.READ, Item.READ).everywhere().to("viewer"));
        try (JenkinsRule.WebClient client = jsClient(jenkins).login("viewer")) {
            HtmlPage page = render(jenkins, client, view.getViewUrl());
            assertThat(page.querySelector("section.pipeline-consolidated"), notNullValue());
            assertThat(page.querySelector("button.consolidated-start"), nullValue());
            assertThat(page.asNormalizedText(), not(containsString("Error")));
        }
    }
}
