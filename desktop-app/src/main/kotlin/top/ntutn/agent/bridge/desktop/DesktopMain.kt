package top.ntutn.agent.bridge.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.google.gson.JsonObject
import org.jetbrains.compose.resources.painterResource
import top.ntutn.agent.bridge.desktop.resources.Res
import top.ntutn.agent.bridge.desktop.resources.agent_bridge_app_icon
import java.awt.Frame
import java.nio.file.Path

private val Ink = Color(0xFF203330)
private val Muted = Color(0xFF6E7B75)
private val Accent = Color(0xFF247565)
private val Paper = Color(0xFFF9FAF7)
private val Sidebar = Color(0xFFEDF1EB)

fun main(args: Array<String>) {
    var root = Path.of(System.getProperty("user.home"), ".agent-im-bridge-kt")
    var environment = "dev"
    require(args.size % 2 == 0) { "用法：--root <状态目录> --env <dev|prod>" }
    args.toList().chunked(2).forEach { (key, value) ->
        when (key) {
            "--root" -> root = Path.of(value).toAbsolutePath().normalize()
            "--env" -> environment = value
            else -> error("未知桌面参数")
        }
    }
    val target = ConnectionTarget(root, environment)
    application {
        Window(onCloseRequest = ::exitApplication, title = "Agent Bridge · 本地工作台",
            icon = painterResource(Res.drawable.agent_bridge_app_icon),
            state = rememberWindowState(width = 1180.dp, height = 820.dp)) {
            val scope = rememberCoroutineScope()
            val model = remember { DesktopModel(scope, target) }
            DisposableEffect(model) { onDispose { model.close() } }
            MaterialTheme(colors = lightColors(primary = Accent, background = Paper, surface = Color.White,
                onBackground = Ink, onSurface = Ink, secondary = Accent)) {
                Workbench(model, window)
            }
        }
    }
}

