// Scripted: starts two jobs from one stage, one without waiting; both started runs follow that stage
node {
  stage('Build') { echo 'building' }
  stage('Trigger') {
    parallel(
      basic: { build job: 'declarative-basic', wait: true },
      plain: { build job: 'scripted-plain-steps', wait: false })
  }
  stage('Report') { echo 'both started' }
}
