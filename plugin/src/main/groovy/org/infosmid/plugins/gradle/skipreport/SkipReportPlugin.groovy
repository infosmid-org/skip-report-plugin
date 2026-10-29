package org.infosmid.plugins.gradle.skipreport

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.infosmid.build.skipreport.SkipReasonListener

/**
 * Convention plugin: wires every {@link Test} task to produce a detailed
 * skip-reason report at {@code build/reports/skip-reasons.psv}.
 *
 * <p>It also adds the companion JUnit Platform runtime listener
 * ({@code org.infosmid:skip-report-listener}) to the consuming project's test
 * classpath, where JUnit discovers it via ServiceLoader. That listener captures
 * the actual skip reasons (condition messages, assumption exceptions); this
 * plugin handles Gradle-level filtering and writes the report header/footer.</p>
 *
 * Apply with:  plugins { id 'org.infosmid.plugins.skip-report' version '1.0.0' }
 */
class SkipReportPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        // Put the JUnit runtime listener on the test classpath. The plugin depends on the
        // listener module, so the jar it was loaded from is the matching listener version.
        def listenerJar = new File(SkipReasonListener.protectionDomain.codeSource.location.toURI())
        project.plugins.withId('java') {
            project.dependencies.add('testRuntimeOnly', project.files(listenerJar))
        }

        def reportProvider = project.layout.buildDirectory.file('reports/skip-reasons.psv')

        project.tasks.withType(Test).configureEach { Test testTask ->
            def reportFile = reportProvider.get().asFile

            testTask.systemProperty('skipReport.file', reportFile.absolutePath)
            testTask.useJUnitPlatform()

            testTask.doFirst {
                reportFile.parentFile.mkdirs()
                if (reportFile.exists()) {
                    reportFile.delete()
                }
                reportFile.text = 'category | uniqueId | displayName | source | reason\n'
            }

            testTask.addTestListener(new TestListener() {
                @Override void beforeSuite(TestDescriptor suite) {}
                @Override void afterSuite(TestDescriptor suite, TestResult result) {}
                @Override void beforeTest(TestDescriptor d) {}

                @Override
                void afterTest(TestDescriptor d, TestResult result) {
                    def parentName = d.parent ? d.parent.displayName : d.className
                    reportFile << ([
                            result.resultType.name(),
                            "${d.className}.${d.name}".toString(),
                            "${parentName}.${d.displayName}",
                            d.className ?: '',
                            result.toString()
                        ].join(' | ')) + '\n'
                }
            })

            testTask.doLast {
                def excl = testTask.filter.excludePatterns
                if (!excl.isEmpty()) {
                    reportFile << ([
                        'TASK_EXCLUSION', 'n/a', 'n/a', 'filter',
                        "exclude patterns: ${excl.join(', ')}"
                    ].join(' | ')) + '\n'
                }
                def inc = testTask.filter.includePatterns + testTask.filter.commandLineIncludePatterns
                if (!inc.isEmpty()) {
                    reportFile << ([
                        'TASK_FILTER', 'n/a', 'n/a', 'filter',
                        "include/--tests patterns: ${inc.join(', ')} (non-matching excluded at task level)"
                    ].join(' | ')) + '\n'
                }
                testTask.logger.info("Skip-reason report: ${reportFile.path}")
            }
        }
    }
}
