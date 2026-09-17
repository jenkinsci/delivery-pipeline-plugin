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
package se.diabol.jenkins.pipeline.consolidated;

import hudson.model.Cause;
import java.util.Objects;

/** Why a build started: the consolidated pipeline of a view ran the pipeline that the build's job starts. */
public class ConsolidatedCause extends Cause {

    private final String viewName;
    private final int number;

    public ConsolidatedCause(String viewName, int number) {
        this.viewName = viewName;
        this.number = number;
    }

    public String getViewName() {
        return viewName;
    }

    /** The number of the consolidated run within its view. */
    public int getNumber() {
        return number;
    }

    @Override
    public String getShortDescription() {
        return "Started by run #" + number + " of the consolidated pipeline of view " + viewName;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConsolidatedCause cause && number == cause.number
                && Objects.equals(viewName, cause.viewName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(viewName, number);
    }
}
