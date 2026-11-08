package top.ntutn.agent.bridge.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

internal data class AuthUiState(
    val address: String,
    val session: SessionInfo? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

internal class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val client = AuthClient()
    private val mutableState =
        MutableStateFlow(
            AuthUiState(preferences.getString("address", DEFAULT_ADDRESS) ?: DEFAULT_ADDRESS)
        )
    val state = mutableState.asStateFlow()
    val chat = ChatController(viewModelScope, File(application.filesDir, "chat"))

    private class ActiveSession(val origin: HttpUrl, val token: String)

    private var activeSession: ActiveSession? = null
    private var expiration: Job? = null

    fun signIn(address: String, password: String, totp: String): Boolean {
        if (state.value.busy || activeSession != null) {
            return false
        }
        val origin = parseServerAddress(address)
        if (origin == null) {
            showMessage("请输入 HTTPS 服务地址，可包含端口。")
            return false
        }
        if (password.isEmpty() || password.toByteArray().size > 1_024) {
            showMessage("请输入有效密码。")
            return false
        }
        if (totp.length != 6 || totp.any { it !in '0'..'9' }) {
            showMessage("请输入六位验证码。")
            return false
        }
        val canonicalAddress = origin.toString().removeSuffix("/")
        preferences.edit().putString("address", canonicalAddress).apply()
        mutableState.value = AuthUiState(canonicalAddress, busy = true)
        viewModelScope.launch {
            try {
                val result = client.login(origin, password, totp)
                val active = ActiveSession(origin, result.token)
                activeSession = active
                confirmSession(active, result.session)
                if (activeSession === active) {
                    chat.openSession(origin, active.token, result.session.environment) {
                        if (activeSession === active) {
                            clearSession("会话已失效，请重新登录。")
                        }
                    }
                }
            } catch (e: AuthHttpFailure) {
                val message =
                    when (e.status) {
                        401 -> {
                            "密码或验证码错误。"
                        }
                        429 -> {
                            e.retryAfter?.let { "尝试过于频繁，请在 $it 秒后重试。" } ?: "尝试过于频繁，请稍后重试。"
                        }
                        400 -> {
                            "登录参数无效，请检查输入。"
                        }
                        else -> {
                            "服务暂时无法登录（HTTP ${e.status}）。"
                        }
                    }
                mutableState.update { it.copy(busy = false, message = message) }
            } catch (_: IOException) {
                mutableState.update { it.copy(busy = false, message = "无法登录，请检查网络、服务地址和证书。") }
            }
        }
        return true
    }

    fun refreshSession() {
        val active = activeSession ?: return
        if (state.value.busy) {
            return
        }
        val session = checkNotNull(state.value.session)
        if (session.expiresAt <= Clock.System.now()) {
            clearSession("会话已过期，请重新登录。")
            return
        }
        mutableState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val details = client.session(active.origin, active.token)
                if (activeSession === active) {
                    confirmSession(active, details)
                }
            } catch (e: AuthHttpFailure) {
                if (activeSession === active) {
                    if (e.status == 401) {
                        clearSession("会话已失效，请重新登录。")
                    } else {
                        connectionUnconfirmed()
                    }
                }
            } catch (_: IOException) {
                if (activeSession === active) {
                    connectionUnconfirmed()
                }
            }
        }
    }

    fun signOut() {
        val active = activeSession ?: return
        if (state.value.busy) {
            return
        }
        expiration?.cancel()
        chat.closeSession()
        mutableState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                client.logout(active.origin, active.token)
                if (activeSession === active) {
                    clearSession("已退出登录。")
                }
            } catch (_: IOException) {
                if (activeSession === active) {
                    clearSession("已在本机退出，服务器退出未确认，会话将在到期时失效。")
                }
            }
        }
    }

    private fun confirmSession(active: ActiveSession, details: SessionInfo) {
        expiration?.cancel()
        val remaining = details.expiresAt - Clock.System.now()
        if (!remaining.isPositive()) {
            clearSession("会话已过期，请重新登录。")
            return
        }
        mutableState.update { it.copy(session = details, busy = false, message = null) }
        expiration =
            viewModelScope.launch {
                delay(remaining)
                if (activeSession === active) {
                    clearSession("会话已过期，请重新登录。")
                }
            }
    }

    private fun clearSession(message: String) {
        expiration?.cancel()
        expiration = null
        activeSession = null
        chat.closeSession()
        mutableState.value = AuthUiState(state.value.address, message = message)
    }

    private fun showMessage(message: String) {
        mutableState.update { it.copy(message = message) }
    }

    private fun connectionUnconfirmed() {
        mutableState.update { it.copy(busy = false, message = "无法确认服务连接，请检查网络后刷新。") }
    }
}
