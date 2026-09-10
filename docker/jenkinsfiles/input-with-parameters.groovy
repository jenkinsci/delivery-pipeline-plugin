// Declarative: an input directive with a parameter; the view links to the input page instead of proceeding
pipeline {
  agent none
  stages {
    stage('Build') { agent any; steps { echo 'b' } }
    stage('Approve') {
      input {
        message 'Ship it?'
        parameters { string(name: 'RELEASE', defaultValue: '1.0') }
      }
      steps { echo "shipping ${RELEASE}" }
    }
  }
}
