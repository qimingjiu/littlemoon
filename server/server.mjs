// 小月助手 server (LittleMoon). Implements AssistantClient/README.md API.
// Node 22, no deps. Data in DATA_DIR/db.json. Replies via OpenAI-compatible API with persona; FCM HTTP v1 push.
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const env = process.env;
const LISTEN_HOST = env.LM_HOST || '127.0.0.1';
const LISTEN_PORT = Number(env.LM_PORT || 8792);
const DATA_DIR = env.LM_DATA || './data';
const SA_FILE = env.LM_FCM_SA || './secrets/fcm-sa.json';
const LLM_BASE = (env.LM_LLM_BASE || 'https://api.minimaxi.com/v1').replace(/\/$/, '');
const LLM_KEY = env.LM_LLM_KEY || '';
const LLM_MODEL = env.LM_LLM_MODEL || 'MiniMax-M3';
const PERSONA_FILE = env.LM_PERSONA || './persona.md';
const HISTORY_N = Number(env.LM_HISTORY || 30);

fs.mkdirSync(DATA_DIR, { recursive: true });
const DB_FILE = path.join(DATA_DIR, 'db.json');
let db = { pair_codes: {}, devices: {}, conversations: {}, tool_results: {}, tool_requests: {}, scheduled: {} };
try { db = { ...db, ...JSON.parse(fs.readFileSync(DB_FILE, 'utf8')) }; } catch {}
db.tool_requests ||= {}; db.scheduled ||= {};
let saveTimer = null;
const save = () => { clearTimeout(saveTimer); saveTimer = setTimeout(() => { const tmp = DB_FILE + '.tmp'; fs.writeFileSync(tmp, JSON.stringify(db)); fs.renameSync(tmp, DB_FILE); }, 50); };
const log = (...a) => console.log(new Date().toISOString(), ...a);
const sha = s => crypto.createHash('sha256').update(s).digest('hex');
const rid = (p, n = 9) => p + crypto.randomBytes(n).toString('base64url');

// ---- CLI: node server.mjs newcode  -> prints a one-time pairing code (valid 30 min)
if (process.argv[2] === 'newcode') {
  const code = String(crypto.randomInt(100000, 999999)) + '-' + crypto.randomBytes(2).toString('hex');
  db.pair_codes[sha(code)] = { expires: Date.now() + 30 * 60e3 };
  fs.writeFileSync(DB_FILE, JSON.stringify(db));
  console.log(code);
  process.exit(0);
}

const conv = id => db.conversations[id];
function addMsg(c, m) {
  c.seq = (c.seq || 0) + 1;
  const msg = { id: rid('m'), seq: c.seq, ts: Date.now(), ...m };
  c.messages.push(msg);
  save();
  return msg;
}

