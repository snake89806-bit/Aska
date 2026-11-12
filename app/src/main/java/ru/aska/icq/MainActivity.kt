package ru.aska.icq

import android.content.SharedPreferences
import android.Manifest
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.sin
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Один клиент на всё приложение, чтобы поворот экрана не рвал соединение. */
val oscar = OscarClient()

class MainActivity : ComponentActivity() {
    override fun onStart() { super.onStart(); oscar.appVisible = true }
    override fun onStop() { oscar.appVisible = false; super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF3F9E3A))) {
                Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding()) { App() }
            }
        }
    }
}

val AskaGreen = Color(0xFF3F9E3A)

@Composable
fun Flower(color: Color, dp: Dp = 22.dp) {
    Canvas(Modifier.size(dp)) {
        val r = size.minDimension / 2
        for (i in 0 until 5) {
            val a = Math.toRadians(i * 72.0 - 90)
            drawCircle(
                color, r * 0.37f,
                Offset(center.x + (r * 0.62f * cos(a)).toFloat(), center.y + (r * 0.62f * sin(a)).toFloat())
            )
        }
        drawCircle(Color.White.copy(alpha = 0.9f), r * 0.2f, center)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = AskaGreen,
        modifier = Modifier.fillMaxWidth().background(Color(0xFFF1F8F0)).padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
fun ContactRow(c: Contact, n: Int, onOpen: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(c.uin) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Flower(statusColor(c.status), 24.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.uin, style = MaterialTheme.typography.bodyLarge)
            Text(statusName(c.status), style = MaterialTheme.typography.bodySmall)
        }
        if (n > 0) Box(Modifier.clip(CircleShape).background(AskaGreen).padding(horizontal = 9.dp, vertical = 3.dp)) {
            Text("$n", color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun statusName(s: Int) = when {
    s == OFFLINE -> "не в сети"
    (s and 0x0100) != 0 -> "Невидимка"
    (s and 0x0002) != 0 || (s and 0x0010) != 0 -> "Не беспокоить"
    (s and 0x0001) != 0 || (s and 0x0004) != 0 -> "Отошёл"
    else -> "В сети"
}

fun statusColor(s: Int) = when {
    s == OFFLINE -> Color.Gray
    (s and 0x0012) != 0 -> Color(0xFFD32F2F)
    (s and 0x0005) != 0 -> Color(0xFFFFA000)
    else -> Color(0xFF2E9E3E)
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("icq", 0)
    val conn by oscar.conn.collectAsState()
    var chat by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (oscar.contacts.value.isEmpty())
            oscar.loadContacts(prefs.getStringSet("c", emptySet())!!.sorted())
        oscar.contacts.collect { l ->
            prefs.edit().putStringSet("c", l.map { it.uin }.toSet()).apply()
        }
    }
    LaunchedEffect(conn) {
        val svc = Intent(ctx, ConnService::class.java)
        when (conn) {
            Conn.OFF -> { chat = null; ctx.stopService(svc) }
            Conn.CONNECTING -> ContextCompat.startForegroundService(ctx, svc)
            else -> {}
        }
    }

    val crashFile = remember { File(ctx.filesDir, "crash.txt") }
    var crash by remember { mutableStateOf(if (crashFile.exists()) crashFile.readText() else null) }
    val crashText = crash
    if (crashText != null) {
        CrashScreen(crashText) { crashFile.delete(); crash = null }
        return
    }

    val open = chat
    when {
        conn == Conn.OFF || conn == Conn.CONNECTING -> LoginScreen(prefs)
        open == null -> ContactsScreen { chat = it }
        else -> {
            BackHandler { chat = null }
            ChatScreen(open) { chat = null }
        }
    }
}

@Composable
fun LoginScreen(prefs: SharedPreferences) {
    val conn by oscar.conn.collectAsState()
    val err by oscar.error.collectAsState()
    var host by remember { mutableStateOf(prefs.getString("host", "192.168.0.15")!!) }
    var uin by remember { mutableStateOf(prefs.getString("uin", "")!!) }
    var pass by remember { mutableStateOf(prefs.getString("pass", "")!!) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Flower(AskaGreen, 44.dp)
            Spacer(Modifier.width(12.dp))
            Text("Аська", style = MaterialTheme.typography.headlineLarge)
        }
        OutlinedTextField(host, { host = it }, label = { Text("Сервер (IP компьютера)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(uin, { uin = it.filter(Char::isDigit) }, label = { Text("Номер ICQ (UIN)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(pass, { pass = it }, label = { Text("Пароль") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        err?.let { Text(it, color = Color(0xFFD32F2F)) }
        Button(
            onClick = {
                prefs.edit().putString("host", host.trim()).putString("uin", uin).putString("pass", pass).apply()
                oscar.connect(host.trim(), uin.trim(), pass)
            },
            enabled = conn == Conn.OFF && uin.isNotBlank() && host.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (conn == Conn.CONNECTING) "Подключаюсь…" else "Войти") }
        Text("Номер из цифр, например 100001. Пока сервер в режиме DISABLE_AUTH, аккаунт создаётся при первом входе.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ContactsScreen(onOpen: (String) -> Unit) {
    val list by oscar.contacts.collectAsState()
    val my by oscar.myStatus.collectAsState()
    val conn by oscar.conn.collectAsState()
    val unread by oscar.unread.collectAsState()
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(PowerManager::class.java)
    val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    var menu by remember { mutableStateOf(false) }
    var newUin by remember { mutableStateOf("") }
    val white = ButtonDefaults.textButtonColors(contentColor = Color.White)
    val online = list.filter { it.status != OFFLINE }
    val offline = list.filter { it.status == OFFLINE }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(AskaGreen).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Flower(Color.White, 30.dp)
            Spacer(Modifier.width(8.dp))
            Text("Аська", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { menu = true }, colors = white) { Text(statusName(my)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(ST_ONLINE to "В сети", ST_AWAY to "Отошёл",
                        ST_DND to "Не беспокоить", ST_INVISIBLE to "Невидимка").forEach { (code, name) ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { oscar.setMyStatus(code); menu = false })
                    }
                }
            }
            TextButton(onClick = { oscar.disconnect() }, colors = white) { Text("Выйти") }
        }
        if (conn == Conn.RECONNECTING)
            Text("Нет связи с сервером, переподключаюсь…", color = Color(0xFFD32F2F),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        if (!ignoring)
            TextButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ctx.packageName)))
            }) { Text("Разрешить работу в фоне без ограничений") }
        LazyColumn(Modifier.weight(1f)) {
            item { SectionTitle("В сети — ${online.size}") }
            items(online, key = { it.uin }) { ContactRow(it, unread[it.uin] ?: 0, onOpen) }
            item { SectionTitle("Не в сети — ${offline.size}") }
            items(offline, key = { it.uin }) { ContactRow(it, unread[it.uin] ?: 0, onOpen) }
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newUin, { newUin = it.filter(Char::isDigit) }, label = { Text("Добавить номер") },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (newUin.isNotBlank()) { oscar.addContact(newUin); newUin = "" } }) { Text("＋") }
        }
    }
}

