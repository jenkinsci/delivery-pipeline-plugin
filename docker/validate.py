#!/usr/bin/env python3
"""Validates a running test controller (docker/docker-compose.yml) against the Delivery Pipeline plugin.

Waits for the seeded jobs to build, then checks the JSON of every view, the pages, the permission gating and
each action the page can post (start, manual step, proceed input, rebuild, abort). Exits non-zero on any failure.

    JENKINS_URL=http://localhost:8080 JENKINS_USER=admin JENKINS_PASSWORD=admin python3 docker/validate.py
"""
import base64
import http.cookiejar
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

URL = os.environ.get('JENKINS_URL', 'http://localhost:8080').rstrip('/')
USER = os.environ.get('JENKINS_USER', 'admin')
PASSWORD = os.environ.get('JENKINS_PASSWORD', 'admin')
VIEWER = os.environ.get('VIEWER_USER', 'viewer')
VIEWER_PASSWORD = os.environ.get('VIEWER_PASSWORD', 'viewer')
DEMO = 'job/demo/'

failures = []
passed = 0


def check(condition, message):
    global passed
    if condition:
        passed += 1
        print('  ok   ' + message)
    else:
        failures.append(message)
        print('  FAIL ' + message)


class Client:
    def __init__(self, user, password):
        self.auth = 'Basic ' + base64.b64encode(f'{user}:{password}'.encode()).decode()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.crumb = None

    def request(self, path, method='GET', data=None, timeout=60):
        url = path if path.startswith('http') else URL + '/' + path.lstrip('/')
        headers = {'Authorization': self.auth}
        body = None
        if method == 'POST':
            if self.crumb is None:
                status, text = self._raw(URL + '/crumbIssuer/api/json', 'GET', None, {'Authorization': self.auth}, timeout)
                self.crumb = json.loads(text) if status == 200 else {}
            if self.crumb:
                headers[self.crumb['crumbRequestField']] = self.crumb['crumb']
            headers['Content-Type'] = 'application/x-www-form-urlencoded'
            body = urllib.parse.urlencode(data or {}).encode()
        return self._raw(url, method, body, headers, timeout)

    def _raw(self, url, method, body, headers, timeout):
        req = urllib.request.Request(url, data=body, method=method, headers=headers)
        try:
            with self.opener.open(req, timeout=timeout) as r:
                return r.status, r.read().decode('utf-8', 'replace')
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode('utf-8', 'replace')
        except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
            return 0, str(e)

    def get(self, path):
        return self.request(path)

    def json(self, path):
        status, text = self.get(path)
        if status != 200:
            raise RuntimeError(f'{path} -> {status}: {text[:200]}')
        return json.loads(text)

    def post(self, path, data=None):
        return self.request(path, 'POST', data)


admin = Client(USER, PASSWORD)


def wait_for(description, predicate, timeout=600, interval=5):
    print(f'waiting for {description} ...')
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if predicate():
                return True
        except Exception as e:  # noqa: BLE001 - the controller may still be starting
            last = e
        time.sleep(interval)
    failures.append(f'timed out waiting for {description}')
    print(f'  FAIL timed out waiting for {description}')
    return False


def job(path):
    return admin.json(f'{DEMO}job/{path}/api/json?tree=builds[number,building,result],nextBuildNumber,inQueue')


def last_build(path):
    builds = job(path)['builds']
    return builds[0] if builds else None


def finished(path):
    b = last_build(path)
    return b is not None and not b['building'] and b['result'] is not None


def view_json(view, params=''):
    return admin.json(f'{view}api/json{params}')


def tasks_of(component, stage_name):
    for pipeline in component['pipelines']:
        if not pipeline['aggregated']:
            for stage in pipeline['stages']:
                if stage['name'] == stage_name:
                    return stage['tasks']
    return []


def newest(component):
    for pipeline in component['pipelines']:
        if not pipeline['aggregated']:
            return pipeline
    return None


def stage_map(pipeline):
    return {stage['name']: stage for stage in pipeline['stages']}


