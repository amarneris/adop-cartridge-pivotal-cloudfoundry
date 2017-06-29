import static groovy.io.FileType.FILES

def jenkinsConf = build.getEnvironment(listener).get('WORKSPACE') + '/jenkins/conf/groovy/'
def fileMatchPattern = ".groovy"

new File(jenkinsConf).eachFileRecurse(FILES) {
  if(it.name.endsWith(fileMatchPattern)) {
   	println "Evaluting file: $it.name"
    evaluate(it)
  }
}
