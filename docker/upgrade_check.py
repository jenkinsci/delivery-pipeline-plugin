#!/usr/bin/env python3
"""Checks of the upgrade exercise (docker/upgrade.sh): the 1.4.2 phase writes a configuration, the 2.0 phase loads it.

    python3 docker/upgrade_check.py legacy   # with the 1.4.2 controller running: create the old view type, build, snapshot
    python3 docker/upgrade_check.py new      # with the 2.0 controller running on the same home: check what loaded
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
OUT = os.environ.get('OUT_DIR', os.path.join(os.path.dirname(__file__), 'out'))
FOLDER = 'job/legacy/'
phase = sys.argv[1] if len(sys.argv) > 1 else 'new'
failures = []

AUTH = 'Basic ' + base64.b64encode(f'{USER}:{PASSWORD}'.encode()).decode()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
crumb = {}


def request(path, method='GET', body=None, content_type=None):
    headers = {'Authorization': AUTH}
    if method == 'POST':
        global crumb
        if not crumb:
            status, text = request('crumbIssuer/api/json')
            crumb = json.loads(text) if status == 200 else {'none': True}
        if 'crumb' in crumb:
            headers[crumb['crumbRequestField']] = crumb['crumb']
        headers['Content-Type'] = content_type or 'application/x-www-form-urlencoded'
    req = urllib.request.Request(URL + '/' + path.lstrip('/'), data=body, method=method, headers=headers)
    try:
        with opener.open(req, timeout=60) as r:
            return r.status, r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
        return 0, str(e)


def get_json(path):
    status, text = request(path)
    if status != 200:
        raise RuntimeError(f'{path} -> {status}: {text[:200]}')
    return json.loads(text)


def check(condition, message):
    print(('  ok   ' if condition else '  FAIL ') + message)
    if not condition:
        failures.append(message)


def wait_for(description, predicate, timeout=600):
    print(f'waiting for {description} ...')
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if predicate():
                return True
        except Exception:  # noqa: BLE001
            pass
        time.sleep(5)
    check(False, f'timed out waiting for {description}')
    return False


def plugin():
    for p in get_json('pluginManager/api/json?depth=1&tree=plugins[shortName,version,active]')['plugins']:
        if p['shortName'] == 'delivery-pipeline-plugin':
            return p
    return None


def views():
    return {v['name']: v['_class'] for v in get_json(FOLDER + 'api/json?tree=views[name,_class]')['views']}


def builds(job):
    return get_json(f'{FOLDER}job/{job}/api/json?tree=builds[number,building,result]')['builds']


wait_for('the controller', lambda: request('login')[0] == 200)
wait_for('authentication', lambda: get_json('whoAmI/api/json').get('name') == USER, timeout=120)
wait_for('the seed', lambda: 'Chain' in views())
p = plugin()
print(f'== phase {phase}: plugin {p and p["version"]} active={p and p["active"]}')
os.makedirs(OUT, exist_ok=True)

OLD_VIEW_XML = '''<se.diabol.jenkins.workflow.WorkflowPipelineView>
  <name>Flows</name>
  <description>Pipeline jobs</description>
  <filterExecutors>false</filterExecutors>
  <filterQueue>false</filterQueue>
  <properties class="hudson.model.View$PropertyList"/>
  <updateInterval>9</updateInterval>
  <noOfPipelines>2</noOfPipelines>
  <noOfColumns>1</noOfColumns>
  <sorting>none</sorting>
  <allowPipelineStart>true</allowPipelineStart>
  <allowAbort>true</allowAbort>
  <showChanges>true</showChanges>
  <showAbsoluteDateTime>false</showAbsoluteDateTime>
  <maxNumberOfVisiblePipelines>-1</maxNumberOfVisiblePipelines>
  <componentSpecs>
    <se.diabol.jenkins.workflow.WorkflowPipelineView_-ComponentSpec>
      <name>Flow</name>
      <job>flow</job>
    </se.diabol.jenkins.workflow.WorkflowPipelineView_-ComponentSpec>
  </componentSpecs>
  <linkToConsoleLog>true</linkToConsoleLog>
</se.diabol.jenkins.workflow.WorkflowPipelineView>'''

if phase == 'legacy':
    check(p is not None and p['version'].startswith('1.4.2') and p['active'], 'Delivery Pipeline 1.4.2 is active')
    status, text = request(FOLDER + 'createView?name=Flows', 'POST', OLD_VIEW_XML.encode(), 'application/xml')
    check(status in (200, 302) or 'Flows' in views(), f'the Pipeline-only view type of 1.x was created ({status})')
    check(views().get('Flows') == 'se.diabol.jenkins.workflow.WorkflowPipelineView', f'views under 1.4.2: {views()}')
    for job in ('build', 'flow'):
        if not builds(job):
            request(f'{FOLDER}job/{job}/build', 'POST', b'')
    wait_for('the chain and the Pipeline job to build', lambda: builds('test') and not builds('test')[0]['building']
             and builds('flow') and not builds('flow')[0]['building'])
    check(not builds('deploy'), 'the manual step was not built automatically')
    status, text = request(FOLDER + 'view/Chain/api/json')
    check(status == 200 and '"pipelines"' in text, f'1.4.2 answers its own API ({status})')
    status, cfg = request(FOLDER + 'config.xml')
    open(os.path.join(OUT, 'legacy-folder-config-1.4.2.xml'), 'w').write(cfg)
    check('<showAvatars>true</showAvatars>' in cfg and 'WorkflowPipelineView' in cfg and 'embeddedCss' in cfg,
          '1.4.2 wrote the removed options and the old view type to the folder configuration')
else:
    check(p is not None and p['version'].startswith('2.0') and p['active'], 'Delivery Pipeline 2.0 is active')
    v = views()
    check(v.get('Chain') == 'se.diabol.jenkins.pipeline.DeliveryPipelineView', f'the 1.4.2 view loaded as a Delivery Pipeline View ({v.get("Chain")})')
    check(v.get('Flows') == 'se.diabol.jenkins.pipeline.DeliveryPipelineView', f'the Pipeline-only view became a Delivery Pipeline View ({v.get("Flows")})')
    chain = get_json(FOLDER + 'view/Chain/api/json')
    s = chain['settings']
    check(s['noOfPipelines'] == 3 and s['updateInterval'] == 3 and s['showAggregatedPipeline'] and s['allowManualTriggers']
          and s['allowRebuild'] and s['allowPipelineStart'] and s['allowAbort'] and s['showAbsoluteDateTime']
          and s['pagingEnabled'] and s['showTestResults'] and s['showDescription'], f'the kept options survived: {s}')
    comp = chain['components'][0]
    check(comp.get('error') is None and comp['firstJob']['fullName'] == 'legacy/build', f'the chain resolves ({comp.get("error")})')
    tasks = {t['name']: t for pl in comp['pipelines'] if not pl['aggregated'] for st in pl['stages'] for t in st['tasks']}
    check(tasks['deploy']['manual'] is not None and tasks['deploy']['manual']['enabled'], f'the Build Pipeline manual step is recognised: {tasks["deploy"]["manual"]}')
    check(re.fullmatch(r'1\.0\.\d+', next(pl['version'] for pl in comp['pipelines'] if not pl['aggregated'])), 'the version from 1.4.2 builds shows')
    flows = get_json(FOLDER + 'view/Flows/api/json')
    check(flows['settings']['updateInterval'] == 9 and flows['settings']['noOfPipelines'] == 2 and flows['settings']['allowAbort'],
          f'the old view type kept its options: {flows["settings"]}')
    check(flows['components'][0]['firstJob']['fullName'] == 'legacy/flow' and flows['components'][0]['pipelines'],
          'the old view type shows its Pipeline job with stages')
    status, html = request('administrativeMonitor/OldData/manage')
    rows = [re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', ' ', row)).strip()
            for row in re.findall(r'<tr[^>]*>(.*?)</tr>', html, re.S)]
    rows = [r for r in rows if r and not r.startswith('Type Name')]
    for row in rows:
        print('    old data: ' + row[:200])
    check(status == 200 and 'diabol' not in html and not any(
        word in html for word in ('showAvatars', 'linkRelative', 'linkToConsoleLog', 'embeddedCss', 'fullScreenCss',
                                  'showAggregatedChanges', 'aggregatedChangesGroupingPattern', 'WorkflowPipelineView')),
          'the old data monitor has nothing from the plugin or its removed options')
    status, html = request('manage')
    warning = re.search(r'.{0,160}older format.{0,160}', re.sub(r'<[^>]+>', ' ', html), re.S)
    if warning:
        print('    manage page: ' + re.sub(r'\s+', ' ', warning.group(0)))
    check(not warning, 'no old data warning on the manage page')
    status, log = request('log/all')
    lines = [l for l in re.sub(r'<[^>]+>', '', log).split('\n') if 'diabol' in l]
    check(not lines, f'no plugin lines in the system log {lines[:3]}')
    for name in ('Chain', 'Flows'):
        status, html = request(FOLDER + 'view/' + name + '/')
        check(status == 200 and 'dpp-view' in html, f'the {name} page renders with 2.0')
    status, cfg = request(FOLDER + 'view/Chain/config.xml')
    request(FOLDER + 'view/Chain/config.xml', 'POST', cfg.encode(), 'application/xml')
    status, saved = request(FOLDER + 'view/Chain/config.xml')
    open(os.path.join(OUT, 'legacy-chain-view-config-2.0.xml'), 'w').write(saved)
    check('showAvatars' not in saved and 'embeddedCss' not in saved and 'aggregatedChangesGroupingPattern' not in saved,
          'after a save under 2.0 the removed options are gone from the configuration')
    check('<sorting>se.diabol.jenkins.pipeline.sort.NameComparator</sorting>' in saved, 'the sorting id 1.4.2 wrote is kept')

print(f'== {len(failures)} failures')
for f in failures:
    print('  FAIL ' + f)
sys.exit(1 if failures else 0)
