import jenkins.model.*
import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.common.*
import com.cloudbees.plugins.credentials.domains.*
import com.cloudbees.plugins.credentials.impl.*

//https://gist.github.com/iocanel/9de5c976cc0bd5011653

//Get the credentials from a pre-configured file
def credentialsLocation = '/var/jenkins_home/pivotal_credentials.txt'
def pivotalCredentials = []

pivotalCredentials = new File(credentialsLocation).readLines()

// Add the credentials as global credentials in Jenkins
domain = Domain.global()
store = Jenkins.instance.getExtensionList('com.cloudbees.plugins.credentials.SystemCredentialsProvider')[0].getStore()

usernameAndPassword = new UsernamePasswordCredentialsImpl(
  CredentialsScope.GLOBAL,
  "CF_Credentials", "Pivotal Web Services Credentials",
  pivotalCredentials[0],
  pivotalCredentials[1]
)

store.addCredentials(domain, usernameAndPassword)
