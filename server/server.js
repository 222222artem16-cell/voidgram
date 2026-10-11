// Voidgram server: REST + WebSocket messenger backend.
// Requires Node >= 22.5 (built-in node:sqlite).
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { WebSocketServer } from 'ws';

const PORT = Number(process.env.PORT || 8080);
const DATA_DIR = process.env.DATA_DIR || path.join(process.cwd(), 'data');
const MAX_UPLOAD = 50 * 1024 * 1024;
fs.mkdirSync(path.join(DATA_DIR, 'media'), { recursive: true });

const db = new DatabaseSync(path.join(DATA_DIR, 'voidgram.db'));
db.exec(`
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS users(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT UNIQUE NOT NULL COLLATE NOCASE,
  name TEXT NOT NULL,
  bio TEXT NOT NULL DEFAULT '',
  pass_hash TEXT NOT NULL,
  avatar_media TEXT,
  last_seen INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions(
  token TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS chats(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  type TEXT NOT NULL CHECK(type IN ('direct','group')),
  title TEXT,
  direct_key TEXT UNIQUE,
  owner_id INTEGER,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS members(
  chat_id INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role TEXT NOT NULL DEFAULT 'member',
  last_read_id INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(chat_id, user_id)
);
CREATE TABLE IF NOT EXISTS messages(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  chat_id INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
  sender_id INTEGER NOT NULL REFERENCES users(id),
  text TEXT NOT NULL DEFAULT '',
  reply_to INTEGER,
  media_id TEXT,
  edited_at INTEGER,
  deleted INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_chat ON messages(chat_id, id);
CREATE TABLE IF NOT EXISTS reactions(
  message_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL,
  emoji TEXT NOT NULL,
  PRIMARY KEY(message_id, user_id)
);
CREATE TABLE IF NOT EXISTS media(
  id TEXT PRIMARY KEY,
  owner_id INTEGER NOT NULL,
  filename TEXT NOT NULL,
  mime TEXT NOT NULL,
  size INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
`);

const now = () => Date.now();
const q = (sql) => db.prepare(sql);
const tx = (fn) => {
  db.exec('BEGIN');
  try { const r = fn(); db.exec('COMMIT'); return r; }
  catch (e) { db.exec('ROLLBACK'); throw e; }
};

// ---------- helpers ----------
class HttpError extends Error {
  constructor(status, msg) { super(msg); this.status = status; }
}
const bad = (m) => new HttpError(400, m);

function hashPassword(pw) {
  const salt = crypto.randomBytes(16);
  const h = crypto.scryptSync(pw, salt, 32);
  return salt.toString('hex') + ':' + h.toString('hex');
}
function checkPassword(pw, stored) {
  const [s, h] = stored.split(':');
  const calc = crypto.scryptSync(pw, Buffer.from(s, 'hex'), 32);
  return crypto.timingSafeEqual(calc, Buffer.from(h, 'hex'));
}

const publicUser = (u) => u && ({
  id: u.id, username: u.username, name: u.name, bio: u.bio,
  avatar: u.avatar_media, lastSeen: u.last_seen,
  online: online.has(u.id),
});

// ---------- realtime ----------
const online = new Map(); // userId -> Set<ws>
function sendTo(userId, event) {
  const set = online.get(userId);
  if (!set) return;
  const data = JSON.stringify(event);
  for (const ws of set) if (ws.readyState === 1) ws.send(data);
}
const chatMemberIds = (chatId) =>
  q('SELECT user_id FROM members WHERE chat_id=?').all(chatId).map((r) => r.user_id);
function broadcastChat(chatId, event, exceptUser) {
  for (const uid of chatMemberIds(chatId)) if (uid !== exceptUser) sendTo(uid, event);
}

