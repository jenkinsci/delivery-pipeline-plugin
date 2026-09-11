/*
 * Delivery Pipeline view: polls the view's api/json and renders the pipelines.
 *
 * Plain DOM, fetch and SVG. Everything the server sends becomes a text node or an attribute value; the one thing
 * rendered as HTML is the task description, which the server has already passed through the Jenkins markup
 * formatter. The script reads all it needs from the .dpp-view element's data attributes and uses no page globals.
 *
 * Syntax stays at ES2015 without classes, rest parameters or optional chaining so that the HtmlUnit based tests can
 * run it.
 */
(function () {
    'use strict';

    var REQUEST_TIMEOUT = 60000;
    var CONNECTOR_STUB = 25;
    var CONNECTOR_GAP = 2;
    var CONNECTOR_ANCHOR_OFFSET = 37;
    var PAGE_WINDOW = 10;
    var viewCount = 0;

    /* ------------------------------------------------------------------ statuses outside tasks */

    // how bad a status is; only these can be worse than what the tasks of a stage or run already show
    var BADNESS = {UNSTABLE: 1, CANCELLED: 2, FAILED: 3};
    var OUTCOME = {UNSTABLE: ['went unstable', 'Unstable'], CANCELLED: ['was aborted', 'Aborted'], FAILED: ['failed', 'Failed']};

    function badness(status) {
        return status && BADNESS[status.type] ? BADNESS[status.type] : 0;
    }

    function worstBadness(tasks) {
        var worst = 0;
        (tasks || []).forEach(function (task) {
            worst = Math.max(worst, badness(task.status));
        });
        return worst;
    }

    /* ------------------------------------------------------------------ DOM helpers */

    function append(node, children) {
        if (children === null || children === undefined || children === false) {
            return;
        }
        if (Array.isArray(children)) {
            children.forEach(function (child) {
                append(node, child);
            });
            return;
        }
        if (typeof children === 'object' && children.nodeType !== undefined) {
            node.appendChild(children);
            return;
        }
        node.appendChild(document.createTextNode(String(children)));
    }

    function el(tag, attributes, children) {
        var node = document.createElement(tag);
        if (attributes) {
            Object.keys(attributes).forEach(function (name) {
                var value = attributes[name];
                if (value !== null && value !== undefined && value !== false) {
                    node.setAttribute(name, String(value));
                }
            });
        }
        append(node, children);
        return node;
    }

    function svgEl(tag, attributes) {
        var node = document.createElementNS('http://www.w3.org/2000/svg', tag);
        if (attributes) {
            Object.keys(attributes).forEach(function (name) {
                node.setAttribute(name, String(attributes[name]));
            });
        }
        return node;
    }

    function clear(node) {
        while (node.firstChild) {
            node.removeChild(node.firstChild);
        }
    }

    function multiline(value) {
        var fragment = document.createDocumentFragment();
        String(value === null || value === undefined ? '' : value).split(/\r?\n/).forEach(function (line, index) {
            if (index > 0) {
                fragment.appendChild(el('br'));
            }
            fragment.appendChild(document.createTextNode(line));
        });
        return fragment;
    }

    /** A name reduced to characters that are safe in a class name, so that stages can be styled by name. */
    function cssIdentifier(value) {
        return String(value).replace(/[^A-Za-z0-9_-]/g, '_');
    }

    /* ------------------------------------------------------------------ icons, drawn in currentColor */

    function icon(name, label) {
        var svg = svgEl('svg', {class: 'dpp-icon dpp-icon-' + name, viewBox: '0 0 24 24', width: '16', height: '16',
            role: 'img', 'aria-label': label, focusable: 'false'});
        var title = svgEl('title');
        title.appendChild(document.createTextNode(label));
        svg.appendChild(title);
        var stroke = {fill: 'none', stroke: 'currentColor', 'stroke-width': '2', 'stroke-linecap': 'round',
            'stroke-linejoin': 'round'};
        if (name === 'clock') {
            svg.appendChild(svgEl('circle', {cx: '12', cy: '12', r: '9', fill: 'none', stroke: 'currentColor', 'stroke-width': '2'}));
            svg.appendChild(svgEl('path', Object.assign({d: 'M12 7v5l3 3'}, stroke)));
        } else if (name === 'play') {
            svg.appendChild(svgEl('path', {d: 'M7 4l13 8-13 8z', fill: 'currentColor'}));
        } else if (name === 'rebuild') {
            svg.appendChild(svgEl('path', Object.assign({d: 'M20 12a8 8 0 1 1-2.3-5.7'}, stroke)));
            svg.appendChild(svgEl('path', {d: 'M20 3v5h-5z', fill: 'currentColor'}));
        } else if (name === 'stop') {
            svg.appendChild(svgEl('rect', {x: '5', y: '5', width: '14', height: '14', rx: '2', fill: 'currentColor'}));
        } else if (name === 'input') {
            svg.appendChild(svgEl('path', Object.assign({d: 'M4 12h12M11 6l6 6-6 6'}, stroke)));
            svg.appendChild(svgEl('path', Object.assign({d: 'M20 4v16'}, stroke)));
        }
        return svg;
    }

    /* ------------------------------------------------------------------ dates and durations */

    function pad(number) {
        return (number < 10 ? '0' : '') + number;
    }

    function formatAbsolute(date) {
        return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
            + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds());
    }

    function formatRelative(date, now) {
        var seconds = Math.round((date.getTime() - now.getTime()) / 1000);
        var past = seconds <= 0;
        var abs = Math.abs(seconds);
        var phrase;
        if (abs < 45) {
            phrase = 'a few seconds';
        } else if (abs < 90) {
            phrase = 'a minute';
        } else if (abs < 45 * 60) {
            phrase = Math.round(abs / 60) + ' minutes';
        } else if (abs < 90 * 60) {
            phrase = 'an hour';
        } else if (abs < 22 * 3600) {
            phrase = Math.round(abs / 3600) + ' hours';
        } else if (abs < 36 * 3600) {
            phrase = 'a day';
        } else if (abs < 26 * 86400) {
            phrase = Math.round(abs / 86400) + ' days';
        } else if (abs < 45 * 86400) {
            phrase = 'a month';
        } else if (abs < 320 * 86400) {
            phrase = Math.round(abs / (30.4 * 86400)) + ' months';
        } else if (abs < 548 * 86400) {
            phrase = 'a year';
        } else {
            phrase = Math.round(abs / (365 * 86400)) + ' years';
        }
        return past ? phrase + ' ago' : 'in ' + phrase;
    }

    function formatDate(millis, absolute) {
        if (!millis) {
            return '';
        }
        var date = new Date(millis);
        return absolute ? formatAbsolute(date) : formatRelative(date, new Date());
    }

    function formatDuration(millis) {
        var seconds = Math.floor(Math.max(0, millis) / 1000);
        var minutes = Math.floor(seconds / 60);
        var hours = Math.floor(minutes / 60);
        seconds = seconds % 60;
        minutes = minutes % 60;
        return (hours ? hours + ' h ' : '') + (hours || minutes ? minutes + ' min ' : '') + seconds + ' sec';
    }

    /* ------------------------------------------------------------------ requests */

    function encodeForm(params) {
        return Object.keys(params).map(function (name) {
            var value = params[name];
            return encodeURIComponent(name) + '=' + encodeURIComponent(value === null || value === undefined ? '' : String(value));
        }).join('&');
    }

    function currentQuery() {
        var result = {page: 1, component: 1};
        var search = window.location.search.replace(/^\?/, '');
        search.split('&').forEach(function (pair) {
            var parts = pair.split('=');
            var value = parseInt(decodeURIComponent(parts[1] || ''), 10);
            if ((parts[0] === 'page' || parts[0] === 'component') && !isNaN(value) && value > 0) {
                result[parts[0]] = value;
            }
        });
        return result;
    }

    /* ------------------------------------------------------------------ the view */

    function View(root) {
        var data = root.dataset;
        this.root = root;
        this.index = viewCount++;
        this.viewUrl = data.viewUrl;
        this.rootUrl = data.rootUrl || '';
        this.fullscreen = data.fullscreen === 'true';
        this.crumbField = data.crumbField;
        this.crumbValue = data.crumbValue;
        this.errorDiv = root.querySelector('.dpp-error');
        this.messageDiv = root.querySelector('.dpp-message');
        this.columnsDiv = root.querySelector('.dpp-columns');
        var query = currentQuery();
        this.page = query.page;
        this.component = query.component;
        this.settings = {noOfColumns: 1, updateInterval: 5};
        this.lastRendered = null;
        this.relations = [];
        this.liveTasks = [];
        this.timer = null;
    }

    View.prototype.start = function () {
        var self = this;
        this.root.addEventListener('click', function (event) {
            self.onClick(event);
        });
        window.addEventListener('resize', function () {
            self.drawConnectors();
        });
        this.poll();
    };

    View.prototype.url = function (relative) {
        return this.rootUrl + '/' + relative;
    };

    View.prototype.apiUrl = function () {
        return this.url(this.viewUrl + 'api/json?page=' + this.page + '&component=' + this.component
            + '&fullscreen=' + this.fullscreen);
    };

    View.prototype.schedule = function () {
        var self = this;
        window.clearTimeout(this.timer);
        this.timer = window.setTimeout(function () {
            self.poll();
        }, Math.max(1, this.settings.updateInterval) * 1000);
    };

    View.prototype.poll = function () {
        var self = this;
        if (document.hidden) {
            this.schedule();
            return;
        }
        var controller = typeof AbortController === 'function' ? new AbortController() : null;
        var timeout = controller ? window.setTimeout(function () {
            controller.abort();
        }, REQUEST_TIMEOUT) : null;
        var request = {credentials: 'same-origin', cache: 'no-store', headers: {Accept: 'application/json'}};
        if (controller) {
            request.signal = controller.signal;
        }
        fetch(this.apiUrl(), request).then(function (response) {
            if (!response.ok) {
                throw new Error(response.status + ' ' + response.statusText);
            }
            return response.json();
        }).then(function (data) {
            window.clearTimeout(timeout);
            self.refresh(data);
            self.schedule();
        }).catch(function (error) {
            window.clearTimeout(timeout);
            self.showError('Error communicating to server! ' + (error && error.message ? error.message : ''));
            self.schedule();
        });
    };

    View.prototype.showError = function (message) {
        clear(this.errorDiv);
        this.errorDiv.appendChild(document.createTextNode(message));
        this.errorDiv.hidden = false;
    };

    View.prototype.hideError = function () {
        this.errorDiv.hidden = true;
        clear(this.errorDiv);
    };

    /** What must be the same for the page to be left alone: everything but the parts that tick while a build runs. */
    function fingerprint(components) {
        return JSON.stringify(components, function (key, value) {
            if (key === 'progress' || key === 'duration' || key === 'totalBuildTime') {
                return undefined;
            }
            return value;
        });
    }

    View.prototype.refresh = function (data) {
        this.hideError();
        this.settings = data.settings || this.settings;
        var components = data.components || [];
        var print = fingerprint(components);
        if (this.lastRendered !== print) {
            this.render(components);
            this.lastRendered = print;
        } else {
            this.updateLive(components);
        }
        this.drawConnectors();
    };

    View.prototype.render = function (components) {
        var self = this;
        var count = Math.max(1, parseInt(this.settings.noOfColumns, 10) || 1);
        clear(this.columnsDiv);
        // Set through the CSSOM rather than a style attribute in markup, so that a Content-Security-Policy
        // without 'unsafe-inline' styles is not needed.
        this.columnsDiv.style.gridTemplateColumns = 'repeat(' + count + ', minmax(max-content, 1fr))';
        this.columns = [];
        for (var i = 0; i < count; i++) {
            var column = el('div', {class: 'dpp-column'});
            this.columnsDiv.appendChild(column);
            this.columns.push(column);
        }
        this.relations = [];
        this.liveTasks = [];
        clear(this.messageDiv);
        if (components.length === 0) {
            append(this.messageDiv, ['No pipelines configured or found. Please review the ',
                el('a', {href: this.url(this.viewUrl + 'configure')}, 'configuration'), '.']);
        }
        components.forEach(function (component, index) {
            self.columns[index % count].appendChild(self.renderComponent(component, index));
        });
        equalizeStageHeights(this.root);
    };

    View.prototype.renderComponent = function (component, index) {
        var self = this;
        var section = el('section', {class: 'pipeline-component', 'data-component': component.index});
        section.appendChild(this.renderHeading(component, index));
        if (component.error) {
            section.appendChild(el('div', {class: 'pipeline-error', role: 'alert'}, component.error));
            return section;
        }
        section.appendChild(this.renderPagination(component));
        var pipelines = component.pipelines || [];
        if (pipelines.length === 0) {
            section.appendChild(el('p', {class: 'dpp-empty'}, 'No builds done yet.'));
        }
        pipelines.forEach(function (pipeline, i) {
            if (pipeline.aggregated) {
                if (pipelines.length > 1) {
                    section.appendChild(el('h2', {class: 'pipeline-heading'}, 'Aggregated view'));
                }
            } else {
                section.appendChild(self.renderPipelineHeading(pipeline));
                if (self.settings.showTotalBuildTime) {
                    var total = el('h3', {class: 'total-build-time'}, 'Total build time: ' + formatDuration(pipeline.totalBuildTime));
                    self.liveTasks.push({element: total, key: component.index + '|' + pipeline.id, kind: 'total'});
                    section.appendChild(total);
                }
                if (pipeline.changes && pipeline.changes.length > 0) {
                    section.appendChild(renderChangeLog(pipeline.changes));
                }
                var runTests = renderTests(self, self.settings, pipeline);
                if (runTests) {
                    section.appendChild(el('div', {class: 'pipeline-tests'}, runTests));
                }
                var analysis = renderAnalysis(self, self.settings, pipeline);
                if (analysis) {
                    section.appendChild(el('div', {class: 'pipeline-analysis'}, analysis));
                }
            }
            section.appendChild(self.renderPipeline(component, pipeline, i));
        });
        section.appendChild(this.renderPagination(component));
        return section;
    };

    View.prototype.renderHeading = function (component, index) {
        var heading = el('h1', {class: 'pipeline-title'}, component.name);
        if (this.settings.allowPipelineStart && component.firstJob) {
            heading.appendChild(document.createTextNode(' '));
            var link;
            if (component.firstJob.parameterized) {
                link = el('a', {id: 'startpipeline-' + index, class: 'task-icon-link task-trigger-parametrized-build',
                    href: this.url(component.firstJob.url + 'build?delay=0sec'), title: 'Build with parameters'});
            } else {
                link = el('a', {id: 'startpipeline-' + index, class: 'task-icon-link task-trigger-build', href: '#',
                    'data-action': 'start', 'data-url': component.firstJob.url, 'data-name': component.firstJob.fullName,
                    title: 'Build now'});
            }
            link.appendChild(icon('clock', 'Build now'));
            heading.appendChild(link);
        }
        return heading;
    };

    View.prototype.renderPagination = function (component) {
        var paging = component.paging;
        if (this.fullscreen || !paging || paging.pages <= 1) {
            return document.createDocumentFragment();
        }
        var self = this;
        var div = el('nav', {class: 'pagination', 'aria-label': 'Older pipelines of ' + component.name});
        var current = paging.page;
        var first = Math.floor((current - 1) / PAGE_WINDOW) * PAGE_WINDOW + 1;
        var last = Math.min(paging.pages, first + PAGE_WINDOW - 1);
        function link(page, label, extraClass) {
            return el('a', {href: '?component=' + component.index + '&page=' + page, class: extraClass,
                'data-action': 'page', 'data-component': component.index, 'data-page': page}, label);
        }
        if (first > 1) {
            div.appendChild(link(first - 1, 'Prev', 'pagination-prev'));
        }
        for (var page = first; page <= last; page++) {
            if (page === current) {
                div.appendChild(el('span', {class: 'active_link'}, el('a', null, String(page))));
            } else {
                div.appendChild(link(page, String(page), null));
            }
        }
        if (last < paging.pages) {
            div.appendChild(link(last + 1, 'Next', 'pagination-next'));
        }
        div.appendChild(el('span', {class: 'pagination-total'}, paging.total + ' pipelines'));
        return div;
    };

    View.prototype.renderPipelineHeading = function (pipeline) {
        var heading = el('h2', {class: 'pipeline-heading'}, pipeline.version);
        var triggered = [];
        (pipeline.triggers || []).forEach(function (trigger, index) {
            triggered.push(index === 0 ? ' ' : ', ');
            triggered.push(el('span', {class: 'trigger trigger-' + cssIdentifier(trigger.type)}, trigger.description));
        });
        if (pipeline.contributors && pipeline.contributors.length > 0) {
            triggered.push(' changes by ' + pipeline.contributors.map(function (contributor) {
                return contributor.name;
            }).join(', '));
        }
        if (triggered.length > 0) {
            heading.appendChild(document.createTextNode(' triggered by'));
            append(heading, triggered);
        }
        var started = el('span', {class: 'pipeline-started'}, formatDate(pipeline.timestamp, this.settings.showAbsoluteDateTime));
        this.liveTasks.push({element: started, timestamp: pipeline.timestamp, kind: 'time'});
        append(heading, [' started ', started]);
        var shown = 0;
        (pipeline.stages || []).forEach(function (stage) {
            shown = Math.max(shown, badness(stage.status), worstBadness(stage.tasks));
        });
        if (badness(pipeline.status) > shown) {
            heading.appendChild(document.createTextNode(' '));
            heading.appendChild(el('span', {class: 'pipeline-status ' + pipeline.status.type,
                title: 'The run ' + OUTCOME[pipeline.status.type][0] + ' outside its stages'}, OUTCOME[pipeline.status.type][1]));
        }
        var permissions = pipeline.permissions || {};
        if (this.settings.allowRebuild && pipeline.rebuildable && permissions.build && pipeline.buildNumber && pipeline.jobFullName) {
            heading.appendChild(document.createTextNode(' '));
            heading.appendChild(button('pipeline-rebuild', 'rebuild', 'rebuild', 'Run the pipeline again', {
                'data-project': pipeline.jobFullName, 'data-build': pipeline.buildNumber}));
        }
        return heading;
    };

    View.prototype.renderPipeline = function (component, pipeline, instanceIndex) {
        var self = this;
        var section = el('section', {class: 'pipeline' + (pipeline.aggregated ? ' pipeline-aggregated' : ''),
            'data-pipeline': pipeline.id});
        var row = el('div', {class: 'pipeline-row'});
        section.appendChild(row);
        var rowIndex = 0;
        var column = 0;
        var elements = {};
        (pipeline.stages || []).forEach(function (stage) {
            while (stage.row > rowIndex) {
                row = el('div', {class: 'pipeline-row'});
                section.appendChild(row);
                column = 0;
                rowIndex++;
            }
            while (stage.column > column) {
                row.appendChild(el('div', {class: 'pipeline-cell'}, el('div', {class: 'stage hide', 'aria-hidden': 'true'})));
                column++;
            }
            var stageDiv = self.renderStage(component, pipeline, stage, instanceIndex);
            elements[stage.id] = stageDiv;
            row.appendChild(el('div', {class: 'pipeline-cell'}, stageDiv));
            column++;
        });
        (pipeline.stages || []).forEach(function (stage) {
            (stage.downstream || []).forEach(function (target) {
                if (elements[stage.id] && elements[target]) {
                    self.relations.push({source: elements[stage.id], target: elements[target]});
                }
            });
        });
        return section;
    };

    View.prototype.renderStage = function (component, pipeline, stage, instanceIndex) {
        var self = this;
        var header = el('div', {class: 'stage-header'}, el('div', {class: 'stage-name'}, stage.name));
        if (badness(stage.status) > worstBadness(stage.tasks)) {
            header.classList.add(stage.status.type);
            header.title = 'The stage ' + OUTCOME[stage.status.type][0] + ' in steps outside its tasks';
        }
        if (pipeline.aggregated) {
            header.appendChild(el('div', {class: 'stage-version'}, stage.version || 'N/A'));
        }
        var stageDiv = el('div', {class: 'stage ' + 'stage_' + cssIdentifier(stage.name), 'data-stage': stage.id}, header);
        (stage.tasks || []).forEach(function (task) {
            append(stageDiv, self.renderTask(component, pipeline, task, instanceIndex));
        });
        return stageDiv;
    };

    View.prototype.renderTask = function (component, pipeline, task, instanceIndex) {
        var settings = this.settings;
        var status = task.status || {type: 'IDLE'};
        var active = status.type === 'RUNNING' || status.type === 'PAUSED_PENDING_INPUT';
        var permissions = task.permissions || {};
        var header = el('div', {class: 'task-header'},
            el('div', {class: 'taskname'}, el('a', {href: this.url(task.url)}, task.name)));
        var actions = el('div', {class: 'task-actions'});
        if (!pipeline.aggregated) {
            if (settings.allowManualTriggers && task.manual && task.manual.enabled && permissions.build) {
                actions.appendChild(button('task-manual', 'manual', 'play', 'Trigger manual build', {
                    'data-project': task.jobFullName, 'data-upstream': task.manual.upstreamJob, 'data-build': task.manual.upstreamBuild}));
            }
            if (task.requiresInput && permissions.build) {
                if (task.inputUrl) {
                    // the input step has parameters, which only its own page can collect
                    actions.appendChild(el('a', {class: 'dpp-button task-manual-specify task-input-link',
                        href: this.url(task.inputUrl), title: 'Provide input', 'aria-label': 'Provide input'},
                        icon('input', 'Provide input')));
                } else {
                    actions.appendChild(button('task-manual-specify', 'input', 'input', 'Proceed input step', {
                        'data-project': task.jobFullName, 'data-build': task.buildNumber, 'data-task': task.id}));
                }
            }
            if (settings.allowRebuild && task.rebuildable && permissions.build && task.buildNumber) {
                actions.appendChild(button('task-rebuild', 'rebuild', 'rebuild',
                    task.restart ? 'Restart from stage ' + task.restart : 'Rebuild', {
                        'data-project': task.jobFullName, 'data-build': task.buildNumber, 'data-stage': task.restart}));
            }
            if (settings.allowAbort && active && permissions.cancel && task.buildNumber) {
                actions.appendChild(button('task-abort', 'abort', 'stop', 'Abort build', {
                    'data-project': task.jobFullName, 'data-build': task.buildNumber}));
            }
        }
        if (actions.childNodes.length > 0) {
            header.appendChild(actions);
        }
        var details = el('div', {class: 'task-details'});
        var timestamp = el('div', {class: 'timestamp'}, formatDate(status.timestamp, settings.showAbsoluteDateTime));
        var duration = el('div', {class: 'duration'}, status.timestamp && (active || status.duration > 0)
            ? formatDuration(status.duration) : '');
        append(details, [timestamp, duration]);
        var progress = el('div', {class: 'task-progress ' + (active ? 'task-progress-running' : 'task-progress-notrunning')},
            el('div', {class: 'task-content'}, [header, details]));
        progress.style.width = (active && status.progress !== null && status.progress !== undefined ? status.progress : 100) + '%';
        var classes = 'stage-task ' + status.type + (task.manual ? ' manual' : '');
        var taskDiv = el('div', {class: classes, 'data-task': task.id}, progress);
        this.liveTasks.push({element: taskDiv, timestamp: timestamp, duration: duration, progress: progress,
            key: component.index + '|' + pipeline.id + '|' + task.id, kind: 'task'});
        return [taskDiv, renderDescription(settings, task), renderTests(this, settings, task),
            renderAnalysis(this, settings, task), renderPromotions(this, settings, task)];
    };

    function button(className, action, iconName, label, data) {
        var attributes = {type: 'button', class: 'dpp-button ' + className, 'data-action': action, title: label,
            'aria-label': label};
        Object.keys(data).forEach(function (name) {
            attributes[name] = data[name];
        });
        return el('button', attributes, icon(iconName, label));
    }

    /** Only the relative times, elapsed times and progress bars change when the pipelines themselves did not. */
    View.prototype.updateLive = function (components) {
        var byKey = {};
        var pipelinesByKey = {};
        components.forEach(function (component) {
            (component.pipelines || []).forEach(function (pipeline) {
                pipelinesByKey[component.index + '|' + pipeline.id] = pipeline;
                (pipeline.stages || []).forEach(function (stage) {
                    (stage.tasks || []).forEach(function (task) {
                        byKey[component.index + '|' + pipeline.id + '|' + task.id] = task;
                    });
                });
            });
        });
        var settings = this.settings;
        this.liveTasks.forEach(function (live) {
            if (live.kind === 'time') {
                live.element.textContent = formatDate(live.timestamp, settings.showAbsoluteDateTime);
                return;
            }
            if (live.kind === 'total') {
                var pipeline = pipelinesByKey[live.key];
                if (pipeline) {
                    live.element.textContent = 'Total build time: ' + formatDuration(pipeline.totalBuildTime);
                }
                return;
            }
            var task = byKey[live.key];
            if (!task || !task.status) {
                return;
            }
            var status = task.status;
            var active = status.type === 'RUNNING' || status.type === 'PAUSED_PENDING_INPUT';
            live.timestamp.textContent = formatDate(status.timestamp, settings.showAbsoluteDateTime);
            live.duration.textContent = status.timestamp && (active || status.duration > 0) ? formatDuration(status.duration) : '';
            if (active && status.progress !== null && status.progress !== undefined) {
                live.progress.style.width = status.progress + '%';
            }
        });
    };

    /* ------------------------------------------------------------------ info panels */

    function panel(children) {
        return el('div', {class: 'infoPanelOuter'}, el('div', {class: 'infoPanel'}, el('div', {class: 'infoPanelInner'}, children)));
    }

    function renderDescription(settings, task) {
        if (!settings.showDescription || !task.description) {
            return null;
        }
        var inner = el('div', {class: 'infoPanelInner task-description'});
        // Already formatted by the server with the Jenkins markup formatter, like a job description.
        inner.innerHTML = task.description;
        return el('div', {class: 'infoPanelOuter'}, el('div', {class: 'infoPanel'}, inner));
    }

    /** The test counts of a task, or of a Pipeline run for what was recorded outside its tasks. */
    function renderTests(view, settings, owner) {
        if (!settings.showTestResults || !owner.tests || owner.tests.length === 0) {
            return null;
        }
        return owner.tests.map(function (tests) {
            return panel([
                el('a', {href: view.url(tests.url)}, tests.name),
                el('table', {class: 'dpp-table test-results'}, [
                    el('thead', null, el('tr', null, [el('th', null, 'Total'), el('th', null, 'Failed'), el('th', null, 'Skipped')])),
                    el('tbody', null, el('tr', null, [el('td', null, tests.total), el('td', {class: tests.failed > 0 ? 'failed' : null}, tests.failed), el('td', null, tests.skipped)]))
                ])
            ]);
        });
    }

    /** The warning counts of a task, or of a whole Pipeline run when they belong to the run. */
    function renderAnalysis(view, settings, owner) {
        if (!settings.showStaticAnalysisResults || !owner.analysis || owner.analysis.length === 0) {
            return null;
        }
        var body = el('tbody');
        owner.analysis.forEach(function (analysis) {
            body.appendChild(el('tr', null, [
                el('td', null, el('a', {href: view.url(analysis.url)}, analysis.name)),
                el('td', {class: 'analysis-count'}, analysis.high),
                el('td', {class: 'analysis-count'}, analysis.normal),
                el('td', {class: 'analysis-count'}, analysis.low)
            ]));
        });
        return panel(el('table', {class: 'dpp-table analysis-results'}, [
            el('thead', null, el('tr', null, [el('th', null, 'Warnings'), el('th', {class: 'analysis-header'}, 'High'),
                el('th', {class: 'analysis-header'}, 'Normal'), el('th', {class: 'analysis-header'}, 'Low')])),
            body
        ]));
    }

    function renderPromotions(view, settings, task) {
        if (!settings.showPromotions || !task.promotions || task.promotions.length === 0) {
            return null;
        }
        return task.promotions.map(function (promotion) {
            var layer = el('div', {class: 'promo-layer'}, [
                el('img', {class: 'promo-icon', height: '16', width: '16', alt: '', src: view.rootUrl + promotion.icon}),
                el('span', {class: 'promo-name'}, el('a', {href: view.url(task.url) + 'promotion'}, promotion.name)),
                promotion.user && promotion.user !== 'anonymous' ? el('span', {class: 'promo-user'}, promotion.user) : null,
                el('span', {class: 'promo-time'}, formatDuration(promotion.duration))
            ]);
            (promotion.params || []).forEach(function (param) {
                layer.appendChild(el('div', {class: 'promo-param'}, multiline(param)));
            });
            return panel(layer);
        });
    }

    function renderChangeLog(changes) {
        var div = el('div', {class: 'changes'}, el('h3', null, 'Changes'));
        changes.forEach(function (change) {
            var commit = el('span', {class: 'change-commit-id'}, change.commitId);
            div.appendChild(el('div', {class: 'change'}, [
                el('div', {class: 'change-author'}, change.author ? change.author.name : ''),
                el('div', {class: 'change-commit'}, change.url ? el('a', {href: change.url}, commit) : commit),
                el('div', {class: 'change-message'}, multiline(change.message))
            ]));
        });
        return div;
    }

    /* ------------------------------------------------------------------ layout */

    /** Gives every stage box in a row the height of the tallest one; placeholders keep their natural height. */
    function equalizeStageHeights(root) {
        Array.prototype.forEach.call(root.querySelectorAll('.pipeline-row'), function (row) {
            var stages = Array.prototype.filter.call(row.querySelectorAll('.pipeline-cell > .stage'), function (stage) {
                return !stage.classList.contains('hide');
            });
            stages.forEach(function (stage) {
                stage.style.height = 'auto';
            });
            var max = stages.reduce(function (height, stage) {
                return Math.max(height, stage.offsetHeight);
            }, 0);
            if (max > 0) {
                stages.forEach(function (stage) {
                    stage.style.height = max + 'px';
                });
            }
        });
    }

    View.prototype.connectorLayer = function () {
        var svg = this.root.querySelector('svg.pipeline-connectors');
        if (svg) {
            return svg;
        }
        svg = svgEl('svg', {class: 'pipeline-connectors', 'aria-hidden': 'true'});
        var marker = svgEl('marker', {id: 'dpp-arrow-' + this.index, viewBox: '0 0 12 12', refX: '12', refY: '6',
            markerWidth: '12', markerHeight: '12', markerUnits: 'userSpaceOnUse', orient: 'auto'});
        marker.appendChild(svgEl('path', {d: 'M0,0 L12,6 L0,12 L1.2,6 z'}));
        var defs = svgEl('defs');
        defs.appendChild(marker);
        svg.appendChild(defs);
        this.root.appendChild(svg);
        return svg;
    };

    /** Draws one orthogonal arrow per stage relation on an SVG overlay covering the view. */
    View.prototype.drawConnectors = function () {
        var root = this.root;
        var svg = this.connectorLayer();
        Array.prototype.slice.call(svg.querySelectorAll('path.relation')).forEach(function (path) {
            svg.removeChild(path);
        });
        svg.setAttribute('width', '0');
        svg.setAttribute('height', '0');
        svg.setAttribute('width', String(root.scrollWidth));
        svg.setAttribute('height', String(root.scrollHeight));
        var rootRect = root.getBoundingClientRect();
        var originX = rootRect.left + root.clientLeft - root.scrollLeft;
        var originY = rootRect.top + root.clientTop - root.scrollTop;
        var markerRef = 'url(#dpp-arrow-' + this.index + ')';
        this.relations.forEach(function (relation) {
            var sourceRect = relation.source.getBoundingClientRect();
            var targetRect = relation.target.getBoundingClientRect();
            var sx = sourceRect.right - originX;
            var sy = sourceRect.top + CONNECTOR_ANCHOR_OFFSET - originY;
            var tx = targetRect.left - originX - CONNECTOR_GAP;
            var ty = targetRect.top + CONNECTOR_ANCHOR_OFFSET - originY;
            var d;
            if (Math.abs(sy - ty) < 1) {
                d = 'M' + sx + ',' + sy + ' L' + tx + ',' + ty;
            } else {
                var elbow = Math.max(sx + CONNECTOR_STUB, tx - CONNECTOR_STUB);
                d = 'M' + sx + ',' + sy + ' L' + elbow + ',' + sy + ' L' + elbow + ',' + ty + ' L' + tx + ',' + ty;
            }
            svg.appendChild(svgEl('path', {class: 'relation', d: d, 'marker-end': markerRef}));
        });
    };

    /* ------------------------------------------------------------------ actions */

    View.prototype.post = function (relativeUrl, params, description) {
        var self = this;
        var headers = {'Content-Type': 'application/x-www-form-urlencoded'};
        if (this.crumbField && this.crumbValue) {
            headers[this.crumbField] = this.crumbValue;
        }
        return fetch(this.url(relativeUrl), {method: 'POST', credentials: 'same-origin', headers: headers,
            body: encodeForm(params)}).then(function (response) {
            if (!response.ok) {
                return response.text().then(function (text) {
                    var reason = (text || '').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
                    throw new Error(response.status + (reason ? ': ' + reason.substring(0, 300) : ''));
                });
            }
            window.clearTimeout(self.timer);
            self.timer = window.setTimeout(function () {
                self.poll();
            }, 500);
        }).catch(function (error) {
            self.showError('Could not ' + description + ': ' + (error && error.message ? error.message : error));
        });
    };

    View.prototype.onClick = function (event) {
        var target = event.target.closest ? event.target.closest('[data-action]') : null;
        if (!target || !this.root.contains(target)) {
            return;
        }
        var d = target.dataset;
        var viewUrl = this.viewUrl;
        switch (d.action) {
            case 'start':
                event.preventDefault();
                this.post(d.url + 'build?delay=0sec', {}, 'start ' + d.name);
                break;
            case 'manual':
                target.disabled = true;
                this.post(viewUrl + 'manualStep', {project: d.project, upstream: d.upstream, buildId: d.build}, 'trigger ' + d.project);
                break;
            case 'rebuild':
                target.disabled = true;
                this.post(viewUrl + 'rebuild', {project: d.project, buildId: d.build, stage: d.stage || ''},
                    d.stage ? 'restart ' + d.project + ' from stage ' + d.stage : 'rebuild ' + d.project);
                break;
            case 'input':
                target.disabled = true;
                this.post(viewUrl + 'proceedInput', {project: d.project, buildId: d.build, task: d.task || ''},
                    'proceed the input step of ' + d.project);
                break;
            case 'abort':
                target.disabled = true;
                this.post(viewUrl + 'abort', {project: d.project, buildId: d.build}, 'abort ' + d.project);
                break;
            case 'page':
                event.preventDefault();
                this.page = parseInt(d.page, 10) || 1;
                this.component = parseInt(d.component, 10) || 1;
                if (window.history && window.history.replaceState) {
                    window.history.replaceState(null, '', '?component=' + this.component + '&page=' + this.page);
                }
                window.clearTimeout(this.timer);
                this.poll();
                break;
            default:
                break;
        }
    };

    /* ------------------------------------------------------------------ start */

    function startViews() {
        Array.prototype.forEach.call(document.querySelectorAll('.dpp-view'), function (root) {
            if (!root.dppStarted) {
                root.dppStarted = true;
                new View(root).start();
            }
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', startViews);
    } else {
        startViews();
    }

    window.DeliveryPipeline = {formatDate: formatDate, formatDuration: formatDuration, formatRelative: formatRelative,
        cssIdentifier: cssIdentifier};
})();
