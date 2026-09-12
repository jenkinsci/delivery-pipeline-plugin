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

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.List;
import se.diabol.jenkins.pipeline.model.AnalysisSummary;
import se.diabol.jenkins.pipeline.model.Promotion;
import se.diabol.jenkins.pipeline.model.TestSummary;

/**
 * Adds facts about a build to its task: test results, static analysis results and promotions. Test results come
 * from the JUnit plugin; the others are contributed by optional integrations when their plugins are installed.
 * <p>For a Pipeline run the tasks are stages, so test results are asked for per flow block: the block of the
 * stage, nested stage or parallel branch the task shows.
 */
public abstract class TaskDetailsContributor implements ExtensionPoint {

    public List<TestSummary> tests(Run<?, ?> build) {
        return List.of();
    }

    /** Test results recorded inside the flow block that starts at the node with the given id. */
    public List<TestSummary> tests(Run<?, ?> build, String flowBlockId) {
        return List.of();
    }

    public List<AnalysisSummary> analysis(Run<?, ?> build) {
        return List.of();
    }

    public List<Promotion> promotions(Run<?, ?> build) {
        return List.of();
    }

    public static ExtensionList<TaskDetailsContributor> all() {
        return ExtensionList.lookup(TaskDetailsContributor.class);
    }

    public static List<TestSummary> testsOf(Run<?, ?> build) {
        List<TestSummary> result = new ArrayList<>();
        for (TaskDetailsContributor contributor : all()) {
            result.addAll(contributor.tests(build));
        }
        return result;
    }

    public static List<TestSummary> testsOf(Run<?, ?> build, String flowBlockId) {
        List<TestSummary> result = new ArrayList<>();
        for (TaskDetailsContributor contributor : all()) {
            result.addAll(contributor.tests(build, flowBlockId));
        }
        return result;
    }

    public static List<AnalysisSummary> analysisOf(Run<?, ?> build) {
        List<AnalysisSummary> result = new ArrayList<>();
        for (TaskDetailsContributor contributor : all()) {
            result.addAll(contributor.analysis(build));
        }
        return result;
    }

    public static List<Promotion> promotionsOf(Run<?, ?> build) {
        List<Promotion> result = new ArrayList<>();
        for (TaskDetailsContributor contributor : all()) {
            result.addAll(contributor.promotions(build));
        }
        return result;
    }
}
