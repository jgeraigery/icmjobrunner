package com.intershop.icm.jobrunner.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JobInfo(val name : String, val status : String, val process : ProcessInfo? = null) {

    companion object {
        const val KEY_NAME = "name"
        const val KEY_STATUS = "status"
        const val KEY_PROCESS = "process"

        fun from(json : JsonObject) : JobInfo {
            val name = json.getValue(KEY_NAME).jsonPrimitive.content
            val status = json.getValue(KEY_STATUS).jsonPrimitive.content
            val process = json[KEY_PROCESS]?.jsonObject?.let {
                ProcessInfo.from(it)
            }

            return JobInfo(name, status, process)
        }

        fun parse(jsonString : String) : JobInfo {
            return from(Json.parseToJsonElement(jsonString).jsonObject)
        }
    }

    fun toJsonObject() : JsonObject {
        val baseInfo: Map<String, JsonElement> = mutableMapOf(KEY_NAME to JsonPrimitive(name), KEY_STATUS to JsonPrimitive(status))
        return JsonObject(if (process != null) {
            baseInfo.plus(KEY_PROCESS to process.toJsonObject())
        }else {
            baseInfo
        })
    }

    fun render() : String {
        return toJsonObject().toString()
    }

}