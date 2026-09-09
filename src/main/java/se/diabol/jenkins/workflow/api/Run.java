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
package se.diabol.jenkins.workflow.api;

import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import java.time.Instant;

import java.util.ArrayList;
import java.util.List;

public class Run {

    public final String id;
    public final String name;
    public final String status;
    public final Instant startTimeMillis;
    public final Instant endTimeMillis;
    public final Long durationMillis;
    public final List<Stage> stages;

    public Run(String id,
               String name,
               String status,
               Instant startTimeMillis,
               Instant endTimeMillis,
               Long durationMillis,
               List<Stage> stages) {
        this.id = id;
        this.name = name;
        this.status = status;
        this.startTimeMillis = startTimeMillis;
        this.endTimeMillis = endTimeMillis;
        this.durationMillis = durationMillis;
        this.stages = stages;
    }

    /** Reads the run's stages, status and timing from its flow graph. */
    public static Run of(WorkflowRun run) {
        List<FlowNode> allNodes = FlowAnalysis.allNodes(run.getExecution());
        List<FlowNode> stageStarts = FlowAnalysis.stageStartNodes(allNodes);
        List<Stage> stages = new ArrayList<>(stageStarts.size());
        for (FlowNode stageStart : stageStarts) {
            stages.add(FlowAnalysis.stage(run, stageStart, allNodes, stageStarts));
        }
        long startTime = run.getStartTimeInMillis();
        long duration = run.isBuilding() ? System.currentTimeMillis() - startTime : run.getDuration();
        return new Run(String.valueOf(run.getNumber()), run.getDisplayName(), FlowAnalysis.runStatus(run),
                Instant.ofEpochMilli(startTime), Instant.ofEpochMilli(startTime + duration), duration, stages);
    }

    /** The stage started by the flow node with the given id, or null. */
    public Stage getStageById(final String id) {
        for (Stage stage : stages) {
            if (id != null && id.equals(stage.id)) {
                return stage;
            }
        }
        return null;
    }

    public boolean hasStage(final String name) {
        for (Stage stage : stages) {
            String stageName = stage.name;
            if (stageName != null && stageName.equals(name)) {
                return true;
            }
        }
        return false;
    }

    public Stage getStageByName(final String name) {
        for (Stage stage : stages) {
            String stageName = stage.name;
            if (stageName != null && stageName.equals(name)) {
                return stage;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "Run{"
                + "id='" + id + '\''
                + ", name='" + name + '\''
                + ", status='" + status + '\''
                + '}';
    }
}
