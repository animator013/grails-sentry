/*
 * Copyright 2016 Alan Rafael Fachini, authors, and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.plugin.sentry

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.helpers.MDCInsertingServletFilter
import grails.plugins.Plugin
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import groovy.util.logging.Commons
import io.sentry.DefaultSentryClientFactory
import io.sentry.dsn.Dsn
import io.sentry.servlet.SentryServletRequestListener
import org.slf4j.LoggerFactory
import org.springframework.boot.web.servlet.FilterRegistrationBean

@CompileStatic
@Commons
class SentryGrailsPlugin extends Plugin {

    // the version or versions of Grails the plugin is designed for
    def grailsVersion = '3.0.0 > *'

    def title = 'Sentry Plugin'
    def author = 'Benoit Hediard'
    def authorEmail = 'ben@benorama.com'
    def description = 'Sentry Client for Grails'
    def profiles = ['web']
    def documentation = 'http://github.com/agorapulse/grails-sentry/blob/master/README.md'

    def license = 'APACHE'
    def developers = [[name: 'Benoit Hediard', email: 'ben@benorama.com'], [name: 'Alexey Zhokhov', email: 'alexey@zhokhov.com']]
    def issueManagement = [system: 'GitHub', url: 'http://github.com/agorapulse/grails-sentry/issues']
    def scm = [url: 'http://github.com/agorapulse/grails-sentry']

    private SentryConfig savedConfig

    @CompileStatic(TypeCheckingMode.SKIP)
    Closure doWithSpring() {
        { ->
            SentryConfig pluginConfig = getSentryConfig()

            if (!pluginConfig.active) {
                log.warn "Sentry disabled"
                return
            }

            if (pluginConfig?.dsn) {
                log.info 'Sentry config found, creating Sentry client and corresponding Logback appender'
                sentryFactory(DefaultSentryClientFactory)
                sentryClient(sentryFactory: 'createSentryClient', new Dsn(pluginConfig.dsn.toString())) { bean ->
                    bean.autowire = 'byName'
                }
                sentryAppender(GrailsLogbackSentryAppender, pluginConfig, grailsApplication.metadata['info.app.version'])

                if (pluginConfig.logHttpRequest) {
                    sentryServletRequestListener(SentryServletRequestListener)
                }

                if (pluginConfig.springSecurityUser) {
                    springSecurityUserEventBuilderHelper(SpringSecurityUserEventBuilderHelper) {
                        springSecurityService = ref('springSecurityService')
                        if (pluginConfig.logHttpRequest)
                            sentryServletRequestListener = ref('sentryServletRequestListener')
                    }
                }

                if (!pluginConfig.disableMDCInsertingServletFilter) {
                    log.info 'Activating MDCInsertingServletFilter'
                    mdcInsertingServletFilter(FilterRegistrationBean) {
                        filter = bean(MDCInsertingServletFilter)
                        urlPatterns = ['/*']
                    }
                }
            } else {
                log.warn "Sentry config not found, add 'grails.plugin.sentry.dsn' to your config to enable Sentry client"
            }
        }
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    void doWithApplicationContext() {
        def pluginConfig = grailsApplication.config.grails?.plugin?.sentry

        if (pluginConfig.containsKey('active') && !pluginConfig.active) {
            return
        }

        def configLoggers = pluginConfig?.loggers

        if (pluginConfig?.springSecurityUser) {
            def springSecurityUserEventBuilderHelper = applicationContext.springSecurityUserEventBuilderHelper
            applicationContext.sentryClient.addBuilderHelper(springSecurityUserEventBuilderHelper)
        }

        GrailsLogbackSentryAppender appender = applicationContext.sentryAppender
        if (appender) {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory()
            if (configLoggers) {
                def loggers = configLoggers.tokenize(',')
                loggers.each { String logger ->
                    loggerContext.getLogger(logger).addAppender(appender)
                }
            } else {
                loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender)
            }
            appender.setContext(loggerContext)
            appender.start()
        }
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    SentryConfig getSentryConfig() {
        if (savedConfig) {
            return savedConfig
        } else {
            def pluginConfig = grailsApplication.config.grails?.plugin?.sentry

            savedConfig = new SentryConfig(pluginConfig)

            return savedConfig
        }
    }

}
