package top.ntutn.agent.bridge.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun AuthScreen(
    state: AuthUiState,
    onSignIn: (address: String, password: String, totp: String) -> Boolean,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier.widthIn(max = 480.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Agent Bridge", style = MaterialTheme.typography.h4)
                if (state.session == null) {
                    SignInForm(state, onSignIn)
                } else {
                    SessionDetails(state.address, state.session, state.busy, onRefresh, onSignOut)
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.message?.let {
                    Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
    }
}

@Composable
private fun SignInForm(state: AuthUiState, onSignIn: (String, String, String) -> Boolean) {
    var address by remember(state.address) { mutableStateOf(state.address) }
    var password by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    Text("连接 Bridge 服务", style = MaterialTheme.typography.h6)
    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text("服务地址") },
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag("bridge_server_address"),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("密码") },
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag("bridge_password"),
    )
    OutlinedTextField(
        value = totp,
        onValueChange = { input -> totp = input.filter { it in '0'..'9' }.take(6) },
        label = { Text("六位验证码") },
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("bridge_totp"),
    )
    Button(
        onClick = {
            if (onSignIn(address, password, totp)) {
                password = ""
                totp = ""
            }
        },
        enabled = !state.busy && address.isNotBlank() && password.isNotEmpty() && totp.length == 6,
        modifier = Modifier.fillMaxWidth().testTag("bridge_login"),
    ) {
        Text("登录")
    }
}

@Composable
private fun SessionDetails(
    address: String,
    session: SessionInfo,
    busy: Boolean,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    val expires =
        remember(session.expiresAt) {
            DateFormat.getDateTimeInstance().format(Date(session.expiresAt.toEpochMilliseconds()))
        }
    Text("已登录", style = MaterialTheme.typography.h6)
    Text(address)
    Text("环境 ${session.environment}")
    Text("会话到期 $expires")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onRefresh,
            enabled = !busy,
            modifier = Modifier.testTag("bridge_session_refresh"),
        ) {
            Text("刷新状态")
        }
        Button(onClick = onSignOut, enabled = !busy, modifier = Modifier.testTag("bridge_logout")) {
            Text("退出登录")
        }
    }
}

@Preview(name = "登录", widthDp = 360, heightDp = 720, showBackground = true)
@Composable
private fun SignInPreview() {
    MaterialTheme {
        AuthScreen(
            AuthUiState(DEFAULT_ADDRESS),
            onSignIn = { _, _, _ -> false },
            onRefresh = {},
            onSignOut = {},
        )
    }
}
