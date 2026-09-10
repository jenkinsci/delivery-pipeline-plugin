// Scripted: plain steps without a stage; the run is one task named after the job, with its test results
node {
  writeFile file: 'unit.xml', text: '<testsuite name="unit" tests="2" failures="1"><testcase classname="A" name="passes"/><testcase classname="A" name="fails"><failure message="boom"/></testcase></testsuite>'
  junit 'unit.xml'
}
