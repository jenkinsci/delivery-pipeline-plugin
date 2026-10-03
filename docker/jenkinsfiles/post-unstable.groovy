// Declarative: a stage marked unstable by the unstable step, with a post section for that result
pipeline {
  agent any
  stages {
    stage('Build') { steps { echo 'b' } }
    stage('Test') { steps { unstable('flaky tests') } }
    stage('Deploy') { steps { echo 'd' } }
  }
  post { unstable { echo 'someone should look at the tests' } }
}
