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
import se.diabol.jenkins.pipeline.domain.PipelineException;
import se.diabol.jenkins.workflow.api.Run;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads runs of a Pipeline job as {@link Run} models. Finished runs never change, and reading their flow
 * graph from disk is the expensive part, so the most recent ones are kept in memory.
 */
public class WorkflowApi {

    private static final int CACHE_SIZE = 500;

    private static final Map<String, Run> FINISHED = Collections.synchronizedMap(
            new LinkedHashMap<String, Run>(64, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Run> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    public WorkflowApi() {
    }

    public List<Run> getRunsFor(WorkflowJob job) {
        List<Run> runs = new ArrayList<>();
        for (WorkflowRun run : job.getBuilds()) {
            runs.add(runFor(run));
        }
        return runs;
    }

    /** The model of one run; cached once the run has finished. */
    public Run runFor(WorkflowRun run) {
        if (run.isBuilding()) {
            return Run.of(run);
        }
        String key = run.getExternalizableId();
        Run cached = FINISHED.get(key);
        if (cached == null) {
            cached = Run.of(run);
            FINISHED.put(key, cached);
        }
        return cached;
    }

    /** The newest run that is neither running nor waiting for input, or null. Newer runs are not analysed. */
    public Run lastFinishedRunFor(WorkflowJob job) throws PipelineException {
        for (WorkflowRun candidate : job.getBuilds()) {
            if (candidate.isBuilding()) {
                continue;
            }
            Run run = runFor(candidate);
            if (!"IN_PROGRESS".equals(run.status) && !"PAUSED_PENDING_INPUT".equals(run.status)) {
                return run;
            }
        }
        return null;
    }
}
