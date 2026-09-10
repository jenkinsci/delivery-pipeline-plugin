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
 * A column of tasks in the pipeline grid.
 *
 * @param id identifies the stage within its pipeline
 * @param name the heading of the box
 * @param row the row of the grid the stage sits in, starting at 0
 * @param column the column of the grid, starting at 0
 * @param version in the aggregated pipeline, the version that last reached the stage; null otherwise
 * @param tasks the tasks, in order
 * @param downstream ids of the stages this stage leads to, for drawing arrows
 */
@ExportedBean(defaultVisibility = 100)
public record Stage(@Exported String id, @Exported String name, @Exported int row, @Exported int column,
                    @Exported String version, @Exported List<Task> tasks, @Exported List<String> downstream) {

    public Stage {
        tasks = List.copyOf(tasks);
        downstream = List.copyOf(downstream);
    }

    public Stage withPosition(int row, int column) {
        return new Stage(id, name, row, column, version, tasks, downstream);
    }

    public Stage withTasks(List<Task> tasks, String version) {
        return new Stage(id, name, row, column, version, tasks, downstream);
    }

    /** The same stage with only the task details the view shows. */
    public Stage withoutDetails(boolean keepTests, boolean keepAnalysis) {
        List<Task> filtered = new ArrayList<>(tasks.size());
        boolean changed = false;
        for (Task task : tasks) {
            Task filteredTask = task.withoutDetails(keepTests, keepAnalysis);
            changed |= filteredTask != task;
            filtered.add(filteredTask);
        }
        return changed ? new Stage(id, name, row, column, version, filtered, downstream) : this;
    }
}
