/*
* This scripts contains the configure Jenkins Job DSL.
*/

// Variables
//def pivotalUser = "${PIVOTAL_USER}"
//def pivotalUserPassword = "${PIVOTAL_USER_PASSWORD}"

// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// jobs
def configureJenkinsJob = freeStyleJob(projectFolderName + "/Configure_Jenkins")

// git repos
def adopCartridgeGitRepo = 'adop-cartridge-pivotal-cloudfoundry'
def adopCartridgeGitRepoUrl = "${CARTRIDGE_CLONE_URL}"
def adopCartridgeGitUrlBranch = 'master'

def logRotatorBuildsToKeep = 5
def logRotatorNumToKeep = 7
def logRotatorArtifactsToKeep = 7
def logRotatorArtifactsNumToKeep = 7

def gerritGitRepoAccessCredentialsKeyName = "adop-jenkins-master"

configureJenkinsJob.with{
    description("This job configure Jenkins (e.g. Plugins) for use with PCF.")
    label('master')
    logRotator {
      daysToKeep(logRotatorBuildsToKeep)
      numToKeep(logRotatorNumToKeep)
      artifactDaysToKeep(logRotatorArtifactsToKeep)
      artifactNumToKeep(logRotatorArtifactsNumToKeep)
    }
    environmentVariables {
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROJECT_NAME',projectFolderName)
    }
    scm {
        git{
            remote{
                name("origin")
                url(adopCartridgeGitRepoUrl)
                credentials(gerritGitRepoAccessCredentialsKeyName)
            }
            branch(adopCartridgeGitUrlBranch)
        }
    }
    environmentVariables {
       env('WORKSPACE_NAME', workspaceFolderName)
       env('PROJECT_NAME', projectFolderName)
   }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
    }
    steps{
      systemGroovyCommand(readFileFromWorkspace('cartridge/jenkins/conf/execute_groovy_scripts.groovy'))
    }
}

// queue job for execution (pre-build) and configure jenkins
queue(configureJenkinsJob)
