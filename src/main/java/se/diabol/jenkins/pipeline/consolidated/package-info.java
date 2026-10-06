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
 * The consolidated pipeline of a view: a parent that runs the view's pipelines a few at a time.
 *
 * <p>{@link se.diabol.jenkins.pipeline.consolidated.ConsolidatedRuns} runs them and keeps the runs on disk, a
 * {@link se.diabol.jenkins.pipeline.consolidated.ConsolidatedRun} is one run with its pipelines in batches, and
 * {@link se.diabol.jenkins.pipeline.consolidated.ConsolidatedComponent} draws a run as a component of the view, a
 * stage per batch and a task per pipeline, which the page shows with what it has for stages and tasks. The view
 * itself holds the three options and the two actions, and hands over the pipelines it is configured with.
 *
 * <p>Whether a pipeline has come to an end is asked of its
 * {@link se.diabol.jenkins.pipeline.source.ComponentSource#instance instance}, the same model the view shows, so
 * that whatever the view follows counts: chains of jobs, runs started with the {@code build} step, and chains
 * that lead from one into the other.
 */
package se.diabol.jenkins.pipeline.consolidated;
