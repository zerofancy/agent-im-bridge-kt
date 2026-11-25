package top.ntutn.agent.bridge.android

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AuthScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun serverDropdownOffersBothEnvironments() {
        compose.setContent {
            MaterialTheme {
                AuthScreen(
                    AuthUiState(DEFAULT_ADDRESS),
                    onSignIn = { _, _, _, _ -> false },
                    onRefresh = {},
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithTag("bridge_server_address").performClick()
        compose.onNodeWithText("https://zp-agent-dev.ntutn.top/").performClick()
        compose
            .onNodeWithTag("bridge_server_address")
            .assertTextEquals("服务地址", "https://zp-agent-dev.ntutn.top/")
    }

    @Test
    fun loginFormPreservesLeadingZeroAndClearsCredentialsAfterSubmit() {
        var input: List<String>? = null
        compose.setContent {
            MaterialTheme {
                AuthScreen(
                    state = AuthUiState(DEFAULT_ADDRESS),
                    onSignIn = { address, username, password, totp ->
                        input = listOf(address, username, password, totp)
                        true
                    },
                    onRefresh = {},
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithText("登录").assertIsNotEnabled()
        compose.onNodeWithTag("bridge_username").performTextInput("zero")
        compose.onNodeWithTag("bridge_password").performTextInput("test-password")
        compose.onNodeWithTag("bridge_totp").performTextInput("012345")
        compose.onNodeWithText("登录").performClick()
        compose.runOnIdle {
            assertEquals(listOf(DEFAULT_ADDRESS, "zero", "test-password", "012345"), input)
        }
        compose.onNodeWithText("登录").assertIsNotEnabled()
    }

    @Test
    fun sessionShowsEnvironmentAndExposesRefreshAndLogoutActions() {
        var refreshed = false
        var signedOut = false
        compose.setContent {
            MaterialTheme {
                AuthScreen(
                    state =
                        AuthUiState(
                            DEFAULT_ADDRESS,
                            SessionInfo("dev", Instant.parse("2026-11-10T00:00:00Z")),
                        ),
                    onSignIn = { _, _, _, _ -> false },
                    onRefresh = { refreshed = true },
                    onSignOut = { signedOut = true },
                )
            }
        }
        compose.onNodeWithText("环境 dev").assertExists()
        compose.onNodeWithText("刷新状态").performClick()
        compose.onNodeWithText("退出登录").performClick()
        compose.runOnIdle {
            assertTrue(refreshed)
            assertTrue(signedOut)
        }
    }
}
