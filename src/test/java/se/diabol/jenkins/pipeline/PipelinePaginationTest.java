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
package se.diabol.jenkins.pipeline;

import org.junit.jupiter.api.Test;
import se.diabol.jenkins.pipeline.domain.Component;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PipelinePaginationTest {

    private static final boolean PAGING_ENABLED = true;

    @Test
    void testPipelinePagination() {
        PipelinePagination pagination = new PipelinePagination(1, 50, 10, "?page=");
        assertEquals(1, pagination.getCurrentPage());
        assertEquals(50, pagination.getTotalCount());
        assertEquals(10, pagination.getPageSize());
        assertNotNull(pagination.getTag());
    }

    @Test
    void testPipelinePaginationPrevStep() {
        PipelinePagination pagination = new PipelinePagination(12, 50, 3, "?page=");
        assertEquals(12, pagination.getCurrentPage());
        assertEquals(50, pagination.getTotalCount());
        assertEquals(3, pagination.getPageSize());
        assertNotNull(pagination.getTag());
    }

    @Test
    void testPipelinePaginationNextStep() {
        PipelinePagination pagination = new PipelinePagination(1, 50, 3, "?page=");
        assertEquals(1, pagination.getCurrentPage());
        assertEquals(50, pagination.getTotalCount());
        assertEquals(3, pagination.getPageSize());
        assertNotNull(pagination.getTag());
    }

    @Test
    void testComponentNumber() {
        Component componentB = new Component("B", "B", "job/A", false, 3, PAGING_ENABLED, 2);
        Component componentA = new Component("A", "A", "job/B", false, 3, PAGING_ENABLED, 1);
        List<Component> list = new ArrayList<>();
        list.add(componentA);  
        list.add(componentB);
        assertEquals(1, list.get(0).getComponentNumber());
        assertEquals(2, list.get(1).getComponentNumber());
    }

    @Test
    void testComponent() {
        Component componentA = new Component("A", "A", "job/B", false, 3, PAGING_ENABLED, 1);
        componentA.setPipelines(new ArrayList<>());
        assertNotNull(componentA.getPagingData());
        assertNotNull(componentA.getPipelines());
    }
}
