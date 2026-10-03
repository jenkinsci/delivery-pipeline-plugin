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
/**
 * The component source for chains of jobs with upstream/downstream dependencies (freestyle, matrix and other
 * {@link hudson.model.AbstractProject}s). The chain is read from the core dependency graph plus any
 * {@link se.diabol.jenkins.pipeline.freestyle.DownstreamResolver}; manual steps come from a
 * {@link se.diabol.jenkins.pipeline.freestyle.ManualTriggerProvider}.
 */
package se.diabol.jenkins.pipeline.freestyle;