@Composable
private fun Workbench(model: DesktopModel, owner: Frame) {
    val state by model.state.collectAsState()
    var settings by remember { mutableStateOf(false) }
    val snapshot = state.snapshot
    val chats = snapshot?.getAsJsonArray("conversations")?.map { it.asJsonObject }.orEmpty()
    val selected = snapshot?.getAsJsonObject("selected")?.takeIf { it.text("id") == state.selectedId }
    val runtime = snapshot?.getAsJsonObject("runtime")
    val canWrite = state.connected && !state.sending && !state.retryAvailable

    Row(Modifier.fillMaxSize().background(Paper)) {
        Column(Modifier.width(256.dp).fillMaxHeight().background(Sidebar).padding(20.dp)) {
            Text("AGENT / BRIDGE", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("本地工作台", fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Spacer(Modifier.height(24.dp))
            Button(onClick = model::create, enabled = canWrite, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp), elevation = ButtonDefaults.elevation(0.dp)) {
                Text("＋  新建会话", modifier = Modifier.padding(4.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("会话  /  ${chats.size}", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(chats.reversed(), key = { it.text("id") }) { chat ->
                    val active = chat.text("id") == state.selectedId
                    Surface(color = if (active) Color.White else Color.Transparent, shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.fillMaxWidth().clickable { model.select(chat.text("id")) }.padding(12.dp)) {
                            Text(chat.text("title"), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, fontSize = 14.sp)
                            val execution = chat.getAsJsonObject("execution")
                            val label = execution?.text("state").orEmpty()
                            Text(if (label == "空闲") "本地会话" else label, fontSize = 11.sp,
                                color = if (label == "空闲") Muted else Accent, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
            Divider(color = Color(0xFFD5DFD5))
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (state.connected) Accent else Color(0xFFB68A45), RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(8.dp))
                Text("${state.target.environment.uppercase()} · ${runtime?.text("backend")?.ifBlank { "Bridge" } ?: "Bridge"}", fontSize = 12.sp)
            }
            TextButton(onClick = { settings = true }, enabled = !state.sending && !state.retryAvailable,
                contentPadding = PaddingValues(top = 8.dp, bottom = 4.dp)) { Text("连接设置", fontSize = 12.sp) }
            Text("窗口关闭后，任务继续运行", color = Muted, fontSize = 11.sp)
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(selected?.text("title") ?: "为下一件事，开启一个会话", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(selected?.text("workspace") ?: "连接你的本地 Agent 运行服务", fontSize = 12.sp, color = Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
                if (selected != null) TextButton(enabled = canWrite && selected.getAsJsonObject("execution")?.text("state") == "空闲" &&
                    selected.getAsJsonObject("execution")?.text("queued") == "0", onClick = {
                    chooseDirectory(owner, selected.text("workspace"))?.let { model.send("/cd $it") }
                }) { Text("选择工作目录") }
            }
            Divider(color = Color(0xFFE4E8E0))
            if (!state.connected) Banner(state.connectionMessage, Color(0xFFFFF2DA))
            else {
                val sandbox = runtime?.text("sandboxMode").orEmpty()
                val permission = when (sandbox) { "read-only" -> "只读"; "workspace-write" -> "工作区可写"; "danger-full-access" -> "完全访问"; else -> sandbox }
                val scheduler = runtime?.getAsJsonObject("scheduler")
                Banner("${runtime?.text("backend")}  ·  $permission  ·  运行 ${scheduler?.text("running")} / 排队 ${scheduler?.text("queued")}" +
                    if (scheduler?.text("draining") == "true") "  ·  服务排空中，新消息暂不执行" else "", Color(0xFFF0F4EE))
            }
            if (state.error != null) Row(Modifier.fillMaxWidth().background(Color(0xFFFFECE5)).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(state.error.orEmpty(), color = Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = model::retry, enabled = state.connected && !state.sending) { Text("重试") }
                TextButton(onClick = model::dismissError, enabled = !state.sending) { Text("关闭提示") }
            }
            if (selected == null) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(60.dp), verticalArrangement = Arrangement.Center) {
                    Text("从一个问题开始。", fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(16.dp))
                    Text("阅读代码、梳理思路，或推进手头的工作。\n任务在本机运行，回复与历史保留在当前环境。", color = Muted, lineHeight = 25.sp)
                    Spacer(Modifier.height(26.dp))
                    OutlinedButton(onClick = model::create, enabled = canWrite, shape = RoundedCornerShape(8.dp)) { Text("创建第一个会话  →") }
                    if (!state.connected) {
                        Spacer(Modifier.height(20.dp))
                        SelectionContainer { Text("启动服务：./bridgectl dev\n已有服务需要先发布包含桌面 API 的版本。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp) }
                    }
                }
            } else {
                History(selected, Modifier.weight(1f).fillMaxWidth())
                Composer(model, state, selected, canWrite)
            }
        }
    }
    if (settings) ConnectionDialog(state.target, onDismiss = { settings = false }, onConnect = {
        model.connect(it); settings = false
    })
}

@Composable
private fun Banner(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().background(color).padding(horizontal = 30.dp, vertical = 10.dp),
        color = Muted, fontSize = 12.sp)
}

@Composable
private fun History(chat: JsonObject, modifier: Modifier) {
    val requests = chat.getAsJsonArray("requests")?.map { it.asJsonObject }.orEmpty()
    val scroll = rememberLazyListState()
    LaunchedEffect(chat.text("id"), requests.size) { if (requests.isNotEmpty()) scroll.animateScrollToItem(requests.lastIndex) }
    Box(modifier) {
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(30.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)) {
            if (requests.isEmpty()) item {
                Text("会话已就绪。选择工作目录，然后输入你的需求。", color = Muted, fontSize = 14.sp)
            }
            items(requests, key = { it.text("id") }) { request ->
                Column {
                    Surface(color = Color(0xFFEAF0E7), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("你", fontSize = 11.sp, color = Muted, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            MarkdownMessageContent(request.text("prompt"), chat.text("workspace"))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AGENT", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                        Text(request.text("state"), color = Muted, fontSize = 11.sp)
                    }
                    if (request.text("answer").isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        MarkdownMessageContent(request.text("answer"), chat.text("workspace"))
                    }
                    request.getAsJsonArray("notes")?.forEach { note ->
                        SelectionContainer { Text(note.asString, color = Muted, fontSize = 12.sp, lineHeight = 20.sp,
                            modifier = Modifier.padding(top = 8.dp)) }
                    }
                    if (request.text("process").isNotBlank()) {
                        var expanded by remember(request.text("id")) { mutableStateOf(false) }
                        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                            Text(if (expanded) "收起执行摘要 ↑" else "查看执行摘要 ↓", fontSize = 12.sp)
                        }
                        if (expanded) Surface(color = Color(0xFFF0F2ED), shape = RoundedCornerShape(8.dp)) {
                            SelectionContainer { Text(request.text("process"), color = Muted, fontSize = 12.sp, lineHeight = 21.sp,
                                modifier = Modifier.fillMaxWidth().padding(14.dp)) }
                        }
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun Composer(model: DesktopModel, state: DesktopState, chat: JsonObject, enabled: Boolean) {
    var draft by remember(chat.text("id")) { mutableStateOf("") }
    val execution = chat.getAsJsonObject("execution")
    val runningId = execution?.text("requestId")?.takeIf { it.isNotBlank() }
    val queued = execution?.text("queued")?.toIntOrNull() ?: 0
    fun send() { if (model.send(draft)) draft = "" }
    Column(Modifier.fillMaxWidth().padding(start = 30.dp, end = 30.dp, bottom = 22.dp, top = 10.dp)) {
        OutlinedTextField(value = draft, onValueChange = { if (it.length <= 32_000) draft = it },
            placeholder = { Text("描述你的需求…", color = Muted) }, enabled = state.connected,
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 190.dp).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && (it.isCtrlPressed || it.isMetaPressed) && enabled) {
                    send(); true
                } else false
            }, shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⌘ / Ctrl + Enter 发送  ·  /help 查看命令", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (runningId != null || queued > 0) TextButton(onClick = { model.send("/stop", runningId) }, enabled = enabled) {
                Text(if (execution?.text("state") == "停止中") "等待停止确认…" else "停止当前会话", color = Color(0xFFA04B37))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = ::send, enabled = enabled && draft.isNotBlank(), shape = RoundedCornerShape(8.dp),
                elevation = ButtonDefaults.elevation(0.dp)) { Text(if (state.sending) "提交中…" else if (runningId != null) "加入队列" else "发送  ↑") }
        }
    }
}

@Composable
private fun ConnectionDialog(target: ConnectionTarget, onDismiss: () -> Unit, onConnect: (ConnectionTarget) -> Unit) {
    var root by remember { mutableStateOf(target.root.toString()) }
    var env by remember { mutableStateOf(target.environment) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("连接本地 Bridge") }, text = {
        Column(Modifier.width(460.dp)) {
            Text("连接由 bridgectl 管理的服务。桌面不会启动、停止或升级机器人。", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(root, { root = it }, label = { Text("Bridge 状态根目录") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(env == "dev", { env = "dev" }); Text("开发 dev")
                Spacer(Modifier.width(16.dp))
                RadioButton(env == "prod", { env = "prod" }); Text("正式 prod")
            }
            Text("当前环境的模型与访问权限由本机配置决定。", color = Muted, fontSize = 12.sp)
        }
    }, confirmButton = {
        TextButton(enabled = root.isNotBlank() && !root.contains('\u0000'), onClick = {
            onConnect(ConnectionTarget(Path.of(root).toAbsolutePath().normalize(), env))
        }) { Text("连接") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
