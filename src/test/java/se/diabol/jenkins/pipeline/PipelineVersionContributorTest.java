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
import static org.hamcrest.Matchers.is;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.tasks.BuildTrigger;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PipelineVersionContributorTest {

    @Test
    void theVersionOfTheFirstBuildTravelsDownTheChain(JenkinsRule jenkins) throws Exception {
        FreeStyleProject build = jenkins.createFreeStyleProject("build");
        FreeStyleProject deploy = jenkins.createFreeStyleProject("deploy");
        build.getBuildWrappersList().add(new PipelineVersionContributor(true, "1.0.${BUILD_NUMBER}"));
        build.getPublishersList().add(new BuildTrigger(deploy.getName(), Result.SUCCESS));
        jenkins.getInstance().rebuildDependencyGraph();
        FreeStyleBuild first = jenkins.buildAndAssertSuccess(build);
        jenkins.waitUntilNoActivity();
        assertThat(first.getDisplayName(), is("1.0.1"));
        assertThat(PipelineVersionContributor.getVersion(first), is("1.0.1"));
        FreeStyleBuild downstream = deploy.getLastBuild();
        assertThat(PipelineVersionContributor.getVersion(downstream), is("1.0.1"));
        assertThat(TokenMacro.expandAll(downstream, TaskListener.NULL, "${PIPELINE_VERSION}"), is("1.0.1"));
        assertThat(downstream.getBuildVariables().get(PipelineVersionContributor.VERSION_PARAMETER), is("1.0.1"));
    }
}
