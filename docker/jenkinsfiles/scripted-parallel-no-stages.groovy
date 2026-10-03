// Scripted: parallel branches without any stage; the branches are the tasks of the one stage named after the job
node {
  parallel(
    a: { echo 'a'; sleep 1 },
    b: { echo 'b' })
}
