# A Jenkins controller with the Delivery Pipeline plugin from this checkout and a seeded set of jobs that
# exercise it. Built from the repository root: docker build -f docker/Dockerfile .
ARG JENKINS_VERSION=2.568.3
FROM jenkins/jenkins:${JENKINS_VERSION}-lts-jdk21

ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Dhudson.model.LoadStatistics.clock=1000 -Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true" \
    CASC_JENKINS_CONFIG=/usr/share/jenkins/ref/casc.yaml \
    JENKINS_OPTS="--httpPort=8080"

COPY docker/plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

COPY docker/casc.yaml /usr/share/jenkins/ref/casc.yaml
COPY docker/jobs.groovy /usr/share/jenkins/ref/jobs.groovy
# One Pipeline shape per file; the seed creates a job for each in the "Jenkinsfile zoo" folder.
COPY docker/jenkinsfiles/*.groovy /usr/share/jenkins/ref/jenkinsfiles/
# A git repository with two branches, each with a Jenkinsfile, for the multibranch project of the zoo. It lives in
# the image rather than under the reference directory, whose start-up copy takes files only and would drop the empty
# refs directory git needs. The branch jobs check it out on the controller, which the git plugin allows only with the
# property in JAVA_OPTS above.
USER root
RUN mkdir -p /srv/zoo && chown jenkins:jenkins /srv/zoo
USER jenkins
COPY --chown=jenkins:jenkins docker/jenkinsfiles/declarative-basic.groovy /tmp/app/Jenkinsfile
RUN cd /tmp/app && git init -q -b main && git add Jenkinsfile \
    && git -c user.name=zoo -c user.email=zoo@example.com commit -q -m 'main' \
    && git checkout -q -b release && sed -i "s/echo 'building'/echo 'building the release'/" Jenkinsfile \
    && git -c user.name=zoo -c user.email=zoo@example.com commit -qam 'release' && git checkout -q main \
    && git clone -q --bare /tmp/app /srv/zoo/app.git && rm -rf /tmp/app
# The plugin built by "mvn package"; the .override suffix makes a rebuilt image win over an older copy in a volume.
COPY target/delivery-pipeline-plugin.hpi /usr/share/jenkins/ref/plugins/delivery-pipeline-plugin.jpi.override
