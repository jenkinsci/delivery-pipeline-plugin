#!/usr/bin/env python3
"""Load test: many viewers polling one large board while a deployment runs through the chains behind it.

Runs against the test controller (docker/run.sh up) as docker/run.sh perf. The seed carries a Performance folder
with chains of eight jobs and four Declarative Pipelines behind one board, the Deployment view. Three phases:

  baseline    one viewer polls the quiet board every second for 20 seconds, sending the ETag back like the page
  deployment  a build of every chain and Pipeline is started, and PERF_VIEWERS viewers poll the board every
              PERF_INTERVAL seconds, the way the page does, until the deployment has run through (or PERF_DURATION)
  storm       the same viewers poll without any pause for PERF_STORM seconds, on the board at rest again, and ask
              for full responses every time, to find the export ceiling rather than the 304 one

Every viewer logs in once through the login form and polls with its session cookie, keeps one connection open and
accepts compressed responses, like a browser tab (PERF_GZIP=0 asks for plain ones), so the size column is bytes on
the wire. Sending the password with every request instead would have Jenkins hash it every time, which costs more
than the board does. The report lists requests, throughput, latency percentiles,
response size, errors and the controller's CPU per phase, the CPU summed over cores as docker stats reports it; the
run fails on any error, or on a p95 above PERF_P95_MAX_MS during the deployment. Reads Server/User/Password from
JENKINS_URL, JENKINS_USER and JENKINS_PASSWORD (or ADMIN_PASSWORD).
"""
import base64
import http.client
import http.cookiejar
import json
import math
import os
import random
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

URL = os.environ.get('JENKINS_URL', 'http://localhost:8080').rstrip('/')
USER = os.environ.get('JENKINS_USER', 'admin')
PASSWORD = os.environ.get('JENKINS_PASSWORD', os.environ.get('ADMIN_PASSWORD', 'admin'))
VIEWERS = int(os.environ.get('PERF_VIEWERS', '50'))
INTERVAL = float(os.environ.get('PERF_INTERVAL', '5'))
DURATION = int(os.environ.get('PERF_DURATION', '300'))
STORM = int(os.environ.get('PERF_STORM', '20'))
P95_MAX_MS = int(os.environ.get('PERF_P95_MAX_MS', '3000'))
GZIP = os.environ.get('PERF_GZIP', '1') != '0'
CONDITIONAL = os.environ.get('PERF_CONDITIONAL', '1') != '0'
FOLDER = 'job/perf/'
VIEW = FOLDER + 'view/Deployment/'
POLL = VIEW + 'api/json?page=1&component=0&fullscreen=false'
AUTH = 'Basic ' + base64.b64encode(f'{USER}:{PASSWORD}'.encode()).decode()
ORIGIN = urllib.parse.urlsplit(URL)
PREFIX = ORIGIN.path.rstrip('/')


class Client:
    """The validator's client: cookies for the session, a crumb for POSTs."""

    def __init__(self):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.crumb = None

    def request(self, path, method='GET', data=None, timeout=60):
        headers = {'Authorization': AUTH}
        body = None
        if method == 'POST':
            if self.crumb is None:
                status, text = self._raw(URL + '/crumbIssuer/api/json', 'GET', None, headers, timeout)
                self.crumb = json.loads(text) if status == 200 else {}
            if self.crumb:
                headers[self.crumb['crumbRequestField']] = self.crumb['crumb']
            headers['Content-Type'] = 'application/x-www-form-urlencoded'
            body = urllib.parse.urlencode(data or {}).encode()
        return self._raw(URL + '/' + path.lstrip('/'), method, body, headers, timeout)

    def _raw(self, url, method, body, headers, timeout):
        req = urllib.request.Request(url, data=body, method=method, headers=headers)
        try:
            with self.opener.open(req, timeout=timeout) as r:
                return r.status, r.read().decode('utf-8', 'replace')
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode('utf-8', 'replace')
        except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
            return 0, str(e)

    def json(self, path):
        status, text = self.request(path)
        if status != 200:
            raise RuntimeError(f'{path} -> {status}: {text[:200]}')
        return json.loads(text)

    def post(self, path, data=None):
        return self.request(path, 'POST', data)


def session_cookie():
    """The session a browser gets from the login form, as a Cookie header value: the form carries a crumb and a
    session of its own, which the login needs, and the login answers with the session the polls then use."""
    kind = http.client.HTTPSConnection if ORIGIN.scheme == 'https' else http.client.HTTPConnection
    connection = kind(ORIGIN.hostname, ORIGIN.port, timeout=60)
    connection.request('GET', PREFIX + '/login')
    response = connection.getresponse()
    page = response.read().decode('utf-8', 'replace')
    cookies = {}
    for name, value in response.getheaders():
        if name.lower() == 'set-cookie':
            cookie = value.split(';', 1)[0]
            cookies[cookie.split('=', 1)[0]] = cookie
    form = {'j_username': USER, 'j_password': PASSWORD, 'from': '/'}
    crumb = re.search(r'name="Jenkins-Crumb"[^>]*value="([^"]+)"', page)
    if crumb:
        form['Jenkins-Crumb'] = crumb.group(1)
    connection.request('POST', PREFIX + '/j_spring_security_check', body=urllib.parse.urlencode(form).encode(),
                       headers={'Content-Type': 'application/x-www-form-urlencoded', 'Cookie': '; '.join(cookies.values())})
    response = connection.getresponse()
    response.read()
    for name, value in response.getheaders():
        if name.lower() == 'set-cookie':
            cookie = value.split(';', 1)[0]
            cookies[cookie.split('=', 1)[0]] = cookie
    location = response.getheader('Location') or ''
    connection.close()
    if response.status not in (302, 303) or 'loginError' in location or not any(k.startswith('JSESSIONID') for k in cookies):
        raise RuntimeError(f'login failed: HTTP {response.status} {location}')
    return '; '.join(cookies.values())