// ---------- data shaping ----------
function reactionsFor(messageId) {
  const rows = q('SELECT emoji, user_id FROM reactions WHERE message_id=?').all(messageId);
  const out = {};
  for (const r of rows) (out[r.emoji] ||= []).push(r.user_id);
  return out;
}
function shapeMessage(m) {
  if (!m) return null;
  let media = null;
  if (m.media_id && !m.deleted) {
    const md = q('SELECT id, filename, mime, size FROM media WHERE id=?').get(m.media_id);
    if (md) media = md;
  }
  let reply = null;
  if (m.reply_to) {
    const r = q('SELECT id, sender_id, text, deleted FROM messages WHERE id=?').get(m.reply_to);
    if (r) reply = { id: r.id, senderId: r.sender_id, text: r.deleted ? '' : r.text.slice(0, 120), deleted: !!r.deleted };
  }
  return {
    id: m.id, chatId: m.chat_id, senderId: m.sender_id,
    text: m.deleted ? '' : m.text, replyTo: reply, media,
    editedAt: m.edited_at, deleted: !!m.deleted, createdAt: m.created_at,
    reactions: m.deleted ? {} : reactionsFor(m.id),
  };
}
function shapeChat(chatId, viewerId) {
  const c = q('SELECT * FROM chats WHERE id=?').get(chatId);
  if (!c) return null;
  const me = q('SELECT * FROM members WHERE chat_id=? AND user_id=?').get(chatId, viewerId);
  const memberIds = chatMemberIds(chatId);
  let title = c.title, peer = null, peerRead = 0;
  if (c.type === 'direct') {
    const otherId = memberIds.find((id) => id !== viewerId) ?? viewerId;
    peer = publicUser(q('SELECT * FROM users WHERE id=?').get(otherId));
    title = peer.name;
    peerRead = q('SELECT last_read_id FROM members WHERE chat_id=? AND user_id=?').get(chatId, otherId)?.last_read_id ?? 0;
  }
  const last = q('SELECT * FROM messages WHERE chat_id=? ORDER BY id DESC LIMIT 1').get(chatId);
  const unread = me
    ? q('SELECT COUNT(*) n FROM messages WHERE chat_id=? AND id>? AND sender_id!=? AND deleted=0')
        .get(chatId, me.last_read_id, viewerId).n
    : 0;
  return {
    id: c.id, type: c.type, title, peer, peerRead, ownerId: c.owner_id,
    memberIds, lastMessage: shapeMessage(last), unread, createdAt: c.created_at,
  };
}
function requireMember(chatId, userId) {
  const m = q('SELECT * FROM members WHERE chat_id=? AND user_id=?').get(chatId, userId);
  if (!m) throw new HttpError(403, 'not a member');
  return m;
}

// ---------- routes ----------
const routes = [];
const route = (method, pattern, handler, { auth = true } = {}) => {
  const keys = [];
  const re = new RegExp('^' + pattern.replace(/:(\w+)/g, (_, k) => (keys.push(k), '([^/]+)')) + '$');
  routes.push({ method, re, keys, handler, auth });
};

function newSession(userId) {
  const token = crypto.randomBytes(32).toString('hex');
  q('INSERT INTO sessions(token,user_id,created_at) VALUES(?,?,?)').run(token, userId, now());
  return token;
}

route('POST', '/api/register', ({ body }) => {
  const username = String(body.username || '').trim();
  const name = String(body.name || '').trim();
  const password = String(body.password || '');
  if (!/^[A-Za-z0-9_]{3,32}$/.test(username)) throw bad('username: 3-32 chars, letters, digits, _');
  if (!name || name.length > 64) throw bad('name required (max 64)');
  if (password.length < 6) throw bad('password: min 6 chars');
  if (q('SELECT 1 FROM users WHERE username=?').get(username)) throw new HttpError(409, 'username taken');
  const r = q('INSERT INTO users(username,name,pass_hash,created_at) VALUES(?,?,?,?)')
    .run(username, name, hashPassword(password), now());
  const id = Number(r.lastInsertRowid);
  return { token: newSession(id), user: publicUser(q('SELECT * FROM users WHERE id=?').get(id)) };
}, { auth: false });

route('POST', '/api/login', ({ body }) => {
  const u = q('SELECT * FROM users WHERE username=?').get(String(body.username || '').trim());
  if (!u || !checkPassword(String(body.password || ''), u.pass_hash)) throw new HttpError(401, 'wrong username or password');
  return { token: newSession(u.id), user: publicUser(u) };
}, { auth: false });

route('POST', '/api/logout', ({ token }) => {
  q('DELETE FROM sessions WHERE token=?').run(token);
  return { ok: true };
});

route('GET', '/api/me', ({ user }) => ({ user: publicUser(user) }));

