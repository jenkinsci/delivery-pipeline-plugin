// A 1.x-era configuration: a chain with a Build Pipeline manual trigger, a Pipeline job, and a Delivery Pipeline
// view that sets every option 1.4.2 had, including the ones 2.0 removed. The Pipeline-only view type of 1.x is
// added by docker/upgrade.sh over the REST API, because Job DSL never had a method for it.

folder('legacy') {
    displayName('Legacy pipelines')
}
job('legacy/build') {
    deliveryPipelineConfiguration('Build', 'build')
    wrappers { deliveryPipelineVersion('1.0.${BUILD_NUMBER}', true) }
    steps { shell('sleep 1') }
    publishers { downstream('legacy/test', 'SUCCESS') }
}
job('legacy/test') {
    deliveryPipelineConfiguration('Test', 'test')
    steps { shell('sleep 1') }
    publishers {
        buildPipelineTrigger('legacy/deploy') {
            parameters { currentBuild() }
        }
    }
}
job('legacy/deploy') {
    deliveryPipelineConfiguration('Deploy', 'deploy')
    parameters { stringParam('VERSION', '1.0', '') }
    steps { shell('echo deploying $VERSION') }
}
pipelineJob('legacy/flow') {
    definition {
        cps {
            sandbox(true)
            script("node { stage('Build') { echo 'b' }; stage('Test') { echo 't' } }")
        }
    }
}
deliveryPipelineView('legacy/Chain') {
    pipelineInstances(3)
    showAggregatedPipeline()
    columns(1)
    sorting(javaposse.jobdsl.dsl.views.DeliveryPipelineView.Sorting.TITLE)
    showAvatars()
    updateInterval(3)
    showChangeLog()
    enableManualTriggers()
    showTestResults()
    showTotalBuildTime()
    allowRebuild()
    allowPipelineStart()
    showDescription()
    showPromotions()
    enablePaging()
    showStaticAnalysisResults()
    useRelativeLinks()
    linkToConsoleLog()
    pipelines {
        component('Chain', 'build')
        regex('legacy/(.*)-nothing')
    }
    configure { view ->
        view / 'embeddedCss'('/userContent/pipeline.css')
        view / 'fullScreenCss'('/userContent/fullscreen.css')
        view / 'showAggregatedChanges'(true)
        view / 'aggregatedChangesGroupingPattern'('JIRA-\\d+')
        view / 'allowAbort'(true)
        view / 'showAbsoluteDateTime'(true)
        view / 'maxNumberOfVisiblePipelines'(5)
    }
}
