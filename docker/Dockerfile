# A Jenkins controller with the Delivery Pipeline plugin from this checkout and a seeded set of jobs that
# exercise it. Built from the repository root: docker build -f docker/Dockerfile .
ARG JENKINS_VERSION=2.568.3
FROM jenkins/jenkins:${JENKINS_VERSION}-lts-jdk21

ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Dhudson.model.LoadStatistics.clock=1000" \
    CASC_JENKINS_CONFIG=/usr/share/jenkins/ref/casc.yaml \
    JENKINS_OPTS="--httpPort=8080"

COPY docker/plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

COPY docker/casc.yaml /usr/share/jenkins/ref/casc.yaml
COPY docker/jobs.groovy /usr/share/jenkins/ref/jobs.groovy
# One Pipeline shape per file; the seed creates a job for each in the "Jenkinsfile zoo" folder.
COPY docker/jenkinsfiles/*.groovy /usr/share/jenkins/ref/jenkinsfiles/
# The plugin built by "mvn package"; the .override suffix makes a rebuilt image win over an older copy in a volume.
COPY target/delivery-pipeline-plugin.hpi /usr/share/jenkins/ref/plugins/delivery-pipeline-plugin.jpi.override
