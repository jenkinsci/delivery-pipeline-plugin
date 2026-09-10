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
 * The immutable view model of a Delivery Pipeline view and, through Stapler's {@code @Exported}, its JSON contract.
 *
 * <p>Everything a {@link se.diabol.jenkins.pipeline.source.ComponentSource} produces is one of these records. They
 * hold no references to Jenkins model objects, so a computed model can be cached and served to any user; the few
 * per-user facts (permissions) are computed while exporting.
 *
 * <p>JSON contract, as served by {@code <view>/api/json}:
 * <pre>
 * {
 *   "components": [ {
 *     "name", "index", "error",
 *     "firstJob": { "fullName", "displayName", "url", "parameterized" },
 *     "paging": { "page", "pageSize", "total", "pages" } | null,
 *     "pipelines": [ {
 *       "id", "version", "timestamp", "aggregated", "jobFullName", "buildNumber", "rebuildable", "commits",
 *       "totalBuildTime",
 *       "permissions": { "build", "cancel" },
 *       "triggers": [ { "type", "description" } ],
 *       "contributors": [ { "name", "url" } ],
 *       "changes": [ { "author": { "name", "url" }, "message", "commitId", "url" } ],
 *       "tests": [ { "name", "url", "total", "failed", "skipped" } ],
 *       "analysis": [ { "name", "url", "high", "normal", "low" } ],
 *       "stages": [ {
 *         "id", "name", "row", "column", "version", "downstream": [ stage id ],
 *         "tasks": [ {
 *           "id", "name", "url", "jobFullName", "buildNumber", "description", "rebuildable", "restart",
 *           "requiresInput", "inputUrl",
 *           "status": { "type", "timestamp", "duration", "progress" },
 *           "manual": { "upstreamJob", "upstreamBuild", "enabled" } | null,
 *           "permissions": { "build", "cancel" },
 *           "tests": [ { "name", "url", "total", "failed", "skipped" } ],
 *           "analysis": [ { "name", "url", "high", "normal", "low" } ],
 *           "promotions": [ { "name", "startTime", "duration", "user", "icon", "params" } ],
 *           "downstream": [ task id ]
 *         } ]
 *       } ]
 *     } ]
 *   } ],
 *   "settings": { ... the display and action options of the view ... },
 *   "serverTime": epoch millis
 * }
 * </pre>
 * Timestamps are epoch milliseconds and durations are milliseconds; URLs are relative to the Jenkins root URL.
 * <p>A pipeline's {@code rebuildable} runs the whole pipeline again; a task's {@code rebuildable} builds the task's
 * job again, or when {@code restart} names a stage, restarts the Pipeline run from that stage. Static analysis
 * results sit on the task whose build produced them, or on the pipeline when they belong to a Pipeline run as a whole;
 * test results sit on the task that recorded them, or on the pipeline when they were recorded outside the tasks shown.
 * A task waiting at an input step is proceeded by posting to {@code proceedInput} with its id as {@code task}, or,
 * when {@code inputUrl} is set because the step has parameters, by following that link.
 */
package se.diabol.jenkins.pipeline.model;