route('PATCH', '/api/me', ({ user, body }) => {
  const name = body.name !== undefined ? String(body.name).trim() : user.name;
  const bio = body.bio !== undefined ? String(body.bio).slice(0, 200) : user.bio;
  const avatar = body.avatar !== undefined ? body.avatar : user.avatar_media;
  if (!name || name.length > 64) throw bad('name required (max 64)');
  if (avatar && !q('SELECT 1 FROM media WHERE id=? AND owner_id=?').get(avatar, user.id)) throw bad('bad avatar');
  q('UPDATE users SET name=?, bio=?, avatar_media=? WHERE id=?').run(name, bio, avatar ?? null, user.id);
  return { user: publicUser(q('SELECT * FROM users WHERE id=?').get(user.id)) };
});

route('GET', '/api/users/search', ({ user, query }) => {
  const term = String(query.get('q') || '').trim().replace(/[%_]/g, '');
  if (term.length < 2) return { users: [] };
  const rows = q(`SELECT * FROM users WHERE id!=? AND (username LIKE ? OR name LIKE ?) ORDER BY username LIMIT 30`)
    .all(user.id, term + '%', '%' + term + '%');
  return { users: rows.map(publicUser) };
});

route('GET', '/api/users/:id', ({ params }) => {
  const u = q('SELECT * FROM users WHERE id=?').get(Number(params.id));
  if (!u) throw new HttpError(404, 'user not found');
  return { user: publicUser(u) };
});

route('GET', '/api/chats', ({ user }) => {
  const ids = q('SELECT chat_id FROM members WHERE user_id=?').all(user.id).map((r) => r.chat_id);
  const chats = ids.map((id) => shapeChat(id, user.id));
  chats.sort((a, b) => (b.lastMessage?.createdAt ?? b.createdAt) - (a.lastMessage?.createdAt ?? a.createdAt));
  return { chats };
});

route('POST', '/api/chats/direct', ({ user, body }) => {
  const otherId = Number(body.userId);
  if (!q('SELECT 1 FROM users WHERE id=?').get(otherId)) throw new HttpError(404, 'user not found');
  const key = [user.id, otherId].sort((a, b) => a - b).join(':');
  let chat = q('SELECT * FROM chats WHERE direct_key=?').get(key);
  let created = false;
  if (!chat) {
    const id = tx(() => {
      const r = q("INSERT INTO chats(type,direct_key,created_at) VALUES('direct',?,?)").run(key, now());
      const cid = Number(r.lastInsertRowid);
      q('INSERT INTO members(chat_id,user_id) VALUES(?,?)').run(cid, user.id);
      if (otherId !== user.id) q('INSERT INTO members(chat_id,user_id) VALUES(?,?)').run(cid, otherId);
      return cid;
    });
    chat = { id }; created = true;
  }
  if (created && otherId !== user.id) sendTo(otherId, { type: 'chat.new', chat: shapeChat(chat.id, otherId) });
  return { chat: shapeChat(chat.id, user.id) };
});

route('POST', '/api/chats/group', ({ user, body }) => {
  const title = String(body.title || '').trim();
  if (!title || title.length > 64) throw bad('title required (max 64)');
  const memberIds = [...new Set([user.id, ...(Array.isArray(body.memberIds) ? body.memberIds.map(Number) : [])])];
  for (const id of memberIds) if (!q('SELECT 1 FROM users WHERE id=?').get(id)) throw bad('unknown user ' + id);
  const id = tx(() => {
    const r = q("INSERT INTO chats(type,title,owner_id,created_at) VALUES('group',?,?,?)").run(title, user.id, now());
    const cid = Number(r.lastInsertRowid);
    for (const uid of memberIds)
      q('INSERT INTO members(chat_id,user_id,role) VALUES(?,?,?)').run(cid, uid, uid === user.id ? 'owner' : 'member');
    return cid;
  });
  for (const uid of memberIds) if (uid !== user.id) sendTo(uid, { type: 'chat.new', chat: shapeChat(id, uid) });
  return { chat: shapeChat(id, user.id) };
});

route('GET', '/api/chats/:id', ({ user, params }) => {
  const cid = Number(params.id);
  requireMember(cid, user.id);
  const chat = shapeChat(cid, user.id);
  const members = chat.memberIds.map((id) => publicUser(q('SELECT * FROM users WHERE id=?').get(id)));
  return { chat, members };
});