# ---------------------------------------------------------------- startup
print(f'== controller {URL}')
wait_for('the controller to answer', lambda: admin.get('login')[0] == 200, timeout=600)
wait_for('authentication', lambda: admin.json('whoAmI/api/json').get('name') == USER, timeout=120)
wait_for('the seed to have created the views', lambda: any(v['name'] == 'Mixed' for v in admin.json(DEMO + 'api/json?tree=views[name]')['views']), timeout=600)
plugin = next(p for p in admin.json('pluginManager/api/json?depth=1&tree=plugins[shortName,version,active]')['plugins'] if p['shortName'] == 'delivery-pipeline-plugin')
print(f'plugin {plugin["version"]} active={plugin["active"]}')
check(plugin['active'], 'plugin is active')

# First builds of every chain and Pipeline job, unless the controller has run them already.
FIRST_JOBS = ['simple-build', 'fanout-build', 'bpp-build', 'failing-build', 'diamond-a', 'long-1', 'matrix-build',
              'pipeline-declarative', 'pipeline-scripted', 'pipeline-skipped', 'pipeline-failing', 'pipeline-long']
for name in FIRST_JOBS:
    info = admin.json(f'{DEMO}job/{name}/api/json?tree=builds[number],inQueue,property[_class]')
    if info['builds'] or info['inQueue']:
        continue
    parameterized = any('ParametersDefinitionProperty' in p.get('_class', '') for p in info['property'])
    status, _ = admin.post(f'{DEMO}job/{name}/' + ('buildWithParameters' if parameterized else 'build'))
    check(status in (200, 201), f'started the first build of {name} (status {status})')

wait_for('the simple chain to finish', lambda: finished('simple-deploy'), timeout=900)
wait_for('the fan-out chain to reach the package step', lambda: finished('fanout-package'), timeout=900)
wait_for('the failing chain to fail', lambda: last_build('failing-test') and last_build('failing-test')['result'] == 'FAILURE', timeout=600)
wait_for('the diamond chain to finish', lambda: finished('diamond-e'), timeout=600)
wait_for('the long chain to finish', lambda: finished('long-8'), timeout=600)
wait_for('the matrix job to finish', lambda: finished('matrix-test'), timeout=600)
wait_for('the bpp build to finish', lambda: finished('bpp-build'), timeout=600)
wait_for('the scripted pipelines to finish', lambda: finished('pipeline-scripted') and finished('pipeline-skipped') and finished('pipeline-failing'), timeout=600)
wait_for('the declarative pipeline to wait for input', lambda: any(
    t['requiresInput'] for t in tasks_of(view_json(DEMO + 'view/Pipelines/')['components'][0], 'Approve')), timeout=600)

# ---------------------------------------------------------------- views
print('== views')
views = [v for v in admin.json(DEMO + 'api/json?tree=views[name,url,_class]')['views'] if 'diabol' in v['_class']]
root_views = [v for v in admin.json('api/json?tree=views[name,url,_class]')['views'] if 'diabol' in v['_class']]
check(len(views) == 9, f'nine Delivery Pipeline views in the folder (found {len(views)})')
check(len(root_views) == 1, 'the regular-expression view exists at the root')
for v in views + root_views:
    rel = DEMO + 'view/' + urllib.parse.quote(v['name']) + '/' if v in views else 'view/' + urllib.parse.quote(v['name']) + '/'
    data = view_json(rel)
    errors = [c['error'] for c in data['components'] if c.get('error')]
    check(not errors, f'view {v["name"]}: no component errors {errors}')
    check('settings' in data and 'serverTime' in data, f'view {v["name"]}: settings and serverTime present')

simple = view_json(DEMO + 'view/Simple/')['components'][0]
latest = newest(simple)
stages = stage_map(latest)
check(list(stages) == ['Build', 'Test', 'Deploy'], f'simple: stages in order {list(stages)}')
check(re.fullmatch(r'1\.0\.\d+', latest['version'] or ''), f'simple: version from the version contributor ({latest["version"]})')
check([t['name'] for t in stages['Build']['tasks']] == ['compile'], 'simple: task named by the job property')
tests = stages['Test']['tasks'][0]['tests']
check(tests and tests[0]['total'] == 3 and tests[0]['failed'] == 1, f'simple: test counts on the test task {tests}')
deploy_task = stages['Deploy']['tasks'][0]
check(deploy_task['description'] and '<b>' in deploy_task['description'] and '1.0.' in deploy_task['description'],
      f'simple: description with the version, rendered as safe HTML ({deploy_task["description"]})')
