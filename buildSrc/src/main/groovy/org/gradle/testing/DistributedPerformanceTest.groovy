/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.testing

import com.google.common.base.Splitter
import com.google.common.collect.Lists
import groovy.transform.CompileStatic
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import groovy.util.slurpersupport.NodeChildren
import groovyx.net.http.ContentType
import groovyx.net.http.RESTClient
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import java.util.concurrent.TimeUnit

/**
 * Runs each performance test scenario in a dedicated TeamCity job.
 *
 * The test runner is instructed to just write out the list of scenarios
 * to run instead of actually running the tests. Then this list is used
 * to schedule TeamCity jobs for each individual scenario. This task
 * blocks until all the jobs have finished and aggregates their status.
 */
@CompileStatic
class DistributedPerformanceTest extends PerformanceTest {

    @Input
    String buildTypeId

    @Input
    String revision

    @Input
    String teamCityUrl

    @Input
    String teamCityUsername

    @Input
    String teamCityPassword

    @OutputFile
    File scenarioList

    RESTClient client

    List<String> scheduledJobs = Lists.newArrayList()

    List<Object> failedJobs = Lists.newArrayList()

    void setScenarioList(File scenarioList) {
        systemProperty "org.gradle.performance.scenario.list", scenarioList
        this.scenarioList = scenarioList
    }

    @TaskAction
    void executeTests() {
        scenarioList.delete()

        fillScenarioList()

        def scenarios = scenarioList.readLines()
            .collect { line ->
                def parts = Splitter.on(';').split(line).toList()
                new Scenario(id : parts[0], estimatedRuntime: new BigDecimal(parts[1]), templates: parts.subList(2, parts.size()))
            }
            .sort{ -it.estimatedRuntime }

        createClient()

        scenarios.each {
            schedule(it)
        }

        scheduledJobs.each {
            join(it)
        }

        reportErrors()
    }

    private void fillScenarioList() {
        super.executeTests()
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private void schedule(Scenario scenario) {
        logger.info("Scheduling $scenario.id, estimated runtime: $scenario.estimatedRuntime")
        def response = client.post(
            path: "buildQueue",
            requestContentType: ContentType.XML,
            body: """
                <build>
                    <buildType id="${buildTypeId}"/>
                    <properties>
                        <property name="scenario" value="${scenario.id}"/>
                        <property name="templates" value="${scenario.templates.join(' ')}"/>
                    </properties>
                    <revisions>
                        <revision version="$revision"/>
                    </revisions>
                </build>
            """
        )

        scheduledJobs += response.data.@id
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private void join(String jobId) {
        def finished = false
        def response
        while (!finished) {
            response = client.get(path: "builds/id:$jobId")
            finished = response.data.@state == "finished"
            if (!finished) {
                sleep(TimeUnit.MINUTES.toMillis(1))
            }
        }
        if (response.data.@status != "SUCCESS") {
            failedJobs += response.data
        }
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private void reportErrors() {
        if (failedJobs) {
            throw new GradleException("${failedJobs.size()} performance tests failed. See individual builds for details:\n" +
                    failedJobs.collect {job ->
                        NodeChildren properties = job.properties.children()
                        String scenario = properties.find { it.@name == 'scenario' }.@value
                        String url = job.@webUrl
                        "$scenario -> $url"
                    }.join("\n")
            )
        }
    }

    private RESTClient createClient() {
        client = new RESTClient("$teamCityUrl/httpAuth/app/rest/")
        client.auth.basic(teamCityUsername, teamCityPassword)
        client
    }


    private static class Scenario {
        String id
        long estimatedRuntime
        List<String> templates
    }

}
