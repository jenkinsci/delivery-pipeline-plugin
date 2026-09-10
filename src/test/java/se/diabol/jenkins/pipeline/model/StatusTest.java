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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class StatusTest {

    @Test
    void progressOfARunningBuildFollowsTheEstimateAndStopsAtNinetyNine() {
        Status running = Status.running(1000, 10000);
        assertThat(running.progress(1000), is(0));
        assertThat(running.progress(6000), is(50));
        assertThat(running.progress(20000), is(99));
        assertThat("without an estimate the bar is nearly full", Status.running(1000, -1).progress(2000), is(99));
        assertThat(Status.running(System.currentTimeMillis(), 60000).exportedDuration(), greaterThanOrEqualTo(0L));
    }

    @Test
    void finishedStatusesReportWhatHappened() {
        Status status = Status.finished(StatusType.FAILED, 5000, 300);
        assertThat(status.progress(), nullValue());
        assertThat(status.exportedDuration(), is(300L));
        assertThat(status.exportedTimestamp(), is(5000L));
        assertThat(status.isFailed(), is(true));
        assertThat(Status.idle().exportedTimestamp(), nullValue());
        assertThrows(IllegalArgumentException.class, () -> Status.finished(StatusType.RUNNING, 0, 0));
    }
}
