// Scripted: parallel branches with failFast; one fails at once and interrupts the other
node {
  stage('Build') { echo 'b' }
  stage('Test') {
    parallel(failFast: true,
      slow: { sleep 30 },
      broken: { error 'boom' })
  }
}
