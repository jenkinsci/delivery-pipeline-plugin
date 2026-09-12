// Scripted: retry, timeout and milestone wrappers around ordinary stages
node {
  stage('Build') {
    retry(2) {
      if (!fileExists('attempted')) {
        writeFile file: 'attempted', text: 'x'
        error 'the first attempt fails'
      }
      echo 'the second attempt works'
    }
  }
  stage('Test') {
    timeout(time: 30, unit: 'SECONDS') { sleep 1 }
  }
  milestone 1
  stage('Deploy') { echo 'd' }
}
