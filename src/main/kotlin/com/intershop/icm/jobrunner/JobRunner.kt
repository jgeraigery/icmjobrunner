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
import com.intershop.icm.jobrunner.utils.JobRunnerException
import com.intershop.icm.jobrunner.utils.NoOpHostnameVerifier
import com.intershop.icm.jobrunner.utils.NoOpTrustManager
import com.intershop.icm.jobrunner.utils.Protocol
import org.apache.commons.httpclient.util.URIUtil
import org.codehaus.jettison.json.JSONException
import org.codehaus.jettison.json.JSONObject
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature
import org.slf4j.Logger
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.ws.rs.client.Client
import javax.ws.rs.client.ClientBuilder
import javax.ws.rs.client.Entity
import javax.ws.rs.client.WebTarget
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

/**
 * Main class for the execution of Intershop Commerce Management jobs
 * over a Rest interface.
 * @constructor prepares a configured instance of the runner
 * @param protocol http or https
 * @param host  hostname of the web server
 * @param port  port of the webserver
 * @param domain Intershop domain
 * @param srvgroup Intershop server group
 * @param username SMC user with the permissions to start a job
 * @param password user password
 * @param timeout waiting time for the job execution
 * @param logger slf4j logger instance for output
 */
class JobRunner(
    private val server: Server, private val domain: String, private val srvgroup: String,
    private val user: User, private val timeout: Long, private val logger: Logger) {

    private var pSSLVerification = false

    companion object {
        const val POLLINTERVAL: Long = 15000
        private val expectedEndStates = listOf("READY", "DISABLED")

        /**
         * Configures SSL context for self signed certificates.
         *
         * @return an SSL context withour verification
         */
        @Throws(NoSuchAlgorithmException::class, KeyManagementException::class)
        fun getSslContext(): SSLContext? {
            val sslContext = SSLContext.getInstance("TLSv1")
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

        val target = getWebTarget(client, jobName)

        val response = target.request(MediaType.APPLICATION_JSON).put(
            Entity.entity(getPayLoad(jobName).toString(), MediaType.APPLICATION_JSON), Response::class.java
        )

        val assertion = assertResponseStatus(
            response, 200, MediaType.APPLICATION_JSON_TYPE, true
        )

        if (assertion.succeeded()) {
            val s = response.readEntity(String::class.java)
            val jobInfo = try {
                JSONObject(s)
            } catch (e: JSONException) {
                throw JobRunnerException("Retrieving job info failed: $e")
            }
            val status: String = jobInfo.optString("status")
            logger.info("  Started job: {}", jobName)
            if (!expectedEndStates.contains(status)) {
                pollJobInfo(target, jobName, timeout)
            }
        } else {
            throw JobRunnerException("Error while communicating with server: " + assertion.summarize("; "))
        }

    }

    private fun getClient(): Client {
        val client = if(sslVerification) {
                        ClientBuilder.newBuilder()
                            .sslContext(getSslContext())
                            .hostnameVerifier(NoOpHostnameVerifier()).build()
                     } else {
                        ClientBuilder.newClient()
                     }

        if(user.name.isNullOrEmpty() || user.password.isNullOrEmpty()) {
            throw JobRunnerException("User is not configured.")
        }

        val feature = HttpAuthenticationFeature.basic(user.name, user.password)
        client.register(feature)

        return client
    }

    private fun getWebTarget(client: Client, jobName: String) : WebTarget {
        val encJobName = URIUtil.encodePath(jobName)
        val hostConnectStr = "${server.protocol.pname}://${server.host}:${server.port}"
        val mainPath = "INTERSHOP/rest/${srvgroup}/SMC/-/domains/${domain}/jobs"

        val uri = "${hostConnectStr}/${mainPath}/${encJobName}"
        logger.debug("Request  {}", uri)

        return client.target(uri)
    }

    private fun getPayLoad(jobName: String): JSONObject {
        val pl = JSONObject()
        pl.put("name", jobName)
        pl.put("status", "RUNNING")
        return pl
    }

    /**
     * Poll a given resource for a JobInfo object, until an acceptable end state is reached,
     * or the maximum time to wait is exceeded.
     * @param resource The resource providing the JobInfo
     * @param jobName Job name
     * @param maxWait The maximum time to wait.
     * @return The last retrieved JobInfo
     */
    @Throws(JobRunnerException::class)
    private fun pollJobInfo(resource: WebTarget, jobName: String, maxWait: Long): JSONObject? {
        logger.debug("Polling status for {} until finished or timeout of {}ms reached", jobName, maxWait)
        waitFor(POLLINTERVAL)

        var jobInfo: JSONObject? = getJobInfo(resource)
        if(jobInfo != null) {
            var status = jobInfo.optString("status")

            logger.info("Status of {} is now {}", jobName, status)
            var oldStatus = status
            val startTime = System.currentTimeMillis()

            while (!expectedEndStates.contains(status)) {
                waitFor(POLLINTERVAL)

                jobInfo = getJobInfo(resource)
                status = jobInfo?.optString("status")
                logger.trace("{} returned status {}", resource.uri.toString(), status)
                if (oldStatus != status) {
                    logger.info("Status of {} is now {}  ", jobName, status)
                    oldStatus = status
                }
                if (System.currentTimeMillis() - startTime > maxWait) {
                    throw JobRunnerException("Job $jobName didn't finish within the maximum wait time of ${maxWait}ms")
                }
            }

            if(jobInfo != null) {
                logger.info(" Job {} returned with finish status {}", jobName, status)
                val processInfo = jobInfo.optJSONObject("process")
                if (processInfo != null) {
                    with(processInfo) {
                        logger.info("{} after {}ms", optString("status"), optLong("duration"))
                    }
                }
            }
        }
        return jobInfo
    }

    private fun waitFor(pollingIntervalMillis: Long) {
        try {
            Thread.sleep(pollingIntervalMillis)
        } catch (e: InterruptedException) {
            // can be ignored
        }
    }

    /**
     * Retrieve a JobInfo from a given resource and perform a basic sanity check on the result.
     * @param resource
     * @return
     */
    @Throws(JobRunnerException::class)
    private fun getJobInfo(resource: WebTarget): JSONObject? {
        val response = resource.request(MediaType.APPLICATION_JSON).get()
        val assertion = assertResponseStatus(response, 200, MediaType.APPLICATION_JSON_TYPE, true)
        if (assertion.succeeded()) {
            return try {
                JSONObject(response.readEntity(String::class.java))
            } catch (e: JSONException) {
                throw JobRunnerException("Retrieving job info failed: $e")
            }
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
        response: Response, expectedStatusCode: Int,
        expectedType: MediaType?, requireContent: Boolean
    ): AssertionResult {

        val result = AssertionResult()
        if (expectedStatusCode != response.status) {
            result.addFailure(
                "Call returned status code {} ({}). Expected {}.",
                response.status, Response.Status.fromStatusCode(response.status).reasonPhrase, expectedStatusCode
            )
        }
        if (expectedType != null && expectedType != response.mediaType) {
            result.addFailure(
                "Call returned wrong content type '{}'. Expected {}.",
                response.mediaType, expectedType
            )
        }
        if (requireContent && !response.hasEntity()) {
            result.addFailure("Call returned no content.")
        }
        return result
    }
}