check(latest['totalBuildTime'] > 0, 'simple: total build time computed')
check(simple['pipelines'][0]['aggregated'] and all(s['version'] for s in simple['pipelines'][0]['stages']), 'simple: aggregated row shows a version per stage')
check(all(t['status']['type'] == 'SUCCESS' for s in latest['stages'] for t in s['tasks']) or stages['Test']['tasks'][0]['status']['type'] == 'UNSTABLE',
      'simple: statuses are success (unstable allowed for the failing test case)')
check(stages['Deploy']['tasks'][0]['rebuildable'] and not stages['Build']['tasks'][0]['rebuildable'], 'simple: rebuildable on the downstream task only')

fanout = view_json(DEMO + 'view/Fan-out/')['components'][0]
fl = newest(fanout)
fs = stage_map(fl)
check(sorted(t['name'] for t in fs['Test']['tasks']) == ['integration', 'lint', 'unit'], 'fan-out: three tasks share the Test stage')
production = next(t for t in fs['Deploy']['tasks'] if t['name'] == 'production')
check(production['manual'] is not None and production['manual']['upstreamJob'] == 'demo/fanout-package',
      f'fan-out: production is a manual step of package ({production["manual"]})')
check(production['manual']['enabled'] and production['status']['type'] == 'IDLE', 'fan-out: manual step enabled once package finished, not built yet')
check(fs['Deploy']['row'] == 0 and any(s['name'] == 'Verify' for s in fl['stages']), 'fan-out: verify stage behind the manual step is laid out')

bpp = view_json(DEMO + 'view/BPP/')['components'][0]
bpp_deploy = stage_map(newest(bpp))['Deploy']['tasks'][0]
check(bpp_deploy['manual'] and bpp_deploy['manual']['enabled'], 'bpp: Build Pipeline manual trigger recognised and enabled')

failing = view_json(DEMO + 'view/Failing/')['components'][0]
fst = stage_map(newest(failing))
check(fst['Test']['tasks'][0]['status']['type'] == 'FAILED', 'failing: failed task')
check(fst['Deploy']['tasks'][0]['status']['type'] == 'IDLE', 'failing: task behind the failure stays idle')
check(fst['Docs']['tasks'][0]['status']['type'] == 'DISABLED', 'failing: disabled job shows as disabled')
check(failing['lastActivity'] > 0, 'failing: last activity reported')

diamond = view_json(DEMO + 'view/Diamond/')['components'][0]
dst = stage_map(newest(diamond))
positions = {name: (s['row'], s['column']) for name, s in dst.items()}
check(positions == {'Build': (0, 0), 'Test A': (0, 1), 'Test B': (1, 1), 'Package': (0, 2), 'Release': (0, 3)}, f'diamond: layout {positions}')
check(sorted(dst['Build']['downstream']) == ['Test A', 'Test B'] and dst['Test B']['downstream'] == ['Package'], 'diamond: stage arrows')

long_view = view_json(DEMO + 'view/Long/')['components'][0]
check(len(newest(long_view)['stages']) == 8 and all(s['row'] == 0 for s in newest(long_view)['stages']), 'long: eight stages on one row')
check(long_view['paging'] and long_view['paging']['pageSize'] == 2, f'long: paging with page size 2 ({long_view["paging"]})')

matrix = view_json(DEMO + 'view/Matrix/')['components'][0]
matrix_tasks = sorted(t['name'] for t in stage_map(newest(matrix))['Test']['tasks'])
check(matrix_tasks == ['test on'], f'matrix: the multi-configuration project is one task, named by its property {matrix_tasks}')

