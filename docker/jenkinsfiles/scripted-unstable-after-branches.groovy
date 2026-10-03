// Scripted: a junit step after the branches marks the stage unstable while both branches passed; the stage header shows it
node {
  stage('Test') {
    parallel(a: { echo 'a' }, b: { echo 'b' })
    writeFile file: 'unit.xml', text: '<testsuite name="unit" tests="1" failures="1"><testcase classname="A" name="fails"><failure message="boom"/></testcase></testsuite>'
    junit 'unit.xml'
  }
}
