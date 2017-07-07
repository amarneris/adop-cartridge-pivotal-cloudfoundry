import com.cloudbees.plugins.credentials.impl.*;
import com.cloudbees.plugins.credentials.*;
import com.cloudbees.plugins.credentials.domains.*;
import jenkins.model.*;

// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Variables
def pivotalCredentials = "CF_Credentials"
def projectNameKey = projectFolderName.toLowerCase().replace("/", "-")
def referenceAppgitRepo = "spring-framework-petclinic"
def regressionTestGitRepo = "adop-cartridge-java-regression-tests"
def referenceAppGitUrl = "ssh://jenkins@gerrit:29418/${PROJECT_NAME}/" + referenceAppgitRepo
def regressionTestGitUrl = "ssh://jenkins@gerrit:29418/${PROJECT_NAME}/" + regressionTestGitRepo

// Jobs
def buildAppJob = freeStyleJob(projectFolderName + "/Application_Build")
def pivotalEphemerolJob = freeStyleJob(projectFolderName + "/Application_Cloud_Readiness_Test")
def unitTestJob = freeStyleJob(projectFolderName + "/Application_Unit_Tests")
def codeAnalysisJob = freeStyleJob(projectFolderName + "/Application_Code_Analysis")
def deployJob = freeStyleJob(projectFolderName + "/Application_Deploy_Dev")
def deployJobToProd = freeStyleJob(projectFolderName + "/Application_Deploy_Prod")
def regressionTestJob = freeStyleJob(projectFolderName + "/Application_Regression_Tests")
def performanceTestJob = freeStyleJob(projectFolderName + "/Application_Performance_Tests")
def highavailabilityCFDevJob = freeStyleJob(projectFolderName + "/High_Availability_Dev_Test")
def highavailabilityCFProdJob = freeStyleJob(projectFolderName + "/High_Availability_Prod_Test")
def destroyCFDevJob = freeStyleJob(projectFolderName + "/Destroy_Dev_Environment")
def destroyCFProdJob = freeStyleJob(projectFolderName + "/Destroy_Prod_Environment")

// Views
def pipelineView = buildPipelineView(projectFolderName + "/Java Application Pivotal")

pipelineView.with {
    title('Java Reference Application Pivotal Pipeline')
    displayedBuilds(5)
    selectedJob(projectFolderName + "/Application_Build")
    showPipelineDefinitionHeader()
    refreshFrequency(5)
}

def environmentResetPipelineView = buildPipelineView(projectFolderName + "/Reset Pivotal Environments")

environmentResetPipelineView.with {
    title('Reset Pivotal Environments Pipeline')
    displayedBuilds(5)
    selectedJob(projectFolderName + "/Destroy_Dev_Environment")
    showPipelineDefinitionHeader()
    refreshFrequency(5)
}

destroyCFDevJob.with {
    description("This job destroys the application, service-bindings and space from Pivotal CF")
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
        credentialsBinding {
          usernamePassword("CF_USERNAME", "CF_PASSWORD", pivotalCredentials)
        }
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
        env('ENVIRONMENT_NAME', 'ci')
    }
    label("docker")
    steps {
        shell('''
            |export SERVICE_NAME="$(echo ${PROJECT_NAME} | tr '/' '_')_${ENVIRONMENT_NAME}"
            |curl -L "https://cli.run.pivotal.io/stable?release=linux64-binary&source=github" | tar -zx
            |set +x
            |./cf login -a api.run.pivotal.io -u ${CF_USERNAME} -p ${CF_PASSWORD}
            |set -x
            |set +x
            |echo "========================================================================"
            |echo "Deleting the ${ENVIRONMENT_NAME} environment"
            |echo "========================================================================"
            |set -x
            |./cf target -s development
            |./cf delete -f adop-petclinic-${ENVIRONMENT_NAME}
            |./cf delete-service -f cf-petclinic-${ENVIRONMENT_NAME}-db
            |./cf delete-space -f development
            |set +x
            |'''.stripMargin()
        )
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/Destroy_Prod_Environment") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("ENVIRONMENT_NAME", 'prod')
                }
            }
        }
    }
}

