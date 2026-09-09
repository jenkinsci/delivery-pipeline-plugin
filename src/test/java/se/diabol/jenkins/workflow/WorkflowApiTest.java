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

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import se.diabol.jenkins.workflow.api.Run;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

@WithJenkins
class WorkflowApiTest {

    @Test
    void finishedRunsAreReadFromTheFlowGraphOnce(JenkinsRule jenkins) throws Exception {
        WorkflowJob job = jenkins.getInstance().createProject(WorkflowJob.class, "wf");
        job.setDefinition(new CpsFlowDefinition("node { stage('Build') { echo 'b' }; stage('Test') { echo 't' } }", true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);

        WorkflowApi api = new WorkflowApi();
        Run first = api.runFor(run);
        assertThat(first.id, is("1"));
        assertThat(first.status, is("SUCCESS"));
        assertThat(first.stages.size(), is(2));
        assertThat(first.stages.get(0).name, is("Build"));
        assertThat(first.stages.get(1).name, is("Test"));
        assertThat(first.stages.get(1).status, is("SUCCESS"));
        assertThat("a finished run is analysed once and then served from the cache", api.runFor(run), sameInstance(first));
        assertThat(api.lastFinishedRunFor(job).id, is("1"));
    }
}
