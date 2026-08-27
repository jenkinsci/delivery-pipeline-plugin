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
package se.diabol.jenkins.pipeline.sort;

import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import se.diabol.jenkins.pipeline.domain.Component;
import se.diabol.jenkins.pipeline.domain.status.StatusType;
import se.diabol.jenkins.workflow.model.WorkflowStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static se.diabol.jenkins.pipeline.domain.status.StatusType.SUCCESS;
import static se.diabol.jenkins.pipeline.test.PipelineUtil.createComponent;
import static se.diabol.jenkins.pipeline.test.PipelineUtil.status;

class LatestActivityComparatorTest {

    @Test
    void shouldSortRecentlyRunDeliveryPipelineFirst() {
        Component componentRunLongAgo = createComponent(status(SUCCESS, new DateTime().minusDays(2)));
        Component componentRunRecently = createComponent(status(SUCCESS, new DateTime().minusDays(1)));
        List<Component> list = new ArrayList<>();
        list.add(componentRunRecently);
        list.add(componentRunLongAgo);
        list.sort(new LatestActivityComparator.DescriptorImpl().createInstance());
        assertEquals(componentRunRecently, list.get(0));
        assertEquals(componentRunLongAgo, list.get(1));
    }

    @Test
    void shouldSortRecentlyRunWorkflowPipelineFirst() {
        se.diabol.jenkins.workflow.model.Component componentRunLongAgo = createComponent(
                new WorkflowStatus(StatusType.SUCCESS, 1000, 100));
        se.diabol.jenkins.workflow.model.Component componentRunRecently = createComponent(
                new WorkflowStatus(StatusType.SUCCESS, 9000, 100));
        List<se.diabol.jenkins.workflow.model.Component> components = Arrays
                .asList(componentRunLongAgo, componentRunRecently);
        components.sort(new LatestActivityComparator.DescriptorImpl().createInstance());
        assertEquals(componentRunRecently, components.get(0));
        assertEquals(componentRunLongAgo, components.get(1));
    }
}