route('POST', '/api/chats/:id/members', ({ user, params, body }) => {
  const cid = Number(params.id);
  const chat = q('SELECT * FROM chats WHERE id=?').get(cid);
  const me = requireMember(cid, user.id);
  if (!chat || chat.type !== 'group') throw bad('not a group');
  if (me.role === 'member' && chat.owner_id !== user.id) { /* any member may add, like small groups */ }
  const uid = Number(body.userId);
  if (!q('SELECT 1 FROM users WHERE id=?').get(uid)) throw new HttpError(404, 'user not found');
  q('INSERT OR IGNORE INTO members(chat_id,user_id) VALUES(?,?)').run(cid, uid);
  sendTo(uid, { type: 'chat.new', chat: shapeChat(cid, uid) });
  broadcastChat(cid, { type: 'chat.members', chatId: cid, memberIds: chatMemberIds(cid) });
  return { ok: true };
});

route('DELETE', '/api/chats/:id/members/:uid', ({ user, params }) => {
  const cid = Number(params.id), uid = Number(params.uid);
  const chat = q('SELECT * FROM chats WHERE id=?').get(cid);
  requireMember(cid, user.id);
  if (!chat || chat.type !== 'group') throw bad('not a group');
  if (uid !== user.id && chat.owner_id !== user.id) throw new HttpError(403, 'only owner can remove others');
  const targets = chatMemberIds(cid);
  q('DELETE FROM members WHERE chat_id=? AND user_id=?').run(cid, uid);
  for (const t of targets) sendTo(t, { type: 'chat.members', chatId: cid, memberIds: chatMemberIds(cid) });
  return { ok: true };
});

route('GET', '/api/chats/:id/messages', ({ user, params, query }) => {
  const cid = Number(params.id);
  requireMember(cid, user.id);
  const before = Number(query.get('before') || 0) || Number.MAX_SAFE_INTEGER;
  const limit = Math.min(Number(query.get('limit') || 50), 100);
  const rows = q('SELECT * FROM messages WHERE chat_id=? AND id<? ORDER BY id DESC LIMIT ?').all(cid, before, limit);
  return { messages: rows.reverse().map(shapeMessage) };
});

route('GET', '/api/chats/:id/search', ({ user, params, query }) => {
  const cid = Number(params.id);
  requireMember(cid, user.id);
  const term = String(query.get('q') || '').trim().replace(/[%_]/g, '');
  if (!term) return { messages: [] };
  const rows = q('SELECT * FROM messages WHERE chat_id=? AND deleted=0 AND text LIKE ? ORDER BY id DESC LIMIT 50')
    .all(cid, '%' + term + '%');
  return { messages: rows.map(shapeMessage) };
});

route('POST', '/api/chats/:id/messages', ({ user, params, body }) => {
  const cid = Number(params.id);
  requireMember(cid, user.id);
  const text = String(body.text || '').slice(0, 4096);
  const mediaId = body.mediaId || null;
  if (!text.trim() && !mediaId) throw bad('empty message');
  if (mediaId && !q('SELECT 1 FROM media WHERE id=? AND owner_id=?').get(mediaId, user.id)) throw bad('bad media');
  let replyTo = null;
  if (body.replyTo) {
    const r = q('SELECT id FROM messages WHERE id=? AND chat_id=?').get(Number(body.replyTo), cid);
    if (!r) throw bad('reply target not in this chat');
    replyTo = r.id;
  }
  const res = q('INSERT INTO messages(chat_id,sender_id,text,reply_to,media_id,created_at) VALUES(?,?,?,?,?,?)')
    .run(cid, user.id, text, replyTo, mediaId, now());
  const id = Number(res.lastInsertRowid);
  q('UPDATE members SET last_read_id=? WHERE chat_id=? AND user_id=?').run(id, cid, user.id);
  const msg = shapeMessage(q('SELECT * FROM messages WHERE id=?').get(id));
  broadcastChat(cid, { type: 'message.new', message: msg });
  sendTo(user.id, { type: 'message.new', message: msg, echo: body.clientId || null });
  return { message: msg };
});

