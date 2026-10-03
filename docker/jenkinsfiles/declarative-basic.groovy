// Declarative: three stages and a post section, which the view leaves out
pipeline {
  agent any
  stages {
    stage('Build') { steps { echo 'building'; sleep 1 } }
    stage('Test') { steps { echo 'testing' } }
    stage('Deploy') { steps { echo 'deploying' } }
  }
  post { always { echo 'done' } }
}
