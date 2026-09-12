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

import java.util.Comparator;
import se.diabol.jenkins.pipeline.model.Component;

/** The orders the components of a view can be shown in; ids are those 1.x used, which Job DSL writes. */
public enum Sorting {
    NONE("none", "None"),
    NAME("se.diabol.jenkins.pipeline.sort.NameComparator", "By title"),
    LATEST_ACTIVITY("se.diabol.jenkins.pipeline.sort.LatestActivityComparator", "By last activity"),
    FAILED_FIRST("se.diabol.jenkins.pipeline.sort.FailedJobComparator", "Failed pipelines first, then by last activity");

    private final String id;
    private final String displayName;

    Sorting(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** The sorting with the given id or name; unknown values, including the 1.x "NoOpComparator", mean none. */
    public static Sorting fromId(String value) {
        if (value == null) {
            return NONE;
        }
        for (Sorting sorting : values()) {
            if (sorting.id.equals(value) || sorting.name().equalsIgnoreCase(value)) {
                return sorting;
            }
        }
        return NONE;
    }

    public Comparator<Component> comparator() {
        Comparator<Component> byActivity = Comparator.comparingLong(Component::lastActivity).reversed();
        return switch (this) {
            case NAME -> Comparator.comparing(Component::name, String.CASE_INSENSITIVE_ORDER);
            case LATEST_ACTIVITY -> byActivity;
            case FAILED_FIRST -> Comparator.comparing((Component component) -> !component.hasFailure()).thenComparing(byActivity);
            case NONE -> (a, b) -> 0;
        };
    }
}