route('PATCH', '/api/messages/:id', ({ user, params, body }) => {
  const m = q('SELECT * FROM messages WHERE id=?').get(Number(params.id));
  if (!m || m.sender_id !== user.id || m.deleted) throw new HttpError(404, 'message not found');
  const text = String(body.text || '').slice(0, 4096);
  if (!text.trim()) throw bad('empty text');
  q('UPDATE messages SET text=?, edited_at=? WHERE id=?').run(text, now(), m.id);
  const msg = shapeMessage(q('SELECT * FROM messages WHERE id=?').get(m.id));
  broadcastChat(m.chat_id, { type: 'message.update', message: msg });
  return { message: msg };
});

route('DELETE', '/api/messages/:id', ({ user, params }) => {
  const m = q('SELECT * FROM messages WHERE id=?').get(Number(params.id));
  if (!m) throw new HttpError(404, 'message not found');
  const chat = q('SELECT * FROM chats WHERE id=?').get(m.chat_id);
  requireMember(m.chat_id, user.id);
  if (m.sender_id !== user.id && chat.owner_id !== user.id) throw new HttpError(403, 'forbidden');
  q('UPDATE messages SET deleted=1, text=\'\' WHERE id=?').run(m.id);
  broadcastChat(m.chat_id, { type: 'message.update', message: shapeMessage(q('SELECT * FROM messages WHERE id=?').get(m.id)) });
  return { ok: true };
});

route('PUT', '/api/messages/:id/reaction', ({ user, params, body }) => {
  const m = q('SELECT * FROM messages WHERE id=? AND deleted=0').get(Number(params.id));
  if (!m) throw new HttpError(404, 'message not found');
  requireMember(m.chat_id, user.id);
  const emoji = String(body.emoji || '');
  if (!emoji) q('DELETE FROM reactions WHERE message_id=? AND user_id=?').run(m.id, user.id);
  else {
    if ([...emoji].length > 4) throw bad('bad emoji');
    q('INSERT INTO reactions(message_id,user_id,emoji) VALUES(?,?,?) ON CONFLICT(message_id,user_id) DO UPDATE SET emoji=excluded.emoji')
      .run(m.id, user.id, emoji);
  }
  const msg = shapeMessage(m);
  broadcastChat(m.chat_id, { type: 'message.update', message: msg });
  return { message: msg };
});

route('POST', '/api/chats/:id/read', ({ user, params, body }) => {
  const cid = Number(params.id);
  requireMember(cid, user.id);
  const upTo = Number(body.upTo) || q('SELECT COALESCE(MAX(id),0) m FROM messages WHERE chat_id=?').get(cid).m;
  q('UPDATE members SET last_read_id=MAX(last_read_id,?) WHERE chat_id=? AND user_id=?').run(upTo, cid, user.id);
  broadcastChat(cid, { type: 'chat.read', chatId: cid, userId: user.id, upTo }, user.id);
  return { ok: true };
});

route('POST', '/api/chats/:id/typing', ({ user, params }) => {
  const cid = Number(params.id);
  requireMember(cid, user.id);
  broadcastChat(cid, { type: 'typing', chatId: cid, userId: user.id }, user.id);
  return { ok: true };
});

// upload: raw body, headers X-Filename and Content-Type
route('POST', '/api/upload', async ({ user, req }) => {
  const filename = path.basename(decodeURIComponent(String(req.headers['x-filename'] || 'file')));
  const mime = String(req.headers['content-type'] || 'application/octet-stream').split(';')[0];
  const id = crypto.randomBytes(16).toString('hex');
  const file = path.join(DATA_DIR, 'media', id);
  let size = 0;
  const out = fs.createWriteStream(file);
  await new Promise((resolve, reject) => {
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_UPLOAD) { req.destroy(); out.destroy(); fs.rmSync(file, { force: true }); reject(new HttpError(413, 'file too large')); return; }
      out.write(chunk);
    });
    req.on('end', () => out.end(resolve));
    req.on('error', reject);
  });
  if (size === 0) { fs.rmSync(file, { force: true }); throw bad('empty file'); }
  q('INSERT INTO media(id,owner_id,filename,mime,size,created_at) VALUES(?,?,?,?,?,?)').run(id, user.id, filename, mime, size, now());
  return { media: { id, filename, mime, size } };
}, { raw: true });

