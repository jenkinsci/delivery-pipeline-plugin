// Seed of the test controller: chains of freestyle jobs and Pipeline jobs that exercise every part of the
// Delivery Pipeline plugin, plus the views that show them. Run by Configuration as Code through Job DSL.

folder('demo') {
    displayName('Demo pipelines')
    description('Jobs that exercise the Delivery Pipeline plugin')
}

def junitReport = '''mkdir -p reports
cat > reports/junit.xml <<'XML'
<testsuite name="unit" tests="3" failures="1" skipped="0">
  <testcase classname="demo.CalculatorTest" name="adds"/>
  <testcase classname="demo.CalculatorTest" name="subtracts"/>
  <testcase classname="demo.CalculatorTest" name="divides"><failure message="division by zero"/></testcase>
</testsuite>
XML
'''

// ---------------------------------------------------------------- simple: three stages, version, tests, description
job('demo/simple-build') {
    description('First job of the simple chain')
    deliveryPipelineConfiguration('Build', 'compile')
    wrappers { deliveryPipelineVersion('1.0.${BUILD_NUMBER}', true) }
    triggers { cron('H/6 * * * *') }
    steps { shell('echo compiling; sleep 3') }
    publishers { downstream('demo/simple-test', 'SUCCESS') }
}
job('demo/simple-test') {
    deliveryPipelineConfiguration('Test', 'unit tests')
    steps { shell(junitReport) }
    publishers {
        archiveJunit('reports/*.xml')
        downstream('demo/simple-deploy', 'UNSTABLE')
    }
    configure { project ->
        // Job DSL's archiveJunit still writes keepLongStdio, which the JUnit plugin replaced; it would show up
        // in the old data monitor and hide anything this plugin left there.
        def archiver = project / 'publishers' / 'hudson.tasks.junit.JUnitResultArchiver'
        archiver.get('keepLongStdio').each { archiver.remove(it) }
    }
}
job('demo/simple-deploy') {
    deliveryPipelineConfiguration('Deploy', 'deploy to staging')
    configure { project ->
        (project / 'properties' / 'se.diabol.jenkins.pipeline.PipelineProperty')
            .appendNode('descriptionTemplate', 'Deployed <b>${PIPELINE_VERSION}</b> to staging')
    }
    steps { shell('echo deploying $PIPELINE_VERSION; sleep 2') }
}

// ---------------------------------------------------------------- fan-out: three tasks in one stage, native manual step
job('demo/fanout-build') {
    deliveryPipelineConfiguration('Build', 'build')
    steps { shell('sleep 2') }
    publishers { downstream('demo/fanout-unit, demo/fanout-integration, demo/fanout-lint', 'SUCCESS') }
}
['unit', 'integration', 'lint'].each { name ->
    job("demo/fanout-${name}") {
        deliveryPipelineConfiguration('Test', name)
        steps { shell("sleep ${name == 'integration' ? 6 : 2}") }
        publishers { downstream('demo/fanout-package', 'SUCCESS') }
    }
}
job('demo/fanout-package') {
    deliveryPipelineConfiguration('Package', 'package')
    steps { shell('sleep 1') }
    publishers {
        downstream('demo/fanout-deploy-staging', 'SUCCESS')
        // The plugin's own manual step: production is started by a person from the view.
        deliveryPipelineManualStep {
            downstreamProjectNames('demo/fanout-deploy-production')
        }
    }
}
job('demo/fanout-deploy-staging') {
    deliveryPipelineConfiguration('Deploy', 'staging')
    steps { shell('sleep 1') }
}
job('demo/fanout-deploy-production') {
    deliveryPipelineConfiguration('Deploy', 'production')
    parameters { stringParam('TARGET', 'eu-west', 'Where to deploy') }
    steps { shell('echo deploying to $TARGET; sleep 1') }
    publishers { downstream('demo/fanout-smoke', 'SUCCESS') }
}
job('demo/fanout-smoke') {
    deliveryPipelineConfiguration('Verify', 'smoke tests')
    steps { shell('sleep 1') }
}

