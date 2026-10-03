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
 * A task that a person has to trigger, as the Build Pipeline plugin's manual trigger defines it.
 *
 * @param upstreamJob full name of the job whose build the trigger continues from
 * @param upstreamBuild number of that build, or null when there is nothing to continue from yet
 * @param enabled whether the trigger can be used right now
 */
@ExportedBean(defaultVisibility = 100)
public record ManualStep(@Exported String upstreamJob, @Exported Integer upstreamBuild, @Exported boolean enabled) {
}
