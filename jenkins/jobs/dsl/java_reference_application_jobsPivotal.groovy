import jenkins.model.*
import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.common.*
import com.cloudbees.plugins.credentials.domains.*
import com.cloudbees.plugins.credentials.impl.*
import com.cloudbees.jenkins.plugins.sshcredentials.impl.*
import hudson.plugins.sshslaves.*;
/*
domain = Domain.global()
store = Jenkins.instance.getExtensionList('com.cloudbees.plugins.credentials.SystemCredentialsProvider')[0].getStore()

pivotalCred = new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL,"CF_Credentials", "userP", "password")

store.addCredentials(domain, pivotalCred)


//Credentials pivotalCred = (Credentials) new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL,java.util.UUID.randomUUID().toString(), "CF_Credentials", "user", "password")
//SystemCredentialsProvider.getInstance().getStore().addCredentials(Domain.global(), pivotalCred)

def list_pcf_credentials(){
  def creds = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
    com.cloudbees.plugins.credentials.common.StandardUsernameCredentials.class,
    Jenkins.instance,
    null,
    null
  );

  for (c in creds) {
    if (c.description=="CF_Credentials"){
      return c.id
    }
  }
}
*/
def pivotalCred = 'CF_Credentials'
def pivotalCredentials = jm.getCredentialsId(pivotalCred)
println "CF_Credentials ID is: "+pivotalCredentials

// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Variables
def projectNameKey = projectFolderName.toLowerCase().replace("/", "-")
def referenceAppgitRepo = "spring-petclinic"
def regressionTestGitRepo = "adop-cartridge-java-regression-tests"
def referenceAppGitUrl = "ssh://jenkins@gerrit:29418/${PROJECT_NAME}/" + referenceAppgitRepo
def regressionTestGitUrl = "ssh://jenkins@gerrit:29418/${PROJECT_NAME}/" + regressionTestGitRepo

// Jobs
def buildAppJob = freeStyleJob(projectFolderName + "/Reference_Application_Build")
def unitTestJob = freeStyleJob(projectFolderName + "/Reference_Application_Unit_Tests")
def codeAnalysisJob = freeStyleJob(projectFolderName + "/Reference_Application_Code_Analysis")
def deployJob = freeStyleJob(projectFolderName + "/Reference_Application_Deploy_Dev")
def deployJobToProd = freeStyleJob(projectFolderName + "/Reference_Application_Deploy_Prod")
def regressionTestJob = freeStyleJob(projectFolderName + "/Reference_Application_Regression_Tests")
def performanceTestJob = freeStyleJob(projectFolderName + "/Reference_Application_Performance_Tests")
def highavailabilityCFDevJob = freeStyleJob(projectFolderName + "/ADOP-PivotalCF_High_Availability_Dev_Test")
def highavailabilityCFProdJob = freeStyleJob(projectFolderName + "/ADOP-PivotalCF_High_Availability_Prod_Test")

// Views
def pipelineView = buildPipelineView(projectFolderName + "/Java_Reference_Application_Pivotal")