// ---------------------------------------------------------------- bpp: the Build Pipeline plugin's manual trigger, with parameters
job('demo/bpp-build') {
    deliveryPipelineConfiguration('Build', 'build')
    parameters { stringParam('VERSION', '1.0', 'Version to deploy') }
    steps { shell('echo building $VERSION') }
    publishers {
        buildPipelineTrigger('demo/bpp-deploy') {
            parameters { currentBuild() }
        }
    }
}
job('demo/bpp-deploy') {
    deliveryPipelineConfiguration('Deploy', 'deploy')
    parameters { stringParam('VERSION', 'unset', 'Version to deploy') }
    steps { shell('echo deploying $VERSION') }
}

// ---------------------------------------------------------------- failing: a red task, an idle one behind it, a disabled one
job('demo/failing-build') {
    deliveryPipelineConfiguration('Build', 'build')
    steps { shell('sleep 1') }
    publishers { downstream('demo/failing-test, demo/failing-docs', 'SUCCESS') }
}
job('demo/failing-test') {
    deliveryPipelineConfiguration('Test', 'test')
    steps { shell('echo "tests failed"; exit 1') }
    publishers { downstream('demo/failing-deploy', 'SUCCESS') }
}
job('demo/failing-deploy') {
    deliveryPipelineConfiguration('Deploy', 'deploy')
    steps { shell('sleep 1') }
}
job('demo/failing-docs') {
    deliveryPipelineConfiguration('Docs', 'publish docs')
    disabled()
    steps { shell('sleep 1') }
}

// ---------------------------------------------------------------- diamond: fan-out and fan-in, five stages
job('demo/diamond-a') {
    deliveryPipelineConfiguration('Build', 'a')
    steps { shell('sleep 1') }
    publishers { downstream('demo/diamond-b, demo/diamond-c', 'SUCCESS') }
}
job('demo/diamond-b') {
    deliveryPipelineConfiguration('Test A', 'b')
    steps { shell('sleep 1') }
    publishers { downstream('demo/diamond-d', 'SUCCESS') }
}
job('demo/diamond-c') {
    deliveryPipelineConfiguration('Test B', 'c')
    steps { shell('sleep 3') }
    publishers { downstream('demo/diamond-d', 'SUCCESS') }
}
job('demo/diamond-d') {
    deliveryPipelineConfiguration('Package', 'd')
    steps { shell('sleep 1') }
    publishers { downstream('demo/diamond-e', 'SUCCESS') }
}
job('demo/diamond-e') {
    deliveryPipelineConfiguration('Release', 'e')
    steps { shell('sleep 1') }
}

// ---------------------------------------------------------------- long: eight stages in a row
(1..8).each { n ->
    job("demo/long-${n}") {
        deliveryPipelineConfiguration("Stage ${n}", "step ${n}")
        steps { shell('sleep 1') }
        if (n < 8) {
            publishers { downstream("demo/long-${n + 1}", 'SUCCESS') }
        }
    }
}

// ---------------------------------------------------------------- matrix: a multi-configuration job as a task
job('demo/matrix-build') {
    deliveryPipelineConfiguration('Build', 'build')
    steps { shell('sleep 1') }
    publishers { downstream('demo/matrix-test', 'SUCCESS') }
}
matrixJob('demo/matrix-test') {
    deliveryPipelineConfiguration('Test', 'test on')
    axes { text('os', 'linux', 'mac') }
    steps { shell('echo testing on $os') }
}

