package app.voidgram
import kotlinx.serialization.json.decodeFromJsonElement

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.WebSocket

class AppModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("voidgram", 0)
    val api = Api()

    var serverUrl by mutableStateOf(prefs.getString("server", "http://10.0.2.2:8080")!!)
    var me by mutableStateOf<User?>(null)
    var loggedIn by mutableStateOf(false)
    var booting by mutableStateOf(true)
    var screen by mutableStateOf<Screen>(Screen.Chats)
    var chats by mutableStateOf<List<Chat>>(emptyList())
    val messages = mutableStateMapOf<Int, List<Message>>()
    val users = mutableStateMapOf<Int, User>()
    val typing = mutableStateMapOf<Int, Int>() // chatId -> userId currently typing
    var replyTo by mutableStateOf<Message?>(null)
    var editing by mutableStateOf<Message?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var connected by mutableStateOf(false)

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private val typingJobs = mutableMapOf<Int, Job>()
    private var lastTypingSent = 0L

    init {
        val saved = prefs.getString("token", null)
        if (saved != null) {
            api.baseUrl = serverUrl
            api.token = saved
            viewModelScope.launch {
                try {
                    me = api.get<UserResponse>("/api/me").user
                    loggedIn = true
                    afterLogin()
                } catch (e: ApiException) {
                    if (e.status == 401) clearSession() else { loggedIn = true; error = e.message; afterLogin() }
                }
                booting = false
            }
        } else booting = false
    }

    // ---------- auth ----------
    fun login(server: String, username: String, password: String) = auth(server, "/api/login", buildJsonObject {
        put("username", username); put("password", password)
    })

    fun register(server: String, username: String, name: String, password: String) = auth(server, "/api/register", buildJsonObject {
        put("username", username); put("name", name); put("password", password)
    })

    private fun auth(server: String, path: String, body: JsonObject) {
        viewModelScope.launch {
            busy = true; error = null
            try {
                serverUrl = server.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
                api.baseUrl = serverUrl
                api.token = null
                val r = api.send<AuthResponse>("POST", path, body)
                api.token = r.token
                prefs.edit().putString("server", serverUrl).putString("token", r.token).apply()
                me = r.user
                loggedIn = true
                afterLogin()
            } catch (e: ApiException) { error = e.message }
            busy = false
        }
    }

    fun logout() {
        viewModelScope.launch { runCatching { api.call("POST", "/api/logout", JsonObject(emptyMap())) } }
        clearSession()
    }

    private fun clearSession() {
        socket?.close(1000, null); socket = null
        reconnectJob?.cancel()
        prefs.edit().remove("token").apply()
        api.token = null
        me = null; loggedIn = false; chats = emptyList(); messages.clear(); users.clear()
        screen = Screen.Chats
    }

    private fun afterLogin() {
        refreshChats()
        connectSocket()
    }

    // ---------- realtime ----------
    private fun connectSocket() {
        socket?.cancel()
        socket = api.openSocket(
            onEvent = { ev -> viewModelScope.launch { handleEvent(ev) } },
            onClosed = {
                viewModelScope.launch {
                    connected = false
                    if (loggedIn) {
                        reconnectJob?.cancel()
                        reconnectJob = launch { delay(3000); if (loggedIn) { refreshChats(); connectSocket() } }
                    }
                }
            },
        )
    }

    private fun handleEvent(ev: JsonObject) {
        when (ev["type"]?.jsonPrimitive?.contentOrNull) {
            "hello" -> connected = true
            "message.new" -> onMessage(AppJson.decodeFromJsonElement(ev["message"]!!), isNew = true)
            "message.update" -> onMessage(AppJson.decodeFromJsonElement(ev["message"]!!), isNew = false)
            "chat.new", "chat.members" -> refreshChats()
            "chat.read" -> {
                val cid = ev["chatId"]?.jsonPrimitive?.intOrNull ?: return
                val upTo = ev["upTo"]?.jsonPrimitive?.intOrNull ?: return
                chats = chats.map { if (it.id == cid && it.peer != null) it.copy(peerRead = maxOf(it.peerRead, upTo)) else it }
            }
            "typing" -> {
                val cid = ev["chatId"]?.jsonPrimitive?.intOrNull ?: return
                val uid = ev["userId"]?.jsonPrimitive?.intOrNull ?: return
                typing[cid] = uid
                typingJobs[cid]?.cancel()
                typingJobs[cid] = viewModelScope.launch { delay(4000); typing.remove(cid) }
            }
            "presence" -> {
                val uid = ev["userId"]?.jsonPrimitive?.intOrNull ?: return
                val on = ev["online"]?.jsonPrimitive?.booleanOrNull ?: false
                val seen = ev["lastSeen"]?.jsonPrimitive?.longOrNull ?: 0
                users[uid]?.let { users[uid] = it.copy(online = on, lastSeen = seen) }
                chats = chats.map { c ->
                    val p = c.peer
                    if (p != null && p.id == uid) c.copy(peer = p.copy(online = on, lastSeen = seen)) else c
                }
            }
        }
    }

    private fun onMessage(m: Message, isNew: Boolean) {
        val open = (screen as? Screen.Dialog)?.chatId == m.chatId
        messages[m.chatId]?.let { list ->
            messages[m.chatId] = if (list.any { it.id == m.id }) list.map { if (it.id == m.id) m else it } else list + m
        }
        val known = chats.any { it.id == m.chatId }
        if (!known) { refreshChats(); return }
        chats = chats.map { c ->
            if (c.id != m.chatId) c else {
                val isLast = c.lastMessage == null || m.id >= c.lastMessage.id
                val unread = if (isNew && m.senderId != me?.id && !open) c.unread + 1 else c.unread
                c.copy(lastMessage = if (isLast) m else c.lastMessage, unread = unread)
            }
        }.sortedByDescending { it.lastMessage?.createdAt ?: it.createdAt }
        if (isNew && open && m.senderId != me?.id) markRead(m.chatId)
    }

    // ---------- chats ----------
    fun refreshChats() {
        viewModelScope.launch {
            try {
                val list = api.get<ChatsResponse>("/api/chats").chats
                chats = list
                list.forEach { c -> c.peer?.let { users[it.id] = it } }
            } catch (e: ApiException) { if (e.status == 401) clearSession() }
        }
    }

    fun chatById(id: Int) = chats.firstOrNull { it.id == id }

    fun openChat(chatId: Int) {
        screen = Screen.Dialog(chatId)
        replyTo = null; editing = null
        viewModelScope.launch {
            try {
                val chat = api.get<ChatDetailResponse>("/api/chats/$chatId")
                chat.members.forEach { users[it.id] = it }
                if (chats.none { it.id == chatId }) chats = listOf(chat.chat) + chats
                messages[chatId] = api.get<MessagesResponse>("/api/chats/$chatId/messages?limit=60").messages
                markRead(chatId)
            } catch (e: ApiException) { error = e.message }
        }
    }

    fun loadOlder(chatId: Int) {
        val first = messages[chatId]?.firstOrNull() ?: return
        viewModelScope.launch {
            try {
                val older = api.get<MessagesResponse>("/api/chats/$chatId/messages?before=${first.id}&limit=50").messages
                if (older.isNotEmpty()) messages[chatId] = older + (messages[chatId] ?: emptyList())
            } catch (_: ApiException) {}
        }
    }

    fun markRead(chatId: Int) {
        chats = chats.map { if (it.id == chatId) it.copy(unread = 0) else it }
        viewModelScope.launch { runCatching { api.call("POST", "/api/chats/$chatId/read", JsonObject(emptyMap())) } }
    }

    fun sendText(chatId: Int, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val edit = editing
        if (edit != null) {
            editing = null
            viewModelScope.launch {
                try { onMessage(api.send<MessageResponse>("PATCH", "/api/messages/${edit.id}", buildJsonObject { put("text", t) }).message, false) }
                catch (e: ApiException) { error = e.message }
            }
            return
        }
        val reply = replyTo
        replyTo = null
        viewModelScope.launch {
            try {
                val r = api.send<MessageResponse>("POST", "/api/chats/$chatId/messages", buildJsonObject {
                    put("text", t)
                    if (reply != null) put("replyTo", reply.id)
                })
                onMessage(r.message, isNew = false)
            } catch (e: ApiException) { error = e.message }
        }
    }

    fun sendFile(chatId: Int, uri: Uri) {
        viewModelScope.launch {
            busy = true
            try {
                val cr = app.contentResolver
                val mime = cr.getType(uri) ?: "application/octet-stream"
                var name = "file"
                cr.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) name = c.getString(i)
                }
                val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw ApiException(0, "Не удалось прочитать файл")
                val media = api.upload(bytes, mime, name)
                val r = api.send<MessageResponse>("POST", "/api/chats/$chatId/messages", buildJsonObject {
                    put("text", ""); put("mediaId", media.id)
                })
                onMessage(r.message, isNew = false)
            } catch (e: ApiException) { error = e.message }
            busy = false
        }
    }

    fun notifyTyping(chatId: Int) {
        val now = System.currentTimeMillis()
        if (now - lastTypingSent < 3000) return
        lastTypingSent = now
        viewModelScope.launch { runCatching { api.call("POST", "/api/chats/$chatId/typing", JsonObject(emptyMap())) } }
    }

    fun react(m: Message, emoji: String) {
        val myId = me?.id
        val mine = m.reactions.entries.firstOrNull { myId != null && myId in it.value }?.key
        val send = if (mine == emoji) "" else emoji
        viewModelScope.launch {
            try { onMessage(api.send<MessageResponse>("PUT", "/api/messages/${m.id}/reaction", buildJsonObject { put("emoji", send) }).message, false) }
            catch (e: ApiException) { error = e.message }
        }
    }

    fun delete(m: Message) {
        viewModelScope.launch {
            try { api.call("DELETE", "/api/messages/${m.id}") } catch (e: ApiException) { error = e.message }
        }
    }

    // ---------- people ----------
    suspend fun searchUsers(q: String): List<User> =
        try {
            api.get<UsersResponse>("/api/users/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")).users
                .also { l -> l.forEach { users[it.id] = it } }
        } catch (e: ApiException) { error = e.message; emptyList() }

    fun startDirect(userId: Int) {
        viewModelScope.launch {
            try {
                val chat = api.send<ChatResponse>("POST", "/api/chats/direct", buildJsonObject { put("userId", userId) }).chat
                if (chats.none { it.id == chat.id }) chats = listOf(chat) + chats
                chat.peer?.let { users[it.id] = it }
                openChat(chat.id)
            } catch (e: ApiException) { error = e.message }
        }
    }

    fun createGroup(title: String, members: List<Int>) {
        viewModelScope.launch {
            try {
                val chat = api.send<ChatResponse>("POST", "/api/chats/group", buildJsonObject {
                    put("title", title)
                    put("memberIds", buildJsonArray { members.forEach { add(it) } })
                }).chat
                chats = listOf(chat) + chats
                openChat(chat.id)
            } catch (e: ApiException) { error = e.message }
        }
    }

    fun leaveGroup(chatId: Int) {
        val uid = me?.id ?: return
        viewModelScope.launch {
            try {
                api.call("DELETE", "/api/chats/$chatId/members/$uid")
                chats = chats.filterNot { it.id == chatId }
                messages.remove(chatId)
                screen = Screen.Chats
            } catch (e: ApiException) { error = e.message }
        }
    }

    fun updateProfile(name: String, bio: String) {
        viewModelScope.launch {
            try {
                me = api.send<UserResponse>("PATCH", "/api/me", buildJsonObject { put("name", name); put("bio", bio) }).user
            } catch (e: ApiException) { error = e.message }
        }
    }

    fun back() {
        screen = when (val s = screen) {
            is Screen.Info -> Screen.Dialog(s.chatId)
            else -> Screen.Chats
        }
        replyTo = null; editing = null
    }

    fun userName(id: Int): String = if (id == me?.id) "Вы" else users[id]?.name ?: "…"
}
