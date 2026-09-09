/*
 * Delivery Pipeline view renderer.
 *
 * Plain DOM, fetch and SVG; no jQuery, jsPlumb or moment. Everything the server sends becomes a
 * text node or an attribute value, so names cannot turn into markup. The two exceptions are
 * documented where they happen: the pagination links and the task description template, both of
 * which the server produces as HTML on purpose.
 *
 * Syntax is kept at ES2015 (no classes, rest parameters or optional chaining) so that the HtmlUnit
 * based tests can execute this file.
 */
var DeliveryPipeline = (function () {
    'use strict';

    var ARROW_MARKER_PREFIX = 'pipeline-arrow-';
    var CONNECTOR_STUB = 25;
    var CONNECTOR_GAP = 2;
    var CONNECTOR_ANCHOR_OFFSET = 37;
    var REQUEST_TIMEOUT = 60000;

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

    /** Text with line breaks rendered as <br>, as the old htmlEncode did. */
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

    function hide(node) {
        node.style.display = 'none';
    }

    /* ------------------------------------------------------------------ identifiers */

    /**
     * Reduces a job, stage or view name to characters that are safe in an id, a class name and a
     * selector. Names may contain quotes, parentheses and other characters.
     */
    function cssIdentifier(value) {
        return String(value).replace(/[^A-Za-z0-9_-]/g, '_');
    }

    function taskElementId(taskId, count) {
        return 'task-' + cssIdentifier(taskId) + count;
    }

    function stageElementId(stageId, count) {
        return cssIdentifier(stageId) + '_' + count;
    }

    function stageClassName(stageName) {
        return 'stage_' + cssIdentifier(stageName);
    }

    /* ------------------------------------------------------------------ dates */

    var TIMESTAMP = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})/;

    /** The server formats timestamps in its own time zone with a literal Z; read them as-is. */
    function parseTimestamp(value) {
        var match = TIMESTAMP.exec(String(value === null || value === undefined ? '' : value));
        if (!match) {
            return null;
        }
        return new Date(+match[1], match[2] - 1, +match[3], +match[4], +match[5], +match[6]);
    }

    function pad(number) {
        return (number < 10 ? '0' : '') + number;
    }

    function formatAbsolute(date) {
        return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
            + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds());
    }

    /** Relative time in the wording moment.js used ("a few seconds ago", "2 hours ago"). */
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

    function formatDate(value, currentTime, showAbsoluteDateTime) {
        var date = parseTimestamp(value);
        if (!date) {
            return '';
        }
        if (showAbsoluteDateTime) {
            return formatAbsolute(date);
        }
        return formatRelative(date, parseTimestamp(currentTime) || new Date());
    }

    function formatDuration(millis) {
        if (millis === 0) {
            return '0 sec';
        }
        var seconds = Math.floor(millis / 1000);
        var minutes = Math.floor(seconds / 60);
        seconds = seconds % 60;
        return (minutes === 0 ? '' : minutes + ' min ') + seconds + ' sec';
    }

    /* ------------------------------------------------------------------ requests */

    function crumbHeaders(headers) {
        if (typeof crumb !== 'undefined' && crumb && crumb.value !== null && crumb.value !== undefined && crumb.value !== '') {
            headers[crumb.fieldName] = crumb.value;
        }
        return headers;
    }

    function encodeForm(params) {
        return Object.keys(params).map(function (name) {
            var value = params[name];
            return encodeURIComponent(name) + '=' + encodeURIComponent(value === null || value === undefined ? '' : String(value));
        }).join('&');
    }

    /** POSTs a form to a Jenkins URL relative to the root URL; failures are reported with an alert as before. */
    function postForm(relativeUrl, params, action) {
        var headers = crumbHeaders({'Content-Type': 'application/x-www-form-urlencoded'});
        return fetch(rootURL + '/' + relativeUrl, {
            method: 'POST',
            credentials: 'same-origin',
            headers: headers,
            body: encodeForm(params)
        }).then(function (response) {
            if (!response.ok) {
                throw new Error(response.status + ' ' + response.statusText);
            }
            console.info('Request to ' + action + ' succeeded');
        }).catch(function (error) {
            window.alert('Could not ' + action + '! error: ' + (error && error.message ? error.message : error));
        });
    }

    function getLink(data, link) {
        return data.linkRelative ? link : rootURL + '/' + link;
    }

    function isTaskLinkedToConsoleLog(data, task) {
        return data.linkToConsoleLog
            && (task.status.success || task.status.failed || task.status.unstable || task.status.cancelled);
    }

    function trimWarningsFromString(label) {
        var offset = String(label).indexOf('Warnings');
        return offset === -1 ? label : String(label).substring(0, offset).trim();
    }

    /* ------------------------------------------------------------------ info panels */

    function renderDescription(data, task) {
        if (!data.showDescription || !task.description || task.description === '') {
            return null;
        }
        var inner = el('div', {class: 'infoPanelInner'});
        // The description template is configured by the job owner and documented as accepting
        // markup, so it is the one place that renders server HTML.
        inner.innerHTML = String(task.description).replace(/\r\n/g, '<br/>');
        return el('div', {class: 'infoPanelOuter'}, el('div', {class: 'infoPanel'}, inner));
    }

    function renderTestInfo(data, task) {
        if (!data.showTestResults || !task.testResults || task.testResults.length === 0) {
            return null;
        }
        var outer = el('div', {class: 'infoPanelOuter'});
        task.testResults.forEach(function (analysis) {
            var table = el('table', {id: 'priority.summary', class: 'pane'}, [
                el('tbody', null, el('tr', null, [
                    el('td', {class: 'pane-header'}, 'Total'),
                    el('td', {class: 'pane-header'}, 'Failures'),
                    el('td', {class: 'pane-header'}, 'Skipped')
                ])),
                el('tbody', null, el('tr', null, [
                    el('td', {class: 'pane'}, analysis.total),
                    el('td', {class: 'pane'}, analysis.failed),
                    el('td', {class: 'pane'}, analysis.skipped)
                ]))
            ]);
            outer.appendChild(el('div', {class: 'infoPanel'}, el('div', {class: 'infoPanelInner'}, [
                el('a', {href: getLink(data, analysis.url)}, analysis.name),
                table
            ])));
        });
        return outer;
    }

    function renderStaticAnalysisInfo(data, task) {
        if (!data.showStaticAnalysisResults || !task.staticAnalysisResults || task.staticAnalysisResults.length === 0) {
            return null;
        }
        var body = el('tbody');
        task.staticAnalysisResults.forEach(function (analysis) {
            body.appendChild(el('tr', null, [
                el('td', {class: 'pane'}, el('a', {href: getLink(data, analysis.url)}, trimWarningsFromString(analysis.name))),
                el('td', {class: 'pane analysis-count'}, analysis.high),
                el('td', {class: 'pane analysis-count'}, analysis.normal),
                el('td', {class: 'pane analysis-count'}, analysis.low)
            ]));
        });
        var table = el('table', {id: 'priority.summary', class: 'pane'}, [
            el('thead', null, el('tr', null, [
                el('td', {class: 'pane-header'}, 'Warnings'),
                el('td', {class: 'pane-header analysis-header'}, 'High'),
                el('td', {class: 'pane-header analysis-header'}, 'Normal'),
                el('td', {class: 'pane-header analysis-header'}, 'Low')
            ])),
            body
        ]);
        return el('div', {class: 'infoPanelOuter'}, el('div', {class: 'infoPanel'}, el('div', {class: 'infoPanelInner'}, table)));
    }

    function renderPromotionsInfo(data, task) {
        if (!data.showPromotions || !task.status.promoted || !task.status.promotions || task.status.promotions.length === 0) {
            return null;
        }
        var outer = el('div', {class: 'infoPanelOuter'});
        task.status.promotions.forEach(function (promo) {
            var layer = el('div', {class: 'promo-layer'}, [
                el('img', {class: 'promo-icon', height: '16', width: '16', src: rootURL + promo.icon}),
                el('span', {class: 'promo-name'}, el('a', {href: getLink(data, task.link) + 'promotion'}, promo.name)),
                el('br')
            ]);
            if (promo.user !== 'anonymous') {
                layer.appendChild(el('span', {class: 'promo-user'}, promo.user));
            }
            append(layer, [el('span', {class: 'promo-time'}, formatDuration(promo.duration)), el('br')]);
            if (promo.params && promo.params.length > 0) {
                layer.appendChild(el('br'));
            }
            (promo.params || []).forEach(function (param) {
                append(layer, [multiline(param), el('br')]);
            });
            outer.appendChild(el('div', {class: 'infoPanel'}, el('div', {class: 'infoPanelInner'}, layer)));
        });
        return outer;
    }

    function renderChangeLog(changes) {
        var div = el('div', {class: 'changes'}, el('h1', null, 'Changes:'));
        changes.forEach(function (change) {
            var commit = el('div', {class: 'change-commit-id'}, change.commitId);
            div.appendChild(el('div', {class: 'change'}, [
                change.changeLink ? el('a', {href: change.changeLink}, commit) : commit,
                el('div', {class: 'change-author'}, change.author ? change.author.name : ''),
                el('div', {class: 'change-message'}, multiline(change.message))
            ]));
        });
        return div;
    }

    function unique(values) {
        var seen = {};
        return values.filter(function (value) {
            if (Object.prototype.hasOwnProperty.call(seen, value)) {
                return false;
            }
            seen[value] = true;
            return true;
        });
    }

    function renderAggregatedChangelog(stageChanges, groupingPattern) {
        var unmatchedKey = '';
        var groups = {};
        if (groupingPattern) {
            stageChanges.forEach(function (change) {
                var matches = String(change.message || '').match(groupingPattern) || [unmatchedKey];
                unique(matches).forEach(function (match) {
                    groups[match] = groups[match] || [];
                    groups[match].push(change);
                });
            });
        } else {
            groups[unmatchedKey] = stageChanges;
        }
        var keys = Object.keys(groups).sort().filter(function (key) {
            return key !== unmatchedKey;
        });
        keys.push(unmatchedKey);

        var list = el('ul');
        keys.forEach(function (key) {
            var target = list;
            if (key !== unmatchedKey) {
                var sublist = el('ul');
                list.appendChild(el('li', {class: 'aggregatedKey'}, [el('b', null, key), sublist]));
                target = sublist;
            }
            (groups[key] || []).forEach(function (change) {
                target.appendChild(el('li', null, change.message ? multiline(change.message) : ' '));
            });
        });
        return el('div', {class: 'aggregatedChangesPanelOuter'},
            el('div', {class: 'aggregatedChangesPanel'},
                el('div', {class: 'aggregatedChangesPanelInner'}, [el('b', null, 'Changes:'), list])));
    }

    /* ------------------------------------------------------------------ layout */

    /** Gives every stage box in a row the height of the tallest one. */
    function equalizeStageHeights(main) {
        Array.prototype.forEach.call(main.querySelectorAll('.pipeline-row'), function (row) {
            var stages = [];
            Array.prototype.forEach.call(row.children, function (cell) {
                Array.prototype.forEach.call(cell.children, function (child) {
                    // Placeholders (.stage.hide) only keep the column; stretching them would move the
                    // row's real boxes down by their height because table cells align on the baseline.
                    if (child.classList && child.classList.contains('stage') && !child.classList.contains('hide')) {
                        stages.push(child);
                    }
                });
            });
            stages.forEach(function (stage) {
                stage.style.height = 'auto';
            });
            var max = stages.reduce(function (height, stage) {
                return Math.max(height, stage.offsetHeight);
            }, 0);
            stages.forEach(function (stage) {
                stage.style.height = max + 'px';
            });
        });
    }

    /* ------------------------------------------------------------------ the view */

    function PipelineView(options) {
        this.options = options;
        this.main = document.getElementById('pipeline-main-' + options.id);
        this.errorDiv = document.getElementById('pipelineerror-' + options.id);
        this.messageDiv = document.getElementById('pipeline-message-' + options.id);
        this.columns = [];
        for (var i = 1; i <= options.numberOfColumns; i++) {
            var column = document.getElementById('pipelines-' + i + '-' + options.id);
            if (column) {
                // Set through the CSSOM rather than a style attribute so that the page works
                // under a Content-Security-Policy without 'unsafe-inline' styles.
                column.style.width = (100 / options.numberOfColumns) + '%';
                this.columns.push(column);
            }
        }
        this.relations = [];
        this.lastResponse = null;
        this.timer = null;
    }

    PipelineView.prototype.start = function () {
        var self = this;
        this.main.addEventListener('click', function (event) {
            self.onClick(event);
        });
        window.addEventListener('resize', function () {
            self.drawConnectors();
        });
        this.poll();
    };

    PipelineView.prototype.apiUrl = function () {
        var o = this.options;
        return rootURL + '/' + o.viewUrl + 'api/json?page=' + o.page + '&component=' + o.component + '&fullscreen=' + o.fullscreen;
    };

    PipelineView.prototype.schedule = function () {
        var self = this;
        this.timer = window.setTimeout(function () {
            self.poll();
        }, this.options.updateInterval);
    };

    PipelineView.prototype.poll = function () {
        var self = this;
        // JENKINS-46160 Don't refresh pipelines if the tab/window is not active
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
            if (timeout !== null) {
                window.clearTimeout(timeout);
            }
            self.refresh(data);
            self.schedule();
        }).catch(function (error) {
            if (timeout !== null) {
                window.clearTimeout(timeout);
            }
            self.showError('Error communicating to server! ' + (error && error.message ? error.message : ''));
            self.drawConnectors();
            self.schedule();
        });
    };

    PipelineView.prototype.showError = function (message) {
        clear(this.errorDiv);
        this.errorDiv.appendChild(document.createTextNode(message));
        this.errorDiv.style.display = 'block';
    };

    PipelineView.prototype.hideError = function () {
        this.errorDiv.style.display = 'none';
        clear(this.errorDiv);
    };

    PipelineView.prototype.refresh = function (data) {
        if (data.error) {
            this.showError('Error: ' + data.error);
        } else {
            this.hideError();
        }
        var pipelines = data.pipelines || [];
        if (this.lastResponse === null || JSON.stringify(pipelines) !== JSON.stringify(this.lastResponse.pipelines || [])) {
            this.render(data);
        } else {
            this.updateTimestamps(data);
        }
        this.drawConnectors();
    };

    PipelineView.prototype.render = function (data) {
        var self = this;
        var pipelines = data.pipelines || [];
        this.columns.forEach(clear);
        this.relations = [];
        clear(this.messageDiv);
        if (pipelines.length === 0) {
            append(this.messageDiv, ['No pipelines configured or found. Please review the ', el('a', {href: 'configure'}, 'configuration')]);
        }
        pipelines.forEach(function (component, index) {
            self.columns[index % self.columns.length].appendChild(self.renderComponent(component, index, data));
        });
        this.lastResponse = data;
        equalizeStageHeights(this.main);
    };

    PipelineView.prototype.renderComponent = function (component, index, data) {
        var self = this;
        var section = el('section', {class: 'pipeline-component'}, this.renderHeader(component, index, data));
        section.appendChild(this.renderPagination(component));
        var pipelines = component.pipelines || [];
        if (pipelines.length === 0) {
            section.appendChild(document.createTextNode('No builds done yet.'));
        }
        pipelines.forEach(function (pipeline, i) {
            if (!pipeline.aggregated) {
                section.appendChild(self.renderPipelineHeading(pipeline, data));
                if (data.showTotalBuildTime) {
                    section.appendChild(el('h3', null, 'Total build time: ' + formatDuration(pipeline.totalBuildTime)));
                }
                if (self.options.showChanges && pipeline.changes && pipeline.changes.length > 0) {
                    section.appendChild(renderChangeLog(pipeline.changes));
                }
            } else if (pipelines.length > 1) {
                section.appendChild(el('h2', null, 'Aggregated view'));
            }
            section.appendChild(self.renderPipeline(component, pipeline, i, data));
        });
        section.appendChild(this.renderPagination(component));
        return section;
    };

    PipelineView.prototype.renderHeader = function (component, index, data) {
        var heading = el('h1', null, component.name);
        if (data.allowPipelineStart) {
            heading.appendChild(document.createTextNode(' '));
            var link;
            if (component.workflowComponent) {
                link = el('a', {id: 'startpipeline-' + index, class: 'task-icon-link task-trigger-build', href: '#',
                    'data-workflow-url': component.workflowUrl, 'data-task-id': data.name});
            } else if (component.firstJobParameterized) {
                link = el('a', {id: 'startpipeline-' + index, class: 'task-icon-link task-trigger-parametrized-build', href: '#',
                    'data-first-job-url': component.firstJobUrl});
            } else {
                link = el('a', {id: 'startpipeline-' + index, class: 'task-icon-link task-trigger-build', href: '#',
                    'data-workflow-url': component.firstJobUrl, 'data-task-id': data.name});
            }
            link.appendChild(buildNowIcon());
            heading.appendChild(link);
        }
        return heading;
    };

    /** The Build-now clock, drawn inline in currentColor so it follows the page's text colour in every theme. */
    function buildNowIcon() {
        var svg = svgEl('svg', {class: 'icon-clock icon-md', viewBox: '0 0 24 24', width: '24', height: '24',
            role: 'img', 'aria-label': 'Build now'});
        var title = svgEl('title');
        title.appendChild(document.createTextNode('Build now'));
        svg.appendChild(title);
        svg.appendChild(svgEl('circle', {cx: '12', cy: '12', r: '10', fill: 'none', stroke: 'currentColor', 'stroke-width': '2'}));
        svg.appendChild(svgEl('path', {d: 'M12 6v6h4', fill: 'none', stroke: 'currentColor', 'stroke-width': '2',
            'stroke-linecap': 'round', 'stroke-linejoin': 'round'}));
        return svg;
    }

    PipelineView.prototype.renderPagination = function (component) {
        if (this.options.fullscreen || !component.pagingData) {
            return document.createDocumentFragment();
        }
        var div = el('div', {class: 'pagination'});
        // The server renders the page links (PipelinePagination) as HTML.
        div.innerHTML = component.pagingData;
        return div;
    };

    PipelineView.prototype.renderPipelineHeading = function (pipeline, data) {
        var heading = el('h2', null, pipeline.version);
        var triggered = [];
        if (pipeline.triggeredBy && pipeline.triggeredBy.length > 0) {
            pipeline.triggeredBy.forEach(function (trigger, index) {
                triggered.push(' ');
                triggered.push(el('span', {class: trigger.type}, trigger.description));
                if (index < pipeline.triggeredBy.length - 1) {
                    triggered.push(',');
                }
            });
        }
        if (pipeline.contributors && pipeline.contributors.length > 0) {
            triggered.push(' changes by ' + pipeline.contributors.map(function (contributor) {
                return contributor.name;
            }).join(', '));
        }
        if (triggered.length > 0) {
            heading.appendChild(document.createTextNode(' triggered by'));
            append(heading, triggered);
        }
        append(heading, [' started ', el('span', {id: pipeline.id},
            formatDate(pipeline.timestamp, data.lastUpdated, data.showAbsoluteDateTime))]);
        return heading;
    };

    PipelineView.prototype.renderPipeline = function (component, pipeline, i, data) {
        var self = this;
        var section = el('section', {class: 'pipeline'});
        var row = el('div', {class: 'pipeline-row'});
        section.appendChild(row);
        var rowIndex = 0;
        var column = 0;
        (pipeline.stages || []).forEach(function (stage) {
            if (stage.row > rowIndex) {
                row = el('div', {class: 'pipeline-row'});
                section.appendChild(row);
                column = 0;
                rowIndex++;
            }
            while (stage.column > column) {
                row.appendChild(el('div', {class: 'pipeline-cell'}, el('div', {class: 'stage hide'})));
                column++;
            }
            row.appendChild(self.renderStage(component, pipeline, stage, i, data));
            column++;
        });
        return section;
    };

    PipelineView.prototype.renderStage = function (component, pipeline, stage, i, data) {
        var self = this;
        var header = el('div', {class: 'stage-header'}, el('div', {class: 'stage-name'}, stage.name));
        if (pipeline.aggregated) {
            append(header, [' ', el('div', {class: 'stage-version'}, stage.version || 'N/A')]);
        }
        var stageDiv = el('div', {id: stageElementId(stage.id, i), class: 'stage ' + stageClassName(stage.name)}, header);
        (stage.tasks || []).forEach(function (task) {
            append(stageDiv, self.renderTask(component, pipeline, task, i, data));
        });
        if (pipeline.aggregated && stage.changes && stage.changes.length > 0) {
            stageDiv.appendChild(renderAggregatedChangelog(stage.changes, this.options.aggregatedChangesGroupingPattern));
        }
        if (stage.downstreamStages) {
            (stage.downstreamStageIds || []).forEach(function (target) {
                self.relations.push({source: stageElementId(stage.id, i), target: stageElementId(target, i)});
            });
        }
        return el('div', {class: 'pipeline-cell'}, stageDiv);
    };

    PipelineView.prototype.renderTask = function (component, pipeline, task, i, data) {
        var id = taskElementId(task.id, i);
        var viewUrl = this.options.viewUrl;
        var timestamp = formatDate(task.status.timestamp, data.lastUpdated, data.showAbsoluteDateTime);
        var progress = 100;
        var progressClass = 'task-progress-notrunning';
        var consoleLogLink = '';
        if (task.status.percentage) {
            progress = task.status.percentage;
            progressClass = 'task-progress-running';
        } else if (isTaskLinkedToConsoleLog(data, task)) {
            consoleLogLink = 'console';
        }
        var showAbortButton = false;
        if (data.allowAbort) {
            progressClass += ' task-abortable';
            if (progressClass.indexOf('task-progress-running') !== -1) {
                showAbortButton = true;
            }
        }

        var header = el('div', {class: 'task-header'},
            el('div', {class: 'taskname'}, el('a', {href: getLink(data, task.link) + consoleLogLink}, task.name)));

        if (data.allowManualTriggers && task.manual && task.manualStep && task.manualStep.enabled && task.manualStep.permission) {
            header.appendChild(el('div', {class: 'task-manual', id: 'manual-' + id, title: 'Trigger manual build',
                'data-task-id': id, 'data-downstream-project': task.id,
                'data-upstream-project': task.manualStep.upstreamProject, 'data-upstream-build': task.manualStep.upstreamId,
                'data-view-url': viewUrl}));
        } else if (!pipeline.aggregated) {
            if (data.allowRebuild && task.rebuildable) {
                header.appendChild(el('div', {class: 'task-rebuild', id: 'rebuild-' + id, title: 'Trigger rebuild',
                    'data-task-id': id, 'data-project': task.id, 'data-build-id': task.buildId, 'data-view-url': viewUrl}));
            }
            if (task.requiringInput) {
                showAbortButton = true;
                header.appendChild(el('div', {class: 'task-manual-specify', id: 'input-' + id, title: 'Specify input',
                    'data-task-id': id, 'data-project': component.fullJobName, 'data-build-id': task.buildId, 'data-view-url': viewUrl}));
            }
            if (showAbortButton) {
                var projectName = component.fullJobName === undefined ? task.id : component.fullJobName;
                header.appendChild(el('div', {class: 'task-abort', id: 'abort-' + id, title: 'Abort progress',
                    'data-task-id': id, 'data-project-name': projectName, 'data-build-id': task.buildId, 'data-view-url': viewUrl}));
            }
        }

        var details = el('div', {class: 'task-details'});
        if (timestamp !== '') {
            details.appendChild(el('div', {id: id + '.timestamp', class: 'timestamp'}, timestamp));
        }
        if (task.status.duration >= 0) {
            details.appendChild(el('div', {class: 'duration'}, formatDuration(task.status.duration)));
        }

        var progressDiv = el('div', {class: 'task-progress ' + progressClass}, el('div', {class: 'task-content'}, [header, details]));
        progressDiv.style.width = progress + '%';
        var taskDiv = el('div', {id: id, class: 'status stage-task ' + task.status.type}, progressDiv);

        return [taskDiv, renderDescription(data, task), renderTestInfo(data, task),
            renderStaticAnalysisInfo(data, task), renderPromotionsInfo(data, task)];
    };

    /** Only the relative timestamps change when the pipelines themselves did not. */
    PipelineView.prototype.updateTimestamps = function (data) {
        (data.pipelines || []).forEach(function (component) {
            (component.pipelines || []).forEach(function (pipeline, d) {
                var head = document.getElementById(String(pipeline.id));
                if (head) {
                    head.textContent = formatDate(pipeline.timestamp, data.lastUpdated, data.showAbsoluteDateTime);
                }
                (pipeline.stages || []).forEach(function (stage) {
                    (stage.tasks || []).forEach(function (task) {
                        var time = document.getElementById(taskElementId(task.id, d) + '.timestamp');
                        if (time) {
                            time.textContent = formatDate(task.status.timestamp, data.lastUpdated, data.showAbsoluteDateTime);
                        }
                    });
                });
            });
        });
    };

    /* ------------------------------------------------------------------ connectors */

    PipelineView.prototype.connectorLayer = function () {
        var svg = this.main.querySelector('svg.pipeline-connectors');
        if (svg) {
            return svg;
        }
        svg = svgEl('svg', {class: 'pipeline-connectors', 'aria-hidden': 'true'});
        var marker = svgEl('marker', {id: ARROW_MARKER_PREFIX + this.options.id, viewBox: '0 0 12 12', refX: '12', refY: '6',
            markerWidth: '12', markerHeight: '12', markerUnits: 'userSpaceOnUse', orient: 'auto'});
        marker.appendChild(svgEl('path', {d: 'M0,0 L12,6 L0,12 L1.2,6 z'}));
        var defs = svgEl('defs');
        defs.appendChild(marker);
        svg.appendChild(defs);
        this.main.appendChild(svg);
        return svg;
    };

    /** Draws one orthogonal arrow per upstream/downstream stage relation on an SVG overlay. */
    PipelineView.prototype.drawConnectors = function () {
        var main = this.main;
        var svg = this.connectorLayer();
        Array.prototype.slice.call(svg.querySelectorAll('path.relation')).forEach(function (path) {
            svg.removeChild(path);
        });
        svg.setAttribute('width', '0');
        svg.setAttribute('height', '0');
        var width = main.scrollWidth;
        var height = main.scrollHeight;
        svg.setAttribute('width', String(width));
        svg.setAttribute('height', String(height));

        var mainRect = main.getBoundingClientRect();
        var originX = mainRect.left + main.clientLeft - main.scrollLeft;
        var originY = mainRect.top + main.clientTop - main.scrollTop;
        var markerRef = 'url(#' + ARROW_MARKER_PREFIX + this.options.id + ')';

        this.relations.forEach(function (relation) {
            var source = document.getElementById(relation.source);
            var target = document.getElementById(relation.target);
            if (!source || !target) {
                return;
            }
            var sourceRect = source.getBoundingClientRect();
            var targetRect = target.getBoundingClientRect();
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

    var ACTION_SELECTOR = '.task-trigger-build, .task-trigger-parametrized-build, .task-manual, .task-rebuild, .task-manual-specify, .task-abort';

    PipelineView.prototype.onClick = function (event) {
        var target = event.target.closest ? event.target.closest(ACTION_SELECTOR) : null;
        if (!target || !this.main.contains(target)) {
            return;
        }
        var d = target.dataset;
        var classes = target.classList;
        if (classes.contains('task-trigger-build')) {
            event.preventDefault();
            postForm(d.workflowUrl + 'build?delay=0sec', {}, 'trigger build of ' + d.taskId);
        } else if (classes.contains('task-trigger-parametrized-build')) {
            event.preventDefault();
            console.info('Job is parameterized');
            window.location.href = rootURL + '/' + d.firstJobUrl + 'build?delay=0sec';
        } else if (classes.contains('task-manual')) {
            hide(target);
            postForm(d.viewUrl + 'api/manualStep',
                {project: d.downstreamProject, upstream: d.upstreamProject, buildId: d.upstreamBuild},
                'trigger build of ' + d.downstreamProject);
        } else if (classes.contains('task-rebuild')) {
            hide(target);
            postForm(d.viewUrl + 'api/rebuildStep', {project: d.project, buildId: d.buildId}, 'trigger rebuild of ' + d.project);
        } else if (classes.contains('task-manual-specify')) {
            hide(target);
            postForm(d.viewUrl + 'api/inputStep', {project: d.project, upstream: 'N/A', buildId: d.buildId},
                'trigger input step of ' + d.project);
        } else if (classes.contains('task-abort')) {
            hide(target);
            postForm(d.viewUrl + 'api/abortBuild', {project: d.projectName, upstream: 'N/A', buildId: d.buildId},
                'abort build of ' + d.projectName);
        }
    };

    /* ------------------------------------------------------------------ public API */

    var views = [];

    return {
        /** Starts polling and rendering for one view; options come from the data holder in main.jelly. */
        start: function (options) {
            var view = new PipelineView(options);
            views.push(view);
            view.start();
            return view;
        },
        views: views,
        formatDate: formatDate,
        formatDuration: formatDuration,
        cssIdentifier: cssIdentifier
    };
})();
