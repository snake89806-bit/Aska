package ru.aska.icq

import android.content.SharedPreferences
import android.os.Bundle
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding()) { App() }
            }
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
    val prefs = LocalContext.current.getSharedPreferences("icq", 0)
    val conn by oscar.conn.collectAsState()
    var chat by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (oscar.contacts.value.isEmpty())
            oscar.loadContacts(prefs.getStringSet("c", emptySet())!!.sorted())
        oscar.contacts.collect { l ->
            prefs.edit().putStringSet("c", l.map { it.uin }.toSet()).apply()
        }
    }
    LaunchedEffect(conn) { if (conn != Conn.ON) chat = null }

    val open = chat
    when {
        conn != Conn.ON -> LoginScreen(prefs)
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
        Text("🌼 Аська", style = MaterialTheme.typography.headlineLarge)
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
    var menu by remember { mutableStateOf(false) }
    var newUin by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🌼 Аська", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { menu = true }) { Text(statusName(my)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(ST_ONLINE to "В сети", ST_AWAY to "Отошёл",
                        ST_DND to "Не беспокоить", ST_INVISIBLE to "Невидимка").forEach { (code, name) ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { oscar.setMyStatus(code); menu = false })
                    }
                }
            }
            TextButton(onClick = { oscar.disconnect() }) { Text("Выйти") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            items(list.sortedBy { it.status == OFFLINE }, key = { it.uin }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(c.uin) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(statusColor(c.status)))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(c.uin, style = MaterialTheme.typography.bodyLarge)
                        Text(statusName(c.status), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
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
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) state.animateScrollToItem(msgs.size - 1) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text(uin, style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider()
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
            Button(onClick = { oscar.sendMessage(uin, text); text = "" }, enabled = text.isNotBlank()) { Text("➤") }
        }
    }
}