// ---- LLM
let persona = '';
try { persona = fs.readFileSync(PERSONA_FILE, 'utf8'); } catch {}
const USER_NAME = env.LM_USER_NAME || '用户';
const SYS_EXTRA = `\n\n[场景] 你现在在${USER_NAME}自己的手机 App「小月助手」里和对方一对一私聊。像私聊一样自然回复。你可以用 set_media_volume 工具调${USER_NAME}手机的媒体音量（App 关着也行，可以设延迟）。你也可以用 python_version 工具查对方手机 Termux 里的 Python 版本。`;
const TOOLS = [{
  type: 'function',
  function: {
    name: 'set_media_volume',
    description: `调节${USER_NAME}手机的媒体音量（音乐/视频）。App 关着、锁屏也能生效（静默推送）。可延迟执行。`,
    parameters: { type: 'object', properties: {
      percent: { type: 'integer', minimum: 0, maximum: 100, description: '目标音量百分比；和 step 二选一' },
      step: { type: 'integer', description: '相对调节的档数，负数为调低，例如 -3' },
      delay_seconds: { type: 'integer', minimum: 0, maximum: 86400, description: '多少秒后执行，默认 0' },
    } },
  },
}, {
  type: 'function',
  function: {
    name: 'python_version',
    description: `查询${USER_NAME}手机 Termux 里的 Python 版本（后台执行 python --version）。手机需在线，可能要等几十秒。`,
    parameters: { type: 'object', properties: {} },
  },
}];
const timers = new Set();
const TOOL_WAIT_MS = Number(env.LM_TOOL_WAIT || 75000);
const ID_RE = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/;
const waiters = new Map(); // request_id -> resolve
function pickDevice(c) {
  return Object.values(db.devices)
    .filter(d => !d.revoked && d.conversation_id === c.id && d.fid && d.fid_invalid !== d.fid)
    .sort((a, b) => (b.bound_at || b.created || 0) - (a.bound_at || a.created || 0))[0] || null;
}
async function runDeviceTool(c, tool) {
  const dev = pickDevice(c);
  if (!dev) return { status: 'blocked', error: 'no_registered_device' };
  const id = rid('r', 9);
  const reqRec = db.tool_requests[id] = { id, conversation_id: c.id, device_id: dev.id, tool, args: '{}', created: Date.now(), expires: Date.now() + 10 * 60e3, status: 'waiting_device' };
  save();
  const data = { kind: 'tool_request', tool, device_id: dev.id, conversation_id: c.id, request_id: id, args: '{}' };
  const got = new Promise(ok => waiters.set(id, ok));
  const r = await sendData(c, data, { ttl: '120s', device: dev }).catch(e => { log('tool push error', e.message); return []; });
  if (!r.length || r.some(x => x.status !== 200)) { waiters.delete(id); reqRec.status = 'push_failed'; save(); return { status: 'blocked', error: 'push_failed', request_id: id }; }
  const res = await Promise.race([got, new Promise(ok => setTimeout(() => ok(null), TOOL_WAIT_MS))]);
  waiters.delete(id);
  if (!res) { reqRec.status = 'late'; save(); }
  if (!res) return { status: 'waiting_device', request_id: id, note: '推送已提交，手机还没回结果；可能被系统限制后台启动，让她打开一下 App 再试' };
  return res;
}
// ---- persistent delayed volume tasks
function armScheduled(rec) {
  const ms = Math.max(0, rec.fire_at - Date.now());
  const t = setTimeout(async () => {
    timers.delete(t);
    const cur = db.scheduled[rec.id]; if (!cur) return;
    delete db.scheduled[rec.id]; save();
    if (Date.now() > cur.expires) { log('volume task expired', cur.id); return; }
    const c = conv(cur.conversation_id); if (!c) return;
    await sendData(c, cur.data).catch(e => log('volume push error', e.message));
  }, Math.min(ms, 2 ** 31 - 1));
  timers.add(t);
}
function restoreScheduled() {
  for (const rec of Object.values(db.scheduled)) {
    if (Date.now() > rec.expires) { delete db.scheduled[rec.id]; log('volume task dropped (expired)', rec.id); continue; }
    armScheduled(rec); log('volume task restored', rec.id, new Date(rec.fire_at).toISOString());
  }
  save();
}
async function runTool(c, name, args) {
  if (name === 'python_version') return runDeviceTool(c, 'python_version');
  if (name !== 'set_media_volume') return { ok: false, error: 'unknown tool' };
  const data = { kind: 'media_volume' };
  if (Number.isFinite(args.percent)) data.percent = String(Math.max(0, Math.min(100, Math.round(args.percent))));
  else if (Number.isFinite(args.step)) data.step = String(Math.round(args.step));
  else return { ok: false, error: 'need percent or step' };
  const delay = Math.max(0, Math.min(86400, Number(args.delay_seconds) || 0));
  const fire = () => sendData(c, data).catch(e => log('volume push error', e.message));
  if (delay) {
    const id = rid('v', 9); const fire_at = Date.now() + delay * 1000;
    const rec = db.scheduled[id] = { id, conversation_id: c.id, data: { ...data, request_id: id }, fire_at, expires: fire_at + 10 * 60e3 };
    save(); armScheduled(rec); log('volume scheduled', id, delay, JSON.stringify(data));
    return { ok: true, scheduled_in_seconds: delay };
  }
  const r = await fire();
  return { ok: !!r && r.every(x => x.status === 200), note: '已发出静默推送；手机若被强行停止可能收不到' };
}
async function llmReply(c) {
  const hist = c.messages.filter(m => m.role === 'user' || m.role === 'assistant' || m.role === 'event').slice(-HISTORY_N)
    .map(m => m.role === 'event' ? { role: 'user', content: `[系统事件，不是${USER_NAME}说的] ` + m.text } : { role: m.role, content: m.text });
  const messages = [{ role: 'system', content: persona + SYS_EXTRA }, ...hist];
  for (let round = 0; round < 4; round++) {
    const body = { model: LLM_MODEL, messages, tools: TOOLS };
    const r = await fetch(LLM_BASE + '/chat/completions', {
      method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer ' + LLM_KEY },
      body: JSON.stringify(body), signal: AbortSignal.timeout(180000),
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error('LLM ' + r.status + ' ' + JSON.stringify(j).slice(0, 300));
    const m = j.choices?.[0]?.message || {};
    if (m.tool_calls?.length) {
      messages.push({ role: 'assistant', content: m.content || null, tool_calls: m.tool_calls });
      for (const tc of m.tool_calls) {
        let args = {}; try { args = JSON.parse(tc.function?.arguments || '{}'); } catch {}
        const out = await runTool(c, tc.function?.name, args);
        log('tool', tc.function?.name, JSON.stringify(args), JSON.stringify(out));
        messages.push({ role: 'tool', tool_call_id: tc.id, content: JSON.stringify(out) });
      }
      continue;
    }
    return String(m.content || '').replace(/<think>[\s\S]*?<\/think>/g, '').replace(/^[\s\S]*?<\/think>/, '').trim() || '……';
  }
  return '（工具调用太多次了，先停一下）';
}

// per-conversation serial queue
const queues = {};
function enqueue(cid, fn) { const p = (queues[cid] || Promise.resolve()).then(fn, fn); queues[cid] = p.catch(() => {}); return p; }
function generate(cid) {
  return enqueue(cid, async () => {
    const c = conv(cid); if (!c) return;
    c.generating = true; save();
    let msg;
    try { msg = addMsg(c, { role: 'assistant', text: await llmReply(c) }); }
    catch (e) { log('llm error', e.message); msg = addMsg(c, { role: 'assistant', text: '（出错了：' + e.message.slice(0, 120) + '，再发一次试试）' }); }
    c.generating = false; save();
    pushAll(c, msg).catch(e => log('push error', e.message));
  });
}

// ---- FCM HTTP v1
let sa = null, tokCache = { t: '', exp: 0 };
function loadSA() { if (!sa) sa = JSON.parse(fs.readFileSync(SA_FILE, 'utf8')); return sa; }
async function fcmToken() {
  if (tokCache.exp > Date.now() + 60e3) return tokCache.t;
  const s = loadSA(); const now = Math.floor(Date.now() / 1000);
  const b64 = o => Buffer.from(JSON.stringify(o)).toString('base64url');
  const unsigned = b64({ alg: 'RS256', typ: 'JWT' }) + '.' + b64({ iss: s.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: s.token_uri, iat: now, exp: now + 3600 });
  const sig = crypto.sign('RSA-SHA256', Buffer.from(unsigned), s.private_key).toString('base64url');
  const r = await fetch(s.token_uri, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: unsigned + '.' + sig }) });
  const j = await r.json(); if (!j.access_token) throw new Error('oauth ' + JSON.stringify(j).slice(0, 200));
  tokCache = { t: j.access_token, exp: Date.now() + (j.expires_in || 3600) * 1000 };
  return tokCache.t;
}
async function fcmSend(dev, c, msg) {
  const s = loadSA();
  const base = {
    notification: { title: env.LM_TITLE || '小月助手', body: env.LM_PREVIEW === '0' ? '你有一条新消息' : (msg.text.length > 300 ? msg.text.slice(0, 300) + '…' : msg.text) },
    data: { conversation_id: c.id, message_id: msg.id },
    android: { priority: 'high', ttl: '3600s', notification: { channel_id: 'assistant_replies', tag: 'chat_' + c.id } },
  };
  const tryOnce = async target => {
    const r = await fetch(`https://fcm.googleapis.com/v1/projects/${s.project_id}/messages:send`, {
      method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer ' + await fcmToken() },
      body: JSON.stringify({ message: { ...target, ...base } }),
    });
    return { status: r.status, body: await r.text() };
  };
  let res = await tryOnce({ fid: dev.fid });
  if (res.status === 400 && /fid|Unknown name|Invalid JSON/i.test(res.body)) res = await tryOnce({ token: dev.fid });
  log('fcm', dev.id, res.status, res.body.slice(0, 200));
  if (res.status === 404 || /UNREGISTERED/.test(res.body)) { dev.fid_invalid = dev.fid; save(); }
  return res;
}
async function pushAll(c, msg) {
  for (const dev of Object.values(db.devices)) {
    if (dev.revoked || dev.conversation_id !== c.id || !dev.fid || !dev.notifications_enabled || dev.fid_invalid === dev.fid) continue;
    await fcmSend(dev, c, msg);
  }
}

