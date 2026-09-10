// Scripted: starts another Pipeline with the build step; the started run's stages follow the stage that started it
node {
  stage('Trigger') { build job: 'declarative-basic', wait: true }
  stage('Report') { echo 'the downstream run finished' }
}
