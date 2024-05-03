/*
 * Copyright 2020 Intershop Communications AG.
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
 *
 */
package com.intershop.icm.jobrunner

import com.intershop.icm.jobrunner.configuration.Server
import com.intershop.icm.jobrunner.configuration.User
import com.intershop.icm.jobrunner.utils.AssertionResult
import com.intershop.icm.jobrunner.utils.JobInfo
import com.intershop.icm.jobrunner.utils.JobRunnerException
import com.intershop.icm.jobrunner.utils.NoOpTrustManager
import org.slf4j.Logger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * Main class for the execution of Intershop Commerce Management jobs
 * over a Rest interface.
 * @constructor prepares a configured instance of the runner
 * @param server the server to be used
 * @param domain Intershop domain
 * @param srvGroup Intershop server group
 * @param user SMC user with the permissions to start a job
 * @param timeout waiting time for the job execution
 * @param logger slf4j logger instance for output
 */
class JobRunner(
    private val server: Server, private val domain: String, private val srvGroup: String,
    private val user: User, private val timeout: Long, private val logger: Logger) {

    private var pSSLVerification = false

    companion object {
        const val POLLINTERVAL: Long = 15000
        private val expectedEndStates = listOf("READY", "DISABLED")
        private const val HEADER_ACCEPT = "Accept"
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val HEADER_AUTHORIZATION = "Authorization"
        private const val CONTENT_TYPE_JSON = "application/json"

        /**
         * Configures SSL context for self signed certificates.
         *
         * @return an SSL context withour verification
         */
        @Throws(NoSuchAlgorithmException::class, KeyManagementException::class)
        fun getSslContext(): SSLContext {
            val sslContext = SSLContext.getInstance("TLS")
            val keyManagers: Array<KeyManager>? = null
            val trustManager: Array<TrustManager> = arrayOf(NoOpTrustManager())
            val secureRandom = SecureRandom()
            sslContext.init(keyManagers, trustManager, secureRandom)
            return sslContext
        }
    }

    /**
     * Enable the SSL verification of the used client.
     */
    fun enableSSLVerification() {
        sslVerification = true
    }

    var sslVerification: Boolean
        get() = pSSLVerification
        set(value) {
            pSSLVerification = value
        }

    /**
     * Triggers the job identified by the name.
     *
     * @param jobName Name of the job - see SMC overview
     */
    @Throws(JobRunnerException::class)
    fun triggerJob(jobName: String) {
        val client = getClient()

        val requestBuilder = requestBuilder(jobName)


        val response = client.send(requestBuilder.PUT(BodyPublishers.ofString(getPayLoad(jobName).render())).build(), BodyHandlers.ofString())

        val assertion = assertResponseStatus(
            response, 200, CONTENT_TYPE_JSON, true
        )

        if (assertion.succeeded()) {
            val jobInfo = JobInfo.parse(response.body())
            logger.info("  Started job: {}", jobName)
            if (!expectedEndStates.contains(jobInfo.status)) {
                pollJobInfo(client, requestBuilder, jobName, timeout)
            }
        } else {
            throw JobRunnerException("Error while communicating with server: " + assertion.summarize("; "))
        }

    }

    private fun getClient(): HttpClient {

        val clientBuilder = HttpClient.newBuilder()
        if(sslVerification) {
            clientBuilder.sslContext(getSslContext())
        }
        return clientBuilder.build()
    }

    private fun encodePathEntry(jobName: String) : String {
        return URLEncoder.encode(jobName.replace("\\+", "%2B"), StandardCharsets.UTF_8).replace("\\+", "%20").replace("%252B", "+");
    }

    private fun requestBuilder(jobName: String) : HttpRequest.Builder {
        val hostConnectStr = "${server.protocol.pname}://${server.host}:${server.port}"
        val mainPath = "INTERSHOP/rest/${srvGroup}/SMC/-/domains/${domain}/jobs"
        val encJobName = encodePathEntry(jobName);

        val uri = URI.create("${hostConnectStr}/${mainPath}/${encJobName}")

        logger.debug("Sending request to {}", uri)

        if(user.name.isEmpty() || user.password.isEmpty()) {
            throw JobRunnerException("User is not configured.")
        }
        val authHeader = "Basic " + Base64.getEncoder().encodeToString("${user.name}:${user.password}".toByteArray(StandardCharsets.UTF_8))

        return HttpRequest
            .newBuilder(uri)
            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
            .header(HEADER_ACCEPT, CONTENT_TYPE_JSON)
            .header(HEADER_AUTHORIZATION, authHeader)
    }

    private fun getPayLoad(jobName: String): JobInfo = JobInfo(jobName, "RUNNING")

    /**
     * Poll a given resource for a JobInfo object, until an acceptable end state is reached,
     * or the maximum time to wait is exceeded.
     * @param requestBuilder a request builder preconfigured for the job-resource
     * @param jobName Job name
     * @param maxWait The maximum time to wait.
     * @return The last retrieved JobInfo
     */
    @Throws(JobRunnerException::class)
    private fun pollJobInfo(client : HttpClient, requestBuilder: HttpRequest.Builder, jobName: String, maxWait: Long): JobInfo {
        logger.debug("Polling status for {} until finished or timeout of {}ms reached", jobName, maxWait)
        waitFor(POLLINTERVAL)

        var jobInfo = getJobInfo(client, requestBuilder)
        logger.info("Status of {} is now {}", jobName, jobInfo.status)
        var oldStatus = jobInfo.status
        val startTime = System.currentTimeMillis()

        while (!expectedEndStates.contains(jobInfo.status)) {
            waitFor(POLLINTERVAL)

            jobInfo = getJobInfo(client, requestBuilder)

            logger.trace("{} returned status {}", requestBuilder.build().uri().toString(), jobInfo.status)
            if (oldStatus != jobInfo.status) {
                logger.info("Status of {} is now {}  ", jobName, jobInfo.status)
                oldStatus = jobInfo.status
            }
            if (System.currentTimeMillis() - startTime > maxWait) {
                throw JobRunnerException("Job $jobName didn't finish within the maximum wait time of ${maxWait}ms")
            }
        }

        logger.info(" Job {} returned with finish status {}", jobName, jobInfo.status)
        val processInfo = jobInfo.process
        processInfo?.run {
            logger.info("{} after {}ms", status, duration)
        }

        return jobInfo
    }

    private fun waitFor(pollingIntervalMillis: Long) {
        try {
            Thread.sleep(pollingIntervalMillis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted during sleep", e)
        }
    }

    /**
     * Retrieve a JobInfo from a given resource and perform a basic sanity check on the result.
     * @param resource
     * @return
     */
    @Throws(JobRunnerException::class)
    private fun getJobInfo(client : HttpClient, requestBuilder: HttpRequest.Builder): JobInfo {
        val response = client.send(requestBuilder.GET().build(), BodyHandlers.ofString())
        val assertion = assertResponseStatus(response, 200, CONTENT_TYPE_JSON, true)
        if (assertion.succeeded()) {
            return JobInfo.parse(response.body())
        } else {
            throw JobRunnerException("Retrieving job info failed: " + assertion.summarize(";  "))
        }
    }

    /**
     * Utility method to perform the most common checks/assertions for REST responses.
     *
     * @param response the response from the server
     * @param expectedStatusCode the HTTP Status Code that should have been returned
     * @param expectedType the Content-Type that should have been returned
     * @param requireContent set to true if the request was required to return content
     * @return an AssertionResult containing the boolean result and the list of validation failure messages
     */
    private fun assertResponseStatus(
        response: HttpResponse<String>, expectedStatusCode: Int,
        expectedType: String, requireContent: Boolean
    ): AssertionResult {

        val result = AssertionResult()
        if (expectedStatusCode != response.statusCode()) {
            result.addFailure(
                "Call returned status code {}. Expected {}.",
                response.statusCode(), expectedStatusCode
            )
        }


        val contentType = response.headers().firstValue(HEADER_CONTENT_TYPE).orElse(null)
        if (contentType.isNullOrBlank()) {
            result.addFailure(
                "Call response is missing a content type. Expected {}.", expectedType
            )
        }

        if (!contentType.startsWith(expectedType, ignoreCase = true)) {
            result.addFailure(
                "Call returned wrong content type '{}'. Expected {}.", contentType, expectedType
            )
        }
        if (requireContent && response.body().isNullOrBlank()) {
            result.addFailure("Call returned no content.")
        }
        return result
    }
}
