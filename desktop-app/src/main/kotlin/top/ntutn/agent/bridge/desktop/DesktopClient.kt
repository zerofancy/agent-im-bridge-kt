package top.ntutn.agent.bridge.desktop

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ConnectionTarget(val root: Path, val environment: String) {
    init { require(environment in setOf("dev", "prod")) }
    val endpointPath: Path get() = root.resolve("environments/$environment/control/endpoint.json")
}

internal data class Endpoint(val port: Int, val token: String, val bootId: String)

/** This client only reads the private discovery file. All history and mutations go through the service. */
class DesktopClient : AutoCloseable {
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false).retryOnConnectionFailure(false).build()

    internal suspend fun discover(target: ConnectionTarget): Endpoint = withContext(Dispatchers.IO) {
        try {
            val json = JsonParser.parseString(Files.readString(target.endpointPath)).asJsonObject
            val port = json["port"].asInt
            val token = json["token"].asString
            val bootId = json["bootId"].asString
            if (port !in 1..65535 || token.isBlank() || bootId.isBlank()) throw IOException("服务端点无效")
            Endpoint(port, token, bootId)
        } catch (_: com.google.gson.JsonParseException) { throw IOException("服务端点无效") }
        catch (_: IllegalStateException) { throw IOException("服务端点无效") }
        catch (_: NullPointerException) { throw IOException("服务端点无效") }
    }

    internal fun events(endpoint: Endpoint, selected: String?) = callbackFlow {
        val suffix = selected?.let { "?conversationId=$it" }.orEmpty()
        val call = http.newCall(request(endpoint, "/desktop/events$suffix").build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(IOException("无法连接服务")) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) { close(IOException("服务不支持桌面 API 或连接被拒绝（${it.code}）")); return }
                    try {
                        val source = it.body?.source() ?: throw IOException("服务响应为空")
                        while (!call.isCanceled()) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data: ")) continue
                            val snapshot = decodeSnapshot(line.removePrefix("data: "), endpoint.bootId)
                            trySend(snapshot)
                        }
                        close()
                    } catch (_: IOException) { close(IOException("连接已断开")) }
                    catch (_: com.google.gson.JsonParseException) { close(IOException("服务响应无效")) }
                    catch (_: IllegalStateException) { close(IOException("服务响应无效")) }
                }
            }
        })
        awaitClose { call.cancel() }
    }

    internal suspend fun post(endpoint: Endpoint, path: String, body: JsonObject): JsonObject = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request(endpoint, path).post(body.toString().toRequestBody("application/json".toMediaType())).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("未确认提交结果；可使用同一请求 ID 重试。"))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!it.isSuccessful) throw IOException("服务拒绝请求（${it.code}），请检查会话或服务版本。")
                        val value = JsonParser.parseString(it.body?.string().orEmpty())
                        if (!value.isJsonObject) throw IOException("服务响应无效")
                        if (continuation.isActive) continuation.resume(value.asJsonObject)
                    } catch (_: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("未确认提交结果；请重连后重试同一请求。"))
                    } catch (_: com.google.gson.JsonParseException) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("服务响应无效"))
                    }
                }
            }
        })
    }

    private fun request(endpoint: Endpoint, path: String) = Request.Builder().url("http://127.0.0.1:${endpoint.port}$path")
        .header("Authorization", "Bearer ${endpoint.token}")

    override fun close() {
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
}

internal fun decodeSnapshot(text: String, expectedBootId: String): JsonObject {
    val parsed = JsonParser.parseString(text)
    if (!parsed.isJsonObject) throw IOException("服务响应无效")
    val snapshot = parsed.asJsonObject
    val runtime = snapshot.getAsJsonObject("runtime") ?: throw IOException("请升级 Bridge 服务")
    if (runtime.get("apiVersion")?.asInt != 1 || runtime.get("bootId")?.asString != expectedBootId)
        throw IOException("服务版本或实例已变化")
    return snapshot
}

internal fun objectJson(vararg fields: Pair<String, String?>) = JsonObject().apply {
    fields.forEach { (key, value) -> if (value != null) addProperty(key, value) }
}

internal fun JsonObject.text(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