class Viewer(threading.Thread):
    """One browser tab: polls the board's JSON over a kept-alive connection every interval seconds, or without pause."""

    def __init__(self, interval, stop_at, samples, errors, conditional):
        super().__init__(daemon=True)
        self.interval = interval
        self.stop_at = stop_at
        self.samples = samples
        self.errors = errors
        self.conditional = conditional
        self.etag = None

    def run(self):
        connection = None
        time.sleep(random.uniform(0, min(self.interval, 5)))  # tabs are not opened in the same second
        try:
            cookie = session_cookie()
        except Exception as e:  # noqa: BLE001
            self.errors.append(f'login: {str(e)[:80]}')
            return
        while time.time() < self.stop_at:
            if connection is None:
                kind = http.client.HTTPSConnection if ORIGIN.scheme == 'https' else http.client.HTTPConnection
                connection = kind(ORIGIN.hostname, ORIGIN.port, timeout=60)
            started = time.perf_counter()
            try:
                headers = {'Cookie': cookie, 'Accept': 'application/json'}
                if GZIP:
                    headers['Accept-Encoding'] = 'gzip'
                if self.conditional and self.etag:
                    headers['If-None-Match'] = self.etag
                connection.request('GET', PREFIX + '/' + POLL, headers=headers)
                response = connection.getresponse()
                body = response.read()
                elapsed = time.perf_counter() - started
                if response.status == 200:
                    self.etag = response.getheader('ETag')
                    self.samples.append((elapsed, len(body), False))
                elif response.status == 304:
                    self.samples.append((elapsed, 0, True))
                else:
                    self.errors.append(f'HTTP {response.status}')
            except Exception as e:  # noqa: BLE001 - every failure is a finding
                elapsed = time.perf_counter() - started
                self.errors.append(f'{type(e).__name__}: {str(e)[:80]}')
                connection.close()
                connection = None
            pause = self.interval - elapsed
            if pause > 0:
                time.sleep(pause)
        if connection is not None:
            connection.close()


class CpuSampler(threading.Thread):
    """Samples the controller container's CPU through docker stats every few seconds; silent without docker."""

    def __init__(self):
        super().__init__(daemon=True)
        self.readings = []  # (time, cpu percent)
        self.stopped = False
        self.container = None
        try:
            here = os.path.dirname(os.path.abspath(__file__))
            out = subprocess.run(['docker', 'compose', '-f', os.path.join(here, 'docker-compose.yml'), 'ps', '-q', 'jenkins'],
                                 capture_output=True, text=True, timeout=30)
            self.container = out.stdout.strip() or None
        except Exception:  # noqa: BLE001 - docker is optional here
            self.container = None

    def run(self):
        while self.container and not self.stopped:
            try:
                out = subprocess.run(['docker', 'stats', '--no-stream', '--format', '{{.CPUPerc}}', self.container],
                                     capture_output=True, text=True, timeout=30)
                match = re.search(r'([\d.]+)%', out.stdout)
                if match:
                    self.readings.append((time.time(), float(match.group(1))))
            except Exception:  # noqa: BLE001
                pass
            time.sleep(3)

    def between(self, start, end):
        values = [cpu for at, cpu in self.readings if start <= at <= end]
        return (sum(values) / len(values), max(values)) if values else None


class Phase:
    def __init__(self, name, viewers, interval, conditional=True):
        self.name, self.viewers, self.interval, self.conditional = name, viewers, interval, conditional and CONDITIONAL
        self.samples, self.errors = [], []
        self.started = self.ended = 0

    def run(self, seconds, until=None, min_seconds=30):
        stop_at = time.time() + seconds
        threads = [Viewer(self.interval, stop_at, self.samples, self.errors, self.conditional) for _ in range(self.viewers)]
        self.started = time.time()
        for thread in threads:
            thread.start()
        if until is not None:
            while time.time() < stop_at:
                time.sleep(10)
                if time.time() - self.started >= min_seconds and until():
                    for thread in threads:
                        thread.stop_at = 0
                    break
        for thread in threads:
            thread.join()
        self.ended = time.time()
        return self

    def percentile(self, p):
        values = sorted(s[0] for s in self.samples)
        if not values:
            return 0
        k = (len(values) - 1) * p
        low, high = math.floor(k), math.ceil(k)
        return (values[low] + (values[high] - values[low]) * (k - low)) * 1000

    def row(self, cpu):
        n = len(self.samples)
        full = [s for s in self.samples if not s[2]]
        seconds = max(self.ended - self.started, 1e-9)
        kb = (sum(s[1] for s in full) / len(full) / 1024) if full else 0
        cpu_text = f'{cpu[0]:.0f} / {cpu[1]:.0f}' if cpu else 'n/a'
        return (f'{self.name:<12}{self.viewers:>8}{n:>9}{n - len(full):>6}{n / seconds:>8.1f}{self.percentile(0.5):>8.0f}'
                f'{self.percentile(0.95):>8.0f}{self.percentile(0.99):>8.0f}{self.percentile(1):>8.0f}'
                f'{kb:>7.0f}{len(self.errors):>7}  {cpu_text}')


