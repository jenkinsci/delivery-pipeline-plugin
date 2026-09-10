# Changelog

Release notes are generated on GitHub for every release:

<https://github.com/jenkinsci/delivery-pipeline-plugin/releases/>

### 2.0.0

2.0 is a rewrite of the plugin on the Jenkins 2.555 LTS baseline and Java 21. Existing views, jobs and Job DSL
scripts keep working. Two changes can affect description templates, scripts and wall boards; read those first.

#### Behaviour changes

**Task descriptions go through the markup formatter.** Descriptions produced by the *Delivery Pipeline
configuration* templates are rendered by the markup formatter configured under *Manage Jenkins → Security*, exactly
like job descriptions. With the default *Plain text* formatter, HTML in a template is shown as text. To keep using
HTML, configure a formatter that allows it, for example the OWASP Markup Formatter plugin with *Safe HTML*.

**The JSON contract and the action endpoints changed.** Anything that reads `<view>/api/json` or posts actions to
the view needs an update:

- Timestamps are epoch milliseconds instead of `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` strings; durations are milliseconds.
  The response carries `serverTime` for clock differences.
- Renamed fields: `triggeredBy` is `triggers`, `buildId` is `buildNumber`, `link` is `url`, `percentage` is
  `progress` (inside `status`), `manualStep` is `manual`, `testResults` is `tests`, `staticAnalysisResults` is
  `analysis`. Tasks also carry `jobFullName` and the caller's `permissions`; the view's display options come along
  as `settings`.
- The actions moved from `<view>/api/manualStep`, `api/rebuildStep`, `api/inputStep` and `api/abortBuild` to
  `<view>/manualStep`, `rebuild`, `proceedInput` and `abort`. They take the same `project`, `upstream` and `buildId`
  parameters, require POST with a crumb (1.x also accepted GET), and are refused when the view does not allow the
  action or the user lacks the permission: Build for manual steps and rebuilds, Cancel for aborts, and the input
  step's own submitter rule for `proceedInput`.

The full contract is documented in the `se.diabol.jenkins.pipeline.model` package.

#### Requirements

- Jenkins 2.555.3 or newer and Java 21; 2.555 is the first LTS line that requires Java 21.
- Required plugins are ones a fresh Jenkins installs with its suggested set (Pipeline: Job, Pipeline: API,
  Pipeline: Input Step, JUnit, Token Macro, Structs) plus Pipeline Graph Analysis, which the plugin manager installs
  alongside. Build Pipeline, Promoted Builds, Warnings Next Generation, Parameterized Trigger, Pipeline:
  Declarative and Pipeline Graph View are optional and activate when present.

#### Removed

Old views keep loading; these settings are read and ignored, without old-data entries.

- Custom CSS URLs and themes (`embeddedCss`, `fullScreenCss`, `theme`): the view follows the Jenkins theme, dark
  themes included.
- `showAvatars`, `linkRelative` and `linkToConsoleLog`: links are always relative to the Jenkins root, and running
  tasks always link to their console.
- The aggregated change log (`showAggregatedChanges`, `aggregatedChangesGroupingPattern`).
- The Dashboard View portlet.
- The *Delivery Pipeline View for Jenkins Pipelines* view type: a saved one becomes a Delivery Pipeline View with the
  same components when Jenkins loads it.

#### Deprecated

- The `task` Pipeline step. It still runs, its block still shows as a task, and it prints a reminder to use a nested
  `stage` instead. It will be removed in a later release.
- In Job DSL, `showAvatars`, `useTheme`, `useRelativeLinks` and `linkToConsoleLog` of `deliveryPipelineView` are
  deprecated and `allowAbort` is added (jenkinsci/job-dsl-plugin#2646).

#### New

- One view type for chains of jobs and for Pipeline jobs. Pipeline runs are read from the flow graph: every
  top-level stage is a stage, and the stages nested in it or else its parallel branches (declarative `parallel` and
  `matrix` included) are its tasks. No `task` step is needed.
- A *Delivery Pipeline manual step* post-build action (`deliveryPipelineManualStep` in Job DSL), so manual steps no
  longer need the Build Pipeline plugin. Build Pipeline's manual triggers are still recognised when it is installed.
- Computed models are cached; a build or queue event drops only the models that show the job in question, and a
  job being created, reconfigured, renamed or deleted empties the cache, so many wall boards polling one view cost
  little more than one even on a busy controller. The system properties
  `se.diabol.jenkins.pipeline.cache.ModelCache.idleSeconds` (default 30) and `activeSeconds` (default 2) tune how
  long an idle and an active view may be served from the cache; they are read on every request, so they can be
  changed at runtime. The README's "Performance and caching" section explains when to change them.
- A page script without libraries or page globals that works under a Content-Security-Policy and follows the
  Jenkins theme. Stages are laid out by the longest path from the first stage, so arrows always point to the right.
- Actions are checked on the server against the view's settings and the user's permissions; buttons appear only for
  users who may use them.
- Pipeline runs can be run again with the same parameters from the button next to the run's heading. Declarative
  runs can also be restarted from any of their stages, from the button of the stage's task, through the "Restart
  from Stage" feature of the Pipeline: Declarative plugin when it is installed.
- Test results recorded by a `junit` step inside a stage or a parallel branch show on that task. Warnings Next
  Generation results of a Pipeline run belong to the run as a whole and show under the run's heading.
- Only the stages a Jenkinsfile declares are shown; the stages Declarative Pipeline generates around them
  ("Declarative: Checkout SCM", "Post Actions", "Tool Install") are left out, and test results recorded there, or
  anywhere outside the tasks shown, are listed under the run's heading. A run waiting in the queue is shown before it
  starts, laid out like the previous run. A task waiting at an input step with parameters links to the input page,
  since only that page can collect them; one without parameters is proceeded from the view.
- When the Pipeline Graph View plugin is installed (it is one of the plugins a fresh Jenkins suggests), every stage,
  nested stage and parallel branch links to its own log in that plugin's console page. Without it, a running stage
  links to the run's console and a finished one to the run.
- A Pipeline that starts other jobs with the `build` step shows the runs it started as part of the same pipeline:
  their stages follow the stage that started them, named "job: stage", with an arrow from that stage, and the runs
  they start in turn follow them; a started job that is not a Pipeline is one task. This needs the Pipeline: Build
  Step plugin at version 539 or newer, which records the started runs. Everyone who can see the view sees every job
  the chain reaches, as with chains of jobs; acting on one still needs the permission on that job.
- A Declarative stage skipped because an earlier stage failed shows as not built, like one skipped by a `when`
  condition; matrix cells are named by their axes, a stage inside a scripted parallel branch is shown as
  "branch: stage", and a `parallel` nested inside a branch shows its inner branches as tasks, named the same way. A
  run without any stage, as a scripted Pipeline of plain steps is, is shown as one task named after its job, with
  the run's status and test results. The Docker suite carries a corpus of Jenkinsfile shapes
  (`docker/jenkinsfiles/`) with the stages, tasks and statuses the view must show for each.

#### Upgrading

Install the new version; no configuration change is needed. Then check description templates that use HTML (see
above) and update anything that reads the view's JSON. Upgrades from 1.4.2 and from 1.6 were exercised; the Docker
test controller under `docker/` reproduces the 1.4.2 exercise with `docker/upgrade.sh`.

Releases before 2.0 are listed on the GitHub releases page.
