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
        shoot(page, f'{URL}/job/demo/view/Fan-out/?fullscreen=true', f'{scheme}-fanout-fullscreen', full_page=False)
        context.close()
    # a wide light capture of the fan-out chain, the source of the README screenshot
    wide = browser.new_context(viewport={'width': 1900, 'height': 1000}, color_scheme='light')
    page = wide.new_page()
    login(page)
    shoot(page, f'{URL}/job/demo/view/Fan-out/', 'wide-fanout', full_page=False)
    wide.close()
    phone = browser.new_context(viewport={'width': 430, 'height': 932}, device_scale_factor=2, is_mobile=True, has_touch=True)
    page = phone.new_page()
    login(page)
    shoot(page, f'{URL}/job/demo/view/Fan-out/', 'phone-fanout', full_page=False)
    shoot(page, f'{URL}/job/demo/view/Simple/?fullscreen=true', 'phone-simple-fullscreen', full_page=False)
    phone.close()
    browser.close()
