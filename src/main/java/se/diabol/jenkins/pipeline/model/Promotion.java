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

import java.util.List;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * A promotion of a build.
 *
 * @param name the promotion process
 * @param startTime epoch milliseconds when the promotion ran
 * @param duration milliseconds between the build start and the promotion
 * @param user who promoted, or "anonymous"
 * @param icon path of the promotion's star icon relative to the Jenkins root
 * @param params the promotion's parameters as "name: value" strings
 */
@ExportedBean(defaultVisibility = 100)
public record Promotion(@Exported String name, @Exported long startTime, @Exported long duration, @Exported String user,
                        @Exported String icon, @Exported List<String> params) {
    public Promotion {
        params = List.copyOf(params);
    }
}
