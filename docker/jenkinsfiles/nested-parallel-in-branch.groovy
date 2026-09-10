// Scripted: a parallel inside a parallel branch; the inner branches are the tasks, named after both branches
node {
  stage('Test') {
    parallel(
      a: { parallel(a1: { sleep 1 }, a2: { sleep 1 }) },
      b: { echo 'b' })
  }
}
