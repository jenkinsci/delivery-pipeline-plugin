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
package se.diabol.jenkins.pipeline.trigger;

import au.com.centrumsystems.hudson.plugin.buildpipeline.trigger.BuildPipelineTrigger;
import hudson.model.FreeStyleProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.WithoutJenkins;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import se.diabol.jenkins.pipeline.test.TestUtil;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@WithJenkins
class ManualTriggerFactoryTest {

    private JenkinsRule jenkins;
    
    @BeforeEach
    void setUp(JenkinsRule rule) {
        jenkins = rule;
    }

    @Test
    @WithoutJenkins
    void testValidUtilClass() throws Exception {
        TestUtil.assertUtilityClassWellDefined(ManualTriggerFactory.class);
    }

    @Test
    void testNotFound() throws Exception {
        FreeStyleProject projectA = jenkins.createFreeStyleProject("a");
        FreeStyleProject projectB = jenkins.createFreeStyleProject("b");
        assertNull(ManualTriggerFactory.getManualTrigger(projectB, projectA));
    }

    @Test
    void testFound() throws Exception {
        FreeStyleProject projectA = jenkins.createFreeStyleProject("a");
        FreeStyleProject projectB = jenkins.createFreeStyleProject("b");
        projectA.getPublishersList().add(new BuildPipelineTrigger("b", null));
        jenkins.getInstance().rebuildDependencyGraph();
        assertNotNull(ManualTriggerFactory.getManualTrigger(projectB, projectA));
    }
}