async function sendData(c, data, opt = {}) {
  const s = loadSA(); const out = [];
  for (const dev of (opt.device ? [opt.device] : Object.values(db.devices))) {
    if (dev.revoked || dev.conversation_id !== c.id || !dev.fid) continue;
    const r = await fetch(`https://fcm.googleapis.com/v1/projects/${s.project_id}/messages:send`, {
      method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer ' + await fcmToken() },
      body: JSON.stringify({ message: { fid: dev.fid, data, android: { priority: 'high', ttl: opt.ttl || '600s' } } }),
    });
    const b = await r.text(); log('fcm data', dev.id, r.status, b.slice(0, 150)); out.push({ status: r.status });
  }
  return out;
}

// ---- HTTP
const send = (res, code, obj) => { res.writeHead(code, { 'content-type': 'application/json; charset=utf-8' }); res.end(obj === undefined ? '' : JSON.stringify(obj)); };
const readJson = req => new Promise((ok, bad) => { let b = ''; req.on('data', d => { b += d; if (b.length > 1e6) req.destroy(); }); req.on('end', () => { try { ok(b ? JSON.parse(b) : {}); } catch { bad(new Error('bad json')); } }); });
const fails = {};
function auth(req) {
  const m = /^Bearer (.+)$/.exec(req.headers.authorization || ''); if (!m) return null;
  const d = Object.values(db.devices).find(x => x.token_hash === sha(m[1]) && !x.revoked);
  return d || null;
}

