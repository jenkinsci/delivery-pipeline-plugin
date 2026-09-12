// Declarative: a stage skipped by a when condition evaluated before its agent is allocated
pipeline {
  agent none
  stages {
    stage('Build') { agent any; steps { echo 'b' } }
    stage('Deploy') {
      agent any
      when { beforeAgent true; expression { return false } }
      steps { echo 'never' }
    }
  }
}
