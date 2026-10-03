// Scripted: starts another Pipeline with the build step, outside node so that no executor waits; the started run's stages follow the stage that started it
stage('Trigger') { build job: 'declarative-basic', wait: true }
stage('Report') { node { echo 'the downstream run finished' } }
