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
package se.diabol.jenkins.pipeline.freestyle;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.diabol.jenkins.pipeline.PipelineException;

class StageLayoutTest {

    @Test
    void aChainSitsOnOneRow() throws Exception {
        Map<String, StageLayout.Position> placed = StageLayout.place(List.of("A", "B", "C"),
                Map.of("A", List.of("B"), "B", List.of("C")), "A");
        assertThat(placed.keySet(), contains("A", "B", "C"));
        assertThat(placed.get("A"), is(new StageLayout.Position(0, 0)));
        assertThat(placed.get("B"), is(new StageLayout.Position(0, 1)));
        assertThat(placed.get("C"), is(new StageLayout.Position(0, 2)));
    }

    @Test
    void aFanOutGetsOneRowPerBranch() throws Exception {
        Map<String, StageLayout.Position> placed = StageLayout.place(List.of("A", "B", "C"),
                Map.of("A", List.of("B", "C")), "A");
        assertThat(placed.get("B"), is(new StageLayout.Position(0, 1)));
        assertThat(placed.get("C"), is(new StageLayout.Position(1, 1)));
        assertThat("rows first, then columns", placed.keySet(), contains("A", "B", "C"));
    }

    @Test
    void theColumnIsTheLongestPathSoArrowsPointRight() throws Exception {
        // A -> B -> C -> D and A -> D: D belongs in the fourth column, not the second
        Map<String, StageLayout.Position> placed = StageLayout.place(List.of("A", "D", "B", "C"),
                Map.of("A", List.of("D", "B"), "B", List.of("C"), "C", List.of("D")), "A");
        assertThat(placed.get("D"), is(new StageLayout.Position(0, 3)));
        assertThat("the long chain stays on the top row", placed.get("B"), is(new StageLayout.Position(0, 1)));
        assertThat(placed.get("C"), is(new StageLayout.Position(0, 2)));
    }

    @Test
    void theLongestChainIsFollowedFirstSoItStaysOnTop() throws Exception {
        // A -> B, A -> C -> D: C and D make the longer chain and take the top row
        Map<String, StageLayout.Position> placed = StageLayout.place(List.of("A", "B", "C", "D"),
                Map.of("A", List.of("B", "C"), "C", List.of("D")), "A");
        assertThat(placed.get("C"), is(new StageLayout.Position(0, 1)));
        assertThat(placed.get("D"), is(new StageLayout.Position(0, 2)));
        assertThat(placed.get("B"), is(new StageLayout.Position(1, 1)));
    }

    @Test
    void aCircleIsReported() {
        PipelineException e = assertThrows(PipelineException.class, () -> StageLayout.place(List.of("A", "B", "C"),
                Map.of("A", List.of("B"), "B", List.of("C"), "C", List.of("B")), "A"));
        assertThat(e.getMessage(), containsString("Circular dependencies between stages"));
        assertThat(e.getMessage(), containsString("B"));
        assertThat(e.getMessage(), containsString("C"));
    }
}