destroyCFProdJob.with {
    description("This job destroys the application, service-bindings and space from Pivotal CF")
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
        credentialsBinding {
          usernamePassword("CF_USERNAME", "CF_PASSWORD", pivotalCredentials)
        }
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
        env('ENVIRONMENT_NAME', 'prod')
    }
    label("docker")
    steps {
        shell('''
            |export SERVICE_NAME="$(echo ${PROJECT_NAME} | tr '/' '_')_${ENVIRONMENT_NAME}"
            |curl -L "https://cli.run.pivotal.io/stable?release=linux64-binary&source=github" | tar -zx
            |set +x
            |./cf login -a api.run.pivotal.io -u ${CF_USERNAME} -p ${CF_PASSWORD}
            |set -x
            |set +x
            |echo "========================================================================"
            |echo "Deleting the ${ENVIRONMENT_NAME} environment"
            |echo "========================================================================"
            |set -x
            |./cf target -s production
            |./cf delete -f adop-petclinic-${ENVRIRONMENT_NAME}
            |./cf delete-service -f cf-petclinic-${ENVRIRONMENT_NAME}-db
            |./cf delete-space -f production
            |set +x
            |'''.stripMargin()
        )
    }
}

buildAppJob.with {
    description("This job builds Java Spring reference application")
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    scm {
        git {
            remote {
                url(referenceAppGitUrl)
                credentials("adop-jenkins-master")
            }
            branch("*/master")
        }
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    label("java8")
    triggers {
        gerrit {
            events {
                refUpdated()
            }
            project(projectFolderName + '/' + referenceAppgitRepo, 'plain:master')
            configure { node ->
                node / serverName("ADOP Gerrit")
            }
        }
    }
    steps {
        maven {
            goals('clean install -P PostgreSQL -Dmaven.test.skip=true')
            mavenInstallation("ADOP Maven")
        }
    }
    publishers {
        archiveArtifacts("**/*")
        downstreamParameterized {
            trigger(projectFolderName + "/Application_Unit_Tests") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${BUILD_NUMBER}')
                    predefinedProp("PARENT_BUILD", '${JOB_NAME}')
                }
            }
        }
    }
}
unitTestJob.with {
    description("This job runs unit tests on Java Spring reference application.")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Application_Build", "Parent build name")
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    label("java8")
    steps {
        copyArtifacts("Application_Build") {
            buildSelector {
                buildNumber('${B}')
            }
        }
        maven {
            goals('clean test')
            mavenInstallation("ADOP Maven")
        }
    }
    publishers {
        archiveArtifacts("**/*")
        downstreamParameterized {
            trigger(projectFolderName + "/Application_Code_Analysis") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                }
            }
        }
    }
}

codeAnalysisJob.with {
    description("This job runs code quality analysis for Java reference application using SonarQube.")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Application_Build", "Parent build name")
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
        env('PROJECT_NAME_KEY', projectNameKey)
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    label("java8")
    steps {
        copyArtifacts('Application_Build') {
            buildSelector {
                buildNumber('${B}')
            }
        }
    }
    configure { myProject ->
        myProject / builders << 'hudson.plugins.sonar.SonarRunnerBuilder'(plugin: "sonar@2.2.1") {
            project('sonar-project.properties')
            properties('''sonar.projectKey=${PROJECT_NAME_KEY}
sonar.projectName=${PROJECT_NAME}
sonar.projectVersion=1.0.${B}
sonar.sources=src/main/java
sonar.language=java
sonar.sourceEncoding=UTF-8
sonar.scm.enabled=false''')
            javaOpts()
            jdk('(Inherit From Job)')
            task()
        }
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/Application_Cloud_Readiness_Test") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                }
            }
        }
    }
}

