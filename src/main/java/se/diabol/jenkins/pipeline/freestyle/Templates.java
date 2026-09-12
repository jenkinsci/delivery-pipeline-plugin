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

import hudson.FilePath;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;

/** Expands the token macros of task names and description templates, and formats descriptions for display. */
public final class Templates {

    private static final Logger LOG = Logger.getLogger(Templates.class.getName());

    private Templates() {
    }

    /** Expands the macros of the template against the build; without a build the variables are blanked out. */
    public static String expand(Run<?, ?> build, String template) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        if (build == null) {
            return template.replaceAll("\\$\\{[^}]*\\}", "...");
        }
        try {
            return TokenMacro.expandAll(build, workspaceOf(build), TaskListener.NULL, template);
        } catch (MacroEvaluationException | IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not expand '" + template + "' for " + build.getFullDisplayName(), e);
            return template;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return template;
        }
    }

    /**
     * Turns text into the HTML the page may show, through the markup formatter configured for Jenkins, exactly like
     * a job description.
     */
    public static String toHtml(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return Jenkins.get().getMarkupFormatter().translate(text);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not format a description", e);
            return Util.escape(text);
        }
    }

    private static FilePath workspaceOf(Run<?, ?> build) {
        if (build instanceof AbstractBuild<?, ?> abstractBuild && abstractBuild.getWorkspace() != null) {
            return abstractBuild.getWorkspace();
        }
        return new FilePath((VirtualChannel) null, "");
    }
}