pipelineView.with {
    title('Java Reference Application Pivotal Pipeline')
    displayedBuilds(5)
    selectedJob(projectFolderName + "/Reference_Application_Build")
    showPipelineParameters()
    showPipelineDefinitionHeader()
    refreshFrequency(5)
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
            goals('clean install -DskipTests')
            mavenInstallation("ADOP Maven")
        }
    }
    publishers {
        archiveArtifacts("**/*")
        downstreamParameterized {
            trigger(projectFolderName + "/Reference_Application_Unit_Tests") {
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
        stringParam("PARENT_BUILD", "Reference_Application_Build", "Parent build name")
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
        copyArtifacts("Reference_Application_Build") {
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
            trigger(projectFolderName + "/Reference_Application_Code_Analysis") {
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
        stringParam("PARENT_BUILD", "Reference_Application_Build", "Parent build name")
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
        copyArtifacts('Reference_Application_Unit_Tests') {
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
            trigger(projectFolderName + "/Reference_Application_Deploy_Dev") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                    predefinedProp("CF_APP_INSTANCES", '1')
                    predefinedProp("ENVIRONMENT_NAME", 'CI')
                }
            }
        }
    }
}

deployJob.with {
    description("This job deploys the java reference application to Pivotal CF development space using one instance")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Reference_Application_Build", "Parent build name")
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
        copyArtifacts("Reference_Application_Build") {
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
            |./cf delete -f adop-petclinic
            |set +x
            |echo "========================================================================"
            |echo "Pushing application to Pivotal CF using ${CF_APP_INSTANCES} instance(s)"
            |echo "========================================================================"
            |set -x
            |./cf push adop-petclinic -p ${WORKSPACE}/target/petclinic.war -m 1g -i ${CF_APP_INSTANCES}
            |COUNT=1
            |while ! curl -f -q http://adop-petclinic.cfapps.io/ -o /dev/null
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
            |echo "Application URL: http://adop-petclinic.cfapps.io/"
            |echo "=.=.=.=.=.=.=.=.=.=.=.=."
            |echo "=.=.=.=.=.=.=.=.=.=.=.=."'''.stripMargin()
        )
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/Reference_Application_Regression_Tests") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                    predefinedProp("ENVIRONMENT_NAME", '${ENVIRONMENT_NAME}')
                }
            }
        }
    }
}

regressionTestJob.with {
    description("This job runs regression tests on deployed java application")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Reference_Application_Build", "Parent build name")
        stringParam("ENVIRONMENT_NAME", "CI", "Name of the environment.")
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
            |APP_URL=http://adop-petclinic.cfapps.io
            |ZAP_PORT="9090"
            |
            |echo CONTAINER_NAME=$CONTAINER_NAME >> env.properties
            |echo APP_URL=$APP_URL >> env.properties
            |echo ZAP_PORT=$ZAP_PORT >> env.properties
            |
            |echo "Starting OWASP ZAP Intercepting Proxy"
            |JOB_WORKSPACE_PATH="/var/lib/docker/volumes/jenkins_slave_home/_data/${PROJECT_NAME}/Reference_Application_Regression_Tests"
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
            trigger(projectFolderName + "/Reference_Application_Performance_Tests") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                    predefinedProp("ENVIRONMENT_NAME", '${ENVIRONMENT_NAME}')
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
        stringParam("PARENT_BUILD", "Reference_Application_Regression_Tests", "Parent build name")
        stringParam("ENVIRONMENT_NAME", "CI", "Name of the environment.")
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
    steps {
        copyArtifacts("Reference_Application_Build") {
            buildSelector {
                buildNumber('${B}')
            }
            targetDirectory('${JMETER_TESTDIR}')
        }
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
            |sed -i "s/PETCLINIC_HOST_VALUE/adop-petclinic.cfapps.io/g" src/test/jmeter/petclinic_test_plan.jmx
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
            |sed -i "s/###TOKEN_VALID_URL###/http:\\/\\/adop-petclinic.cfapps.io/g" ${WORKSPACE}/src/test/scala/default/RecordedSimulation.scala
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
            trigger(projectFolderName + "/ADOP-PivotalCF_High_Availability_Dev_Test") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                    predefinedProp("ENVIRONMENT_NAME", '${ENVIRONMENT_NAME}')
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
        stringParam("PARENT_BUILD", "Reference_Application_Build", "Parent build name")
        stringParam("ENVIRONMENT_NAME", "CI", "Name of the environment.")
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
        copyArtifacts("Reference_Application_Build") {
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
            |./cf ssh adop-petclinic -c 'kill -9 $(pidof java)'
            |set +x
            |echo "================================================================="
            |echo "Instance killed - Waiting until the application comes up again"
            |echo "================================================================="
            |set -x
            |COUNT=1
            |while ! curl -f -q http://adop-petclinic.cfapps.io/ -o /dev/null
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
            trigger(projectFolderName + "/Reference_Application_Deploy_Prod") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                    predefinedProp("CF_APP_INSTANCES", '2')
                    predefinedProp("ENVIRONMENT_NAME", '${ENVIRONMENT_NAME}')
                }
            }
        }
    }
}
deployJobToProd.with {
    description("This job deploys the java reference application to Pivotal CF production space using 2 instances")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Reference_Application_Build", "Parent build name")
        stringParam("CF_APP_INSTANCES", '', "Number of CF instances")
        stringParam("ENVIRONMENT_NAME", "PROD", "Name of the environment.")
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
        copyArtifacts("Reference_Application_Build") {
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
            |#./cf create-service cleardb spark mysql-test-prod
            |#./cf bind-service adop-petclinic-prod mysql-test-prod
            |set +x
            |echo "========================================================================"
            |echo "Pushing application to Pivotal CF using ${CF_APP_INSTANCES} instance(s)"
            |echo "========================================================================"
            |set -x
            |./cf push adop-petclinic-prod -p ${WORKSPACE}/target/petclinic.war -m 512mb -i ${CF_APP_INSTANCES}
            |
            |COUNT=1
            |while ! curl -f -q http://adop-petclinic-prod.cfapps.io/ -o /dev/null
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
            |echo "Application URL: http://adop-petclinic-prod.cfapps.io/"
            |echo "=.=.=.=.=.=.=.=.=.=.=.=."
            |echo "=.=.=.=.=.=.=.=.=.=.=.=."'''.stripMargin())
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/ADOP-PivotalCF_High_Availability_Prod_Test") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                    predefinedProp("ENVIRONMENT_NAME", '${ENVIRONMENT_NAME}')
                }
            }
        }
    }
}

highavailabilityCFProdJob.with {
    description("This job makes a high availability test on the application that runs in Pivotal CF production space")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Reference_Application_Build", "Parent build name")
        stringParam("ENVIRONMENT_NAME", "CI", "Name of the environment.")
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
        copyArtifacts("Reference_Application_Build") {
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
            |./cf ssh adop-petclinic-prod -c 'kill -9 $(pidof java)' -i 0
            |set +x
            |echo "=================================================================================="
            |echo "Instance killed - Verifying that the application is not affected"
            |echo "=================================================================================="
            |if curl -f -q http://adop-petclinic-prod.cfapps.io/ -o /dev/null; then
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