@Composable
fun ChatScreen(uin: String, onBack: () -> Unit) {
    val all by oscar.chats.collectAsState()
    val msgs = all[uin].orEmpty()
    var text by remember { mutableStateOf("") }
    val state = rememberLazyListState()
    val conn by oscar.conn.collectAsState()
    DisposableEffect(uin) {
        oscar.openChat = uin
        onDispose { oscar.openChat = null }
    }
    LaunchedEffect(uin, msgs.size) { oscar.clearUnread(uin) }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) state.animateScrollToItem(msgs.size - 1) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(AskaGreen).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("←") }
            Flower(Color.White, 22.dp)
            Spacer(Modifier.width(8.dp))
            Text(uin, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { oscar.removeContact(uin); onBack() },
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
            ) { Text("Удалить") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), state = state) {
            items(msgs) { m ->
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Text(
                        m.text,
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (m.mine) Color(0xFFCDEBC5) else Color(0xFFE8E8E8)).padding(10.dp),
                        color = Color.Black
                    )
                }
            }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, placeholder = { Text("Сообщение") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { oscar.sendMessage(uin, text); text = "" }, enabled = text.isNotBlank() && conn == Conn.ON) { Text("➤") }
        }
    }
}

@Composable
fun CrashScreen(text: String, onClose: () -> Unit) {
    val clip = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Прошлый запуск закончился ошибкой", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { clip.setText(AnnotatedString(text)) }) { Text("Скопировать") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClose) { Text("Продолжить") }
        }
        SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(text.take(4000), style = MaterialTheme.typography.bodySmall)
        }
    }
}