def main():
    admin = Client()
    status, _ = admin.request(FOLDER + 'api/json')
    if status != 200:
        print(f'the Performance folder is missing ({status}); start the controller with docker/run.sh up first', file=sys.stderr)
        return 2
    jobs = admin.json(FOLDER + 'api/json?tree=jobs[name]')['jobs']
    chains = sorted(int(m.group(1)) for j in jobs for m in [re.fullmatch(r'chain(\d+)-1', j['name'])] if m)
    pipelines = sorted(j['name'] for j in jobs if j['name'].startswith('pipeline'))
    board = admin.json(POLL)
    tasks = sum(len(stage['tasks']) for c in board['components'] for p in c['pipelines'] for stage in p['stages'])
    print(f'== board {VIEW}: {len(board["components"])} components, {tasks} tasks, '
          f'{len(json.dumps(board)) / 1024:.0f} KB of JSON; {len(chains)} chains of 8 jobs, {len(pipelines)} Pipelines')
    print(f'== {VIEWERS} viewers every {INTERVAL:g} s, deployment capped at {DURATION} s, storm {STORM} s, '
          f'{"compressed" if GZIP else "plain"} responses, ETags {"sent back" if CONDITIONAL else "ignored"}, '
          f'a session cookie per viewer')
    cpu = CpuSampler()
    cpu.start()

    baseline = Phase('baseline', 1, 1).run(20)

    started_at_ms = int(time.time() * 1000) - 1000
    for n in chains:
        code, _ = admin.post(FOLDER + f'job/chain{n}-1/build')
        if code not in (200, 201):
            print(f'could not start chain {n}: HTTP {code}', file=sys.stderr)
    for name in pipelines:
        code, _ = admin.post(FOLDER + f'job/{name}/build')
        if code not in (200, 201):
            print(f'could not start {name}: HTTP {code}', file=sys.stderr)

    def deployment_done():
        listing = admin.json(FOLDER + 'api/json?tree=jobs[name,lastBuild[timestamp,building]]')['jobs']
        ends = [j for j in listing if j['name'].endswith('-8') or j['name'].startswith('pipeline')]
        return all(j.get('lastBuild') and j['lastBuild']['timestamp'] >= started_at_ms and not j['lastBuild']['building']
                   for j in ends)

    deployment = Phase('deployment', VIEWERS, INTERVAL).run(DURATION, until=deployment_done)
    finished = deployment_done()
    storm = Phase('storm', VIEWERS, 0, conditional=False).run(STORM)
    cpu.stopped = True

    print()
    print(f'{"phase":<12}{"viewers":>8}{"requests":>9}{"304s":>6}{"req/s":>8}{"p50 ms":>8}{"p95 ms":>8}{"p99 ms":>8}'
          f'{"max ms":>8}{"KB":>7}{"errors":>7}  CPU % avg / max')
    for phase in (baseline, deployment, storm):
        print(phase.row(cpu.between(phase.started, phase.ended)))
    print()
    print(f'deployment: {"ran through" if finished else "still running when the cap was reached"} in '
          f'{deployment.ended - deployment.started:.0f} s')
    failures = []
    for phase in (baseline, deployment, storm):
        if phase.errors:
            counts = {}
            for error in phase.errors:
                counts[error] = counts.get(error, 0) + 1
            # a storm of tens of thousands of requests may lose one in a thousand; the other phases may lose none
            tolerated = len(phase.samples) // 1000 if phase is storm else 0
            print(f'{phase.name}: {len(phase.errors)} errors {counts}' + (' (tolerated)' if len(phase.errors) <= tolerated else ''))
            if len(phase.errors) > tolerated:
                failures.append(f'{phase.name}: {len(phase.errors)} errors {counts}')
    if deployment.percentile(0.95) > P95_MAX_MS:
        failures.append(f'deployment: p95 {deployment.percentile(0.95):.0f} ms above {P95_MAX_MS} ms')
    if baseline.samples and deployment.samples and deployment.percentile(0.5) > 10 * baseline.percentile(0.5) + 200:
        failures.append(f'deployment: median {deployment.percentile(0.5):.0f} ms is more than ten times the quiet '
                        f'baseline {baseline.percentile(0.5):.0f} ms; is the cache doing its job?')
    for failure in failures:
        print('  FAIL', failure)
    print('== perf:', 'FAILED' if failures else 'PASSED')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
