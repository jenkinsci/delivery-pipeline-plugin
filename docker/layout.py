#!/usr/bin/env python3
"""Checks the geometry of the boards in a real browser, where HtmlUnit cannot: what the test suite cannot measure.

Runs inside the playwright service of docker/docker-compose.yml as docker/run.sh layout, after docker/run.sh validate
has built the chains. Fails, listing every offender, when

  JENKINS-62159  a panel below a task (description, tests, warnings, promotions: div.infoPanelOuter) is not centred
                 in its stage: its gap to the stage's left edge and to its right edge differ by more than a pixel.
                 The counts of a Pipeline run as a whole (div.pipeline-tests, div.pipeline-analysis) sit beside the
                 run's heading, outside any stage, and are not measured.
"""
import os
import sys
import urllib.parse
from playwright.sync_api import sync_playwright

URL = os.environ.get('JENKINS_URL', 'http://jenkins:8080').rstrip('/')
USER = os.environ.get('JENKINS_USER', 'admin')
PASSWORD = os.environ.get('JENKINS_PASSWORD', 'admin')

# the views whose tasks carry panels: a description and test results (Simple), and the Pipeline runs
VIEWS = ['Simple', 'Fan-out', 'Pipelines']
TOLERANCE = 1

PANEL_GAPS = """() => Array.from(document.querySelectorAll('div.infoPanelOuter'))
        .filter(panel => panel.parentElement.querySelector(':scope > .stage-task'))
        .map(panel => {
    const stage = panel.parentElement.getBoundingClientRect();
    const box = panel.getBoundingClientRect();
    const task = panel.parentElement.querySelector(':scope > .stage-task');
    const taskBox = task.getBoundingClientRect();
    return {
        text: panel.innerText.trim().split('\\n')[0].slice(0, 40),
        left: box.left - stage.left,
        right: stage.right - box.right,
        taskLeft: taskBox ? taskBox.left - stage.left : null,
        taskRight: taskBox ? stage.right - taskBox.right : null,
    };
})"""


def login(page):
    page.goto(URL + '/login')
    page.fill('input[name="j_username"]', USER)
    page.fill('input[name="j_password"]', PASSWORD)
    page.click('button[name="Submit"], input[name="Submit"]')
    page.wait_for_load_state('networkidle')


def settle(page):
    page.wait_for_selector('.pipeline-component, .dpp-message a', timeout=60000)
    page.wait_for_timeout(1500)


failures = []
checked = 0
with sync_playwright() as p:
    browser = p.chromium.launch()
    for name, viewport, suffix in (('desktop', {'width': 1440, 'height': 900}, ''),
                                   ('full screen', {'width': 1440, 'height': 900}, '?fullscreen=true'),
                                   ('phone', {'width': 430, 'height': 932}, '')):
        context = browser.new_context(viewport=viewport)
        page = context.new_page()
        login(page)
        for view in VIEWS:
            page.goto(f'{URL}/job/demo/view/{urllib.parse.quote(view)}/{suffix}')
            settle(page)
            for gap in page.evaluate(PANEL_GAPS):
                checked += 1
                where = f"{view} ({name}) panel '{gap['text']}'"
                if abs(gap['left'] - gap['right']) > TOLERANCE:
                    failures.append(f"{where}: {gap['left']:.1f}px from the left of its stage, "
                                    f"{gap['right']:.1f}px from the right")
                if gap['taskLeft'] is not None and abs(gap['left'] - gap['taskLeft']) > TOLERANCE:
                    failures.append(f"{where}: starts {gap['left']:.1f}px in, its task {gap['taskLeft']:.1f}px")
                if gap['taskRight'] is not None and abs(gap['right'] - gap['taskRight']) > TOLERANCE:
                    failures.append(f"{where}: ends {gap['right']:.1f}px short, its task {gap['taskRight']:.1f}px")
        context.close()
    browser.close()

print(f'checked {checked} panels')
if checked == 0:
    print('no panels on the boards: run docker/run.sh validate first, which builds the chains')
    sys.exit(1)
for failure in failures:
    print('FAIL', failure)
sys.exit(1 if failures else 0)