pivotalEphemerolJob.with {
description("This job runs Pivotal's ephemerol test utility to detect cloud-readiness.")
parameters {
    stringParam("B", '', "Parent build number")
    stringParam("PARENT_BUILD", "Application_Build", "Parent build name")
}
wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
}
environmentVariables {
    env('WORKSPACE_NAME', workspaceFolderName)
    env('PROJECT_NAME', projectFolderName)
}
label("java8")
steps {
    copyArtifacts("Application_Build") {
        targetDirectory('spring-framework-petclinic')
        buildSelector {
            buildNumber('${B}')
        }
    }
    shell('''
        |#Environment preparation
        |set +e
        |pip show pyyaml || pip install pyyaml
        |pip show json2html || pip install json2html
        |set -e
        |git clone https://github.com/Pivotal-Field-Engineering/ephemerol.git
        |'''.stripMargin()
    )
    shell('''
        |python -m zipfile -c spring-framework-petclinic.zip spring-framework-petclinic/
        |cd ephemerol
        |python -m ephemerol ./ephemerol/static/default-rulebase.yml ../spring-framework-petclinic.zip > ${WORKSPACE}/ephemerol_output.json
        |'''.stripMargin()
    )
    shell('''#!/usr/bin/python
        |from json2html import *
        |json_object  = open("ephemerol_output.json", "r")
        |html_object = json2html.convert(json = json_object.read())
        |json_object.close()
        |
        |html_report = open("ephemerol_output.html", "w")
        |html_report.write(html_object)
        |html_report.close()
        |'''.stripMargin()
    )
    shell('''
        |# Confirm that the ephemerol analysis does not contain "refactor_rating" over 0
        |for rating in $(grep "refactor_rating" ephemerol_output.json |uniq |awk -F\\" '{print $4}'); do
        |  if [ $rating -gt 0 ]; then
        |    echo "The rating is not great. Code needs refactoring";
        |    exit 1
        |  fi;
        |done
        |'''.stripMargin()
    )
}
publishers {
    archiveArtifacts("ephemerol_output.html")
    downstreamParameterized {
        trigger(projectFolderName + "/Application_Deploy_Dev") {
            condition("UNSTABLE_OR_BETTER")
            parameters {
                predefinedProp("B", '${B}')
                predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                predefinedProp("CF_APP_INSTANCES", '1')
                predefinedProp("ENVIRONMENT_NAME", 'ci')
            }
        }
    }
  }
}

deployJob.with {
    description("This job deploys the java reference application to Pivotal CF development space using one instance")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Application_Build", "Parent build name")
        stringParam("CF_APP_INSTANCES", '', "Number of CF instances")
        stringParam("ENVIRONMENT_NAME", '', "Name of the environment.")

    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
        credentialsBinding {
          usernamePassword("CF_USERNAME", "CF_PASSWORD", pivotalCredentials)
        }
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    label("docker")
    steps {
        copyArtifacts("Application_Build") {
            buildSelector {
                buildNumber('${B}')
                includePatterns('target/petclinic.war')
            }
        }
        shell('''
            |export SERVICE_NAME="$(echo ${PROJECT_NAME} | tr '/' '_')_${ENVIRONMENT_NAME}"
            |curl -L "https://cli.run.pivotal.io/stable?release=linux64-binary&source=github" | tar -zx
            |set +x
            |./cf login -a api.run.pivotal.io -u ${CF_USERNAME} -p ${CF_PASSWORD}
            |set -x
            |./cf create-space development
            |./cf target -s development
            |./cf delete -f adop-petclinic-${ENVIRONMENT_NAME}
            |./cf delete-service -f cf-petclinic-${ENVIRONMENT_NAME}-db
            |./cf create-service elephantsql turtle cf-petclinic-${ENVIRONMENT_NAME}-db
            |set +x
            |echo "========================================================================"
            |echo "Creating the application manifest file"
            |echo "========================================================================"
            |set -x
            |cat <<EOF > manifest.yml
            |---
            |applications:
            |- name: adop-petclinic-${ENVIRONMENT_NAME}
            |  memory: 512m
            |  instances: ${CF_APP_INSTANCES}
            |  random-route: true
            |  services:
            |    - cf-petclinic-${ENVIRONMENT_NAME}-db
            |  env:
            |    JAVA_OPTS: "-Dspring.profiles.active=jdbc"
            |EOF
            |set +x
            |echo "========================================================================"
            |echo "Pushing application to Pivotal CF using ${CF_APP_INSTANCES} instance(s)"
            |echo "========================================================================"
            |set -x
            |./cf push -f manifest.yml -p ${WORKSPACE}/target/petclinic.war
            |APP_HOSTNAME=$(./cf apps |grep adop-petclinic-${ENVIRONMENT_NAME} |awk '{print $6}')
            |echo "APP_HOSTNAME=${APP_HOSTNAME}" > ${WORKSPACE}/app_hostname.txt
            |COUNT=1
            |while ! curl -f -q http://${APP_HOSTNAME}/ -o /dev/null
            |do
            |  if [ ${COUNT} -gt 10 ]; then
            |    echo "Deploy failed even after ${COUNT}. Please investigate."
            |    exit 1
            |  fi
            |  echo "Application is not up yet. Retrying ..Attempt (${COUNT})"
            |  sleep 5
            |  COUNT=$((COUNT+1))
            |done
            |set +x
            |echo "=.=.=.=.=.=.=.=.=.=.=.=."
            |echo "=.=.=.=.=.=.=.=.=.=.=.=."
            |echo "Application URL: http://${APP_HOSTNAME}/"
            |echo "=.=.=.=.=.=.=.=.=.=.=.=."
            |echo "=.=.=.=.=.=.=.=.=.=.=.=."'''.stripMargin()
        )
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/Application_Regression_Tests") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                    predefinedProp("ENVIRONMENT_NAME", '${ENVIRONMENT_NAME}')
                    propertiesFile("app_hostname.txt")
                }
            }
        }
    }
}

