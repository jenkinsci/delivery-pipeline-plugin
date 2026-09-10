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
package se.diabol.jenkins.pipeline.source;

import hudson.model.ItemGroup;
import hudson.model.Job;
import se.diabol.jenkins.pipeline.model.ViewSettings;

/**
 * What a {@link ComponentSource} needs to build one component.
 *
 * @param name the component's heading
 * @param index the 1-based position of the component in the view
 * @param firstJob the job the pipeline starts with
 * @param lastJob the job the pipeline ends with, or null to follow the chain to its ends
 * @param showUpstream whether to start the pipeline from the jobs upstream of the first job instead
 * @param context the item group the view belongs to, for resolving relative job names
 * @param settings the view's options
 * @param page the 1-based page of pipeline instances to return when paging is on
 * @param paging whether to page at all (paging is off on full screen pages)
 */
public record ComponentRequest(String name, int index, Job<?, ?> firstJob, Job<?, ?> lastJob, boolean showUpstream,
                               ItemGroup<?> context, ViewSettings settings, int page, boolean paging) {
}