pipelines = {c['name']: c for c in view_json(DEMO + 'view/Pipelines/')['components']}
decl = stage_map(newest(pipelines['Declarative']))
check(list(decl) == ['Build', 'Test', 'Approve'], f'declarative: stages reached so far while paused {list(decl)}')
check(sorted(t['name'] for t in decl['Test']['tasks']) == ['Integration', 'Unit'], 'declarative: parallel nested stages are tasks')
approve = decl['Approve']['tasks'][0]
check(approve['status']['type'] == 'PAUSED_PENDING_INPUT' and approve['requiresInput'], 'declarative: input step shows as paused')
scripted = stage_map(newest(pipelines['Scripted']))
check({t['name']: t['status']['type'] for t in scripted['Test']['tasks']} == {'ok': 'SUCCESS', 'flaky': 'UNSTABLE'}, 'scripted: parallel branches with an unstable one')
build_tests = scripted['Build']['tasks'][0]['tests']
check(build_tests and build_tests[0]['total'] == 2 and build_tests[0]['failed'] == 1, f'scripted: test counts of the junit step on the Build stage {build_tests}')
ok_tests = next(t for t in scripted['Test']['tasks'] if t['name'] == 'ok')['tests']
check(ok_tests and ok_tests[0]['total'] == 1 and ok_tests[0]['failed'] == 0, f'scripted: test counts on the ok branch {ok_tests}')
check(not scripted['Package']['tasks'][0]['tests'], 'scripted: no test counts on a stage that recorded none')
scripted_run = newest(pipelines['Scripted'])
check(scripted_run['analysis'] and scripted_run['analysis'][0]['normal'] == 1, f'scripted: warnings of the run sit on the pipeline {scripted_run["analysis"]}')
check(not any(t['analysis'] for st in scripted_run['stages'] for t in st['tasks']), 'scripted: no warnings on the tasks')
check(scripted_run['rebuildable'] and scripted_run['jobFullName'] == 'demo/pipeline-scripted' and scripted_run['buildNumber'] == 1, 'scripted: a finished run can be run again')
check(not any(t['rebuildable'] for st in scripted_run['stages'] for t in st['tasks']), 'scripted: stages of a scripted run cannot be restarted')
check(not newest(pipelines['Long running'])['rebuildable'], 'long running pipeline: a running Pipeline run cannot be run again yet')
skipped = stage_map(newest(pipelines['Skipped']))
check(skipped['Deploy']['tasks'][0]['status']['type'] == 'NOT_BUILT', 'skipped: when-skipped stage is not built')
check(list(skipped) == ['Build', 'Deploy'], f'skipped: the synthetic post stage is not shown {list(skipped)}')
post_tests = newest(pipelines['Skipped'])['tests']
check(post_tests and post_tests[0]['total'] == 1, f'skipped: tests recorded in the post section sit on the run {post_tests}')
pfail = stage_map(newest(pipelines['Failing']))
check({t['name']: t['status']['type'] for t in pfail['Test']['tasks']} == {'ok': 'SUCCESS', 'bad': 'FAILED'}, 'failing pipeline: failed branch')
long_running = newest(pipelines['Long running'])
check(long_running and any(t['status']['type'] == 'RUNNING' for s in long_running['stages'] for t in s['tasks']), 'long running pipeline: a running task with progress')

mixed = view_json(DEMO + 'view/Mixed/')
check(len(mixed['components']) == 2 and mixed['components'][1]['firstJob']['fullName'] == 'demo/pipeline-declarative', 'mixed: chained jobs and a Pipeline job side by side')

everything = view_json('view/All%20pipelines/')
check(everything['settings']['noOfColumns'] == 2 and len(everything['components']) >= 6, f'root regular-expression view: {len(everything["components"])} components in two columns')
names = [c['name'] for c in everything['components']]
check(names == sorted(names, key=lambda n: -next(c['lastActivity'] for c in everything['components'] if c['name'] == n)), 'root view: sorted by last activity')

# ---------------------------------------------------------------- pages
print('== pages')
status, html = admin.get(DEMO + 'view/Simple/')
check(status == 200 and 'class="dpp-view"' in html, 'view page renders the container')
adjuncts = set(re.findall(r'(/adjuncts/[^"\']+/pipeline\.(?:js|css))', html))
check(len(adjuncts) == 2 and all(admin.get(a)[0] == 200 for a in adjuncts), 'script and stylesheet adjuncts are served')
status, html = admin.get(DEMO + 'view/Simple/?fullscreen=true')
check(status == 200 and 'dpp-fullscreen' in html and 'side-panel' not in html, 'full screen page is bare')
status, html = admin.get(DEMO + 'view/Simple/configure')
check(status == 200 and 'noOfPipelines' in html and 'showAvatars' not in html, 'configure page shows the 2.0 options')
status, html = admin.get('administrativeMonitor/OldData/manage')
check(status == 200 and 'diabol' not in html, 'old data monitor has nothing from the plugin')
status, log = admin.get('log/all')
plugin_lines = [l for l in re.sub(r'<[^>]+>', '', log).split('\n') if 'diabol' in l]
check(not plugin_lines, f'system log has no lines from the plugin {plugin_lines[:3]}')
t0 = time.time(); admin.get('view/All%20pipelines/api/json'); t1 = time.time(); admin.get('view/All%20pipelines/api/json'); t2 = time.time()
print(f'  info root view api/json: first {t1 - t0:.2f}s, second {t2 - t1:.2f}s')
check(t2 - t1 < 2, 'cached api/json answers within two seconds')

