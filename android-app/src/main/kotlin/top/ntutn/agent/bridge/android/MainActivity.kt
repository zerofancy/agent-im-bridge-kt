package top.ntutn.agent.bridge.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {
    private val model by viewModels<AuthViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsState()
            MaterialTheme {
                if (state.session == null) {
                    AuthScreen(state, model::signIn, model::refreshSession, model::signOut)
                } else {
                    val chat by model.chat.state.collectAsState()
                    ChatScreen(state, chat, model.chat, model::refreshSession, model::signOut)
                }
            }
        }
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