regressionTestJob.with {
    description("This job runs regression tests on deployed java application")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Application_Build", "Parent build name")
        stringParam("ENVIRONMENT_NAME", "ci", "Name of the environment.")
        stringParam("APP_HOSTNAME", "", "The deployed application's hostname.")
    }
    scm {
        git {
            remote {
                url(regressionTestGitUrl)
                credentials("adop-jenkins-master")
            }
            branch("*/master")
        }
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    label("java8")
    steps {
        shell('''
            |export SERVICE_NAME="$(echo ${PROJECT_NAME} | tr '/' '_')_${ENVIRONMENT_NAME}"
            |echo "SERVICE_NAME=${SERVICE_NAME}" > env.properties
            |
            |echo "Running automation tests"
            |echo "Setting values for container, project and app names"
            |CONTAINER_NAME="owasp_zap-"${SERVICE_NAME}${BUILD_NUMBER}
            |APP_URL="http://${APP_HOSTNAME}/"
            |ZAP_PORT="9090"
            |
            |echo CONTAINER_NAME=$CONTAINER_NAME >> env.properties
            |echo APP_URL=$APP_URL >> env.properties
            |echo ZAP_PORT=$ZAP_PORT >> env.properties
            |
            |echo "Starting OWASP ZAP Intercepting Proxy"
            |JOB_WORKSPACE_PATH="/var/lib/docker/volumes/jenkins_slave_home/_data/${PROJECT_NAME}/Application_Regression_Tests"
            |echo JOB_WORKSPACE_PATH=$JOB_WORKSPACE_PATH >> env.properties
            |mkdir -p ${JOB_WORKSPACE_PATH}/owasp_zap_proxy/test-results
            |docker run -it -d --net=$DOCKER_NETWORK_NAME -v ${JOB_WORKSPACE_PATH}/owasp_zap_proxy/test-results/:/opt/zaproxy/test-results/ -e affinity:container==jenkins-slave --name ${CONTAINER_NAME} -P nhantd/owasp_zap start zap-test
            |
            |sleep 30s
            |ZAP_IP=$( docker inspect --format '{{ .NetworkSettings.Networks.'"$DOCKER_NETWORK_NAME"'.IPAddress }}' ${CONTAINER_NAME} )
            |echo "ZAP_IP =  $ZAP_IP"
            |echo ZAP_IP=$ZAP_IP >> env.properties
            |echo ZAP_ENABLED="true" >> env.properties
            |echo "Running Selenium tests through maven."
            |'''.stripMargin()
        )
        environmentVariables {
            propertiesFile('env.properties')
        }
        maven {
            goals('clean -B test -DPETCLINIC_URL=${APP_URL} -DZAP_IP=${ZAP_IP} -DZAP_PORT=${ZAP_PORT} -DZAP_ENABLED=${ZAP_ENABLED}')
            mavenInstallation("ADOP Maven")
        }
        shell('''
            |echo "Stopping OWASP ZAP Proxy and generating report."
            |docker stop ${CONTAINER_NAME}
            |docker rm ${CONTAINER_NAME}
            |
            |docker run -i --net=$DOCKER_NETWORK_NAME -v ${JOB_WORKSPACE_PATH}/owasp_zap_proxy/test-results/:/opt/zaproxy/test-results/ -e affinity:container==jenkins-slave --name ${CONTAINER_NAME} -P nhantd/owasp_zap stop zap-test
            |docker cp ${CONTAINER_NAME}:/opt/zaproxy/test-results/zap-test-report.html .
            |sleep 10s
            |docker rm ${CONTAINER_NAME}
            |'''.stripMargin()
        )
    }
    configure { myProject ->
        myProject / 'publishers' << 'net.masterthought.jenkins.CucumberReportPublisher'(plugin: 'cucumber-reports@0.1.0') {
            jsonReportDirectory("")
            pluginUrlPath("")
            fileIncludePattern("")
            fileExcludePattern("")
            skippedFails("false")
            pendingFails("false")
            undefinedFails("false")
            missingFails("false")
            noFlashCharts("false")
            ignoreFailedTests("false")
            parallelTesting("false")
        }
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/Application_Performance_Tests") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                    predefinedProp("ENVIRONMENT_NAME", '${ENVIRONMENT_NAME}')
                    predefinedProp("APP_HOSTNAME", '${APP_HOSTNAME}')
                }
            }
        }
        publishHtml {
            report('$WORKSPACE') {
                reportName('ZAP security test report')
                reportFiles('zap-test-report.html')
            }
        }
    }
}

