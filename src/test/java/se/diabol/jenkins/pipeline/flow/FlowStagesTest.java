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
package se.diabol.jenkins.pipeline.flow;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class FlowStagesTest {

    @Test
    void aStageNestedInAParallelBranchIsAddressedByTheBranchOnTheConsole(JenkinsRule jenkins) throws Exception {
        WorkflowJob flow = jenkins.getInstance().createProject(WorkflowJob.class, "nested");
        flow.setDefinition(new CpsFlowDefinition(String.join("\n",
                "pipeline {",
                "  agent any",
                "  stages {",
                "    stage('Build') { steps { echo 'b' } }",
                "    stage('Test') {",
                "      parallel {",
                "        stage('Unit') { steps { echo 'u' } }",
                "        stage('Integration') { steps { echo 'i' } }",
                "      }",
                "    }",
                "  }",
                "}"), true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(flow);
        FlowNode build = null;
        FlowNode unit = null;
        FlowNode unitBranch = null;
        for (FlowNode node : FlowGraph.allNodes(run.getExecution())) {
            LabelAction label = node.getAction(LabelAction.class);
            ThreadNameAction branch = node.getAction(ThreadNameAction.class);
            if (FlowGraph.isStage(node) && "Build".equals(node.getDisplayName())) {
                build = node;
            }
            if (FlowGraph.isStage(node) && label != null && "Unit".equals(label.getDisplayName())) {
                unit = node;
            }
            if (branch != null && "Unit".equals(branch.getThreadName())) {
                unitBranch = node;
            }
        }
        assertThat(FlowStages.consoleNodeOf(build), is(build.getId()));
        assertThat("the nested stage is not the node the plugin selects", unit.getId(), not(is(unitBranch.getId())));
        assertThat(FlowStages.consoleNodeOf(unit), is(unitBranch.getId()));
        assertThat("a branch itself keeps its node", FlowStages.consoleNodeOf(unitBranch), is(unitBranch.getId()));
    }
}
