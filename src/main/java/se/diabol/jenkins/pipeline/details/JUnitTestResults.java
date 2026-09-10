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
package se.diabol.jenkins.pipeline.details;

import hudson.Extension;
import hudson.model.Run;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.test.AbstractTestResultAction;
import java.util.List;
import se.diabol.jenkins.pipeline.model.TestSummary;

/**
 * Test counts as published by the JUnit plugin or anything else that records an {@link AbstractTestResultAction}.
 * For a flow block, the counts of the {@code junit} steps that ran inside it, which the JUnit plugin keeps per
 * enclosing block.
 */
@Extension
public class JUnitTestResults extends TaskDetailsContributor {

    @Override
    public List<TestSummary> tests(Run<?, ?> build) {
        AbstractTestResultAction<?> action = build.getAction(AbstractTestResultAction.class);
        if (action == null) {
            return List.of();
        }
        return List.of(new TestSummary(action.getDisplayName(), build.getUrl() + action.getUrlName(),
                action.getTotalCount(), action.getFailCount(), action.getSkipCount()));
    }

    @Override
    public List<TestSummary> tests(Run<?, ?> build, String flowBlockId) {
        TestResultAction action = build.getAction(TestResultAction.class);
        if (action == null || flowBlockId == null) {
            return List.of();
        }
        TestResult all = action.getResult();
        if (all == null || all.getPipelineBlockWithTests(flowBlockId) == null) {
            // nothing was recorded inside the block; the lookup below would answer with the whole run instead
            return List.of();
        }
        TestResult result = all.getResultForPipelineBlock(flowBlockId);
        if (result.getTotalCount() == 0) {
            return List.of();
        }
        return List.of(new TestSummary(action.getDisplayName(), build.getUrl() + action.getUrlName(),
                result.getTotalCount(), result.getFailCount(), result.getSkipCount()));
    }
}
