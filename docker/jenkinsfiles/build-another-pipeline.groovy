// Scripted: starts another Pipeline with the build step; the two stay separate components, not a chain
node {
  stage('Trigger') { build job: 'declarative-basic', wait: true }
  stage('Report') { echo 'the downstream run finished' }
}
