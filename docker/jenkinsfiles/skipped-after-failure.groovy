// Declarative: a failing stage, after which the remaining stages are skipped
pipeline {
  agent any
  stages {
    stage('Build') { steps { error 'compilation failed' } }
    stage('Test') { steps { echo 't' } }
    stage('Deploy') { steps { echo 'd' } }
  }
}
