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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.TaskListener;
import java.util.Set;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * The {@code task} step of 1.x, kept so that Jenkinsfiles that still use it keep running. It runs its body, names
 * its block so that the view shows the block as a task of its stage, as 1.x did, and prints a reminder that a
 * nested {@code stage} does the same without this plugin.
 *
 * @deprecated since 2.0; use a nested {@code stage} block instead.
 */
@Deprecated
public class TaskStep extends Step {

    private final String name;

    @DataBoundConstructor
    public TaskStep(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("The task step needs a name");
        }
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, name);
    }

    private static final class Execution extends StepExecution {

        private static final long serialVersionUID = 1L;

        private final String name;
        private BodyExecution body;

        Execution(StepContext context, String name) {
            super(context);
            this.name = name;
        }

        @Override
        public boolean start() throws Exception {
            StepContext context = getContext();
            context.get(TaskListener.class).getLogger().println("[Delivery Pipeline] The task step is deprecated:"
                    + " replace task('" + name + "') with stage('" + name + "'), which the view shows the same way.");
            FlowNode node = context.get(FlowNode.class);
            if (node != null) {
                node.addAction(new LabelAction(name));
            }
            body = context.newBodyInvoker().withCallback(BodyExecutionCallback.wrap(context)).start();
            return false;
        }

        @Override
        public void stop(@NonNull Throwable cause) throws Exception {
            if (body != null) {
                body.cancel(cause);
            } else {
                getContext().onFailure(cause);
            }
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "task";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Delivery Pipeline task (deprecated, use a nested stage)";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public boolean isAdvanced() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(TaskListener.class, FlowNode.class);
        }
    }
}
