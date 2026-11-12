package ru.aska.icq

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

const val OFFLINE = -1
const val ST_ONLINE = 0x0000
const val ST_AWAY = 0x0001
const val ST_DND = 0x0002
const val ST_INVISIBLE = 0x0100

data class Contact(val uin: String, val status: Int = OFFLINE)
data class Msg(val mine: Boolean, val text: String)
enum class Conn { OFF, CONNECTING, ON, RECONNECTING }

class AuthRejected(msg: String) : Exception(msg)

/** Запись байтов (big-endian). */
class W {
    private val o = ByteArrayOutputStream()
    fun u8(v: Int) = apply { o.write(v) }
    fun u16(v: Int) = apply { o.write(v shr 8); o.write(v) }
    fun u32(v: Long) = apply { u16((v shr 16).toInt()); u16(v.toInt()) }
    fun bytes(b: ByteArray) = apply { o.write(b) }
    fun str(s: String) = bytes(s.toByteArray(Charsets.ISO_8859_1))
    fun tlv(t: Int, b: ByteArray) = apply { u16(t); u16(b.size); bytes(b) }
    fun tlv(t: Int, s: String) = tlv(t, s.toByteArray(Charsets.ISO_8859_1))
    fun done(): ByteArray = o.toByteArray()
}

/** Чтение байтов (big-endian). */
class ByteReader(private val b: ByteArray, var p: Int = 0) {
    val left get() = b.size - p
    fun u8() = b[p++].toInt() and 0xFF
    fun u16(): Int { val h = u8(); return (h shl 8) or u8() }
    fun bytes(n: Int): ByteArray = b.copyOfRange(p, p + n).also { p += n }
    fun str(n: Int) = String(bytes(n), Charsets.ISO_8859_1)
    fun skip(n: Int) { p += n }
}

fun snac(fam: Int, sub: Int, data: ByteArray, req: Int) =
    W().u16(fam).u16(sub).u16(0).u32(req.toLong()).bytes(data).done()

class Flap(val channel: Int, val data: ByteArray)

/** FLAP — транспортный уровень OSCAR: 0x2A, канал, номер, длина, данные. */
class FlapConnection(host: String, port: Int, timeoutMs: Int = 0) : Closeable {
    private val socket = Socket()
    private val input: DataInputStream
    private var seq = Random.nextInt(0x1000, 0x7000)

    init {
        socket.connect(InetSocketAddress(host, port), 8000)
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = timeoutMs
        input = DataInputStream(socket.getInputStream().buffered())
    }

    @Synchronized
    fun send(channel: Int, data: ByteArray) {
        val out = W().u8(0x2A).u8(channel).u16(seq).u16(data.size).bytes(data).done()
        seq = (seq + 1) and 0xFFFF
        socket.getOutputStream().apply { write(out); flush() }
    }

    fun read(): Flap {
        val h = ByteArray(6)
        input.readFully(h)
        require(h[0] == 0x2A.toByte()) { "Неверный FLAP-пакет" }
        val len = ((h[4].toInt() and 0xFF) shl 8) or (h[5].toInt() and 0xFF)
        val d = ByteArray(len)
        input.readFully(d)
        return Flap(h[1].toInt() and 0xFF, d)
    }

    override fun close() { try { socket.close() } catch (_: Exception) {} }
}

private val ROAST = intArrayOf(
    0xF3, 0x26, 0x81, 0xC4, 0x39, 0x86, 0xDB, 0x92,
    0x71, 0xA3, 0xB9, 0xE6, 0x53, 0x7A, 0x95, 0x7C
)
private fun roast(p: String) = ByteArray(p.length) { (p[it].code xor ROAST[it % 16]).toByte() }
private fun u16b(v: Int) = W().u16(v).done()
private fun u32b(v: Long) = W().u32(v).done()

private val FAMILIES = listOf(1 to 4, 2 to 1, 3 to 1, 4 to 1, 9 to 1, 0x13 to 4)

/** Элемент серверного списка контактов (SSI). type: 0 = контакт, 1 = группа. */
private class SsiItem(val name: String, val gid: Int, val id: Int, val type: Int, val data: ByteArray) {
    fun encode(): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        return W().u16(n.size).bytes(n).u16(gid).u16(id).u16(type).u16(data.size).bytes(data).done()
    }

    fun members(): List<Int> {
        val out = mutableListOf<Int>()
        val r = ByteReader(data)
        while (r.left >= 4) {
            val t = r.u16(); val v = r.bytes(r.u16())
            if (t == 0xC8) { val m = ByteReader(v); while (m.left >= 2) out += m.u16() }
        }
        return out
    }
}

