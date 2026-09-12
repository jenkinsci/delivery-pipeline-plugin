// Scripted: stages inside parallel branches; the tasks carry the branch name, as "branch: stage"
node {
  stage('Build') { echo 'b' }
  stage('Test') {
    parallel(
      linux: { stage('Compile') { echo 'c' }; stage('Unit') { sleep 1 } },
      windows: { stage('Compile') { echo 'c' }; stage('Unit') { sleep 1 } })
  }
}
