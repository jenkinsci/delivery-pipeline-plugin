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
package se.diabol.jenkins.pipeline.integration.declarative;

import hudson.Extension;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jenkinsci.plugins.pipeline.modeldefinition.actions.RestartDeclarativePipelineAction;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import se.diabol.jenkins.pipeline.PipelineException;
import se.diabol.jenkins.pipeline.flow.StageRestart;

/**
 * Restarts Declarative Pipeline runs from a stage through the "Restart from Stage" feature of the Pipeline:
 * Declarative plugin: the new run reuses the original Jenkinsfile and parameters and skips the stages before.
 */
@Extension(optional = true)
public class DeclarativeStageRestart extends StageRestart {

    @SuppressWarnings("unused")
    private static final Class<?> REQUIRED = RestartDeclarativePipelineAction.class;

    @Override
    public Set<String> restartableStages(WorkflowRun run) {
        RestartDeclarativePipelineAction action = run.getAction(RestartDeclarativePipelineAction.class);
        if (action == null || !action.isRestartEnabled()) {
            return Set.of();
        }
        return new LinkedHashSet<>(action.getRestartableStages());
    }

    @Override
    public void restart(WorkflowRun run, String stageName) throws PipelineException {
        RestartDeclarativePipelineAction action = run.getAction(RestartDeclarativePipelineAction.class);
        if (action == null) {
            throw new PipelineException(run.getFullDisplayName() + " is not a Declarative Pipeline run");
        }
        try {
            if (action.run(stageName) == null) {
                throw new PipelineException("Could not schedule a restart of " + run.getFullDisplayName());
            }
        } catch (IllegalStateException e) {
            throw new PipelineException(e.getMessage(), e);
        }
    }
}