# ---------------------------------------------------------------- permissions
print('== permissions')
viewer = Client(VIEWER, VIEWER_PASSWORD)
data = json.loads(viewer.get(DEMO + 'view/Failing/api/json')[1])
task = stage_map(newest(data['components'][0]))['Test']['tasks'][0]
check(task['permissions'] == {'build': False, 'cancel': False}, 'read-only user gets no build or cancel permission on tasks')
status, _ = viewer.post(DEMO + 'view/Failing/rebuild', {'project': 'demo/failing-test', 'buildId': '1'})
check(status == 403, f'read-only user cannot rebuild (status {status})')
locked = admin.json(DEMO + 'view/Simple/api/json')
check(locked['settings']['allowAbort'], 'allowAbort set by the seed through configure')

# ---------------------------------------------------------------- actions
print('== actions')
before = last_build('fanout-deploy-production')
status, text = admin.post(DEMO + 'view/Fan-out/manualStep', {'project': 'demo/fanout-deploy-production', 'upstream': 'demo/fanout-package', 'buildId': str(production['manual']['upstreamBuild'])})
check(status == 200, f'native manual step accepted (status {status} {text[:120]})')
wait_for('the production deploy and the smoke tests', lambda: finished('fanout-deploy-production') and finished('fanout-smoke'), timeout=300)
prod = admin.json(f'{DEMO}job/fanout-deploy-production/lastBuild/api/json?tree=number,result,actions[causes[upstreamProject,upstreamBuild,userId],parameters[name,value]]')
causes = [c for a in prod['actions'] for c in a.get('causes', [])]
params = {p['name']: p['value'] for a in prod['actions'] for p in a.get('parameters', [])}
check(any(c.get('upstreamProject') == 'demo/fanout-package' for c in causes) and any(c.get('userId') == USER for c in causes), f'manual step build has upstream and user causes {causes}')
check(params.get('TARGET') == 'eu-west', f'manual step build got the default parameter {params}')
fanout_after = view_json(DEMO + 'view/Fan-out/')['components'][0]
instance = next((pl for pl in fanout_after['pipelines'] if not pl['aggregated'] and any(
    t['name'] == 'production' and t['buildNumber'] == prod['number'] for s in pl['stages'] for t in s['tasks'])), None)
check(instance is not None and instance['id'] == fl['id'],
      f'production build shows in the pipeline instance the manual step belonged to ({instance and instance["id"]} vs {fl["id"]})')
after_stages = stage_map(instance) if instance else {}
check(bool(after_stages) and after_stages['Verify']['tasks'][0]['status']['type'] == 'SUCCESS', 'smoke tests ran automatically after the manual step, in the same instance')

status, text = admin.post(DEMO + 'view/BPP/manualStep', {'project': 'demo/bpp-deploy', 'upstream': 'demo/bpp-build', 'buildId': '1'})
check(status == 200, f'Build Pipeline manual trigger accepted (status {status})')
wait_for('the bpp deploy', lambda: finished('bpp-deploy'), timeout=300)
bpp_params = {p['name']: p['value'] for a in admin.json(f'{DEMO}job/bpp-deploy/lastBuild/api/json?tree=actions[parameters[name,value]]')['actions'] for p in a.get('parameters', [])}
check(bpp_params.get('VERSION') == '1.0', f'Build Pipeline trigger passed the current build parameters {bpp_params}')

