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

import com.google.common.collect.Lists;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import se.diabol.jenkins.pipeline.domain.Component;
import se.diabol.jenkins.pipeline.domain.status.SimpleStatus;
import se.diabol.jenkins.pipeline.domain.status.Status;
import se.diabol.jenkins.pipeline.domain.status.StatusType;
import se.diabol.jenkins.workflow.model.WorkflowStatus;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.diabol.jenkins.pipeline.domain.status.StatusType.FAILED;
import static se.diabol.jenkins.pipeline.domain.status.StatusType.SUCCESS;
import static se.diabol.jenkins.pipeline.test.PipelineUtil.createComponent;
import static se.diabol.jenkins.pipeline.test.PipelineUtil.createDeliveryPipelineComponentWithNoRuns;
import static se.diabol.jenkins.pipeline.test.PipelineUtil.createWorkflowPipelineComponentWithNoRuns;

class FailedJobComparatorTest {

    @Test
    void shouldSortFailedBeforeSuccessful() {

        Component failedComponent = createComponent(status(FAILED, new DateTime().minusDays(1)));
        Component successfulComponent = createComponent(status(SUCCESS, new DateTime().minusDays(1)));

        List<Component> list = new ArrayList<>();
        list.add(successfulComponent);
        list.add(failedComponent);
        list.sort(new FailedJobComparator.DescriptorImpl().createInstance());
        assertThat(list.size(), is(2));
        assertEquals(failedComponent, list.get(0));
        assertEquals(successfulComponent, list.get(1));
    }

    @Test
    void shouldSortRecentlyRunFirstIfSameStatus() {

        Component failedComponentRunLongAgo = createComponent(status(FAILED, new DateTime().minusDays(10)));
        Component failedComponent = createComponent(status(FAILED, new DateTime().minusDays(1)));
        Component successfulComponent = createComponent(status(SUCCESS, new DateTime().minusDays(1)));
        List<Component> list = new ArrayList<>();
        list.add(successfulComponent);
        list.add(failedComponent);
        list.add(failedComponentRunLongAgo);
        list.sort(new FailedJobComparator.DescriptorImpl().createInstance());
        assertThat(list.size(), is(3));
        assertEquals(failedComponent, list.get(0));
        assertEquals(failedComponentRunLongAgo, list.get(1));
        assertEquals(successfulComponent, list.get(2));
    }

    @Test
    void shouldSortNotRunJobLast() {
        Component notRunComponent = createDeliveryPipelineComponentWithNoRuns();
        Component successfulComponent = createComponent(status(SUCCESS, new DateTime().minusDays(1)));
        Component failedComponent = createComponent(status(FAILED, new DateTime().minusDays(1)));
        List<Component> list = new ArrayList<>();
        list.add(notRunComponent);
        list.add(successfulComponent);
        list.add(failedComponent);
        list.sort(new FailedJobComparator.DescriptorImpl().createInstance());
        assertThat(list.size(), is(3));
        assertEquals(failedComponent, list.get(0));
        assertEquals(successfulComponent, list.get(1));
        assertEquals(notRunComponent, list.get(2));
    }

    @Test
    void shouldSortFailedWorkflowPipelinesBeforeSuccessful() {
        se.diabol.jenkins.workflow.model.Component failedComponent = createComponent(
                new WorkflowStatus(StatusType.FAILED, 1000, 100));
        se.diabol.jenkins.workflow.model.Component successfulComponent = createComponent(
                new WorkflowStatus(StatusType.SUCCESS, 1000, 100));

        List<se.diabol.jenkins.workflow.model.Component> list = new ArrayList<>();
        list.add(successfulComponent);
        list.add(failedComponent);
        list.sort(new FailedJobComparator.DescriptorImpl().createInstance());
        assertThat(list.size(), is(2));
        assertEquals(failedComponent, list.get(0));
        assertEquals(successfulComponent, list.get(1));
    }

    @Test
    void shouldSortRecentlyRunWorkflowPipelinesFirstIfSameStatus() {
        se.diabol.jenkins.workflow.model.Component failedComponentRunLongAgo = createComponent(
                new WorkflowStatus(StatusType.FAILED, 1000, 100));
        se.diabol.jenkins.workflow.model.Component failedComponent = createComponent(
                new WorkflowStatus(StatusType.FAILED, 9000, 100));
        se.diabol.jenkins.workflow.model.Component successfulComponent = createComponent(
                new WorkflowStatus(StatusType.SUCCESS, 9000, 100));

        List<se.diabol.jenkins.workflow.model.Component> list = new ArrayList<>();
        list.add(successfulComponent);
        list.add(failedComponent);
        list.add(failedComponentRunLongAgo);
        list.sort(new FailedJobComparator.DescriptorImpl().createInstance());
        assertThat(list.size(), is(3));
        assertEquals(failedComponent, list.get(0));
        assertEquals(failedComponentRunLongAgo, list.get(1));
        assertEquals(successfulComponent, list.get(2));
    }

    @Test
    void shouldSortNotRunWorkflowPipelineLast() {
        se.diabol.jenkins.workflow.model.Component notRunComponent = createWorkflowPipelineComponentWithNoRuns();
        se.diabol.jenkins.workflow.model.Component successfulComponent = createComponent(
                new WorkflowStatus(StatusType.SUCCESS, 1000, 100));
        se.diabol.jenkins.workflow.model.Component failedComponent = createComponent(
                new WorkflowStatus(StatusType.FAILED, 9000, 100));

        List<se.diabol.jenkins.workflow.model.Component> list = new ArrayList<>();
        list.add(notRunComponent);
        list.add(successfulComponent);
        list.add(failedComponent);
        list.sort(new FailedJobComparator.DescriptorImpl().createInstance());
        assertThat(list.size(), is(3));
        assertEquals(failedComponent, list.get(0));
        assertEquals(successfulComponent, list.get(1));
        assertEquals(notRunComponent, list.get(2));
    }

    @Test
    void shouldHandleNullParameters() {
        assertEquals(0, new FailedJobComparator().compare(null, null));
    }

    @Test
    void shouldBeAbleToCompareWithNull() {
        FailedJobComparator comparator = new FailedJobComparator();
        Component successfulComponent = createComponent(status(SUCCESS, new DateTime()));
        assertTrue(comparator.compare(successfulComponent, null) < 0);
        assertTrue(comparator.compare(null, successfulComponent) > 0);
    }

    private Status status(StatusType statusType, DateTime lastRunAt) {
        return new SimpleStatus(statusType, lastRunAt.getMillis(), 10, false, Lists.newArrayList());
    }
}
