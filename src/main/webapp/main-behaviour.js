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

        const id = itId === undefined ? 0 : itId;

        window[`pipeline${id}`] = function(pipelineid, viewUrl, numberOfColumns, aggregatedChangesGroupingPattern, updateInterval, showChanges) {
            var pipelineContainers = [];
            var jsplumb = jsPlumb.getInstance();
            jsPlumbUtilityVariable.push(jsplumb);

            for (let i = 1; i <= numberOfColumns; i++) {
                pipelineContainers.push(`pipelines-${i}-${pipelineid}`);
            }

            var view = { "viewUrl" : viewUrl };

            var pipelineutils = new pipelineUtils();

            const changesGroupingPattern = aggregatedChangesGroupingPattern == null ? null : `/${aggregatedChangesGroupingPattern}/g`;

            pipelineutils.updatePipelines(pipelineContainers, "pipelineerror-" + pipelineid, view,
                fullscreen, page, component, showChanges, changesGroupingPattern, updateInterval * 1000, pipelineid, jsplumb);

            Q(window).resize(function () {
                jsplumb.repaintEverything();
            });
        }

        window[`pipeline${id}`](id, viewUrl, numberOfColumns, aggregatedChangesGroupingPattern, updateInterval, showChanges);
    });
});
