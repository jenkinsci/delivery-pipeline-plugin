# Design notes: the consolidated pipeline

The consolidated pipeline is one feature: a parent above the components of a view that runs the view's pipelines a
few at a time.
These notes record what it does, the decisions behind it, and where later features would attach.

## 1. The case it was built for

A folder of container image trees. Every image has a `build`, a `push` and a `prune` job; a pushed image starts
the builds of the images made from it. All of it runs on one agent, and each `prune` job is blocked (Build Blocker)
while any `build` or `push` is running on that agent, because it prunes the daemon the builds use.

Start every tree at once and there is always a build running, so every prune waits in the queue until the last
build is over, the images pile up meanwhile, and the agent's disk fills. Three trees at a time is what that agent
survives. What was missing was something that starts three, waits until they have drained, prunes included, waits a
little longer, and starts the next three.

Two things follow from the case and shaped the design:

- **Batches, not a sliding window.** "Keep three running" would start a new tree whenever one ends, there would
  again always be a build running, and the prunes would starve exactly as before. A batch ends with the agent idle.
- **Queued counts as not finished.** The held prunes are blocked queue items, not builds. A pipeline is over only
  when none of its tasks is running *or queued*.

## 2. Decisions

| Decision | Choice | Why |
|---|---|---|
| Where the settings live | Three fields of the view: `showConsolidatedPipeline`, `noOfConcurrentPipelines` (3), `sleepBetweenConcurrentPipelines` (10) | They describe the view's pipelines. Job DSL writes them through `configure` until it has methods. The numbers are `Integer` so that a view saved before they existed, which has neither element, gets the defaults; Jenkins reads a view without running field initializers, and 0 seconds is a legitimate sleep. |
| Where the state lives | `ConsolidatedRuns`, one XML file in `$JENKINS_HOME`, keyed by the view's URL | A view is part of its owner's `config.xml`. A seed job replaces the view object while a run is going, and saving the owner on every transition would be both slow and lost on the next seed run. |
| What a run does to a changed view | Nothing: the plan is fixed at the start | A run in the middle of batch 4 of 8 must not reshuffle because a seed ran. Between runs the component shows the current plan. |
| What "finished" means | Asked of `ComponentSource.instance`, the model the view shows | Chains of jobs, runs started with the `build` step, and chains that lead from one into the other all count without the runner knowing about any of them. A source that cannot tell falls back to the first build. |
| The race after a build | Every task's build must be *completed*, not just have a result, and the pipeline must have been quiet for 3 seconds | A build triggers its downstream jobs after its result is known and before it completes; in between, nothing is running and nothing is queued. The quiet time covers triggers the plugin does not know, such as listeners that fire after completion. |
| A failed pipeline | The run goes on | The pipelines of a view are independent; one red tree should not leave twenty unbuilt. |
| Stop | No further batch; running pipelines go on | Aborting builds is what the components' own buttons are for. The run ends when its batch has. |
| Who may start and stop | Whoever may build the first job of *every* pipeline, and only when the view allows starting pipelines | The batches after the first start on a timer thread as the system, so the check has to be complete up front. |
| How it is drawn | A component like any other, first, `index` 0, with `consolidated` set: a stage per batch, a task per pipeline | The page already draws stages, tasks, statuses, progress and arrows, and the cache, the ETag and the fingerprint work unchanged. The one addition to the page is the heading with the summary and the two buttons, and an arrow that leads on to the next row. |
| Rows | The batches wrap to the width of the view's widest pipeline, four at least | Eight batches in one row are 1900 pixels; the parent should not be wider than its children. |
| Threads | One lock guards the runs; readers take a published copy and never the lock | The view model is computed inside the model cache's `compute`, and the cache is emptied by queue listeners from inside the queue lock. A reader that waited for the runner, which schedules builds, could close that circle. |
| The clock | `PeriodicWork` every 2 seconds, free while no run is going | Image builds take minutes; two seconds of latency per transition is nothing, and there is no listener to get wrong. |

## 3. What could come next

The run is deliberately plain: ordered batches, fixed size. Things that would attach without changing its shape:

- **A schedule.** A cron expression on the view, or a build step that starts a view's run, which would also give the
  run a console log and notifications. `startConsolidated` is scriptable today.
- **Stop on failure**, or **retry the failed ones**: a policy on the run, consulted in `ConsolidatedRuns.advance`.
- **Weights or an explicit order**, for trees that are much heavier than others; today the order is the
  configuration's.
- **History.** Only the last run per view is kept. The file could keep the last N with their outcomes and durations.
- **Job DSL methods** for the three options, once a release carries them.