status, text = admin.post(DEMO + 'view/Pipelines/proceedInput', {'project': 'demo/pipeline-declarative', 'buildId': '1'})
check(status == 200, f'input step proceeded (status {status} {text[:120]})')
wait_for('the declarative pipeline to finish', lambda: finished('pipeline-declarative'), timeout=300)
check(last_build('pipeline-declarative')['result'] == 'SUCCESS', 'declarative pipeline succeeded after the input was proceeded')
decl_after = stage_map(newest({c['name']: c for c in view_json(DEMO + 'view/Pipelines/')['components']}['Declarative']))
check(list(decl_after) == ['Build', 'Test', 'Approve', 'Deploy'] and decl_after['Deploy']['tasks'][0]['status']['type'] == 'SUCCESS',
      f'declarative: all four stages after proceeding {list(decl_after)}')
restart_points = {name: st['tasks'][0]['restart'] for name, st in decl_after.items()}
check(restart_points == {'Build': 'Build', 'Test': 'Test', 'Approve': 'Approve', 'Deploy': 'Deploy'} and all(t['rebuildable'] for st in decl_after.values() for t in st['tasks']),
      f'declarative: every task can restart the run from its stage {restart_points}')
status, text = admin.post(DEMO + 'view/Pipelines/rebuild', {'project': 'demo/pipeline-declarative', 'buildId': '1', 'stage': 'Deploy'})
check(status == 200, f'restart from stage accepted (status {status} {text[:120]})')
wait_for('the restarted declarative run', lambda: len(job('pipeline-declarative')['builds']) >= 2 and finished('pipeline-declarative'), timeout=300)
restarted = stage_map(newest({c['name']: c for c in view_json(DEMO + 'view/Pipelines/')['components']}['Declarative']))
restarted_types = {name: st['tasks'][0]['status']['type'] for name, st in restarted.items()}
check(restarted_types.get('Build') == 'NOT_BUILT' and restarted_types.get('Approve') == 'NOT_BUILT' and restarted_types.get('Deploy') == 'SUCCESS',
      f'restarted run skipped the stages before Deploy {restarted_types}')
status, text = admin.post(DEMO + 'view/Pipelines/rebuild', {'project': 'demo/pipeline-scripted', 'buildId': '1'})
check(status == 200, f'running a Pipeline again accepted (status {status} {text[:120]})')
wait_for('the scripted pipeline to run again', lambda: len(job('pipeline-scripted')['builds']) >= 2 and finished('pipeline-scripted'), timeout=300)
again_causes = [c for a in admin.json(f'{DEMO}job/pipeline-scripted/lastBuild/api/json?tree=actions[causes[userId]]')['actions'] for c in a.get('causes', [])]
check(any(c.get('userId') == USER for c in again_causes), f'the new run of the scripted pipeline was started by the user {again_causes}')
status, text = viewer.post(DEMO + 'view/Pipelines/rebuild', {'project': 'demo/pipeline-scripted', 'buildId': '1'})
check(status == 403, f'read-only user cannot run a Pipeline again (status {status})')

status, text = admin.post(DEMO + 'view/Failing/rebuild', {'project': 'demo/failing-test', 'buildId': '1'})
check(status == 200, f'rebuild accepted (status {status})')
wait_for('the rebuild of the failing test', lambda: len(job('failing-test')['builds']) >= 2 and finished('failing-test'), timeout=300)
check(len(job('failing-test')['builds']) >= 2, 'rebuild produced a new build')

running = last_build('pipeline-long')
if not running or not running['building']:
    admin.post(f'{DEMO}job/pipeline-long/build')
    wait_for('the long pipeline to start', lambda: last_build('pipeline-long') and last_build('pipeline-long')['building'], timeout=300)
    running = last_build('pipeline-long')
status, text = admin.post(DEMO + 'view/Pipelines/abort', {'project': 'demo/pipeline-long', 'buildId': str(running['number'])})
check(status == 200, f'abort accepted (status {status} {text[:120]})')
wait_for('the long pipeline to stop', lambda: not last_build('pipeline-long')['building'], timeout=120)
check(last_build('pipeline-long')['result'] == 'ABORTED', 'long pipeline was aborted from the view')

builds_before = len(job('simple-build')['builds'])
status, _ = admin.post(f'{DEMO}job/simple-build/build?delay=0sec')
check(status in (200, 201), f'start button endpoint accepted (status {status})')
wait_for('the new simple build', lambda: len(job('simple-build')['builds']) > builds_before, timeout=120)

# ---------------------------------------------------------------- summary
print(f'== {passed} checks passed, {len(failures)} failed')
for f in failures:
    print('  FAIL ' + f)
sys.exit(1 if failures else 0)