// ---------------------------------------------------------------- Pipeline jobs
pipelineJob('demo/pipeline-declarative') {
    description('Declarative: parallel nested stages and an input gate without an executor')
    definition {
        cps {
            sandbox(true)
            script('''pipeline {
  agent none
  stages {
    stage('Build') {
      agent any
      steps { echo 'building'; sleep 2 }
    }
    stage('Test') {
      parallel {
        stage('Unit') { agent any; steps { sleep 2 } }
        stage('Integration') { agent any; steps { sleep 4 } }
      }
    }
    stage('Approve') {
      steps { input message: 'Deploy to production?' }
    }
    stage('Deploy') {
      agent any
      steps { echo 'deploying' }
    }
  }
}''')
        }
    }
}
pipelineJob('demo/pipeline-scripted') {
    description('Scripted: an unstable parallel branch, test results per stage and branch, a warning of the run')
    definition {
        cps {
            sandbox(true)
            script('''node {
  stage('Build') {
    sh 'echo build'
    writeFile file: 'build.xml', text: '<testsuite name="unit" tests="2" failures="1"><testcase classname="A" name="passes"/><testcase classname="A" name="fails"><failure message="boom"/></testcase></testsuite>'
    junit 'build.xml'
    echo '[WARNING] /src/A.java:[3,5] [deprecation] foo() in A has been deprecated'
    recordIssues tool: java()
  }
  stage('Test') {
    parallel ok: {
      writeFile file: 'ok.xml', text: '<testsuite name="ok" tests="1"><testcase classname="B" name="works"/></testsuite>'
      junit 'ok.xml'
    }, flaky: { catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') { error 'flaky' } }
  }
  stage('Package') { echo 'packaging' }
}''')
        }
    }
}
pipelineJob('demo/pipeline-skipped') {
    description('Declarative: a stage skipped by a when condition, and a post section the view must not show as a stage')
    definition {
        cps {
            sandbox(true)
            script('''pipeline {
  agent any
  stages {
    stage('Build') { steps { echo 'b' } }
    stage('Deploy') {
      when { expression { false } }
      steps { echo 'd' }
    }
  }
  post {
    always {
      writeFile file: 'post.xml', text: '<testsuite name="post" tests="1"><testcase classname="P" name="one"/></testsuite>'
      junit 'post.xml'
    }
  }
}''')
        }
    }
}
pipelineJob('demo/pipeline-failing') {
    description('Scripted: a failing parallel branch')
    definition {
        cps {
            sandbox(true)
            script('''node {
  stage('Build') { echo 'b' }
  stage('Test') { parallel ok: { echo 'fine' }, bad: { error 'boom' } }
}''')
        }
    }
}
pipelineJob('demo/pipeline-long') {
    description('Scripted: runs for ten minutes, for progress bars and the abort button')
    definition {
        cps {
            sandbox(true)
            script('''node {
  stage('Prepare') { sleep 5 }
  stage('Work') { sleep 600 }
}''')
        }
    }
}

// ---------------------------------------------------------------- views
def pipelineView(String path, Closure components, Map options = [:]) {
    deliveryPipelineView(path) {
        pipelineInstances(options.instances ?: 3)
        showAggregatedPipeline(options.aggregated != false)
        showChangeLog()
        showDescription()
        showTotalBuildTime()
        showTestResults()
        showStaticAnalysisResults()
        showPromotions()
        allowPipelineStart()
        allowRebuild()
        enableManualTriggers()
        enablePaging()
        updateInterval(5)
        if (options.columns) {
            columns(options.columns)
        }
        if (options.sorting) {
            sorting(options.sorting)
        }
        pipelines(components)
        configure { view ->
            view / 'allowAbort'(true)
        }
    }
}

pipelineView('demo/Simple') { component('Simple', 'simple-build') }
pipelineView('demo/Fan-out') { component('Fan-out', 'fanout-build') }
pipelineView('demo/BPP') { component('Build Pipeline manual trigger', 'bpp-build') }
pipelineView('demo/Failing') { component('Failing', 'failing-build') }
pipelineView('demo/Diamond') { component('Diamond', 'diamond-a') }
pipelineView('demo/Long', { component('Long', 'long-1') }, [instances: 2])
pipelineView('demo/Matrix') { component('Matrix', 'matrix-build') }
pipelineView('demo/Pipelines', {
    component('Declarative', 'pipeline-declarative')
    component('Scripted', 'pipeline-scripted')
    component('Skipped', 'pipeline-skipped')
    component('Failing', 'pipeline-failing')
    component('Long running', 'pipeline-long')
}, [instances: 2, aggregated: false])
pipelineView('demo/Mixed', {
    component('Chained jobs', 'simple-build')
    component('Pipeline job', 'pipeline-declarative')
}, [instances: 2])
pipelineView('All pipelines', { regex('demo/(.*)-(build|a|1)$') }, [instances: 2, columns: 2, sorting: javaposse.jobdsl.dsl.views.DeliveryPipelineView.Sorting.LAST_ACTIVITY])

// The first builds are started by docker/validate.py: builds queued from this seed are discarded, because the
// seed runs before the queue is loaded during startup.
