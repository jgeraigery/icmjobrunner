package com.intershop.icm.jobrunner.utils

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class ProcessInfo(val status : String, val duration : Long) {
    companion object {
        const val KEY_STATUS = "status"
        const val KEY_DURATION = "duration"

        fun from(json : JsonObject) : ProcessInfo {
            val status = json.getValue(KEY_STATUS).jsonPrimitive.content
            val duration = json.getValue(KEY_DURATION).jsonPrimitive.long
            return ProcessInfo(status, duration)
        }
    }

    fun toJsonObject() : JsonObject {
        return JsonObject(mapOf(KEY_STATUS to JsonPrimitive(status), KEY_DURATION to JsonPrimitive(duration)))
    }

}