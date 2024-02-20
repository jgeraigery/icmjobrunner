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
package com.intershop.icm.jobrunner.utils

/**
 * Provides a way to store the results of a validation or assertion in a structured way.
 */
open class AssertionResult {

    private var valid = true

    private val failures: MutableList<String> = ArrayList()

    /**
     * Add a validation failure to the list of failures.
     * @param message The message describing the failure. Can contain "{}" placeholders
     * @param variables Values to be used instead of the placeholders
     */
    fun addFailure(message: String, vararg variables: Any) {
        var m = message
        for (i in variables.indices) {
            m = m.replaceFirst( "\\{}".toRegex(), variables[i].toString() )
        }

        failures.add(m)
        valid = false
    }

    /**
     * @return true if the validation succeeded.
     */
    fun succeeded(): Boolean {
        return valid
    }

    /**
     * @return the list of failure messages (never null)
     */
    fun getFailures(): List<String> {
        return failures
    }

    /**
     * @return all failure messages concatenated by a new line character
     */
    fun summarize(): String {
        return summarize("\n")
    }

    /**
     * @param separator
     * @return all failure messages concatenated by the given separator
     */
    fun summarize(separator: String?): String {
        val sb = StringBuilder()
        if (!valid) {
            var first = true
            for (failure in failures) {
                if (first) { first = false } else { sb.append(separator) }
                sb.append(failure)
            }
        }
        return sb.toString()
    }
}
