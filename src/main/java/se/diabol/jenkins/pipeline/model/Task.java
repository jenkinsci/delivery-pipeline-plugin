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
package se.diabol.jenkins.pipeline.model;

import java.util.List;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * One box of a stage: a job of a chain of jobs, or a stage, nested stage or parallel branch of a Pipeline run.
 *
 * @param id identifies the task within its pipeline; the job's full name for a chained job, the flow node id for a
 *           Pipeline stage
 * @param name what the box says
 * @param url where the box links to, relative to the Jenkins root
 * @param jobFullName the job the task belongs to, which actions are posted for and whose permissions decide what
 *                    the current user may do with the task
 * @param buildNumber the build the task shows, or null
 * @param status the status of that build
 * @param description the task's description as safe HTML, or null
 * @param rebuildable whether the build could be rebuilt from the view by a user who may build the job; for a
 *                    Pipeline stage, whether the run can be restarted from the task's top-level stage
 * @param restart the name of the top-level stage a rebuild restarts the Pipeline run from, or null when a rebuild
 *                builds the job again
 * @param requiresInput whether the task is a Pipeline run waiting at an input step
 * @param inputUrl where to provide the input when the waiting input step has parameters, relative to the Jenkins
 *                 root; null when the step can be proceeded from the view or the task is not waiting
 * @param manual the manual trigger leading to the task, or null
 * @param tests test results of the build
 * @param analysis static analysis results of the build
 * @param promotions promotions of the build
 * @param downstream ids of the tasks this task triggers
 */
@ExportedBean(defaultVisibility = 100)
public record Task(@Exported String id, @Exported String name, @Exported String url, @Exported String jobFullName,
                   @Exported Integer buildNumber, @Exported Status status, @Exported String description,
                   @Exported boolean rebuildable, @Exported String restart, @Exported boolean requiresInput,
                   @Exported String inputUrl, @Exported ManualStep manual,
                   @Exported List<TestSummary> tests, @Exported List<AnalysisSummary> analysis,
                   @Exported List<Promotion> promotions, @Exported List<String> downstream) {

    public Task {
        tests = List.copyOf(tests);
        analysis = List.copyOf(analysis);
        promotions = List.copyOf(promotions);
        downstream = List.copyOf(downstream);
    }

    /** Permissions of the current user on the task's job. */
    @Exported
    public Permissions permissions() {
        return Permissions.on(jobFullName);
    }

    /** The same task with only the details the view shows. */
    public Task withoutDetails(boolean keepTests, boolean keepAnalysis) {
        if ((keepTests || tests.isEmpty()) && (keepAnalysis || analysis.isEmpty())) {
            return this;
        }
        return new Task(id, name, url, jobFullName, buildNumber, status, description, rebuildable, restart,
                requiresInput, inputUrl, manual, keepTests ? tests : List.of(), keepAnalysis ? analysis : List.of(),
                promotions, downstream);
    }

    /** Epoch milliseconds of the task's last activity, 0 when none. */
    public long lastActivity() {
        return status.timestamp();
    }
}
