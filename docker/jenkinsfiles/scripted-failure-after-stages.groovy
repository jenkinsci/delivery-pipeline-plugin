// Scripted: a step after the last stage fails; the run's status shows on its heading while every task passed
node {
  stage('Build') { echo 'building' }
  error 'broken after the stages'
}
