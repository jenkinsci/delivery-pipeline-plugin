#!/usr/bin/env python3
"""Renders docs/dpp_logo.svg and docs/dpp_logo_dark.svg to PNG at double resolution with a transparent background.

Runs in the Playwright image the Docker suite uses, which needs nothing but Docker:

    docker run --rm -v "$PWD/docs:/work" mcr.microsoft.com/playwright/python:v1.59.0-noble \
        sh -c "pip install -q --break-system-packages playwright==1.59.0 && python3 /work/render-logo.py"

The wordmark uses the Inter web font, fetched at render time, so the result does not depend on the fonts of the
machine. QuickLook and other thumbnailers render SVG onto opaque white, which is not what a logo on a dark page needs.
"""
import pathlib
from playwright.sync_api import sync_playwright

HERE = pathlib.Path(__file__).parent
PAGE = """<!doctype html><html><head><meta charset="utf-8">
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;600&display=swap" rel="stylesheet">
<style>html,body{margin:0;background:transparent}svg{display:block}</style></head><body>%s</body></html>"""

with sync_playwright() as playwright:
    browser = playwright.chromium.launch()
    page = browser.new_context(viewport={'width': 846, 'height': 149}, device_scale_factor=2).new_page()
    for name in ('dpp_logo', 'dpp_logo_dark'):
        page.set_content(PAGE % (HERE / f'{name}.svg').read_text(), wait_until='networkidle')
        page.wait_for_timeout(1000)
        if not page.evaluate("document.fonts.check('600 48px Inter')"):
            raise SystemExit('the Inter font did not load; is the network available?')
        page.screenshot(path=str(HERE / f'{name}.png'), omit_background=True,
                        clip={'x': 0, 'y': 0, 'width': 846, 'height': 149})
        print('rendered', name)
    browser.close()
