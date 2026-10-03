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

import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * Where a component's pipelines are in the list of all of them.
 *
 * @param page the current page, starting at 1
 * @param pageSize pipelines per page
 * @param total number of pipelines there are
 */
@ExportedBean(defaultVisibility = 100)
public record Paging(@Exported int page, @Exported int pageSize, @Exported int total) {

    @Exported
    public int pages() {
        return pageSize <= 0 ? 1 : Math.max(1, (total + pageSize - 1) / pageSize);
    }

    /** Index of the first pipeline of the page. */
    public int offset() {
        return Math.max(0, (page - 1) * pageSize);
    }
}
