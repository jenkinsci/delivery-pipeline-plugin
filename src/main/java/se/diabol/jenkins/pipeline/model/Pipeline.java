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

import java.util.ArrayList;
import java.util.List;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * One run of a component's pipeline, or the aggregated pipeline that shows each stage's latest version.
 *
 * @param id identifies the instance within its component, such as "build#12" or "aggregated"
 * @param version the display name of the build that started the pipeline, or "#n" for a queued one
 * @param timestamp epoch milliseconds when the pipeline started or was queued
 * @param aggregated whether this is the aggregated pipeline
 * @param jobFullName the job whose build the pipeline is: the first job of a chain of jobs, or the Pipeline job;
 *                    null for the aggregated pipeline
 * @param buildNumber the number of that build, or null while it is queued and for the aggregated pipeline
 * @param rebuildable whether the whole pipeline can be run again from the view with the same parameters, by a user
 *                    who may build the job
 * @param triggers what started the pipeline
 * @param contributors authors of the changes that went into the pipeline
 * @param changes the commits, when the view shows them
 * @param commits number of commits
 * @param totalBuildTime milliseconds of the longest route through the pipeline, when the view shows it
 * @param tests test results the run recorded outside the tasks shown, such as in a Declarative post section
 * @param analysis static analysis results that belong to the run as a whole rather than to one of its tasks
 * @param stages the stages in grid order
 */
@ExportedBean(defaultVisibility = 100)
public record Pipeline(@Exported String id, @Exported String version, @Exported long timestamp,
                       @Exported boolean aggregated, @Exported String jobFullName, @Exported Integer buildNumber,
                       @Exported boolean rebuildable, @Exported List<Trigger> triggers,
                       @Exported List<Contributor> contributors, @Exported List<Change> changes,
                       @Exported int commits, @Exported long totalBuildTime, @Exported List<TestSummary> tests,
                       @Exported List<AnalysisSummary> analysis, @Exported List<Stage> stages) {

    public Pipeline {
        triggers = List.copyOf(triggers);
        contributors = List.copyOf(contributors);
        changes = List.copyOf(changes);
        tests = List.copyOf(tests);
        analysis = List.copyOf(analysis);
        stages = List.copyOf(stages);
    }

    /** Permissions of the current user on the pipeline's job. */
    @Exported
    public Permissions permissions() {
        return Permissions.on(jobFullName);
    }

    /**
     * The same pipeline with only the details the view shows: the change log, test results and static analysis
     * results. Sources that compute a pipeline once and share it between views strip it per view with this.
     */
    public Pipeline forSettings(ViewSettings settings) {
        boolean keepChanges = settings.showChanges();
        boolean keepTests = settings.showTestResults();
        boolean keepAnalysis = settings.showStaticAnalysisResults();
        List<Stage> filtered = new ArrayList<>(stages.size());
        boolean changed = !keepChanges && !changes.isEmpty() || !keepAnalysis && !analysis.isEmpty()
                || !keepTests && !tests.isEmpty();
        for (Stage stage : stages) {
            Stage filteredStage = stage.withoutDetails(keepTests, keepAnalysis);
            changed |= filteredStage != stage;
            filtered.add(filteredStage);
        }
        return changed
                ? new Pipeline(id, version, timestamp, aggregated, jobFullName, buildNumber, rebuildable, triggers,
                        contributors, keepChanges ? changes : List.of(), commits, totalBuildTime,
                        keepTests ? tests : List.of(), keepAnalysis ? analysis : List.of(), filtered)
                : this;
    }

    /** Epoch milliseconds of the most recent task activity, 0 when none. */
    public long lastActivity() {
        long result = 0;
        for (Stage stage : stages) {
            for (Task task : stage.tasks()) {
                result = Math.max(result, task.lastActivity());
            }
        }
        return result;
    }

    public boolean hasFailedTask() {
        for (Stage stage : stages) {
            for (Task task : stage.tasks()) {
                if (task.status().isFailed()) {
                    return true;
                }
            }
        }
        return false;
    }
}
