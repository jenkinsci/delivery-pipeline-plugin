let jsPlumbUtilityVariable;

Q(document).ready(function() {
    if (undefined === jsPlumbUtilityVariable) {
        jsPlumbUtilityVariable = [];
    }

    document.querySelectorAll(".dpv-data-holder").forEach((dataHolder) => {
        const { itId, viewUrl, aggregatedChangesGroupingPattern } = dataHolder.dataset;
        const numberOfColumns = parseInt(dataHolder.dataset.numberOfColumns);
        const updateInterval = parseInt(dataHolder.dataset.updateInterval);
        const showChanges = dataHolder.dataset.showChanges === "true";
        // Request parameters of the current page, rendered server-side into the data holder so the
        // api/json polling request carries the same paging state as the page itself.
        const fullscreen = dataHolder.dataset.fullscreen === "true";
        const page = positiveIntOrDefault(dataHolder.dataset.page, 1);
        const component = positiveIntOrDefault(dataHolder.dataset.component, 1);

        const id = itId === undefined || itId === "" ? 0 : itId;

        window[`pipeline${id}`] = function(pipelineid, viewUrl, numberOfColumns, aggregatedChangesGroupingPattern, updateInterval, showChanges) {
            var pipelineContainers = [];
            var jsplumb = jsPlumb.getInstance({Container: document.getElementById('pipeline-main-' + pipelineid)});
            jsPlumbUtilityVariable.push(jsplumb);

            for (let i = 1; i <= numberOfColumns; i++) {
                pipelineContainers.push(`pipelines-${i}-${pipelineid}`);
            }

            var view = { "viewUrl" : viewUrl };

            var pipelineutils = new pipelineUtils();

            // pipe.js clones this with new RegExp(pattern), which keeps the flags of a RegExp object.
            const changesGroupingPattern = toGlobalRegExp(aggregatedChangesGroupingPattern);

            pipelineutils.updatePipelines(pipelineContainers, "pipelineerror-" + pipelineid, view,
                fullscreen, page, component, showChanges, changesGroupingPattern, updateInterval * 1000, pipelineid, jsplumb);

            Q(window).resize(function () {
                jsplumb.repaintEverything();
            });
        }

        window[`pipeline${id}`](id, viewUrl, numberOfColumns, aggregatedChangesGroupingPattern, updateInterval, showChanges);
    });
});

function positiveIntOrDefault(value, defaultValue) {
    const parsed = parseInt(value, 10);
    return Number.isNaN(parsed) || parsed < 1 ? defaultValue : parsed;
}

function toGlobalRegExp(pattern) {
    if (!pattern) {
        return null;
    }
    try {
        return new RegExp(pattern, "g");
    } catch (e) {
        console.warn("Ignoring invalid aggregated changes grouping pattern: " + pattern, e);
        return null;
    }
}
