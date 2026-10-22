package top.ntutn.agent.bridge.storage

import com.google.gson.JsonObject

internal fun validateSessionV4(root: JsonObject) {
    fun text(item: JsonObject, field: String) {
        val value = requireNotNull(item[field])
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        require(value.asString.isNotBlank())
    }

    fun timestamp(item: JsonObject, field: String) {
        val value = requireNotNull(item[field])
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        require(value.asString.toLong() > 0)
    }

    fun key(item: JsonObject, session: Boolean) {
        val key = item["key"].asJsonObject
        for (field in listOf("appId", "chatId", "runtimeRoot", "backendId")) {
            text(key, field)
        }
        if (session) {
            text(key, "workspace")
        }
    }

    val version = root["version"]
    require(version.isJsonPrimitive && version.asJsonPrimitive.isNumber)
    for (raw in root["workspaces"].asJsonArray) {
        val item = raw.asJsonObject
        key(item, false)
        text(item, "workspace")
    }
    for (raw in root["sessions"].asJsonArray) {
        val item = raw.asJsonObject
        key(item, true)
        text(item, "sessionId")
        text(item, "initialization")
        timestamp(item, "updatedAt")
    }
    for (raw in root["catalog"].asJsonArray) {
        val item = raw.asJsonObject
        key(item, true)
        text(item, "sessionId")
        text(item, "origin")
        text(item, "initialization")
        SessionInitialization.valueOf(item["initialization"].asString)
        timestamp(item, "createdAt")
        timestamp(item, "lastSelectedAt")
    }
    for (raw in root["switches"].asJsonArray) {
        val item = raw.asJsonObject
        key(item, true)
        text(item, "sessionId")
        text(item, "commandId")
        timestamp(item, "updatedAt")
        val previous = item["previousId"]
        if (previous != null && !previous.isJsonNull) {
            text(item, "previousId")
        }
    }
}
