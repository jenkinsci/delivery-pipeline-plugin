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
package se.diabol.jenkins.workflow.step;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskStepTest {

    @Test
    void shouldThrowIllegalArgumentForNullNameInConstructor() {
        assertThrows(IllegalArgumentException.class, () -> new TaskStep(null));
    }

    @Test
    void shouldThrowIllegalArgumentForEmptyNameInConstructor() {
        assertThrows(IllegalArgumentException.class, () -> new TaskStep(""));
    }

    @Test
    void constructorShouldTakeName() {
        String expectedName = "taskStep";
        TaskStep taskStep = new TaskStep(expectedName);
        assertThat(taskStep.name, is(expectedName));
    }

    @Test
    void descriptorShouldReturnExpectedNames() {
        TaskStep.DescriptorImpl descriptor = new TaskStep.DescriptorImpl();
        assertThat(descriptor.getDisplayName(), is("Task"));
        assertThat(descriptor.getFunctionName(), is("task"));
    }
}