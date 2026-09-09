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

import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Actions the view buttons perform on runs of a Pipeline job. Shared by the Delivery Pipeline view,
 * which accepts Pipeline jobs as components, and the deprecated Pipeline-only view.
 */
public final class WorkflowRuns {

    private static final Logger LOG = Logger.getLogger(WorkflowRuns.class.getName());

    private WorkflowRuns() {
    }

    /** Proceeds the pending input step of the given run, which is what the "Specify input" button does. */
    public static void proceedInput(WorkflowJob job, String buildId) {
        try {
            for (WorkflowRun run : job.getBuilds()) {
                if (Integer.toString(run.getNumber()).equals(buildId)) {
                    InputAction inputAction = run.getAction(InputAction.class);
                    if (inputAction != null && !inputAction.getExecutions().isEmpty()) {
                        inputAction.getExecutions().get(0).doProceedEmpty();
                    }
                }
            }
        } catch (IOException | InterruptedException | TimeoutException e) {
            LOG.warning("Failed to proceed input step of " + job.getFullName() + " #" + buildId + ": " + e);
        }
    }

    /** Stops the given run if the current user may abort builds of the job. */
    public static void abort(WorkflowJob job, String buildId) throws AuthenticationException {
        if (!job.hasAbortPermission()) {
            throw new BadCredentialsException("Not authorized to abort build");
        }
        job.getBuilds().stream()
                .filter(run -> Integer.toString(run.getNumber()).equals(buildId))
                .findFirst()
                .ifPresent(WorkflowRun::doStop);
    }
}
