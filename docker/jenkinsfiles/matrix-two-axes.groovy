// Declarative: a matrix over two axes with one cell excluded
pipeline {
  agent none
  stages {
    stage('Build') { agent any; steps { echo 'b' } }
    stage('Test') {
      matrix {
        axes {
          axis { name 'OS'; values 'linux', 'mac' }
          axis { name 'BROWSER'; values 'chrome', 'firefox' }
        }
        excludes {
          exclude {
            axis { name 'OS'; values 'mac' }
            axis { name 'BROWSER'; values 'firefox' }
          }
        }
        agent any
        stages {
          stage('Run') { steps { echo "${OS} ${BROWSER}" } }
        }
      }
    }
  }
}
