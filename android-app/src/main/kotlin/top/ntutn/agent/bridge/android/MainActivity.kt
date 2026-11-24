package top.ntutn.agent.bridge.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private val model by viewModels<AuthViewModel>()
    private val updates by viewModels<AppUpdateViewModel>()
    private var resumed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsState()
            CompositionLocalProvider(LocalAppUpdates provides updates.controller) {
                MaterialTheme {
                    if (state.session == null) {
                        AuthScreen(state, model::signIn, model::refreshSession, model::signOut)
                    } else {
                        val chat by model.chat.state.collectAsState()
                        ChatScreen(state, chat, model.chat, model::refreshSession, model::signOut)
                    }
                }
                AppUpdateHost(updates.controller, this@MainActivity, resumed)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updates.checkOnStart()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        model.chat.setForeground(true)
        model.refreshSession()
    }

    override fun onStop() {
        model.chat.setForeground(false)
        super.onStop()
    }
}