private fun memberData(ids: List<Int>): ByteArray =
    if (ids.isEmpty()) ByteArray(0) else W().tlv(0xC8, W().apply { ids.forEach { u16(it) } }.done()).done()

private const val CLIENT_ID = "ICQ Inc. - Product of ICQ (TM).2000b.4.63.1.3279.85"
private const val TAG = "Aska"

class OscarClient {
    val conn = MutableStateFlow(Conn.OFF)
    val error = MutableStateFlow<String?>(null)
    val contacts = MutableStateFlow<List<Contact>>(emptyList())
    val chats = MutableStateFlow<Map<String, List<Msg>>>(emptyMap())
    val myStatus = MutableStateFlow(ST_ONLINE)

    val unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    val incoming = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 32)
    @Volatile var appVisible = false
    @Volatile var openChat: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var generation = 0
    private var everOn = false
    private var job: Job? = null
    private var bos: FlapConnection? = null
    private var req = 1
    private var closing = false
    private var started = false
    private var finished = false
    private val ssi = mutableListOf<SsiItem>()

    @Synchronized private fun nextReq() = req++

    fun loadContacts(list: List<String>) { contacts.value = list.map { Contact(it) } }

    fun connect(host: String, uin: String, pass: String) {
        job?.cancel()
        bos?.close()
        closing = false
        everOn = false
        val gen = ++generation
        conn.value = Conn.CONNECTING
        error.value = null
        job = scope.launch {
            var wait = 3000L
            while (isActive) {
                started = false
                finished = false
                val ping = launch {
                    while (true) {
                        delay(60_000)
                        try { bos?.send(5, ByteArray(0)) } catch (_: Throwable) {}
                    }
                }
                var stop = false
                try {
                    val (addr, cookie) = authenticate(host, uin, pass)
                    session(addr, cookie)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "session error", e)
                    if (!closing) error.value = e.javaClass.simpleName + ": " + (e.message ?: "соединение разорвано")
                    if (e is AuthRejected) stop = true
                } finally {
                    ping.cancel()
                    bos?.close(); bos = null
                    contacts.update { l -> l.map { it.copy(status = OFFLINE) } }
                }
                if (closing || stop || !everOn) break
                conn.value = Conn.RECONNECTING
                delay(wait)
                wait = minOf(wait * 2, 60_000L)
            }
            if (gen == generation) conn.value = Conn.OFF
        }
    }

    fun disconnect() {
        closing = true
        generation++
        bos?.close()
        job?.cancel()
        conn.value = Conn.OFF
    }

    fun clearUnread(uin: String) { unread.update { it - uin } }

    fun addContact(uin: String) {
        val u = uin.trim()
        if (u.isEmpty() || contacts.value.any { it.uin == u }) return
        contacts.update { it + Contact(u) }
        if (conn.value == Conn.ON) scope.launch {
            send(3, 4, W().u8(u.length).str(u).done())
            ssiAdd(listOf(u))
        }
    }

    fun removeContact(uin: String) {
        contacts.update { l -> l.filter { it.uin != uin } }
        unread.update { it - uin }
        if (conn.value == Conn.ON) scope.launch {
            send(3, 5, W().u8(uin.length).str(uin).done())
            ssiRemove(uin)
        }
    }

    fun setMyStatus(code: Int) {
        myStatus.value = code
        scope.launch { send(1, 0x1E, W().tlv(6, u32b(code.toLong())).done()) }
    }

    fun sendMessage(to: String, text: String) {
        if (text.isBlank()) return
        val body = text.toByteArray(Charsets.UTF_16BE)
        // фрагмент возможностей (05 01) + фрагмент текста (01 01): кодировка 2 = UTF-16BE
        val frag = W().u8(5).u8(1).u16(1).u8(1)
            .u8(1).u8(1).u16(4 + body.size).u16(2).u16(0).bytes(body).done()
        val data = W().bytes(Random.nextBytes(8)).u16(1).u8(to.length).str(to)
            .tlv(2, frag).tlv(6, ByteArray(0)).done()
        addMsg(to, Msg(true, text))
        scope.launch { send(4, 6, data) }
    }

    // ---------- вход ----------

    private fun authenticate(host: String, uin: String, pass: String): Pair<String, ByteArray> {
        FlapConnection(host, 5190, 15000).use { c ->
            c.read() // приветствие сервера (канал 1)
            c.send(
                1,
                W().u32(1)
                    .tlv(0x01, uin).tlv(0x02, roast(pass)).tlv(0x03, CLIENT_ID)
                    .tlv(0x16, u16b(0x010A)).tlv(0x17, u16b(4)).tlv(0x18, u16b(63))
                    .tlv(0x19, u16b(1)).tlv(0x1A, u16b(3279)).tlv(0x14, u32b(85))
                    .tlv(0x0F, "en").tlv(0x0E, "us").done()
            )
            while (true) {
                val f = c.read()
                if (f.channel != 4) continue
                val r = ByteReader(f.data)
                var addr: String? = null
                var cookie: ByteArray? = null
                var err = -1
                while (r.left >= 4) {
                    val t = r.u16(); val v = r.bytes(r.u16())
                    when (t) {
                        0x05 -> addr = String(v, Charsets.ISO_8859_1)
                        0x06 -> cookie = v
                        0x08 -> err = if (v.size >= 2) ((v[0].toInt() and 0xFF) shl 8) or (v[1].toInt() and 0xFF) else 0
                    }
                }
                if (addr == null || cookie == null)
                    throw AuthRejected("Сервер отказал во входе (код $err). Проверьте номер и пароль.")
                return addr to cookie
            }
        }
        throw IllegalStateException("unreachable")
    }

    private fun session(addr: String, cookie: ByteArray) {
        val host = addr.substringBefore(':')
        val port = addr.substringAfter(':', "5190").toInt()
        val c = FlapConnection(host, port)
        bos = c
        synchronized(this) { ssi.clear() }
        c.read() // приветствие BOS
        c.send(1, W().u32(1).tlv(6, cookie).done())
        while (true) {
            val f = c.read()
            when (f.channel) {
                2 -> try { onSnac(f.data) } catch (e: Throwable) { Log.w(TAG, "bad snac", e) }
                4 -> return
            }
        }
    }

    private fun send(fam: Int, sub: Int, data: ByteArray = ByteArray(0)) {
        try { bos?.send(2, snac(fam, sub, data, nextReq())) } catch (e: Exception) { Log.w(TAG, "send failed", e) }
    }

    private fun onSnac(data: ByteArray) {
        val r = ByteReader(data)
        val fam = r.u16(); val sub = r.u16(); val flags = r.u16(); r.skip(4)
        Log.d(TAG, "SNAC %04x,%04x".format(fam, sub))
        when ((fam shl 16) or sub) {
            0x0001_0003 -> send(1, 0x17, W().apply {
                FAMILIES.forEach { u16(it.first); u16(it.second) }
            }.done())
            0x0001_0018 -> send(1, 0x06)
            0x0001_0007 -> {
                send(1, 0x08, W().apply { for (i in 1..5) u16(i) }.done())
                if (!started) {
                    started = true
                    send(0x13, 2)
                    send(0x13, 4)
                    scope.launch { delay(5000); finishLogin() } // запасной вариант, если сервер не ответит
                }
            }
            0x0003_000B -> {
                val name = r.str(r.u8()); r.u16()
                var st = 0
                repeat(r.u16()) {
                    val t = r.u16(); val v = r.bytes(r.u16())
                    if (t == 6 && v.size >= 4) st = ((v[2].toInt() and 0xFF) shl 8) or (v[3].toInt() and 0xFF)
                }
                setContactStatus(name, st)
            }
            0x0003_000C -> setContactStatus(r.str(r.u8()), OFFLINE)
            0x0004_0007 -> onMessage(r)
            0x0013_0006 -> ssiLoad(r, (flags and 1) != 0)
            0x0013_000F -> onSsiLoaded()
        }
    }

    @Synchronized
    private fun finishLogin() {
        if (finished) return
        finished = true
        send(0x13, 7)
        send(2, 2); send(3, 2); send(4, 4); send(9, 2)
        send(4, 2, W().u16(0).u32(3).u16(512).u16(999).u16(999).u32(0).done())
        send(1, 0x1E, W().tlv(6, u32b(myStatus.value.toLong())).done())
        send(1, 0x02, W().apply {
            FAMILIES.forEach { u16(it.first); u16(it.second); u16(0x0110); u16(0x047B) }
        }.done())
        val names = contacts.value
        if (names.isNotEmpty()) send(3, 4, W().apply { names.forEach { u8(it.uin.length); str(it.uin) } }.done())
        everOn = true
        error.value = null
        conn.value = Conn.ON
    }

    @Synchronized
    private fun ssiLoad(r: ByteReader, more: Boolean) {
        r.u8()
        repeat(r.u16()) {
            val name = String(r.bytes(r.u16()), Charsets.UTF_8)
            val gid = r.u16(); val id = r.u16(); val type = r.u16()
            val data = r.bytes(r.u16())
            ssi += SsiItem(name, gid, id, type, data)
        }
        if (!more) onSsiLoaded()
    }

    private fun onSsiLoaded() {
        if (finished) return
        val server = synchronized(this) { ssi.filter { it.type == 0 }.map { it.name } }
        contacts.update { cur -> cur + server.filter { s -> cur.none { it.uin == s } }.map { Contact(it) } }
        val missing = contacts.value.map { it.uin }.filter { it !in server }
        ssiAdd(missing)
        finishLogin()
    }

    /** Добавляет контакты в серверный список (создаёт группу, если её ещё нет). */
    @Synchronized
    private fun ssiAdd(unis: List<String>) {
        if (unis.isEmpty()) return
        var nextId = maxOf(0x100, ssi.maxOfOrNull { maxOf(it.id, it.gid) } ?: 0) + 1
        val root = ssi.firstOrNull { it.type == 1 && it.gid == 0 }
        val group = ssi.firstOrNull { it.type == 1 && it.gid != 0 }
        val gid = group?.gid ?: nextId++
        val buddies = unis.map { SsiItem(it, gid, nextId++, 0, ByteArray(0)) }
        val ids = (group?.members() ?: emptyList()) + buddies.map { it.id }
        val groupItem = SsiItem(group?.name ?: "Buddies", gid, 0, 1, memberData(ids))
        val adds = mutableListOf<SsiItem>()
        val updates = mutableListOf<SsiItem>()
        if (group == null) {
            adds += groupItem
            adds += buddies
            val rootItem = SsiItem("", 0, 0, 1, memberData((root?.members() ?: emptyList()) + gid))
            if (root == null) adds += rootItem else updates += rootItem
        } else {
            adds += buddies
            updates += groupItem
        }
        send(0x13, 0x11)
        send(0x13, 0x08, W().apply { adds.forEach { bytes(it.encode()) } }.done())
        if (updates.isNotEmpty()) send(0x13, 0x09, W().apply { updates.forEach { bytes(it.encode()) } }.done())
        send(0x13, 0x12)
        val changed = adds + updates
        ssi.removeAll { o -> changed.any { it.gid == o.gid && it.id == o.id && it.type == o.type } }
        ssi += changed
    }

    @Synchronized
    private fun ssiRemove(uin: String) {
        val b = ssi.firstOrNull { it.type == 0 && it.name == uin } ?: return
        val g = ssi.firstOrNull { it.type == 1 && it.gid == b.gid && it.id == 0 }
        send(0x13, 0x11)
        send(0x13, 0x0A, b.encode())
        var newGroup: SsiItem? = null
        if (g != null) {
            newGroup = SsiItem(g.name, g.gid, 0, 1, memberData(g.members().filter { it != b.id }))
            send(0x13, 0x09, newGroup.encode())
        }
        send(0x13, 0x12)
        ssi.remove(b)
        if (g != null && newGroup != null) { ssi.remove(g); ssi += newGroup }
    }

    private fun setContactStatus(name: String, st: Int) {
        contacts.update { l -> l.map { if (it.uin == name) it.copy(status = st) else it } }
    }

    private fun onMessage(r: ByteReader) {
        r.skip(8)
        val ch = r.u16(); val from = r.str(r.u8()); r.u16()
        repeat(r.u16()) { r.u16(); r.skip(r.u16()) }
        if (ch != 1) return
        while (r.left >= 4) {
            val t = r.u16(); val v = r.bytes(r.u16())
            if (t != 2) continue
            val f = ByteReader(v)
            while (f.left >= 4) {
                val id = f.u8(); f.u8(); val fd = f.bytes(f.u16())
                if (id == 1 && fd.size >= 4) {
                    val cs = ((fd[0].toInt() and 0xFF) shl 8) or (fd[1].toInt() and 0xFF)
                    val text = String(
                        fd.copyOfRange(4, fd.size),
                        when (cs) { 2 -> Charsets.UTF_16BE; 3 -> Charsets.ISO_8859_1; else -> charset("windows-1251") }
                    )
                    contacts.update { l -> if (l.any { it.uin == from }) l else l + Contact(from, ST_ONLINE) }
                    addMsg(from, Msg(false, text))
                    if (!(appVisible && openChat == from)) unread.update { it + (from to ((it[from] ?: 0) + 1)) }
                    incoming.tryEmit(from to text)
                }
            }
        }
    }

    private fun addMsg(uin: String, m: Msg) {
        chats.update { it + (uin to (it[uin].orEmpty() + m)) }
    }
}
