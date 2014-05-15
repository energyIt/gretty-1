/*
 * gretty
 *
 * Copyright 2013  Andrey Hihlovskiy.
 *
 * See the file "license.txt" for copying and usage permission.
 */
package org.akhikhl.gretty

import java.util.concurrent.ExecutorService
import org.gradle.api.Project
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Base task for starting jetty
 *
 * @author akhikhl
 */
abstract class GrettyStartBaseTask extends GrettyBaseTask {

  protected static final Logger log = LoggerFactory.getLogger(GrettyStartBaseTask)

  boolean interactive = true
  boolean debug = false
  boolean integrationTest = false

  ExecutorService executorService

  @Override
  void action() {
    ServerConfig sconfig = getServerConfig()
    Future futureStatus = ServiceControl.readMessage(executorService, sconfig.statusPort)
    def runThread = Thread.start {
      runJetty(sconfig)
    }
    def status = futureStatus.get()
    log.debug 'Got status: {}', status
    if(!integrationTest) {
      System.out.println 'Jetty server started.'
      List<WebAppRunConfig> webapps = getWebApps()
      if(webapps.size() == 1) {
        System.out.println 'Web-application runs at the address:'
        System.out.println "http://localhost:${sconfig.port}${webapps[0].contextPath}"
      } else if(webapps.size() > 1) {
        System.out.println 'Web-applications run at the addresses:'
        for(def webapp in webapps)
          System.out.println "http://localhost:${sconfig.port}${webapp.contextPath}"
      }
      if(interactive) {
        System.out.println 'Press any key to stop the jetty server.'
        System.in.read()
        log.debug 'Sending command: {}', 'stop'
        ServiceControl.send(sconfig.servicePort, 'stop')
      } else
        System.out.println 'Run \'gradle jettyStop\' to stop the jetty server.'
      runThread.join()
      System.out.println 'Jetty server stopped.'
    }
  }

  protected File discoverLogbackConfigFile() {
    File result
    if(logbackConfigFile) {
      result = new File(logbackConfigFile)
      if(!result.isAbsolute()) {
        result = new File(project.projectDir, logbackConfigFile)
        if(!result.exists())
          result = ProjectUtils.findFileInOutput(project, logbackConfigFile)
      }
      if(!result || !result.exists())
        project.logger.warn 'The specified logback config file "{}" does not exist, ignoring', logbackConfigFile
      else
        project.logger.warn 'Using specified logback config file "{}"', logbackConfigFile
    } else {
      result = ProjectUtils.findFileInOutput(project, ~/logback\.(xml|groovy)/)
      if(result)
        project.logger.warn 'Using discovered logback config file "{}"', result
      else
        project.logger.warn 'Auto-configuring logback'
    }
    return result
  }

  protected abstract ServerConfig getServerConfig()

  protected abstract List<WebAppRunConfig> getWebApps()

  private static prepareJson(ServerConfig sconfig) {
    File logbackConfigFile = discoverLogbackConfigFile(sconfig)
    def webAppsJson = []
    for(def webapp in task.getWebApps())
      webAppsJson.add {
        inplace webapp.inplace
        webappClassPath webapp.classPath
        contextPath webapp.contextPath
        resourceBase webapp.resourceBase
        initParams webapp.initParameters
        realmInfo webapp.realmInfo
        jettyEnvXml webapp.jettyEnvXmlFile
      }
    def json = new JsonBuilder()
    json {
      port task.port
      servicePort task.servicePort
      statusPort task.statusPort
      jettyXml task.jettyXmlFile
      if(logbackConfigFile)
        logbackConfig logbackConfigFile.absolutePath
      else
        logging {
          loggingLevel task.loggingLevel
          consoleLogEnabled task.consoleLogEnabled
          fileLogEnabled task.fileLogEnabled
          logFileName task.logFileName ?: task.project.name
          logDir task.logDir
        }
      webapps webAppsJson
    }
    return json
  }

  protected void runJetty(ServerConfig sconfig) {

    sconfig.onStart*.call()

    def json = prepareJson(sconfig)
    project.logger.info json.toPrettyString()
    json = json.toString()

    // we are going to pass json as argument to java process.
    // under windows we must escape double quotes in process parameters.
    if(System.getProperty("os.name") =~ /(?i).*windows.*/)
      json = json.replace('"', '\\"')

    ScannerManagerBase scanman = project.ext._createScannerManager()
    scanman.startScanner(this, inplace)
    try {
      project.javaexec { spec ->
        spec.classpath = project.configurations.gretty
        spec.main = 'org.akhikhl.gretty.Runner'
        spec.args = [json]
        spec.jvmArgs = sconfig.jvmArgs
        spec.debug = sconfig.debug
      }
    } finally {
      scanman.stopScanner()
    }

    sconfig.onStop*.call()
  }

  @Override
  protected void setupProperties() {
    if(executorService == null) executorService = project.executorService ?: Executors.newSingleThreadExecutor()
  }
}
