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
package se.diabol.jenkins.pipeline.domain;

import hudson.MarkupText;
import hudson.model.AbstractBuild;
import hudson.model.FreeStyleProject;
import hudson.scm.ChangeLogAnnotator;
import hudson.scm.ChangeLogSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FakeChangeLogSCM;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import se.diabol.jenkins.pipeline.test.FakeRepositoryBrowserSCM;
import se.diabol.jenkins.pipeline.test.MeanFakeRepositoryBrowserSCM;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class ChangeTest {

    private JenkinsRule jenkins;

    private static Logger log = Logger.getLogger(Change.class.getName()); // matches the logger in the affected class
    private static OutputStream logCapturingStream;
    private static StreamHandler customLogHandler;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        jenkins = rule;
        logCapturingStream = new ByteArrayOutputStream();
        Handler[] handlers = log.getParent().getHandlers();
        customLogHandler = new StreamHandler(logCapturingStream, handlers[0].getFormatter());
        log.addHandler(customLogHandler);
    }

    public String getTestCapturedLog() {
        customLogHandler.flush();
        return logCapturingStream.toString();
    }

    @Test
    void testGetChangesNoBrowser() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("build");
        FakeChangeLogSCM scm = new FakeChangeLogSCM();
        scm.addChange().withAuthor("test-user").withMsg("Fixed bug");
        project.setScm(scm);
        jenkins.setQuietPeriod(0);
        jenkins.buildAndAssertSuccess(project);
        AbstractBuild build = project.getLastBuild();
        List<Change> changes = Change.getChanges(build);
        assertNotNull(changes);
        assertEquals(1, changes.size());
        Change change = changes.get(0);
        assertEquals("Fixed bug", change.getMessage());
        assertEquals("test-user", change.getAuthor().getName());
        assertNull(change.getCommitId());
        assertNull(change.getChangeLink());
    }

    @Test
    void testGetChangesWithBrowser() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("build");
        FakeRepositoryBrowserSCM scm = new FakeRepositoryBrowserSCM();
        scm.addChange().withAuthor("test-user").withMsg("Fixed bug");
        project.setScm(scm);
        jenkins.setQuietPeriod(0);
        jenkins.buildAndAssertSuccess(project);
        AbstractBuild build = project.getLastBuild();
        List<Change> changes = Change.getChanges(build);
        assertNotNull(changes);
        assertEquals(1, changes.size());
        Change change = changes.get(0);
        assertEquals("Fixed bug", change.getMessage());
        assertEquals("test-user", change.getAuthor().getName());
        assertNull(change.getCommitId());
        assertEquals("http://somewhere.com/test-user", change.getChangeLink());
    }

    @Test
    @Disabled
    void testGetChangesWithAnnotator() throws Exception {
        ChangeLogAnnotator.all().add(new ChangeLogAnnotator() {
            @Override
            public void annotate(AbstractBuild<?, ?> build, ChangeLogSet.Entry change, MarkupText text) {
                text.addMarkup(0, "Something huge: ");
            }
        });
        FreeStyleProject project = jenkins.createFreeStyleProject("build");
        FakeRepositoryBrowserSCM scm = new FakeRepositoryBrowserSCM();
        scm.addChange().withAuthor("test-user").withMsg("Fixed bug");
        project.setScm(scm);
        jenkins.setQuietPeriod(0);
        jenkins.buildAndAssertSuccess(project);
        AbstractBuild build = project.getLastBuild();
        List<Change> changes = Change.getChanges(build);
        assertNotNull(changes);
        assertEquals(1, changes.size());
        Change change = changes.get(0);
        assertEquals("Something huge: Fixed bug", change.getMessage());
    }

    @Test
    void testGetChangesWithBrowserThrowIOException() throws Exception {
        MockFolder folder = jenkins.createFolder("Folder");
        FreeStyleProject project = folder.createProject(FreeStyleProject.class, "build");
        MeanFakeRepositoryBrowserSCM scm = new MeanFakeRepositoryBrowserSCM();
        scm.addChange().withAuthor("test-user").withMsg("Fixed bug");
        project.setScm(scm);
        jenkins.setQuietPeriod(0);
        jenkins.buildAndAssertSuccess(project);
        AbstractBuild build = project.getLastBuild();
        List<Change> changes = Change.getChanges(build);
        assertNotNull(changes);
        assertEquals(1, changes.size());
        Change change = changes.get(0);
        assertEquals("Fixed bug", change.getMessage());
        assertEquals("test-user", change.getAuthor().getName());
        assertNull(change.getCommitId());
        assertNull(change.getChangeLink());

        String capturedLog = getTestCapturedLog();
        assertTrue(capturedLog.contains("Could not get changeset link for: Folder » build #1"));
    }
}