performanceTestJob.with {
    description("This job run the Jmeter test for the java reference application")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Application_Regression_Tests", "Parent build name")
        stringParam("ENVIRONMENT_NAME", "ci", "Name of the environment.")
        stringParam("APP_HOSTNAME", "", "The deployed application's hostname.")
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
        env('JMETER_TESTDIR', 'jmeter-test')
    }
    label("docker")
    scm {
        git {
            remote {
                url("https://github.com/Accenture/spring-petclinic")
            }
            branch("*/master")
            extensions {
              relativeTargetDirectory ('${JMETER_TESTDIR}')
            }
        }
    }
    steps {
        shell('''
            |export SERVICE_NAME="$(echo ${PROJECT_NAME} | tr '/' '_')_${ENVIRONMENT_NAME}"
            |if [ -e ../apache-jmeter-2.13.tgz ]; then
            |   cp ../apache-jmeter-2.13.tgz $JMETER_TESTDIR
            |else
            |   wget https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-2.13.tgz
            |    cp apache-jmeter-2.13.tgz ../
            |    mv apache-jmeter-2.13.tgz $JMETER_TESTDIR
            |fi
            |cd $JMETER_TESTDIR
            |tar -xf apache-jmeter-2.13.tgz
            |echo "Changing user defined parameters for jmx file"
            |sed -i "s/PETCLINIC_HOST_VALUE/${APP_HOSTNAME}/g" src/test/jmeter/petclinic_test_plan.jmx
            |sed -i "s/PETCLINIC_PORT_VALUE/80/g" src/test/jmeter/petclinic_test_plan.jmx
            |sed -i "s/CONTEXT_WEB_VALUE//g" src/test/jmeter/petclinic_test_plan.jmx
            |sed -i "s/HTTPSampler.path\\"></HTTPSampler.path\\">petclinic</g" src/test/jmeter/petclinic_test_plan.jmx
            |'''.stripMargin()
        )
        ant {
            props('testpath': '$WORKSPACE/$JMETER_TESTDIR/src/test/jmeter', 'test': 'petclinic_test_plan')
            buildFile('${WORKSPACE}/$JMETER_TESTDIR/apache-jmeter-2.13/extras/build.xml')
            antInstallation('ADOP Ant')
        }
        shell('''mv $JMETER_TESTDIR/src/test/gatling/* .
            |export SERVICE_NAME="$(echo ${PROJECT_NAME} | tr '/' '_')_${ENVIRONMENT_NAME}"
            |sed -i "s/###TOKEN_VALID_URL###/http:\\/\\/${APP_HOSTNAME}/g" ${WORKSPACE}/src/test/scala/default/RecordedSimulation.scala
            |sed -i "s/###TOKEN_RESPONSE_TIME###/10000/g" ${WORKSPACE}/src/test/scala/default/RecordedSimulation.scala
            |'''.stripMargin()
        )
        maven {
            goals('gatling:execute')
            mavenInstallation('ADOP Maven')
        }
    }
    publishers {
        publishHtml {
            report('$WORKSPACE/$JMETER_TESTDIR/src/test/jmeter') {
                reportName('Jmeter Report')
                reportFiles('petclinic_test_plan.html')
            }
        }
        downstreamParameterized {
            trigger(projectFolderName + "/High_Availability_Dev_Test") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                    predefinedProp("ENVIRONMENT_NAME", '${ENVIRONMENT_NAME}')
                    predefinedProp("APP_HOSTNAME", '${APP_HOSTNAME}')
                }
            }
        }
    }
    configure { project ->
        project / publishers << 'io.gatling.jenkins.GatlingPublisher' {
            enabled true
        }
    }
}

