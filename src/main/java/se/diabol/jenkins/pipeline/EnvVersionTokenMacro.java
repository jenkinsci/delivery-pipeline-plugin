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

import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.Map;
import org.jenkinsci.plugins.tokenmacro.DataBoundTokenMacro;

/** The {@code ${ENV_VERSION}} token: the {@code ENV_VERSION} environment variable, optionally without -SNAPSHOT. */
@Extension
public class EnvVersionTokenMacro extends DataBoundTokenMacro {

    private static final String NAME = "ENV_VERSION";

    @Parameter(required = false)
    public boolean stripSnapshot;

    @Override
    public String evaluate(AbstractBuild<?, ?> context, TaskListener listener, String macroName)
            throws IOException, InterruptedException {
        Map<String, String> env = context.getEnvironment(listener);
        String version = env.get(NAME);
        if (version == null) {
            return "";
        }
        return stripSnapshot ? version.replace("-SNAPSHOT", "") : version;
    }

    @Override
    public boolean acceptsMacroName(String macroName) {
        return NAME.equals(macroName);
    }
}
