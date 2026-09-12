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

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import se.diabol.jenkins.pipeline.PipelineException;

/**
 * Restarts a finished Pipeline run from one of its top-level stages, for the flavours of Pipeline that support it.
 * Declarative Pipeline does; the integration with it activates when that plugin is installed.
 */
public abstract class StageRestart implements ExtensionPoint {

    /** Names of the top-level stages the run can be restarted from by the current user; empty when it cannot. */
    public abstract Set<String> restartableStages(WorkflowRun run);

    /** Schedules a run that restarts the given run from the named stage. */
    public abstract void restart(WorkflowRun run, String stageName) throws PipelineException;

    public static ExtensionList<StageRestart> all() {
        return ExtensionList.lookup(StageRestart.class);
    }

    /**
     * The stages the run can be restarted from, regardless of who asks, so that the answer can be cached with the
     * run's model and served to any user; whether a user may actually restart is checked when they try.
     */
    static Set<String> restartableStagesOf(WorkflowRun run) {
        Set<String> result = new LinkedHashSet<>();
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            for (StageRestart restart : all()) {
                result.addAll(restart.restartableStages(run));
            }
        }
        return result;
    }

    static void restartFrom(WorkflowRun run, String stageName) throws PipelineException {
        for (StageRestart restart : all()) {
            if (restart.restartableStages(run).contains(stageName)) {
                restart.restart(run, stageName);
                return;
            }
        }
        throw new PipelineException(run.getFullDisplayName() + " cannot be restarted from stage " + stageName);
    }
}
