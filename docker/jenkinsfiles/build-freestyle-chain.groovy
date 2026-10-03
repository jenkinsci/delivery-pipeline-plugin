// Scripted: starts a freestyle job whose build trigger starts another, outside node; the whole chain follows the stage
node { stage('Build') { echo 'building' } }
stage('Trigger') { build job: 'free-build' }
node { stage('Report') { echo 'the chain is on its way' } }
