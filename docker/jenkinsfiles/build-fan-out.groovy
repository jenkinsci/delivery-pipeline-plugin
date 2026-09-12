// Scripted: starts two jobs from one stage, one without waiting, outside node; both started runs follow that stage
node { stage('Build') { echo 'building' } }
stage('Trigger') {
  parallel(
    basic: { build job: 'declarative-basic', wait: true },
    plain: { build job: 'scripted-plain-steps', wait: false })
}
node { stage('Report') { echo 'both started' } }
