@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package app.voidgram

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------- helpers ----------

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dateFmt = SimpleDateFormat("dd.MM", Locale.getDefault())

private fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }
    val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

private fun shortTime(ts: Long) = if (sameDay(ts, System.currentTimeMillis())) timeFmt.format(Date(ts)) else dateFmt.format(Date(ts))

private fun lastSeenText(u: User?): String = when {
    u == null -> ""
    u.online -> "в сети"
    u.lastSeen == 0L -> "давно не был(а) в сети"
    else -> "был(а) " + shortTime(u.lastSeen)
}

private val avatarColors = listOf(0xFF7C5CFF, 0xFF3FA7FF, 0xFFFF6B9A, 0xFF35C9A0, 0xFFFFA84A, 0xFFB86BFF)

@Composable
fun Avatar(name: String, seed: Int, size: Int = 48, online: Boolean = false) {
    Box(Modifier.size(size.dp)) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(Color(avatarColors[Math.floorMod(seed, avatarColors.size)])),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.4).sp)
        }
        if (online) Box(
            Modifier.size((size * 0.28).dp).align(Alignment.BottomEnd).clip(CircleShape)
                .background(VoidBg).padding(2.dp).clip(CircleShape).background(Color(0xFF3DDC84)),
        )
    }
}

private fun chatSubtitlePreview(model: AppModel, c: Chat): String {
    val m = c.lastMessage ?: return "Нет сообщений"
    val body = when {
        m.deleted -> "Сообщение удалено"
        m.media != null && m.text.isBlank() -> if (m.media.mime.startsWith("image/")) "🖼 Фото" else "📎 ${m.media.filename}"
        else -> m.text
    }
    val prefix = when {
        m.senderId == model.me?.id -> "Вы: "
        c.isGroup -> model.userName(m.senderId) + ": "
        else -> ""
    }
    return prefix + body
}

// ---------- auth ----------

@Composable
fun AuthScreen(model: AppModel) {
    var register by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(model.serverUrl) }
    var username by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(24.dp).imePadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Voidgram", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = VoidAccent)
        Text("Приватный мессенджер", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(server, { server = it }, label = { Text("Адрес сервера") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(username, { username = it.trim() }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (register) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            password, { password = it }, label = { Text("Пароль") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        model.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (register) model.register(server, username, name.ifBlank { username }, password)
                else model.login(server, username, password)
            },
            enabled = !model.busy && username.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text(if (register) "Создать аккаунт" else "Войти") }
        TextButton(onClick = { register = !register; model.error = null }) {
            Text(if (register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Регистрация")
        }
    }
}

// ---------- chat list ----------

@Composable
fun ChatListScreen(model: AppModel) {
    Scaffold(
        containerColor = VoidBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Voidgram", fontWeight = FontWeight.Bold)
                        if (!model.connected) Text("Подключение…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton({ model.screen = Screen.NewGroup }) { Icon(Icons.Default.Add, "Новая группа") }
                    IconButton({ model.screen = Screen.Profile }) { Icon(Icons.Default.Person, "Профиль") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = VoidSurface),
            )
        },
        floatingActionButton = {
            FloatingActionButton({ model.screen = Screen.NewChat }, containerColor = VoidAccent) {
                Icon(Icons.Default.Edit, "Новый чат", tint = Color(0xFF1A1033))
            }
        },
    ) { pad ->
        if (model.chats.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("Пока нет чатов.\nНажмите ✎ и найдите человека по username.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(model.chats, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { model.openChat(c.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(c.displayTitle, c.peer?.id ?: c.id, 52, online = c.peer?.online == true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(c.displayTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            c.lastMessage?.let { Text(shortTime(it.createdAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val typingUser = model.typing[c.id]
                            Text(
                                if (typingUser != null) "печатает…" else chatSubtitlePreview(model, c),
                                color = if (typingUser != null) VoidAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            if (c.unread > 0) Badge(containerColor = VoidAccent) { Text(c.unread.toString(), color = Color(0xFF1A1033)) }
                        }
                    }
                }
            }
        }
    }
}

// ---------- dialog ----------

private val quickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")

