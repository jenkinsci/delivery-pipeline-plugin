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

import au.com.centrumsystems.hudson.plugin.buildpipeline.extension.StandardBuildCard;
import au.com.centrumsystems.hudson.plugin.buildpipeline.trigger.BuildPipelineTrigger;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.cli.BuildCommand;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.plugins.parameterizedtrigger.BooleanParameterConfig;
import hudson.plugins.parameterizedtrigger.BooleanParameters;
import hudson.tasks.BuildTrigger;
import hudson.util.StreamTaskListener;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.buildnamesetter.BuildNameSetter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class PipelineVersionContributorTest {

    private static final String PIPELINE_VERSION = "PIPELINE_VERSION";

    private JenkinsRule jenkins;
    
    @BeforeEach
    void setUp(JenkinsRule rule) {
        jenkins = rule;
    }

    @Test
    void testVersionContributorNotConfigured() throws Exception {
        final FreeStyleProject firstProject = jenkins.createFreeStyleProject("firstProject");
        final FreeStyleProject secondProject = jenkins.createFreeStyleProject("secondProject");
        firstProject.getPublishersList().add(new BuildTrigger("secondProject", false));
        firstProject.save();

        firstProject.getBuildersList().add(new AssertNoPipelineVersion());
        secondProject.getBuildersList().add(new AssertNoPipelineVersion());

        jenkins.setQuietPeriod(0);
        jenkins.getInstance().rebuildDependencyGraph();
        jenkins.buildAndAssertSuccess(firstProject);
        jenkins.waitUntilNoActivity();

        assertNotNull(firstProject.getLastBuild());
        assertNotNull(secondProject.getLastBuild());
    }

    @Test
    void testVersionNoDisplayname() throws Exception {
        FreeStyleProject firstProject = jenkins.createFreeStyleProject("firstProject");
        FreeStyleProject secondProject = jenkins.createFreeStyleProject("secondProject");
        firstProject.getPublishersList().add(new BuildTrigger("secondProject", false));

        firstProject.getBuildWrappersList().add(new PipelineVersionContributor(false, "1.0.0.${BUILD_NUMBER}"));

        firstProject.getBuildersList().add(new AssertPipelineVersion("1.0.0.1"));
        secondProject.getBuildersList().add(new AssertPipelineVersion("1.0.0.1"));

        jenkins.setQuietPeriod(0);
        jenkins.getInstance().rebuildDependencyGraph();
        jenkins.buildAndAssertSuccess(firstProject);
        jenkins.waitUntilNoActivity();

        assertNotNull(firstProject.getLastBuild());
        assertNotNull(secondProject.getLastBuild());
        assertEquals("#1", firstProject.getLastBuild().getDisplayName());
    }

    @Test
    void testVersionContributorConfigured() throws Exception {
        FreeStyleProject firstProject = jenkins.createFreeStyleProject("firstProject");
        FreeStyleProject secondProject = jenkins.createFreeStyleProject("secondProject");
        firstProject.getPublishersList().add(new BuildTrigger("secondProject", false));

        firstProject.getBuildWrappersList().add(new PipelineVersionContributor(true, "1.0.0.${BUILD_NUMBER}"));

        firstProject.getBuildersList().add(new AssertPipelineVersion("1.0.0.1"));
        secondProject.getBuildersList().add(new AssertPipelineVersion("1.0.0.1"));

        jenkins.setQuietPeriod(0);
        jenkins.getInstance().rebuildDependencyGraph();
        jenkins.buildAndAssertSuccess(firstProject);
        jenkins.waitUntilNoActivity();

        assertNotNull(firstProject.getLastBuild());
        assertNotNull(secondProject.getLastBuild());
        assertEquals("1.0.0.1", firstProject.getLastBuild().getDisplayName());
    }

    @Test
    void testVersionContributorConfiguredManualTrigger() throws Exception {
        FreeStyleProject firstProject = jenkins.createFreeStyleProject("firstProject");
        FreeStyleProject secondProject = jenkins.createFreeStyleProject("secondProject");
        firstProject.getPublishersList().add(new BuildPipelineTrigger("secondProject", null));
        firstProject.save();

        firstProject.getBuildWrappersList().add(new PipelineVersionContributor(true, "1.0.0.${BUILD_NUMBER}"));

        firstProject.getBuildersList().add(new AssertPipelineVersion("1.0.0.1"));
        secondProject.getBuildersList().add(new AssertNoPipelineVersion());

        jenkins.setQuietPeriod(0);
        jenkins.getInstance().rebuildDependencyGraph();
        jenkins.buildAndAssertSuccess(firstProject);
        jenkins.waitUntilNoActivity();

        assertNotNull(firstProject.getLastBuild());
        assertNull(secondProject.getLastBuild());
        assertEquals("1.0.0.1", firstProject.getLastBuild().getDisplayName());

        secondProject.getBuildersList().clear();
        secondProject.getBuildersList().add(new AssertPipelineVersion("1.0.0.1"));

        StandardBuildCard card = new StandardBuildCard();
        card.triggerManualBuild(jenkins.jenkins, 1, "secondProject", "firstProject");
        jenkins.waitUntilNoActivity();

        assertNotNull(secondProject.getLastBuild());
    }

    @Test
    void testIsApplicable() throws Exception {
        PipelineVersionContributor.DescriptorImpl d = new PipelineVersionContributor.DescriptorImpl();
        assertTrue(d.isApplicable(jenkins.createFreeStyleProject("a")));
    }

    @Test
    @Issue("JENKINS-21070")
    void testVersionContributorErrorInPattern() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("firstProject");

        project.getBuildWrappersList().add(new PipelineVersionContributor(true, "${GFGFGFG}"));

        FreeStyleBuild build = project.scheduleBuild2(0).get();

        assertNotNull(build);

        assertEquals("#1", project.getLastBuild().getDisplayName());
        String log = FileUtils.readFileToString(build.getLogFile());
        assertTrue(log.contains("Error creating version"));
    }

    @Test
    @Issue("JENKINS-34805")
    void shouldGetPipelineVersionFromBuildAction() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("firstProject");
        FreeStyleBuild build = project.scheduleBuild2(0, new BuildCommand.CLICause(),
                                                      new ParametersAction(new StringParameterValue("HEPP", "HOPP")),
                                                      new PipelineVersionContributor.PipelineVersionAction("1.1")).get();
        assertEquals("1.1", PipelineVersionContributor.getVersion(build));
    }

    @Test
    void testGetVersionNotFound() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("firstProject");
        FreeStyleBuild build = project.scheduleBuild2(0, new BuildCommand.CLICause(), new ParametersAction(new StringParameterValue("HEPP", "HOPP"))).get();
        assertNull(PipelineVersionContributor.getVersion(build));
    }

    @Test
    @Issue({"JENKINS-28848", "JENKINS-38062", "JENKINS-59651"})
    void testWithBuildNameSetterPluginAndAdditionalParameters() throws Exception {
        try {
            System.setProperty(ParametersAction.SAFE_PARAMETERS_SYSTEM_PROPERTY_NAME, PIPELINE_VERSION);

            FreeStyleProject firstJob = jenkins.createFreeStyleProject("a");
            FreeStyleProject secondJob = jenkins.createFreeStyleProject("b");

            firstJob.addProperty(new ParametersDefinitionProperty(
                    new StringParameterDefinition("BUILD_VERSION", "DEFAULT_VALUE")));
            firstJob.getPublishersList().add(new BuildTrigger("b", false));
            firstJob.getBuildWrappersList().add(
                    new PipelineVersionContributor(true, "1.0.0.$BUILD_NUMBER"));
            secondJob.getBuildWrappersList().add(new BuildNameSetter("$PIPELINE_VERSION", true, false));


            jenkins.getInstance().rebuildDependencyGraph();
            jenkins.setQuietPeriod(0);

            jenkins.buildAndAssertSuccess(firstJob);
            jenkins.waitUntilNoActivity();

            assertEquals("1.0.0.1", firstJob.getLastBuild().getDisplayName());
            assertEquals("1.0.0.1", secondJob.getLastBuild().getDisplayName());
            assertEquals("1.0.0.1", firstJob.getLastBuild().getBuildVariableResolver().resolve(PIPELINE_VERSION));
            assertEquals("1.0.0.1", secondJob.getLastBuild().getBuildVariableResolver().resolve(PIPELINE_VERSION));
        } finally {
            System.clearProperty(ParametersAction.SAFE_PARAMETERS_SYSTEM_PROPERTY_NAME);
        }
    }

    @Test
    void testVersionContributorIsNotBreakingParametersPassing() throws Exception {
        try {
            System.setProperty(ParametersAction.SAFE_PARAMETERS_SYSTEM_PROPERTY_NAME, PIPELINE_VERSION +"," + "test");

            FreeStyleProject firstProject = jenkins.createFreeStyleProject("firstProject");
            FreeStyleProject secondProject = jenkins.createFreeStyleProject("secondProject");
            firstProject.getPublishersList().add(
                    new BuildPipelineTrigger("secondProject",
                            Collections.singletonList(new BooleanParameters(
                                    Collections.singletonList(new BooleanParameterConfig("test", true))))));
            firstProject.save();

            firstProject.getBuildWrappersList().add(new PipelineVersionContributor(true, "1.0.0.${BUILD_NUMBER}"));

            firstProject.getBuildersList().add(new AssertPipelineVersion("1.0.0.1"));
            secondProject.getBuildersList().add(new AssertNoPipelineVersion());

            jenkins.setQuietPeriod(0);
            jenkins.getInstance().rebuildDependencyGraph();
            jenkins.buildAndAssertSuccess(firstProject);
            jenkins.waitUntilNoActivity();

            assertNotNull(firstProject.getLastBuild());
            assertNull(secondProject.getLastBuild());
            assertEquals("1.0.0.1", firstProject.getLastBuild().getDisplayName());

            secondProject.getBuildersList().clear();
            secondProject.getBuildersList().add(new AssertPipelineVersion("1.0.0.1"));

            StandardBuildCard card = new StandardBuildCard();

            card.triggerManualBuild(jenkins.jenkins, 1, "secondProject", "firstProject");
            jenkins.waitUntilNoActivity();

            assertNotNull(secondProject.getLastBuild());
            assertEquals("true", secondProject.getLastBuild().getBuildVariableResolver().resolve("test"));
        } finally {
            System.clearProperty(ParametersAction.SAFE_PARAMETERS_SYSTEM_PROPERTY_NAME);
        }
    }

    private class AssertNoPipelineVersion extends TestBuilder {
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
                               BuildListener listener) throws InterruptedException, IOException {
            EnvVars env = build.getEnvironment(new StreamTaskListener(System.out, null));
            assertFalse(env.containsKey(PIPELINE_VERSION));
            return true;
        }
    }

    private class AssertPipelineVersion extends TestBuilder {
        private String version;

        private AssertPipelineVersion(String version) {
            this.version = version;
        }

        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
                               BuildListener listener) throws InterruptedException, IOException {
            EnvVars env = build.getEnvironment(new StreamTaskListener(System.out, null));
            PipelineVersionContributor.PipelineVersionAction versionAction =
                    build.getAction(PipelineVersionContributor.PipelineVersionAction.class);
            assertNotNull(versionAction);
            assertEquals(version, versionAction.getVersion());
            assertEquals(version, build.getBuildVariableResolver().resolve(PIPELINE_VERSION));
            return true;
        }
    }
}
