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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class TaskUrlTest {

    @Test
    void withPipelineGraphViewATaskLinksToItsOwnLog() {
        assertThat(FlowStages.taskUrl("job/app/12/", "stages", "27", true), is("job/app/12/stages/?selected-node=27"));
        assertThat("finished tasks link there too",
                FlowStages.taskUrl("job/app/12/", "stages", "27", false), is("job/app/12/stages/?selected-node=27"));
        assertThat("older releases named the page differently",
                FlowStages.taskUrl("job/app/12/", "pipeline-console", "27", false),
                is("job/app/12/pipeline-console/?selected-node=27"));
    }

    @Test
    void withoutItARunningTaskLinksToTheConsoleAndAFinishedOneToTheRun() {
        assertThat(FlowStages.taskUrl("job/app/12/", null, "27", true), is("job/app/12/console"));
        assertThat(FlowStages.taskUrl("job/app/12/", null, "27", false), is("job/app/12/"));
    }
}
