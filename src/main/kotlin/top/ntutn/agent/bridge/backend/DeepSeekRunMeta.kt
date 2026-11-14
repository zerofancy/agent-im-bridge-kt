package top.ntutn.agent.bridge.backend

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import top.ntutn.agent.bridge.RunMeta
import top.ntutn.agent.bridge.RunMetaSource
import top.ntutn.agent.bridge.string

/** DSH 0.2.2 encodes its selected ACP model value as JSON [provider, model]. */
internal fun deepSeekRunMeta(configOptions: JsonElement?): RunMeta? {
    val options = configOptions?.arrayOrNull()?.mapNotNull { it.objectOrNull() } ?: return null
    val modelOption = options.singleOrNull { it.string("id") == "model" } ?: return null
    val selected = selectedValue(modelOption) ?: return null
    val route =
        try {
            JsonParser.parseString(selected).arrayOrNull()
        } catch (_: JsonParseException) {
            null
        } ?: return null
    if (route.size() != 2) {
        return null
    }
    val provider = route[0].takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    val model = route[1].takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    if (!validIdentity(provider) || !validIdentity(model)) {
        return null
    }
    val effort =
        options
            .singleOrNull { it.string("id") == "reasoning_effort" }
            ?.let(::selectedValue)
            ?.takeIf(::validIdentity)
    return RunMeta(model = model, effort = effort, source = RunMetaSource.THREAD)
}

/** Unknown, missing or unadvertised selections carry no trustworthy presentation metadata. */
private fun selectedValue(option: JsonObject): String? {
    if (option.string("type") != "select") {
        return null
    }
    val selected = option.string("currentValue")?.takeIf { it.length <= 4096 } ?: return null
    val choices = option["options"]?.arrayOrNull() ?: return null
    val advertised =
        choices.any { entry ->
            val choice = entry.objectOrNull() ?: return@any false
            if (choice.has("group")) {
                choice["options"]?.arrayOrNull()?.any {
                    it.objectOrNull()?.string("value") == selected
                } == true
            } else {
                choice.string("value") == selected
            }
        }
    return selected.takeIf { advertised }
}

private fun validIdentity(value: String?): Boolean =
    value != null && value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)
