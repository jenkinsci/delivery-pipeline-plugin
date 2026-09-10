// Scripted: a parallel inside a parallel branch; the inner one folds into its branch's task
node {
  stage('Test') {
    parallel(
      a: { parallel(a1: { sleep 1 }, a2: { sleep 1 }) },
      b: { echo 'b' })
  }
}
