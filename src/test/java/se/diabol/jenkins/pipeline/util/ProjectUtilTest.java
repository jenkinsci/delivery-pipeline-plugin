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
package se.diabol.jenkins.pipeline.util;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.EnvVars;
import hudson.model.AbstractProject;
import hudson.model.FreeStyleProject;
import hudson.tasks.BuildTrigger;
import hudson.util.ListBoxModel;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.WithoutJenkins;
import se.diabol.jenkins.pipeline.test.TestUtil;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProjectUtilTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @WithoutJenkins
    @Test
    public void testValidUtilClass() throws Exception {
        TestUtil.assertUtilityClassWellDefined(ProjectUtil.class);
    }

    @Test
    public void testFillAllProjects() throws Exception {
        FreeStyleProject build1 = jenkins.createFreeStyleProject("build1");
        FreeStyleProject build2 = jenkins.createFreeStyleProject("build2");
        ListBoxModel list = ProjectUtil.fillAllProjects(jenkins.getInstance(), AbstractProject.class);
        assertEquals(2, list.size());
        ListBoxModel.Option option1 = list.get(0);
        assertEquals(build1.getDisplayName(), option1.name);


        ListBoxModel.Option option2 = list.get(1);
        assertEquals(build2.getDisplayName(), option2.name);
    }

    @Test
    public void testGetProjects() throws Exception {
        jenkins.createFreeStyleProject("build-comp1project");
        jenkins.createFreeStyleProject("build-comp1-project");
        jenkins.createFreeStyleProject("build-comp2-project");
        jenkins.createFreeStyleProject("build-comp3-project");
        Map<String, AbstractProject> result = ProjectUtil.getProjects("^build-(.+?)-project");
        assertEquals(3, result.size());
        assertTrue(result.containsKey("comp1"));
        assertTrue(result.containsKey("comp2"));
        assertTrue(result.containsKey("comp3"));

        Map<String, AbstractProject> result2 = ProjectUtil.getProjects("^build-.+?-project");
        assertEquals(0, result2.size());
        Map<String, AbstractProject> result3 = ProjectUtil.getProjects("*");
        assertEquals(0, result3.size());
    }

    @Test
    public void testGetProjectsInFolders() throws Exception {
        Folder folder1 = jenkins.jenkins.createProject(Folder.class, "folder1");
        Folder folder2 = jenkins.jenkins.createProject(Folder.class, "folder2");

        folder1.createProject(FreeStyleProject.class, "project");
        folder1.createProject(FreeStyleProject.class, "otherProject");
        folder2.createProject(FreeStyleProject.class, "project");
        folder2.createProject(FreeStyleProject.class, "otherProject");

        Map<String, AbstractProject> result = ProjectUtil.getProjects("^(project)");
        assertEquals(0, result.size());

        Map<String, AbstractProject> result2 = ProjectUtil.getProjects("^(.+)/project");
        assertEquals(2, result2.size());
    }

    @Test
    public void testGetProjectList() throws Exception {
        jenkins.createFreeStyleProject("p1");
        jenkins.createFreeStyleProject("p2");

        List<AbstractProject> projects = ProjectUtil.getProjectList("p1,p2", jenkins.getInstance(), null);
        assertEquals(2, projects.size());

        projects = ProjectUtil.getProjectList("p1,p2,p3", jenkins.getInstance(), null);
        assertEquals(2, projects.size());

        projects = ProjectUtil.getProjectList("p1,p2,p3", jenkins.getInstance(), new EnvVars());
        assertEquals(2, projects.size());

        projects = ProjectUtil.getProjectList(",,", jenkins.getInstance(), new EnvVars());
        assertEquals(0, projects.size());
    }

    @Test
    public void testRecursiveProjects() throws Exception {
        FreeStyleProject projectA = jenkins.createFreeStyleProject("projectA");
        FreeStyleProject projectB = jenkins.createFreeStyleProject("projectB");
        projectA.getPublishersList().add(new BuildTrigger(projectB.getName(), true));
        projectB.getPublishersList().add(new BuildTrigger(projectA.getName(), true));

        jenkins.getInstance().rebuildDependencyGraph();

        assertEquals(projectA.getUpstreamProjects().get(0), projectB);
        assertEquals(projectB.getUpstreamProjects().get(0), projectA);

        // If there is a cycle dependency, then a stack overflow will be thrown here.
        ProjectUtil.getAllDownstreamProjects(projectA, null);
    }

    @Test
    public void testGetAllDownstreamProjects() {
        Map<String, AbstractProject<?, ?>> result = ProjectUtil.getAllDownstreamProjects(null, null);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetStartUpstreamsSimple() throws Exception {
        FreeStyleProject projectA = jenkins.createFreeStyleProject("A");
        FreeStyleProject projectB = jenkins.createFreeStyleProject("B");
        FreeStyleProject projectC = jenkins.createFreeStyleProject("C");
        projectA.getPublishersList().add(new BuildTrigger(projectC.getName(), true));
        projectB.getPublishersList().add(new BuildTrigger(projectC.getName(), true));

        jenkins.getInstance().rebuildDependencyGraph();

        List<AbstractProject> upstrems = ProjectUtil.getStartUpstreams(projectC);
        assertEquals(2, upstrems.size());
    }

    @Test
    @WithoutJenkins
    public void getProjectsShouldReturnEmptyMapForEmptyRegExp() {
        assertTrue(ProjectUtil.getProjects(" ").isEmpty());
        assertTrue(ProjectUtil.getProjects("   ").isEmpty());
        assertTrue(ProjectUtil.getProjects("\r\n").isEmpty());
        assertTrue(ProjectUtil.getProjects(" \t\r\n ").isEmpty());
    }
}
