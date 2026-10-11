package app.voidgram

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: Int,
    val username: String,
    val name: String,
    val bio: String = "",
    val avatar: String? = null,
    val lastSeen: Long = 0,
    val online: Boolean = false,
)

@Serializable
data class Media(val id: String, val filename: String, val mime: String, val size: Long)

@Serializable
data class ReplyRef(val id: Int, val senderId: Int, val text: String = "", val deleted: Boolean = false)

@Serializable
data class Message(
    val id: Int,
    val chatId: Int,
    val senderId: Int,
    val text: String = "",
    val replyTo: ReplyRef? = null,
    val media: Media? = null,
    val editedAt: Long? = null,
    val deleted: Boolean = false,
    val createdAt: Long,
    val reactions: Map<String, List<Int>> = emptyMap(),
)

@Serializable
data class Chat(
    val id: Int,
    val type: String,
    val title: String? = null,
    val peer: User? = null,
    val peerRead: Int = 0,
    val ownerId: Int? = null,
    val memberIds: List<Int> = emptyList(),
    val lastMessage: Message? = null,
    val unread: Int = 0,
    val createdAt: Long = 0,
) {
    val isGroup get() = type == "group"
    val displayTitle get() = title ?: "Chat"
}

@Serializable data class AuthResponse(val token: String, val user: User)
@Serializable data class UserResponse(val user: User)
@Serializable data class UsersResponse(val users: List<User>)
@Serializable data class ChatsResponse(val chats: List<Chat>)
@Serializable data class ChatResponse(val chat: Chat)
@Serializable data class ChatDetailResponse(val chat: Chat, val members: List<User>)
@Serializable data class MessagesResponse(val messages: List<Message>)
@Serializable data class MessageResponse(val message: Message)
@Serializable data class MediaResponse(val media: Media)

sealed interface Screen {
    data object Chats : Screen
    data class Dialog(val chatId: Int) : Screen
    data object NewChat : Screen
    data object NewGroup : Screen
    data object Profile : Screen
    data class Info(val chatId: Int) : Screen
}