// ---------- HTTP plumbing ----------
function authUser(token) {
  if (!token) return null;
  const s = q('SELECT user_id FROM sessions WHERE token=?').get(token);
  return s ? q('SELECT * FROM users WHERE id=?').get(s.user_id) : null;
}
const bearer = (req, url) => {
  const h = req.headers.authorization || '';
  return h.startsWith('Bearer ') ? h.slice(7) : url.searchParams.get('token');
};
async function readJson(req) {
  const chunks = []; let n = 0;
  for await (const c of req) { n += c.length; if (n > 1e6) throw new HttpError(413, 'body too large'); chunks.push(c); }
  if (!n) return {};
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw bad('invalid json'); }
}
const send = (res, status, obj) => {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
};

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  try {
    if (req.method === 'GET' && url.pathname === '/health') return send(res, 200, { ok: true, name: 'voidgram' });

    const mm = url.pathname.match(/^\/media\/([a-f0-9]{32})$/);
    if (req.method === 'GET' && mm) {
      const user = authUser(bearer(req, url));
      if (!user) throw new HttpError(401, 'unauthorized');
      const md = q('SELECT * FROM media WHERE id=?').get(mm[1]);
      if (!md) throw new HttpError(404, 'not found');
      res.writeHead(200, {
        'Content-Type': md.mime, 'Content-Length': md.size,
        'Content-Disposition': `inline; filename*=UTF-8''${encodeURIComponent(md.filename)}`,
        'X-Content-Type-Options': 'nosniff',
      });
      return fs.createReadStream(path.join(DATA_DIR, 'media', md.id)).pipe(res);
    }

    for (const r of routes) {
      if (r.method !== req.method) continue;
      const m = url.pathname.match(r.re);
      if (!m) continue;
      const params = Object.fromEntries(r.keys.map((k, i) => [k, decodeURIComponent(m[i + 1])]));
      const token = bearer(req, url);
      let user = null;
      if (r.auth) {
        user = authUser(token);
        if (!user) throw new HttpError(401, 'unauthorized');
      }
      const body = ['POST', 'PUT', 'PATCH'].includes(req.method) && url.pathname !== '/api/upload' ? await readJson(req) : {};
      const result = await r.handler({ req, res, user, token, params, query: url.searchParams, body });
      return send(res, 200, result);
    }
    throw new HttpError(404, 'not found');
  } catch (e) {
    if (e instanceof HttpError) return send(res, e.status, { error: e.message });
    console.error(e);
    send(res, 500, { error: 'internal error' });
  }
});

const wss = new WebSocketServer({ noServer: true });
server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://x');
  const user = url.pathname === '/ws' ? authUser(bearer(req, url)) : null;
  if (!user) { socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n'); return socket.destroy(); }
  wss.handleUpgrade(req, socket, head, (ws) => {
    ws.userId = user.id;
    ws.isAlive = true;
    ws.on('pong', () => (ws.isAlive = true));
    const set = online.get(user.id) || new Set();
    const wasOffline = set.size === 0;
    set.add(ws); online.set(user.id, set);
    if (wasOffline) presence(user.id, true);
    ws.on('close', () => {
      set.delete(ws);
      if (set.size === 0) {
        online.delete(user.id);
        q('UPDATE users SET last_seen=? WHERE id=?').run(now(), user.id);
        presence(user.id, false);
      }
    });
    ws.send(JSON.stringify({ type: 'hello', userId: user.id }));
  });
});
function presence(userId, isOnline) {
  const peers = new Set();
  for (const { chat_id } of q('SELECT chat_id FROM members WHERE user_id=?').all(userId))
    for (const id of chatMemberIds(chat_id)) if (id !== userId) peers.add(id);
  const u = q('SELECT last_seen FROM users WHERE id=?').get(userId);
  for (const id of peers) sendTo(id, { type: 'presence', userId, online: isOnline, lastSeen: u.last_seen });
}
setInterval(() => {
  for (const set of online.values())
    for (const ws of set) { if (!ws.isAlive) { ws.terminate(); continue; } ws.isAlive = false; ws.ping(); }
}, 30000).unref();

export { server, db };
if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname)) {
  server.listen(PORT, () => console.log(`Voidgram server listening on :${PORT} (data: ${DATA_DIR})`));
}