highavailabilityCFDevJob.with {
    description("This job makes a high availability test on the application that runs in Pivotal CF development space")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Application_Build", "Parent build name")
        stringParam("ENVIRONMENT_NAME", "ci", "Name of the environment.")
        stringParam("APP_HOSTNAME", "", "The deployed application's hostname.")
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
        credentialsBinding {
          usernamePassword("CF_USERNAME", "CF_PASSWORD", pivotalCredentials)
        }
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    label("docker")
    steps {
        copyArtifacts("Application_Build") {
            buildSelector {
                buildNumber('${B}')
                includePatterns('target/petclinic.war')
            }
        }
        shell('''set +x
            |export SERVICE_NAME="$(echo ${PROJECT_NAME} | tr '/' '_')_${ENVIRONMENT_NAME}"
            |echo "================================================================="
            |echo "This is a high availability test of Pivotal CF development space."
            |echo "================================================================="
            |curl -L "https://cli.run.pivotal.io/stable?release=linux64-binary&source=github" | tar -zx
            |echo -e "\\nLogin to Pivotal CF\\n"
            |./cf login -a api.run.pivotal.io -u ${CF_USERNAME} -p ${CF_PASSWORD}
            |echo -e "\\nTarget Pivotal CF development space\\n"
            |./cf target -s development
            |echo "================================================================="
            |echo "Killing CF application in CF developement space"
            |echo "================================================================="
            |set -x
            |./cf ssh adop-petclinic-{ENVIRONMENT_NAME} -c 'kill -9 $(pidof java)'
            |set +x
            |echo "================================================================="
            |echo "Instance killed - Waiting until the application comes up again"
            |echo "================================================================="
            |set -x
            |COUNT=1
            |while ! curl -f -q http://${APP_HOSTNAME}/ -o /dev/null
            |do
            |  if [ ${COUNT} -gt 15 ]; then
            |    echo "Deploy failed even after ${COUNT}. Please investigate."
            |    exit 1
            |  fi
            |  echo "Application is not up yet. Retrying ..Attempt (${COUNT})"
            |  sleep 20
            |  COUNT=$((COUNT+1))
            |done
            |set +x
            |echo "================================================================="
            |echo "The application is up and running again..."
            |echo "================================================================="
            |set -x'''.stripMargin()
        )
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/Application_Deploy_Prod") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                    predefinedProp("CF_APP_INSTANCES", '2')
                    predefinedProp("ENVIRONMENT_NAME", 'prod')
                }
            }
        }
    }
}
deployJobToProd.with {
    description("This job deploys the java reference application to Pivotal CF production space using 2 instances")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Application_Build", "Parent build name")
        stringParam("CF_APP_INSTANCES", '', "Number of CF instances")
        stringParam("ENVIRONMENT_NAME", "prod", "Name of the environment.")
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
        credentialsBinding {
          usernamePassword("CF_USERNAME", "CF_PASSWORD", pivotalCredentials)
        }
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    label("docker")
    steps {
        copyArtifacts("Application_Build") {
            buildSelector {
                buildNumber('${B}')
                includePatterns('target/petclinic.war')
            }
        }
        shell('''
            |export SERVICE_NAME="$(echo ${PROJECT_NAME} | tr '/' '_')_${ENVIRONMENT_NAME}"
            |curl -L "https://cli.run.pivotal.io/stable?release=linux64-binary&source=github" | tar -zx
            |echo -e "\\nLogin to Pivotal CF\\n"
            |set +x
            |./cf login -a api.run.pivotal.io -u ${CF_USERNAME} -p ${CF_PASSWORD}
            |set -x
            |./cf create-space production
            |./cf target -s production
            |./cf create-service elephantsql turtle cf-petclinic-${ENVIRONMENT_NAME}-db
            |set +x
            |echo "========================================================================"
            |echo "Creating the application manifest file"
            |echo "========================================================================"
            |set -x
            |cat <<EOF > manifest.yml
            |---
            |applications:
            |- name: adop-petclinic-${ENVIRONMENT_NAME}
            |  memory: 512m
            |  instances: ${CF_APP_INSTANCES}
            |  random-route: true
            |  services:
            |    - cf-petclinic-${ENVIRONMENT_NAME}-db
            |  env:
            |    JAVA_OPTS: "-Dspring.profiles.active=jdbc"
            |EOF
            |set +x
            |echo "========================================================================"
            |echo "Pushing application to Pivotal CF using ${CF_APP_INSTANCES} instance(s)"
            |echo "========================================================================"
            |set -x
            |./cf push -f manifest.yml -p ${WORKSPACE}/target/petclinic.war
            |
            |APP_HOSTNAME=$(./cf apps |grep adop-petclinic-${ENVIRONMENT_NAME} |awk '{print $6}')
            |echo "APP_HOSTNAME=${APP_HOSTNAME}" > ${WORKSPACE}/app_hostname.txt
            |COUNT=1
            |while ! curl -f -q http://${APP_HOSTNAME}/ -o /dev/null
            |do
            |  if [ ${COUNT} -gt 10 ]; then
            |    echo "Deploy failed even after ${COUNT}. Please investigate."
            |    exit 1
            |  fi
            |  echo "Application is not up yet. Retrying ..Attempt (${COUNT})"
            |  sleep 5
            |  COUNT=$((COUNT+1))
            |done
            |set +x
            |echo "=.=.=.=.=.=.=.=.=.=.=.=."
            |echo "=.=.=.=.=.=.=.=.=.=.=.=."
            |echo "Application URL: http://${APP_HOSTNAME}/"
            |echo "=.=.=.=.=.=.=.=.=.=.=.=."
            |echo "=.=.=.=.=.=.=.=.=.=.=.=."'''.stripMargin())
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/High_Availability_Prod_Test") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                    predefinedProp("ENVIRONMENT_NAME", '${ENVIRONMENT_NAME}')
                    propertiesFile("app_hostname.txt")
                }
            }
        }
    }
}

