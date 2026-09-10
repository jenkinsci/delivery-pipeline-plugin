# Design notes for 2.0

These notes started as an assessment of the 1.6.1 branch (pull request #59) against the Jenkins plugin standards
of September 2026, and became the design of 2.0. Sources at the time: the update center (current LTS 2.568.3),
the suggested-plugin list bundled in core (`jenkins/install/platform-plugins.json`, 19 entries), the core's
detached-plugin list, the Jenkins Java support policy, the jenkins-infra plugin-modernizer recipe catalogue, and
this repository. "Modern" below means what the Jenkins project's own instruments enforce: the plugin parent POM
and BOM, JEP-229 continuous delivery, the plugin health score, the plugin-modernizer recipes, the design-system
and Content-Security-Policy work in core. CloudBees publishes no separate standard; its engineers maintain these.

## 1. Where 1.6.1 stood

| Standard | Source | 1.6.1 | 2.0 |
|---|---|---|---|
| Parent POM 6.x, plugin BOM, `jenkins.baseline` | developer docs; modernizer recipes | done | done, baseline 2.555 |
| Java 21 minimum for current cores | Java support policy; recipe `BaseLineToJenkinsMinimumRequiredJava21Version` | baseline 2.541 still admitted Java 17 | Java 21, both CI legs |
| JUnit 5 and `@WithJenkins`, no PowerMock | recipe `MigrateToJUnit5` | done | done |
| `Jenkinsfile` with `buildPlugin()` on two platforms | recipe `SetupJenkinsfile` | done | done |
| Security scan, dependabot, release-drafter, CODEOWNERS | modernizer recipes | done | done, scan clean |
| JEP-229 continuous delivery | developer docs | workflow and versioning in place | same; needs `cd: enabled` in the permissions file |
| API plugins instead of bundled libraries | recipes `ReplaceLibrariesWithApiPlugin` | jgrapht-core still bundled | nothing bundled |
| Jakarta servlet API, `StaplerRequest2`, Spring Security | core deprecations since 2.475 | done for the plugin's code, warnings left in dependencies | done |
| Content-Security-Policy: no inline scripts or styles | core CSP project | done | done |
| No jQuery or Prototype; design-system look; dark theme | core's removal of both libraries | done | done |
| `@Symbol` names | developer docs | none | view and manual step |
| Localization | developer docs | none | none |

## 2. Dependencies against a fresh 2.568.3

The 19 suggested plugins are the same in 2.568 as in 2.541. Computed from the bundled suggested-plugin list and
the update center's dependency data, the closure a fresh installation gets has 92 plugins.

| Plugin | In a fresh install | Used for | 2.0 |
|---|---|---|---|
| workflow-job, workflow-api, workflow-step-api | yes | Pipeline runs, flow graph, the deprecated `task` step | required |
| pipeline-input-step | yes | proceeding an `input` step from the view | required |
| junit | yes (via email-ext) | test counts on tasks | required |
| token-macro | yes (via email-ext, build-timeout) | version templates, task-name and description macros | required |
| structs | yes | `@Symbol` | required |
| pipeline-graph-analysis | **no**, nothing suggested requires it | stage status and timing of Pipeline runs | required; the one addition |
| workflow-basic-steps, workflow-multibranch, cloudbees-folder, git | yes | steps, branch causes, folders, git causes in 1.6.1 | no longer needed |
| parameterized-trigger | no | blocking sub-projects as downstream tasks | optional |
| build-pipeline-plugin | no | manual triggers of the Build Pipeline plugin | optional; 2.0 has its own manual step |
| promoted-builds, warnings-ng | no | promotions, analysis counts | optional |
| pipeline-model-definition | yes (suggested) | restart a Declarative run from a stage | optional |
| dashboard-view | no | a portlet | dropped |

Pipeline Graph Analysis stays required on purpose. It is a library plugin with no user interface, maintained by the
Pipeline team, installed on more than 200,000 controllers, and the plugin manager installs it alongside. Replacing
it means re-implementing the status logic (pending input, queued agents, warnings, aborts, skipped stages) that
Stage View and Pipeline Graph View rely on, which costs more in bugs than it saves in installs.

## 3. What 2.0 changed and why

1. **Baseline 2.555, Java 21.** 2.555 is the first LTS line that requires Java 21; going lower would mean building
   for Java 17 and for cores the plugin is never tested on.
2. **No bundled libraries.** The stage layout is a longest-path layering in one class; jgrapht and its two
   companions are gone from the hpi.
3. **Deprecations finished.** `Step` and `StepExecution` for the one remaining step, Jakarta and `StaplerRequest2`
   throughout, no Guava.
4. **Fewer options.** The 32 view options of 1.x became 20. Removed: custom CSS URLs and themes (the view follows
   the Jenkins theme), avatars (never rendered by 1.x), relative and console links (links are root-relative,
   running tasks link to the console), the aggregated change log and its grouping pattern. Old configurations load
   without warnings; the removed values are read and ignored.
5. **A cache designed in.** Computed models are kept for a short while and dropped on build, queue and job events;
   a model containing a running build expires in seconds because Pipeline stages change without such events.
   Per-user facts (permissions) are computed while exporting, so one cached model serves every user.
6. **The Pipeline-only view type retired.** A saved one becomes a Delivery Pipeline View with the same components
   when Jenkins loads it, owner and all.
7. **The `task` step deprecated, not removed.** It still runs, and its blocks still show as tasks, so a Jenkinsfile
   that uses it keeps working; nested `stage` blocks do the same without it.
8. **Actions checked on the server.** Start, manual step, rebuild, abort and proceed-input are refused when the view
   does not allow them or the user lacks the permission; buttons only appear for users who may use them.
9. **Descriptions through the markup formatter.** Task descriptions are rendered exactly like job descriptions; HTML
   needs a formatter that allows it.

## 4. The structure

- **One view type, one extension point.** `ComponentSource` builds a component from a job: the freestyle source
  from the core dependency graph plus optional `DownstreamResolver`s, the flow source from the run's flow graph.
- **The model is immutable data with one documented JSON contract** (`se.diabol.jenkins.pipeline.model`), shared by
  the API, the page and the tests. Timestamps are epoch milliseconds, durations milliseconds, URLs root-relative.
- **Only core mechanisms for relations.** Parameterized Trigger, Promoted Builds, Warnings NG and Build Pipeline
  plug in as `@Extension(optional = true)` modules that fail to load, and are left out, when their plugin is absent.
- **The plugin's own manual step**, a post-build action that declares its downstream jobs in the dependency graph
  without triggering them and hands the upstream build's parameters down when a person starts one.
- **One page, one script.** Plain DOM and `fetch`; the wall-board page is the same script on a bare page.
- **Tests as the specification.** HtmlUnit page tests with JavaScript, a Docker test controller with seeded jobs
  that validates every view and action over the API and captures screenshots, and an upgrade exercise from 1.4.2
  on the same Jenkins home.
- **Delivery from the start.** JEP-229 versioning, dependabot, the security scan, release-drafter labels.
