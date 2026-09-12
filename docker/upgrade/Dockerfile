# The test controller as it was with Delivery Pipeline 1.4.2 from the update center, used to write a
# configuration that the 2.0 image then has to load from the same Jenkins home. See docker/upgrade.sh.
ARG JENKINS_VERSION=2.568.3
FROM jenkins/jenkins:${JENKINS_VERSION}-lts-jdk21

ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false" \
    CASC_JENKINS_CONFIG=/usr/share/jenkins/ref/casc-legacy.yaml

COPY docker/upgrade/plugins.txt /usr/share/jenkins/ref/plugins-legacy.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins-legacy.txt

COPY docker/upgrade/casc.yaml /usr/share/jenkins/ref/casc-legacy.yaml
COPY docker/upgrade/jobs.groovy /usr/share/jenkins/ref/jobs-legacy.groovy