highavailabilityCFProdJob.with {
    description("This job makes a high availability test on the application that runs in Pivotal CF production space")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Application_Build", "Parent build name")
        stringParam("ENVIRONMENT_NAME", "prod", "Name of the environment.")
        stringParam("APP_HOSTNAME", "", "The deployed application's hostname.")
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
        credentialsBinding {
          usernamePassword("CF_USERNAME", "CF_PASSWORD", pivotalCredentials)
        }
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    label("docker")
    steps {
        copyArtifacts("Application_Build") {
            buildSelector {
                buildNumber('${B}')
                includePatterns('target/petclinic.war')
            }
        }
        shell('''set +x
            |export SERVICE_NAME="$(echo ${PROJECT_NAME} | tr '/' '_')_${ENVIRONMENT_NAME}"
            |echo "======================================================================================================"
            |echo "This is a high availability test with multi application instances in the Pivotal CF production space."
            |echo "======================================================================================================"
            |curl -L "https://cli.run.pivotal.io/stable?release=linux64-binary&source=github" | tar -zx
            |echo -e "\\nLogin to Pivotal CF\\n"
            |./cf login -a api.run.pivotal.io -u ${CF_USERNAME} -p ${CF_PASSWORD}
            |echo -e "\\nTarget Pivotal CF production space\\n"
            |./cf target -s production
            |echo "=================================================================================="
            |echo "Killing 1 of the 2 CF instances where the application is deployed"
            |echo "=================================================================================="
            |set -x
            |./cf ssh adop-petclinic-${ENVIRONMENT_NAME} -c 'kill -9 $(pidof java)' -i 0
            |set +x
            |echo "=================================================================================="
            |echo "Instance killed - Verifying that the application is not affected"
            |echo "=================================================================================="
            |if curl -f -q http://${APP_HOSTNAME}/ -o /dev/null; then
            |   echo "====================================================================================="
            |   echo "Although one of the instances is down the application is still accessible..."
            |   echo "====================================================================================="
            |else
            |  exit 1
            |fi
            |set -x'''.stripMargin()
        )
    }

}
