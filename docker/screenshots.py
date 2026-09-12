#!/usr/bin/env python3
"""Captures the views of the test controller with Playwright, in light and dark, desktop, full screen and phone.

Runs inside the playwright service of docker/docker-compose.yml and writes PNG files to /out.
"""
import os
import urllib.parse
from playwright.sync_api import sync_playwright

URL = os.environ.get('JENKINS_URL', 'http://jenkins:8080').rstrip('/')
USER = os.environ.get('JENKINS_USER', 'admin')
PASSWORD = os.environ.get('JENKINS_PASSWORD', 'admin')
OUT = os.environ.get('OUT_DIR', '/out')

VIEWS = ['Simple', 'Fan-out', 'BPP', 'Failing', 'Diamond', 'Long', 'Matrix', 'Pipelines', 'Mixed']


def login(page):
    page.goto(URL + '/login')
    page.fill('input[name="j_username"]', USER)
    page.fill('input[name="j_password"]', PASSWORD)
    page.click('button[name="Submit"], input[name="Submit"]')
    page.wait_for_load_state('networkidle')


def settle(page):
    # the page polls api/json and draws the arrows once the model is in
    page.wait_for_selector('.pipeline-component, .dpp-message a', timeout=60000)
    page.wait_for_timeout(1500)


def shoot(page, url, name, full_page=True):
    page.goto(url)
    settle(page)
    page.screenshot(path=os.path.join(OUT, name + '.png'), full_page=full_page)
    print('captured', name)


with sync_playwright() as p:
    browser = p.chromium.launch()
    for scheme in ('light', 'dark'):
        context = browser.new_context(viewport={'width': 1440, 'height': 900}, color_scheme=scheme)
        page = context.new_page()
        login(page)
        for view in VIEWS:
            shoot(page, f'{URL}/job/demo/view/{urllib.parse.quote(view)}/', f'{scheme}-{view.lower()}')
        shoot(page, f'{URL}/view/All%20pipelines/', f'{scheme}-all-pipelines')
        shoot(page, f'{URL}/job/zoo/view/Jenkinsfiles/', f'{scheme}-jenkinsfiles')
        shoot(page, f'{URL}/job/demo/view/Fan-out/?fullscreen=true', f'{scheme}-fanout-fullscreen', full_page=False)
        context.close()
    # the log of one stage of a Pipeline run, where a task of the Pipelines view links to with Pipeline Graph View
    context = browser.new_context(viewport={'width': 1440, 'height': 900}, color_scheme='light')
    page = context.new_page()
    login(page)
    model = page.request.get(f'{URL}/job/demo/view/Pipelines/api/json').json()
    declarative = next(c for c in model['components'] if c['name'] == 'Declarative')
    # the first run, which ran every stage; later ones are restarts that skipped most of them
    first_run = declarative['pipelines'][-1]
    for name, shot in (('Build', 'light-stage-console'), ('Unit', 'light-stage-console-nested')):
        task = next(t for st in first_run['stages'] for t in st['tasks'] if t['name'] == name)
        page.goto(f"{URL}/{task['url']}")
        page.wait_for_load_state('networkidle')
        page.wait_for_timeout(5000)
        page.screenshot(path=os.path.join(OUT, shot + '.png'), full_page=False)
        print('captured', shot)
    context.close()
    # the boards themselves, without the Jenkins frame and clipped to what they draw, in both themes: the sources
    # of the README images. The fan-out chain whole, the first component of the Pipelines board (a Declarative run
    # with parallel stages and an input gate), and one stage with its tasks for the help of the job property.
    def board(page, url, name, target, max_height=None):
        page.goto(url)
        settle(page)
        target = page.locator(target).first if isinstance(target, str) else target
        box = target.bounding_box()
        extent = target.evaluate("""element => {
            const stages = Array.from(element.querySelectorAll('.stage')).map(e => e.getBoundingClientRect());
            const all = Array.from(element.querySelectorAll('.stage, h1, h2, h3')).map(e => e.getBoundingClientRect());
            return stages.length === 0 ? null
                : {right: Math.max(...stages.map(b => b.right)), bottom: Math.max(...all.map(b => b.bottom))};
        }""")
        if extent is None:
            print('skipped', name, '(nothing drawn)')
            return
        height = extent['bottom'] - box['y'] + 12
        if max_height:
            height = min(height, max_height)
        page.screenshot(path=os.path.join(OUT, name + '.png'), full_page=True, clip={
            'x': box['x'], 'y': box['y'], 'width': extent['right'] - box['x'] + 12, 'height': height})
        print('captured', name)

    for scheme in ('light', 'dark'):
        wide = browser.new_context(viewport={'width': 1900, 'height': 1000}, color_scheme=scheme)
        page = wide.new_page()
        login(page)
        board(page, f'{URL}/job/demo/view/Fan-out/', f'board-fanout-{scheme}', '.dpp-view')
        page.locator('.stage_Test').first.screenshot(path=os.path.join(OUT, f'board-stage-{scheme}.png'))
        board(page, f'{URL}/job/demo/view/Pipelines/', f'board-pipelines-{scheme}', 'section.pipeline-component')
        wide.close()
    # larger and more complex boards for the README's gallery: the twenty-chain Performance board (built by
    # docker/run.sh perf), the corpus of Pipeline shapes, a chain that fans out and in, and one Pipeline run that
    # started two jobs from one stage. Wide viewports, since a chain of eight stages is wider than a screen and the
    # part of a board that overflows its container is not painted; the twenty-chain board at half scale, so that
    # both of its columns fit.
    for scheme in ('light', 'dark'):
        wide = browser.new_context(viewport={'width': 2800, 'height': 1200}, color_scheme=scheme)
        page = wide.new_page()
        login(page)
        board(page, f'{URL}/job/zoo/view/Jenkinsfiles/', f'board-shapes-{scheme}', '.dpp-view', max_height=1100)
        board(page, f'{URL}/job/demo/view/Diamond/', f'board-diamond-{scheme}', '.dpp-view')
        fan_out = page.locator('section.pipeline-component', has=page.locator('h1.pipeline-title', has_text='build-fan-out'))
        board(page, f'{URL}/job/zoo/view/Jenkinsfiles/', f'board-chain-of-runs-{scheme}', fan_out)
        wide.close()
        huge = browser.new_context(viewport={'width': 4000, 'height': 1400}, device_scale_factor=0.5, color_scheme=scheme)
        page = huge.new_page()
        login(page)
        board(page, f'{URL}/job/perf/view/Deployment/', f'board-large-{scheme}', '.dpp-view', max_height=1300)
        huge.close()
    # the wall board: the large board on the full screen page, which is dark by design, at half scale
    wall = browser.new_context(viewport={'width': 3800, 'height': 2000}, device_scale_factor=0.5, color_scheme='light')
    page = wall.new_page()
    login(page)
    page.goto(f'{URL}/job/perf/view/Deployment/?fullscreen=true')
    settle(page)
    page.screenshot(path=os.path.join(OUT, 'board-wallboard.png'), full_page=False)
    print('captured board-wallboard')
    wall.close()
    phone = browser.new_context(viewport={'width': 430, 'height': 932}, device_scale_factor=2, is_mobile=True, has_touch=True)
    page = phone.new_page()
    login(page)
    shoot(page, f'{URL}/job/demo/view/Fan-out/', 'phone-fanout', full_page=False)
    shoot(page, f'{URL}/job/demo/view/Simple/?fullscreen=true', 'phone-simple-fullscreen', full_page=False)
    phone.close()
    browser.close()
