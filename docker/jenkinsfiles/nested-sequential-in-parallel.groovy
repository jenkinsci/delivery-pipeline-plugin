// Declarative: sequential stages nested inside parallel branches; the branches are the tasks
pipeline {
  agent any
  stages {
    stage('Build') { steps { echo 'b' } }
    stage('Test') {
      parallel {
        stage('Linux') {
          stages {
            stage('Compile') { steps { echo 'c' } }
            stage('Unit') { steps { sleep 1 } }
          }
        }
        stage('Windows') {
          stages {
            stage('Compile') { steps { echo 'c' } }
            stage('Unit') { steps { sleep 1 } }
          }
        }
      }
    }
  }
}
