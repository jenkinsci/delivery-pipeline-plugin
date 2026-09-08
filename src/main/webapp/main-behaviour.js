(function () {
    'use strict';

    function positiveIntOrDefault(value, defaultValue) {
        var parsed = parseInt(value, 10);
        return isNaN(parsed) || parsed < 1 ? defaultValue : parsed;
    }

    function toGlobalRegExp(pattern) {
        if (!pattern) {
            return null;
        }
        try {
            return new RegExp(pattern, 'g');
        } catch (e) {
            console.warn('Ignoring invalid aggregated changes grouping pattern: ' + pattern, e);
            return null;
        }
    }

    function startViews() {
        Array.prototype.forEach.call(document.querySelectorAll('.dpv-data-holder'), function (holder) {
            var data = holder.dataset;
            DeliveryPipeline.start({
                id: data.itId === undefined || data.itId === '' ? '0' : data.itId,
                viewUrl: data.viewUrl,
                numberOfColumns: positiveIntOrDefault(data.numberOfColumns, 1),
                updateInterval: positiveIntOrDefault(data.updateInterval, 2) * 1000,
                showChanges: data.showChanges === 'true',
                // Request parameters of the current page, rendered server-side into the data holder so
                // the api/json polling request carries the same paging state as the page itself.
                fullscreen: data.fullscreen === 'true',
                page: positiveIntOrDefault(data.page, 1),
                component: positiveIntOrDefault(data.component, 1),
                aggregatedChangesGroupingPattern: toGlobalRegExp(data.aggregatedChangesGroupingPattern)
            });
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', startViews);
    } else {
        startViews();
    }
})();
