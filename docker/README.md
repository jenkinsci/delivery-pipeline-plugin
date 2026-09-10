# Docker test controller

A Jenkins controller with the plugin from this checkout and a seeded set of jobs that exercise it: chains of
freestyle jobs (a simple three-stage chain with a version, tests and a description; a fan-out with a native manual
step; a Build Pipeline plugin manual trigger with parameters; a failing chain with a disabled job; a diamond; a
long eight-stage chain; a matrix job) and Pipeline jobs (declarative with parallel nested stages and an `input`
gate, scripted with an unstable branch, a skipped stage, a failing branch, a long-running one). Each has a Delivery
Pipeline view; a root view finds them all with a regular expression in two columns.

    docker/run.sh all

compiles the plugin in a Maven container (nothing but Docker is needed; `docker/run.sh test` runs the test suite
the same way), builds the image, starts the controller on
[http://localhost:8080](http://localhost:8080) (users `admin`/`admin` and the read-only `viewer`/`viewer`), waits for
the seeded builds, runs `docker/validate.py` and captures screenshots into `docker/out/`.

`validate.py` checks the JSON of every view (stages, tasks, statuses, layout, manual steps, test counts,
descriptions, paging), the pages and adjuncts, the old-data monitor and the log, the permission gating for the
read-only user, and then performs every action the page can post: the native manual step, the Build Pipeline
trigger, proceeding an `input` step, a rebuild, an abort and a start. It exits non-zero on any failure.

`screenshots.py` runs in the official Playwright image and captures each view in light and dark, the full screen
page and a phone viewport, so the rendering can be reviewed without a browser session on the controller.

Configuration lives in `casc.yaml` (Configuration as Code), the jobs in `jobs.groovy` (Job DSL), the plugin set in
`plugins.txt`. Override the Jenkins version with `JENKINS_VERSION=2.579.1 docker/run.sh build`.

## The Jenkinsfile zoo

`docker/jenkinsfiles/` holds one Pipeline script per shape a Jenkinsfile can take: Declarative and scripted, nested
and parallel stages, a matrix, `when` and `post` sections, failures and skipped stages, retries and timeouts, an input
with parameters, a Pipeline that starts another. The seed creates one job per file in the *Jenkinsfile zoo* folder and
a view over all of them; `validate.py` runs every job and compares the view with the `.expect.json` next to each
script: the run result, the stages and tasks with their statuses, and where an input task links. The screenshots
include the zoo view in both themes.

To add a shape, drop a `name.groovy` with a one-line comment on top (it becomes the job description) and a
`name.expect.json` beside it, then run `docker/run.sh all`.

## Upgrading from 1.4.2

    docker/upgrade.sh

builds a controller with Delivery Pipeline 1.4.2 from the update center, lets it write a configuration that uses
every 1.x option including the removed ones and the old Pipeline-only view type, builds the chain, then starts the
2.0 image on the same Jenkins home and checks with `docker/upgrade_check.py` that the views loaded, kept their
options, migrated the old view type, recognise the manual step, show no old-data warning and render. `KEEP=1`
leaves the upgraded controller running for a look.