@Composable
fun DialogScreen(model: AppModel, chatId: Int) {
    val chat = model.chatById(chatId)
    val list = model.messages[chatId] ?: emptyList()
    var text by remember(chatId) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val loader = remember { ImageLoader.Builder(context).okHttpClient(model.api.client).build() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) model.sendFile(chatId, uri)
    }

    // entering edit mode puts the message text into the input
    LaunchedEffect(model.editing?.id) { model.editing?.let { text = it.text } }
    // stick to newest message
    LaunchedEffect(list.lastOrNull()?.id) { if (list.isNotEmpty()) listState.animateScrollToItem(0) }
    // load older history when reaching the top
    val atEnd by remember { derivedStateOf { val i = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index; i != null && i >= listState.layoutInfo.totalItemsCount - 1 } }
    LaunchedEffect(atEnd, list.size) { if (atEnd && list.size >= 40) model.loadOlder(chatId) }

    Scaffold(
        containerColor = VoidBg,
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton({ model.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = {
                    Row(
                        Modifier.clickable(enabled = chat?.isGroup == true) { model.screen = Screen.Info(chatId) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(chat?.displayTitle ?: "", chat?.peer?.id ?: chatId, 38)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(chat?.displayTitle ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            val typingUser = model.typing[chatId]
                            Text(
                                when {
                                    typingUser != null -> if (chat?.isGroup == true) "${model.userName(typingUser)} печатает…" else "печатает…"
                                    chat?.isGroup == true -> "${chat?.memberIds?.size ?: 0} участн."
                                    else -> lastSeenText(chat?.peer)
                                },
                                fontSize = 12.sp, color = if (typingUser != null) VoidAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = VoidSurface),
            )
        },
        bottomBar = {
            Column(Modifier.background(VoidSurface)) {
                val reply = model.replyTo
                val edit = model.editing
                if (reply != null || edit != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(32.dp).background(VoidAccent))
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(if (edit != null) "Редактирование" else "Ответ: " + model.userName(reply!!.senderId), color = VoidAccent, fontSize = 12.sp)
                            Text((edit ?: reply)!!.text.ifBlank { "Вложение" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                        }
                        IconButton({ model.replyTo = null; model.editing = null; text = "" }) { Icon(Icons.Default.Close, "Отмена") }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                    IconButton({ picker.launch("*/*") }, enabled = !model.busy) { Icon(Icons.Default.Add, "Прикрепить") }
                    OutlinedTextField(
                        text, { text = it; if (it.isNotEmpty()) model.notifyTyping(chatId) },
                        placeholder = { Text("Сообщение") }, maxLines = 5,
                        shape = RoundedCornerShape(22.dp), modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { model.sendText(chatId, text); text = "" },
                        enabled = text.isNotBlank(),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = if (text.isNotBlank()) VoidAccent else Color.Gray) }
                }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 8.dp),
            state = listState, reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val reversed = list.asReversed()
            items(reversed, key = { it.id }) { m ->
                MessageBubble(model, chat, m, loader) { text = it }
            }
        }
    }
}

@Composable
private fun MessageBubble(model: AppModel, chat: Chat?, m: Message, loader: ImageLoader, setText: (String) -> Unit) {
    val mine = m.senderId == model.me?.id
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val isOwner = chat?.ownerId == model.me?.id

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box {
            Column(
                Modifier.widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, if (mine) 4.dp else 16.dp, if (mine) 16.dp else 4.dp))
                    .background(if (mine) VoidMine else VoidSurfaceHi)
                    .combinedClickable(onClick = {}, onLongClick = { if (!m.deleted) menu = true })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                if (chat?.isGroup == true && !mine) Text(model.userName(m.senderId), color = VoidAccent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                m.replyTo?.let { r ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Box(Modifier.width(2.dp).height(30.dp).background(VoidAccent))
                        Column(Modifier.padding(start = 6.dp)) {
                            Text(model.userName(r.senderId), color = VoidAccent, fontSize = 12.sp)
                            Text(if (r.deleted) "Сообщение удалено" else r.text.ifBlank { "Вложение" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        }
                    }
                }
                if (m.deleted) {
                    Text("Сообщение удалено", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                } else {
                    m.media?.let { md ->
                        if (md.mime.startsWith("image/")) AsyncImage(
                            model = model.api.mediaUrl(md.id), imageLoader = loader, contentDescription = md.filename,
                            modifier = Modifier.padding(top = 2.dp).widthIn(max = 260.dp).clip(RoundedCornerShape(10.dp)),
                        ) else Text("📎 ${md.filename} (${md.size / 1024} KB)", color = VoidAccent)
                    }
                    if (m.text.isNotBlank()) Text(m.text)
                }
                if (m.reactions.isNotEmpty()) Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    m.reactions.forEach { (emoji, uids) ->
                        val myId = model.me?.id
                        val byMe = myId != null && myId in uids
                        Text(
                            "$emoji ${uids.size}", fontSize = 12.sp,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .background(if (byMe) VoidAccent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f))
                                .clickable { model.react(m, emoji) }.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    if (m.editedAt != null) Text("изм. ", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(timeFmt.format(Date(m.createdAt)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (mine && !m.deleted && chat != null && !chat.isGroup) {
                        val seen = m.id <= chat.peerRead
                        Text(if (seen) " ✓✓" else " ✓", fontSize = 10.sp, color = if (seen) VoidAccent else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            DropdownMenu(menu, { menu = false }) {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    quickReactions.forEach { e ->
                        Text(e, fontSize = 22.sp, modifier = Modifier.clickable { model.react(m, e); menu = false }.padding(6.dp))
                    }
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Ответить") }, onClick = { model.replyTo = m; model.editing = null; menu = false })
                if (m.text.isNotBlank()) DropdownMenuItem(text = { Text("Копировать") }, onClick = { clipboard.setText(AnnotatedString(m.text)); menu = false })
                if (mine && m.text.isNotBlank()) DropdownMenuItem(text = { Text("Изменить") }, onClick = { model.editing = m; model.replyTo = null; setText(m.text); menu = false })
                if (mine || isOwner) DropdownMenuItem(text = { Text("Удалить", color = MaterialTheme.colorScheme.error) }, onClick = { model.delete(m); menu = false })
            }
        }
    }
}

// ---------- people picker (shared) ----------

@Composable
private fun UserSearchField(model: AppModel, onResults: (List<User>) -> Unit) {
    var q by remember { mutableStateOf("") }
    LaunchedEffect(q) {
        if (q.trim().length < 2) onResults(emptyList()) else {
            kotlinx.coroutines.delay(250)
            onResults(model.searchUsers(q.trim()))
        }
    }
    OutlinedTextField(
        q, { q = it }, singleLine = true, label = { Text("Поиск по имени или username") },
        leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(12.dp),
    )
}

@Composable
private fun UserRow(u: User, trailing: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(u.name, u.id, 44, online = u.online)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(u.name, fontWeight = FontWeight.SemiBold)
            Text("@${u.username}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) Text(trailing, color = VoidAccent)
    }
}

@Composable
private fun SimpleTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = VoidSurface),
    )
}

@Composable
fun NewChatScreen(model: AppModel) {
    var results by remember { mutableStateOf<List<User>>(emptyList()) }
    Scaffold(containerColor = VoidBg, topBar = { SimpleTopBar("Новый чат", { model.back() }) }) { pad ->
        Column(Modifier.padding(pad)) {
            UserSearchField(model) { results = it }
            LazyColumn { items(results, key = { it.id }) { u -> UserRow(u) { model.startDirect(u.id) } } }
        }
    }
}

@Composable
fun NewGroupScreen(model: AppModel) {
    var title by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<User>>(emptyList()) }
    val selected = remember { mutableStateListOf<User>() }
    Scaffold(
        containerColor = VoidBg,
        topBar = {
            SimpleTopBar("Новая группа", { model.back() }) {
                TextButton(
                    onClick = { model.createGroup(title.trim(), selected.map { it.id }) },
                    enabled = title.isNotBlank() && selected.isNotEmpty(),
                ) { Text("Создать") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("Название группы") }, modifier = Modifier.fillMaxWidth().padding(12.dp, 12.dp, 12.dp, 0.dp))
            if (selected.isNotEmpty()) Text(
                "Участники: " + selected.joinToString { it.name }, color = VoidAccent,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            UserSearchField(model) { results = it }
            LazyColumn {
                items(results, key = { it.id }) { u ->
                    val on = selected.any { it.id == u.id }
                    UserRow(u, if (on) "✓" else null) { if (on) selected.removeAll { it.id == u.id } else selected.add(u) }
                }
            }
        }
    }
}

@Composable
fun GroupInfoScreen(model: AppModel, chatId: Int) {
    val chat = model.chatById(chatId)
    Scaffold(containerColor = VoidBg, topBar = { SimpleTopBar(chat?.displayTitle ?: "Группа", { model.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item { Text("${chat?.memberIds?.size ?: 0} участников", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(chat?.memberIds ?: emptyList(), key = { it }) { id ->
                val u = model.users[id] ?: model.me?.takeIf { it.id == id }
                if (u != null) UserRow(u, if (id == chat?.ownerId) "владелец" else null) {
                    if (id != model.me?.id) model.startDirect(id)
                }
            }
            item {
                OutlinedButton(
                    onClick = { model.leaveGroup(chatId) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) { Text("Покинуть группу", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
fun ProfileScreen(model: AppModel) {
    val me = model.me
    var name by remember { mutableStateOf(me?.name ?: "") }
    var bio by remember { mutableStateOf(me?.bio ?: "") }
    Scaffold(containerColor = VoidBg, topBar = { SimpleTopBar("Профиль", { model.back() }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(name, me?.id ?: 0, 96)
            Text("@${me?.username ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Имя") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(bio, { bio = it.take(200) }, label = { Text("О себе") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button({ model.updateProfile(name.trim(), bio.trim()) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ model.logout() }, modifier = Modifier.fillMaxWidth()) { Text("Выйти", color = MaterialTheme.colorScheme.error) }
            Text("Сервер: ${model.serverUrl}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
        }
    }
}
