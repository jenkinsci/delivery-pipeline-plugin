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
import static org.hamcrest.Matchers.nullValue;

import hudson.model.FreeStyleProject;
import java.net.URL;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PipelinePropertyTest {

    @Test
    void thePropertySurvivesTheJobConfigureForm(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("build");
        project.addProperty(new PipelineProperty("Compile", "Build", "Deploys ${BUILD_NUMBER}"));
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlPage page = client.getPage(new URL(jenkins.getURL(), project.getUrl() + "configure"));
            HtmlForm form = page.getFormByName("config");
            jenkins.submit(form);
        }
        PipelineProperty saved = project.getProperty(PipelineProperty.class);
        assertThat(saved.getTaskName(), is("Compile"));
        assertThat(saved.getStageName(), is("Build"));
        assertThat(saved.getDescriptionTemplate(), is("Deploys ${BUILD_NUMBER}"));
    }

    @Test
    void blankNamesMeanNoName() {
        PipelineProperty property = new PipelineProperty(" ", "", null);
        assertThat(property.getTaskName(), nullValue());
        assertThat(property.getStageName(), nullValue());
        assertThat(property.getDescriptionTemplate(), nullValue());
    }
}