const server = http.createServer(async (req, res) => {
  try {
    const u = new URL(req.url, 'http://x');
    const p = u.pathname.replace(/^\/moon(?=\/)/, '');
    if (req.method === 'GET' && (p === '/' || p === '/health')) return send(res, 200, { ok: true, name: 'littlemoon' });

    if (req.method === 'POST' && p === '/v1/pair') {
      const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress;
      const f = fails[ip] = fails[ip] && fails[ip].until > Date.now() ? fails[ip] : { n: 0, until: Date.now() + 15 * 60e3 };
      if (f.n >= 10) return send(res, 429, { error: 'too_many_attempts' });
      const { code } = await readJson(req);
      const h = sha(String(code || '').trim()); const pc = db.pair_codes[h];
      if (!pc || pc.expires < Date.now()) { f.n++; return send(res, 401, { error: 'invalid_code' }); }
      delete db.pair_codes[h];
      // one personal conversation shared by all of the owner's devices
      let cid = Object.keys(db.conversations)[0];
      if (!cid) { cid = 'c1'; db.conversations[cid] = { id: cid, seq: 0, messages: [] }; }
      const token = crypto.randomBytes(32).toString('base64url');
      const id = rid('d', 6);
      db.devices[id] = { id, token_hash: sha(token), conversation_id: cid, created: Date.now(), binding_version: -1 };
      save(); log('paired', id);
      return send(res, 200, { device_id: id, access_token: token, conversation_id: cid });
    }

    if (req.method === 'POST' && p === '/internal/say' && !req.headers['x-forwarded-for'] && req.socket.remoteAddress === '127.0.0.1') {
      const b = await readJson(req); const c = conv(b.conversation_id || 'c1'); if (!c) return send(res, 404, {});
      const msg = addMsg(c, { role: 'assistant', text: String(b.text || '') });
      pushAll(c, msg).catch(e => log('push error', e.message));
      return send(res, 200, { message_id: msg.id });
    }
    if (req.method === 'POST' && p === '/internal/volume' && !req.headers['x-forwarded-for'] && req.socket.remoteAddress === '127.0.0.1') {
      return send(res, 200, await runTool(conv('c1'), 'set_media_volume', await readJson(req)));
    }
    if (req.method === 'POST' && p === '/internal/tool' && !req.headers['x-forwarded-for'] && req.socket.remoteAddress === '127.0.0.1') {
      const b = await readJson(req); return send(res, 200, await runTool(conv('c1'), String(b.tool || ''), b.args || {}));
    }
    const dev = auth(req);
    if (p.startsWith('/v1/') && !dev) return send(res, 401, { error: 'unauthorized' });

    if (req.method === 'PUT' && p === '/v1/devices/me/push') {
      const b = await readJson(req); const v = Number(b.binding_version ?? 0);
      if (v >= (dev.binding_version ?? -1)) {
        Object.assign(dev, { fid: b.fid ? String(b.fid) : dev.fid, notifications_enabled: !!b.notifications_enabled, binding_version: v, bound_at: Date.now() });
        save(); log('push bind', dev.id, 'v' + v, !!b.fid, !!b.notifications_enabled);
      }
      return send(res, 204);
    }
    if (req.method === 'DELETE' && p === '/v1/devices/me') { dev.revoked = true; delete dev.fid; save(); return send(res, 204); }

    let m;
    if ((m = /^\/v1\/conversations\/([^/]+)\/messages$/.exec(p))) {
      const c = conv(m[1]); if (!c || dev.conversation_id !== c.id) return send(res, 404, { error: 'not_found' });
      if (req.method === 'POST') {
        const b = await readJson(req); const text = String(b.text ?? '').trim(); const cmid = String(b.client_message_id || '');
        if (!text) return send(res, 400, { error: 'empty' });
        const dup = cmid && c.messages.find(x => x.role === 'user' && x.client_message_id === cmid && x.device_id === dev.id);
        if (dup) return dup.text === text ? send(res, 202, { message_id: dup.id }) : send(res, 409, { error: 'conflict' });
        const msg = addMsg(c, { role: 'user', text, client_message_id: cmid, device_id: dev.id });
        generate(c.id);
        return send(res, 202, { message_id: msg.id });
      }
      if (req.method === 'GET') {
        const after = Number(u.searchParams.get('after_seq') || 0); const limit = 100;
        const rows = c.messages.filter(x => x.seq > after);
        const slice = rows.slice(0, limit);
        const page = slice.filter(x => x.role !== 'event').map(({ device_id, ts, ...x }) => x);
        return send(res, 200, { messages: page, next_seq: slice.length ? slice[slice.length - 1].seq : after, has_more: rows.length > limit, generating: !!c.generating });
      }
    }
    if (req.method === 'PUT' && (m = /^\/v1\/conversations\/([^/]+)\/tool-requests\/([^/]+)\/result$/.exec(p))) {
      const c = conv(m[1]); if (!c || dev.conversation_id !== c.id) return send(res, 404, { error: 'not_found' });
      log('tool result PUT', m[1], m[2], dev.id);
      if (!ID_RE.test(m[2])) return send(res, 400, { error: 'bad_request_id' });
      const tr = db.tool_requests[m[2]];
      if (!tr || tr.conversation_id !== c.id || tr.device_id !== dev.id) return send(res, 404, { error: 'not_found' });
      const body = await readJson(req);
      const key = c.id + '/' + m[2];
      const prev = db.tool_results[key];
      if (prev) {
        const same = ['status', 'output', 'exit_code', 'error'].every(k => JSON.stringify(prev[k]) === JSON.stringify(body[k]));
        return same ? send(res, 204) : send(res, 409, { error: 'conflict' });
      }
      if (Date.now() > tr.expires) return send(res, 410, { error: 'expired' });
      db.tool_results[key] = { ...body, device_id: dev.id, ts: Date.now() };
      save();
      log('tool result', key, JSON.stringify(body).slice(0, 300));
      const w = waiters.get(m[2]);
      if (w) w(body);
      else if (tr.status === 'late' || tr.status === 'waiting_device') {
        c.messages.push({ id: rid('e'), seq: c.seq || 0, ts: Date.now(), role: 'event', text: `之前的 ${tr.tool} 请求，手机迟到的结果刚到：${JSON.stringify(body).slice(0, 500)}。用一两句话自然地告诉${USER_NAME}。` }); save();
        generate(c.id);
      }
      tr.status = String(body.status || 'done'); save();
      return send(res, 204);
    }
    send(res, 404, { error: 'not_found' });
  } catch (e) { log('err', e.message); send(res, 500, { error: 'server_error' }); }
});
restoreScheduled();
server.listen(LISTEN_PORT, LISTEN_HOST, () => log(`littlemoon listening ${LISTEN_HOST}:${LISTEN_PORT} model=${LLM_MODEL}`));
