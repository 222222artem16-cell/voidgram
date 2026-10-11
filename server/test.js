// Integration test: boots the server on a temp dir and exercises the whole API.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'voidgram-'));
const { server } = await import('./server.js');
const { WebSocket } = await import('ws');
await new Promise((r) => server.listen(0, r));
const base = `http://127.0.0.1:${server.address().port}`;

async function api(method, p, body, token) {
  const res = await fetch(base + p, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: await res.json() };
}
const events = (token) => new Promise((resolve) => {
  const ws = new WebSocket(`${base.replace('http', 'ws')}/ws?token=${token}`);
  const list = [];
  ws.on('message', (d) => list.push(JSON.parse(d)));
  ws.on('open', () => resolve({ ws, list }));
});
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

let passed = 0;
const ok = (name) => { passed++; console.log('  ✓', name); };

// auth
const a = await api('POST', '/api/register', { username: 'alice', name: 'Alice', password: 'secret1' });
assert.equal(a.status, 200); const A = a.json.token; const aId = a.json.user.id;
const b = await api('POST', '/api/register', { username: 'bob', name: 'Bob', password: 'secret2' });
const B = b.json.token; const bId = b.json.user.id;
const c = await api('POST', '/api/register', { username: 'carol', name: 'Carol', password: 'secret3' });
const C = c.json.token; const cId = c.json.user.id;
ok('register 3 users');
assert.equal((await api('POST', '/api/register', { username: 'ALICE', name: 'x', password: 'secret1' })).status, 409);
assert.equal((await api('POST', '/api/register', { username: 'x', name: 'x', password: 'secret1' })).status, 400);
assert.equal((await api('POST', '/api/login', { username: 'alice', password: 'nope' })).status, 401);
assert.equal((await api('POST', '/api/login', { username: 'alice', password: 'secret1' })).status, 200);
assert.equal((await api('GET', '/api/me')).status, 401);
ok('duplicate/validation/login/unauthorized');

// realtime
const wb = await events(B); const wc = await events(C);
await wait(100);

// search
const s = await api('GET', '/api/users/search?q=bo', null, A);
assert.equal(s.json.users[0].username, 'bob');
assert.equal(s.json.users[0].online, true);
ok('user search + online flag');

// direct chat + messages
const d = await api('POST', '/api/chats/direct', { userId: bId }, A);
const chatId = d.json.chat.id;
assert.equal(d.json.chat.title, 'Bob');
assert.equal((await api('POST', '/api/chats/direct', { userId: aId }, B)).json.chat.id, chatId, 'direct chat is shared');
const m1 = await api('POST', `/api/chats/${chatId}/messages`, { text: 'hello bob' }, A);
assert.equal(m1.status, 200);
await wait(100);
assert.ok(wb.list.some((e) => e.type === 'message.new' && e.message.text === 'hello bob'));
ok('direct chat + realtime delivery');

// reply, edit, reaction, unread, read
const m2 = await api('POST', `/api/chats/${chatId}/messages`, { text: 'hi alice', replyTo: m1.json.message.id }, B);
assert.equal(m2.json.message.replyTo.id, m1.json.message.id);
let chats = await api('GET', '/api/chats', null, A);
assert.equal(chats.json.chats[0].unread, 1);
await api('POST', `/api/chats/${chatId}/read`, {}, A);
chats = await api('GET', '/api/chats', null, A);
assert.equal(chats.json.chats[0].unread, 0);
const ed = await api('PATCH', `/api/messages/${m1.json.message.id}`, { text: 'hello bob!' }, A);
assert.ok(ed.json.message.editedAt);
assert.equal((await api('PATCH', `/api/messages/${m1.json.message.id}`, { text: 'hack' }, B)).status, 404);
const re = await api('PUT', `/api/messages/${m1.json.message.id}/reaction`, { emoji: '👍' }, B);
assert.deepEqual(re.json.message.reactions, { '👍': [bId] });
ok('reply / unread / read / edit / reactions');

// access control
const cm = await api('GET', `/api/chats/${chatId}/messages`, null, C);
assert.equal(cm.status, 403);
assert.equal((await api('POST', `/api/chats/${chatId}/messages`, { text: 'x' }, C)).status, 403);
ok('non-members blocked');

// search in chat
const sr = await api('GET', `/api/chats/${chatId}/search?q=alice`, null, A);
assert.equal(sr.json.messages.length, 1);
ok('message search');

// group
const g = await api('POST', '/api/chats/group', { title: 'Void crew', memberIds: [bId, cId] }, A);
const gid = g.json.chat.id;
assert.equal(g.json.chat.memberIds.length, 3);
await wait(100);
assert.ok(wc.list.some((e) => e.type === 'chat.new' && e.chat.title === 'Void crew'));
await api('POST', `/api/chats/${gid}/messages`, { text: 'welcome' }, A);
await wait(100);
assert.ok(wc.list.some((e) => e.type === 'message.new' && e.message.text === 'welcome'));
assert.equal((await api('DELETE', `/api/chats/${gid}/members/${cId}`, null, B)).status, 403);
assert.equal((await api('DELETE', `/api/chats/${gid}/members/${cId}`, null, A)).status, 200);
assert.equal((await api('GET', `/api/chats/${gid}/messages`, null, C)).status, 403);
ok('groups: create / broadcast / remove member');

// owner deletes someone else's message
const gm = await api('POST', `/api/chats/${gid}/messages`, { text: 'oops' }, B);
assert.equal((await api('DELETE', `/api/messages/${gm.json.message.id}`, null, A)).status, 200);
const hist = await api('GET', `/api/chats/${gid}/messages`, null, A);
assert.ok(hist.json.messages.find((m) => m.id === gm.json.message.id).deleted);
ok('delete message (owner moderation)');

// media upload + download
const png = Buffer.from('89504e470d0a1a0a', 'hex');
const up = await fetch(base + '/api/upload', {
  method: 'POST', body: png,
  headers: { Authorization: 'Bearer ' + A, 'Content-Type': 'image/png', 'X-Filename': encodeURIComponent('фото.png') },
});
const upj = await up.json();
assert.equal(upj.media.size, 8);
const mm = await api('POST', `/api/chats/${chatId}/messages`, { text: '', mediaId: upj.media.id }, A);
assert.equal(mm.json.message.media.filename, 'фото.png');
const dl = await fetch(`${base}/media/${upj.media.id}`, { headers: { Authorization: 'Bearer ' + B } });
assert.equal(dl.status, 200);
assert.deepEqual(Buffer.from(await dl.arrayBuffer()), png);
assert.equal((await fetch(`${base}/media/${upj.media.id}`)).status, 401);
ok('media upload/download + auth');

// presence
wb.ws.close(); await wait(150);
const lastSeenView = await api('GET', `/api/users/${bId}`, null, A);
assert.equal(lastSeenView.json.user.online, false);
ok('presence on disconnect');

console.log(`\n${passed} test groups passed`);
wc.ws.close();
server.close();
process.exit(0);
