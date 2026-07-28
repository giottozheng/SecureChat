/**
 * SecureChat 推送服务端
 *
 * 功能：
 * 1. WebSocket 长连接：维持与 Android 客户端的实时推送
 * 2. HTTP API：注册、登录认证、消息轮询、密钥交换、好友关系
 * 3. 离线消息队列：WebSocket 断开时缓存消息
 * 4. 数据保留：聊天记录 1 天后自动删除（服务端 + 客户端同步按时间戳清理）
 *
 * 部署：
 * - 直接运行：ts-node src/server.ts
 * - 编译运行：./node_modules/.bin/tsc && node dist/server.js
 */

import express, { Request, Response } from 'express';
import helmet from 'helmet';
import cors from 'cors';
import { WebSocketServer } from 'ws';
import { WebSocket } from 'ws';
import { v4 as uuidv4 } from 'uuid';
import * as http from 'http';
import * as fs from 'fs';
import * as path from 'path';
import { scryptSync, randomBytes, timingSafeEqual, createHmac } from 'crypto';

// ── 增强 WebSocket 类型 ──

interface ExtWebSocket extends WebSocket {
  isAlive?: boolean;
  clientUserId?: string;
  clientDeviceId?: string;
}

interface ClientConnection {
  userId: string;
  deviceId: string;
  ws: ExtWebSocket;
  connectedAt: Date;
  lastPingAt: Date;
}

interface OfflineMessage {
  id: string;
  senderId: string;
  recipientId: string;
  conversationId: string;
  encryptedPayload: string;
  timestamp: number;
  senderName?: string;
  messageType?: string;
  // 文件消息元数据（明文，非文件内容；内容经端到端加密后以 fileId 引用）
  fileId?: string;
  fileName?: string;
  fileMime?: string;
  fileSize?: number;
}

interface User {
  id: string;
  username: string;
  password: string;
  displayName: string;
  publicKey: string;
  keyEpoch?: number;      // 公钥版本号：registerPublicKey 时自增，供对端检测密钥变更后自动重新拉取
  avatarUrl?: string;     // 头像 URL（/avatars/<id>.jpg，明文公开，非聊天内容）
  enabled?: boolean;     // 账号是否启用（后台可禁用）
  createdAt?: number;    // 注册时间（毫秒）
}

interface LoginResponse {
  success: boolean;
  token?: string;
  userId?: string;
  deviceToken?: string;
  error?: string;
  userProfile?: { id: string; displayName: string; publicKey: string; avatarUrl?: string };
}

// ── 内存存储 ──

const connectedClients: Map<string, ClientConnection> = new Map();
const offlineMessages: Map<string, OfflineMessage[]> = new Map();
const deviceTokens: Map<string, string> = new Map();
// 离线已读回执：对方离线时暂存，待其上线后随连接建立一并下发
const pendingReadReceipts: Map<string, Array<{ conversationId: string; readerId: string; ts: number }>> = new Map();

// 动态用户表（替代硬编码 TEAM_MEMBERS）：注册时写入，重启后清空。
const USERS: Map<string, User> = new Map();
const usernameIndex: Map<string, string> = new Map(); // username -> userId
let userCounter = 0;

// 好友关系：friendships[userId] = Set<friendId>；accept 时双向写入。
const friendships: Map<string, Set<string>> = new Map();
// 待处理好友请求（收到的）：friendRequests[toUserId] = [{fromId, fromName, timestamp}]
const friendRequests: Map<string, Array<{ fromId: string; fromName: string; timestamp: number }>> = new Map();

// 服务端消息留存（用于 1 天保留策略）：messageStore[conversationId] = 消息列表
const messageStore: Map<string, OfflineMessage[]> = new Map();

// 消息留存时长改为可配置（见管理后台 → 消息保留）
function getRetentionMs(): number {
  const days = Math.max(1, Math.min(365, config.retentionDays || 1));
  return days * 24 * 60 * 60 * 1000;
}

// ── 持久化（重启不丢账号/公钥/好友关系）──
const STATE_DIR = path.join(__dirname, '..', 'data');
const STATE_FILE = path.join(STATE_DIR, 'state.json');

function snapshotState() {
  return {
    userCounter,
    users: Array.from(USERS.values()),
    usernameIndex: Array.from(usernameIndex.entries()),
    friendships: Array.from(friendships.entries()).map(([k, v]) => [k, Array.from(v)]),
    friendRequests: Array.from(friendRequests.entries())
  };
}

function applyState(s: any) {
  userCounter = s.userCounter || 0;
  USERS.clear();
  usernameIndex.clear();
  (s.users || []).forEach((u: User) => {
    u.enabled = u.enabled !== false;
    u.createdAt = u.createdAt || Date.now();
    USERS.set(u.id, u);
    usernameIndex.set(u.username, u.id);
  });
  friendships.clear();
  (s.friendships || []).forEach(([k, v]: [string, string[]]) => {
    friendships.set(k, new Set(v));
  });
  friendRequests.clear();
  (s.friendRequests || []).forEach(([k, v]: [string, any]) => {
    friendRequests.set(k, v);
  });
}

function persistNow() {
  try {
    if (!fs.existsSync(STATE_DIR)) fs.mkdirSync(STATE_DIR, { recursive: true });
    fs.writeFileSync(STATE_FILE, JSON.stringify(snapshotState()));
  } catch (e) {
    console.error('[STATE] save failed', e);
  }
}

function loadState() {
  try {
    if (fs.existsSync(STATE_FILE)) {
      applyState(JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')));
      console.log('[STATE] Loaded ' + USERS.size + ' users from disk');
    } else {
      console.log('[STATE] No saved state, starting fresh');
    }
  } catch (e) {
    console.error('[STATE] load failed', e);
  }
}

let saveTimer: ReturnType<typeof setTimeout> | null = null;
function scheduleSave() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    persistNow();
  }, 800);
}

// ── 管理后台配置（持久化到 config.json）──
const CONFIG_FILE = path.join(STATE_DIR, 'config.json');

interface ServerConfig {
  serverDomain: string;        // 对外绑定的域名（如 https://chat.example.com）
  retentionDays: number;       // 消息留存天数
  serverPort: number;          // 监听端口（默认 8080，修改后重启生效）
  adminUsername: string;
  adminPasswordHash: string;   // scrypt 哈希（十六进制）
  adminPasswordSalt: string;   // 十六进制
  adminMustReset: boolean;     // 首次登录是否必须改密
}

const config: ServerConfig = {
  serverDomain: '',
  retentionDays: 1,
  serverPort: 8080,
  adminUsername: 'admin',
  adminPasswordHash: '',
  adminPasswordSalt: '',
  adminMustReset: true
};

let messagePersistInterval: ReturnType<typeof setInterval> | null = null;

function hashPassword(password: string, salt?: string): { hash: string; salt: string } {
  const useSalt = salt || randomBytes(16).toString('hex');
  const derived = scryptSync(password, useSalt, 64).toString('hex');
  return { hash: derived, salt: useSalt };
}

function verifyPassword(password: string, hash: string, salt: string): boolean {
  const derived = scryptSync(password, salt, 64).toString('hex');
  try {
    return timingSafeEqual(Buffer.from(derived, 'hex'), Buffer.from(hash, 'hex'));
  } catch {
    return false;
  }
}

function signAdminToken(): string {
  const payload = JSON.stringify({ role: 'admin', ts: Date.now() });
  const sig = createHmac('sha256', JWT_SECRET).update(payload).digest('hex');
  return `${Buffer.from(payload).toString('base64')}.${sig}`;
}

function verifyAdminToken(token: string): boolean {
  try {
    const [b64, sig] = token.split('.');
    if (!b64 || !sig) return false;
    const payload = Buffer.from(b64, 'base64').toString('utf8');
    const expected = createHmac('sha256', JWT_SECRET).update(payload).digest('hex');
    const a = Buffer.from(sig, 'hex');
    const b = Buffer.from(expected, 'hex');
    if (a.length !== b.length) return false;
    return timingSafeEqual(a, b);
  } catch {
    return false;
  }
}

function loadConfig() {
  try {
    if (fs.existsSync(CONFIG_FILE)) {
      const saved = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'));
      Object.assign(config, saved);
      console.log('[CONFIG] Loaded admin config');
    } else {
      // 首次启动：初始化默认管理员密码。
      // 优先读取环境变量 ADMIN_INITIAL_PASSWORD；仓库内为占位值，正式部署务必通过环境变量设置强密码。
      // adminMustReset 强制首次登录改密，因此占位密码不会长期有效。
      const initialAdminPassword = process.env.ADMIN_INITIAL_PASSWORD || 'CHANGE_ME_INITIAL_ADMIN_PASSWORD';
      const { hash, salt } = hashPassword(initialAdminPassword);
      config.adminPasswordHash = hash;
      config.adminPasswordSalt = salt;
      config.adminMustReset = true;
      saveConfig();
      console.log('[CONFIG] Initialized default admin (must reset on first login)');
    }
  } catch (e) {
    console.error('[CONFIG] load failed', e);
  }
}

function saveConfig() {
  try {
    if (!fs.existsSync(STATE_DIR)) fs.mkdirSync(STATE_DIR, { recursive: true });
    fs.writeFileSync(CONFIG_FILE, JSON.stringify(config, null, 2));
  } catch (e) {
    console.error('[CONFIG] save failed', e);
  }
}

// ── 操作审计日志（持久化到 audit.json，循环保留最近 2000 条）──
const AUDIT_FILE = path.join(STATE_DIR, 'audit.json');
function logAudit(action: string, detail: string, admin?: string) {
  try {
    let arr: any[] = [];
    if (fs.existsSync(AUDIT_FILE)) {
      const parsed = JSON.parse(fs.readFileSync(AUDIT_FILE, 'utf8'));
      if (Array.isArray(parsed)) arr = parsed;
    }
    arr.push({ ts: Date.now(), action, detail: String(detail).slice(0, 500), admin: admin || config.adminUsername });
    if (arr.length > 2000) arr = arr.slice(-2000);
    if (!fs.existsSync(STATE_DIR)) fs.mkdirSync(STATE_DIR, { recursive: true });
    fs.writeFileSync(AUDIT_FILE, JSON.stringify(arr));
  } catch (e) {
    console.error('[AUDIT] write failed', e);
  }
}
function getAudit(limit = 50, offset = 0, actionFilter = ''): any[] {
  try {
    if (!fs.existsSync(AUDIT_FILE)) return [];
    let arr = JSON.parse(fs.readFileSync(AUDIT_FILE, 'utf8'));
    if (!Array.isArray(arr)) return [];
    if (actionFilter) arr = arr.filter((x: any) => x.action === actionFilter);
    arr = arr.slice().reverse(); // 最新在前
    return arr.slice(offset, offset + limit);
  } catch {
    return [];
  }
}

// ── 系统公告（广播）──
const ANN_FILE = path.join(STATE_DIR, 'announcements.json');
function loadAnnouncements(): any[] {
  try {
    if (!fs.existsSync(ANN_FILE)) return [];
    const arr = JSON.parse(fs.readFileSync(ANN_FILE, 'utf8'));
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
}
function saveAnnouncements(arr: any[]) {
  try {
    if (!fs.existsSync(STATE_DIR)) fs.mkdirSync(STATE_DIR, { recursive: true });
    fs.writeFileSync(ANN_FILE, JSON.stringify(arr, null, 2));
  } catch (e) {
    console.error('[ANN] save failed', e);
  }
}
function getLatestAnnouncement(): any | null {
  const arr = loadAnnouncements();
  return arr.length ? arr[arr.length - 1] : null;
}

// ── 消息持久化（落盘 messages.json，仅存密文+元数据）──
const MESSAGES_FILE = path.join(STATE_DIR, 'messages.json');

function persistMessagesNow() {
  try {
    if (!fs.existsSync(STATE_DIR)) fs.mkdirSync(STATE_DIR, { recursive: true });
    const data = {
      messageStore: Array.from(messageStore.entries()),
      offlineMessages: Array.from(offlineMessages.entries())
    };
    fs.writeFileSync(MESSAGES_FILE, JSON.stringify(data));
  } catch (e) {
    console.error('[MSG] persist failed', e);
  }
}

function loadMessages() {
  try {
    if (fs.existsSync(MESSAGES_FILE)) {
      const data = JSON.parse(fs.readFileSync(MESSAGES_FILE, 'utf8'));
      (data.messageStore || []).forEach(([cid, arr]: [string, OfflineMessage[]]) => {
        messageStore.set(cid, arr);
      });
      (data.offlineMessages || []).forEach(([uid, arr]: [string, OfflineMessage[]]) => {
        offlineMessages.set(uid, arr);
      });
      console.log(`[MSG] Loaded messages from disk (${messageStore.size} conversations)`);
      sweepRetention(); // 加载后立即按当前保留策略清理
    }
  } catch (e) {
    console.error('[MSG] load failed', e);
  }
}

function sweepRetention() {
  const cutoff = Date.now() - getRetentionMs();
  let removed = 0;
  for (const [cid, arr] of messageStore) {
    const before = arr.length;
    const kept = arr.filter(m => m.timestamp >= cutoff);
    if (kept.length !== before) removed += before - kept.length;
    if (kept.length > 0) messageStore.set(cid, kept);
    else messageStore.delete(cid);
  }
  for (const [uid, arr] of offlineMessages) {
    const kept = arr.filter(m => m.timestamp >= cutoff);
    if (kept.length > 0) offlineMessages.set(uid, kept);
    else offlineMessages.delete(uid);
  }
  if (removed > 0) {
    console.log(`[Retention] Removed ${removed} messages older than ${config.retentionDays} day(s)`);
    persistMessagesNow();
  }
}

// ── 服务端实例 ──

const app = express();
const server = http.createServer(app);
let PORT = parseInt(process.env.PORT || '8080', 10);
const HOST = process.env.HOST || '0.0.0.0';
// 管理后台 JWT 签名密钥。生产环境必须通过环境变量 JWT_SECRET 设置强随机值；
// 下方默认值仅为占位符，切勿在生产环境直接使用。
const JWT_SECRET = process.env.JWT_SECRET || 'securechat_default_secret_change_me';

// ── 中间件 ──

app.use(helmet({
  contentSecurityPolicy: false,
  crossOriginEmbedderPolicy: false,
}));
app.use(cors());
app.use(express.json({ limit: '10mb' }));

// 健康检查
app.get('/health', (_req: Request, res: Response) => {
  res.json({
    status: 'ok',
    uptime: process.uptime(),
    clients: connectedClients.size,
    users: USERS.size,
    timestamp: Date.now()
  });
});

// 公开：供客户端「自动获取服务端配置」（域名/端口）
app.get('/api/server-config', (_req: Request, res: Response) => {
  let host = config.serverDomain;
  // 从 serverDomain 解析主机名（可能是 http(s)://host:port 或纯 host）
  try {
    if (host) {
      if (!/^https?:\/\//i.test(host)) host = 'http://' + host;
      host = new URL(host).hostname;
    }
  } catch { /* keep as-is */ }
  if (!host) host = _req.hostname || 'localhost';
  res.json({
    success: true,
    host,
    port: config.serverPort || PORT
  });
});

// 公开：最新系统公告（客户端启动时拉取展示）
app.get('/api/announcement', (_req: Request, res: Response) => {
  const ann = getLatestAnnouncement();
  if (!ann) {
    res.json({ success: true, announcement: null });
    return;
  }
  res.json({
    success: true,
    announcement: {
      id: ann.id,
      title: ann.title,
      content: ann.content,
      timestamp: ann.timestamp
    }
  });
});

// ── 客户端崩溃上报（用于远程诊断通话等偶发闪退）──
const CRASH_DIR = path.join(STATE_DIR, 'crash_reports');
app.post('/api/crash', express.text({ type: '*/*', limit: '1mb' }), (req: Request, res: Response) => {
  try {
    if (!fs.existsSync(CRASH_DIR)) fs.mkdirSync(CRASH_DIR, { recursive: true });
    const ts = Date.now();
    const content = (typeof req.body === 'string' && req.body.length > 0)
      ? req.body
      : `TIME: ${new Date(ts).toISOString()}\n(no payload)`;
    fs.writeFileSync(path.join(CRASH_DIR, `crash_${ts}.txt`), content);
    console.log(`[CRASH] received crash report`);
    res.json({ success: true });
  } catch (e: any) {
    res.status(500).json({ success: false, error: e?.message ?? 'write failed' });
  }
});

// ── OTA 远程升级 ──
const PUBLIC_DIR = path.join(__dirname, '..', 'public');
// 头像存储目录（复用 STATE_DIR 持久化卷，服务重启后文件仍在）
const AVATAR_DIR = path.join(STATE_DIR, 'avatars');
try { fs.mkdirSync(AVATAR_DIR, { recursive: true }); } catch (_) {}
const UPDATE_JSON_PATH = path.join(PUBLIC_DIR, 'update.json');

// OTA：禁止任何中间代理/运营商缓存版本检查与 APK 静态文件
// （否则蜂窝网络下透明代理可能返回缓存的旧版本，导致 app 内更新拿到旧包）
app.use('/apk', (_req: Request, res: Response, next) => {
  res.set('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
  res.set('Pragma', 'no-cache');
  res.set('Expires', '0');
  next();
});
app.use('/api/update/check', (_req: Request, res: Response, next) => {
  res.set('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
  res.set('Pragma', 'no-cache');
  res.set('Expires', '0');
  next();
});

// OTA 专用下载路由（路径中不含 /apk/、也不带 .apk 后缀）。
// 背景：蜂窝运营商透明代理会把「/apk/*.apk」整类路径折叠成最早缓存的那一份旧包返回，
// 导致无论文件名怎么加版本号都下到旧版。该路由的路径每次构建都唯一（token = APK 的 md5），
// 代理从未缓存过，必然返回最新包；同时强制 no-store。
app.use('/ota/dl', (_req: Request, res: Response, next) => {
  res.set('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
  res.set('Pragma', 'no-cache');
  res.set('Expires', '0');
  next();
});
app.get('/ota/dl/:token', (req: Request, res: Response) => {
  let token = req.params.token;
  // 容错：个别生成脚本会把 .apk 后缀写进 token，这里剥掉（服务端本就会拼 .apk）
  if (token.endsWith('.apk')) token = token.slice(0, -4);
  if (!/^[A-Za-z0-9_-]+$/.test(token)) {
    res.status(400).end('bad token');
    return;
  }
  const apkPath = path.join(PUBLIC_DIR, 'apk', `${token}.apk`);
  if (!fs.existsSync(apkPath)) {
    res.status(404).end('not found');
    return;
  }
  res.set('Content-Disposition', 'attachment; filename="securechat-update.apk"');
  res.set('Content-Type', 'application/vnd.android.package-archive');
  res.sendFile(apkPath);
});

// 提供 APK 下载（无需鉴权，便于内部分发；保留作为浏览器手动下载兜底）
app.use('/apk', express.static(path.join(PUBLIC_DIR, 'apk')));
// 头像静态访问（公开 GET，头像不属于敏感聊天内容）
app.use('/avatars', express.static(AVATAR_DIR));

// OTA 检查更新（缓存破坏放 path 版）：部分运营商透明代理按 URL path 缓存、忽略 query 参数，
// 导致旧版 ?_=时间戳 缓存破坏完全失效（客户端一直拿到 1.0.6 等陈旧响应）。
// 这里把每次请求的随机 token 放到 path 里（/ota/check/<token>），代理无缓存可命中必然回源，
// 服务端忽略 token 直接返回最新 update.json。token 由客户端用时间戳/随机数生成。
app.get('/ota/check/:token', (_req: Request, res: Response) => {
  try {
    res.set('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
    res.set('Pragma', 'no-cache');
    res.set('Expires', '0');
    if (!fs.existsSync(UPDATE_JSON_PATH)) {
      res.status(404).json({ success: false, error: 'no update info' });
      return;
    }
    const raw = fs.readFileSync(UPDATE_JSON_PATH, 'utf-8');
    const info = JSON.parse(raw);
    res.json({ success: true, ...info });
  } catch (e: any) {
    res.status(500).json({ success: false, error: e?.message ?? 'server error' });
  }
});

// 检查更新接口：返回最新版本信息（versionCode/versionName/apkUrl/changelog 等）
// 保留作为旧客户端（/api/update/check?_=）兼容路由；新客户端统一走 /ota/check/<token>。
app.get('/api/update/check', (_req: Request, res: Response) => {
  try {
    if (!fs.existsSync(UPDATE_JSON_PATH)) {
      res.status(404).json({ success: false, error: 'no update info' });
      return;
    }
    const raw = fs.readFileSync(UPDATE_JSON_PATH, 'utf-8');
    const info = JSON.parse(raw);
    res.json({ success: true, ...info });
  } catch (e: any) {
    res.status(500).json({ success: false, error: e?.message ?? 'server error' });
  }
});

// ── 注册 ──

app.post('/api/register', (req: Request, res: Response) => {
  const { username, password, displayName, deviceId } = req.body as Record<string, any>;
  if (!username || !password || !displayName) {
    res.status(400).json({ success: false, error: '缺少必要参数（用户名/密码/昵称）' });
    return;
  }
  if (usernameIndex.has(username)) {
    res.status(409).json({ success: false, error: '用户名已存在' });
    return;
  }
  const id = `user-${++userCounter}`;
  const user: User = {
    id,
    username,
    password,
    displayName: String(displayName).trim(),
    publicKey: '',
    enabled: true,
    createdAt: Date.now()
  };
  USERS.set(id, user);
  usernameIndex.set(username, id);
  console.log(`[REGISTER] New user: ${username} -> ${id}`);
  scheduleSave();

  const jwtToken = generateJwt({ userId: id, deviceId: deviceId || 'reg' });
  res.json({
    success: true,
    token: jwtToken,
    userId: id,
    userProfile: { id, displayName: user.displayName, publicKey: user.publicKey }
  } as LoginResponse);
});

// ── 登录认证 ──

app.post('/api/login', (req: Request, res: Response) => {
  const { username, password, deviceId } = req.body as Record<string, any>;

  if (!username || !password || !deviceId) {
    res.status(400).json({ success: false, error: '缺少必要参数' });
    return;
  }

  let user = validateCredentials(username, password);
  if (!user) {
    // 用户名不存在 → 自动创建账号（login 兼具注册，便于恢复/首登）
    if (!usernameIndex.has(username)) {
      const id = `user-${++userCounter}`;
      user = { id, username, password, displayName: String(username).trim(), publicKey: '', enabled: true, createdAt: Date.now() };
      USERS.set(id, user);
      usernameIndex.set(username, id);
      console.log(`[LOGIN] Auto-created user: ${username} -> ${id}`);
      scheduleSave();
    } else {
      res.status(401).json({ success: false, error: '用户名或密码错误' });
      return;
    }
  }

  let deviceToken = deviceTokens.get(deviceId);
  if (!deviceToken) {
    deviceToken = generateDeviceToken(deviceId, user.id);
    deviceTokens.set(deviceId, deviceToken);
  }

  // 头像恢复：内存用户表重启会清空 avatarUrl，但磁盘头像文件仍在，则自动补回
  if (!user.avatarUrl) {
    const candidate = path.join(AVATAR_DIR, `${user.id}.jpg`);
    if (fs.existsSync(candidate)) user.avatarUrl = `/avatars/${user.id}.jpg`;
  }

  const jwtToken = generateJwt({ userId: user.id, deviceId });

  res.json({
    success: true,
    token: jwtToken,
    userId: user.id,
    deviceToken,
    userProfile: {
      id: user.id,
      displayName: user.displayName,
      publicKey: user.publicKey,
      avatarUrl: user.avatarUrl
    }
  } as LoginResponse);
});

// ── 获取用户公钥 ──

app.get('/api/users/:userId/public-key', (req: Request, res: Response) => {
  const { userId } = req.params;
  const user = getUserById(userId);
  if (!user) {
    res.status(404).json({ success: false, error: '用户不存在' });
    return;
  }

  res.json({
    userId: user.id,
    displayName: user.displayName,
    publicKeyPem: user.publicKey,
    keyEpoch: user.keyEpoch || 0,
    avatarUrl: user.avatarUrl
  });
});

// ── 注册真实 RSA 公钥（客户端登录时上传，供其他成员加密）──
app.post('/api/keys/register', (req: Request, res: Response) => {
  const token = (req.headers['authorization'] || '').replace(/^Bearer\s+/i, '');
  const decoded = decodeJwt(token);
  if (!decoded || !decoded.userId) {
    res.status(401).json({ success: false, error: '未授权' });
    return;
  }
  const body = req.body as Record<string, any>;
  const publicKeyPem = body?.publicKeyPem || body?.publicKey;
  if (!publicKeyPem) {
    res.status(400).json({ success: false, error: '缺少公钥' });
    return;
  }
  const user = getUserById(decoded.userId);
  if (!user) {
    res.status(404).json({ success: false, error: '用户不存在' });
    return;
  }
  user.publicKey = publicKeyPem;
  user.keyEpoch = (user.keyEpoch || 0) + 1;
  console.log(`[KEY] Registered real public key for ${user.id} (epoch ${user.keyEpoch})`);
  scheduleSave();
  res.json({ success: true, userId: user.id, registered: true, keyEpoch: user.keyEpoch });
});

// ── 消息轮询 ──

app.post('/api/messages/poll', (req: Request, res: Response) => {
  const { userId, since } = req.body as Record<string, any>;
  if (!userId) {
    res.status(400).json({ success: false, error: '缺少 userId' });
    return;
  }

  const messages = (offlineMessages.get(userId) || [])
    .filter(m => !since || m.timestamp >= since)
    .sort((a, b) => a.timestamp - b.timestamp);

  if (messages.length > 0) {
    messages.forEach(m => {
      const list = offlineMessages.get(userId) || [];
      const idx = list.indexOf(m);
      if (idx >= 0) list.splice(idx, 1);
    });
    offlineMessages.set(userId, messages.length > 0 ? messages : []);
  }

  res.json({ success: true, messages });
});

// ── 发送消息 ──

app.post('/api/messages/send', (req: Request, res: Response) => {
  const body = req.body as Record<string, any>;
  const { senderId, recipientId, conversationId, encryptedContent, senderName, messageType, fileName, fileMime, fileSize, fileId } = body;

  if (!senderId || !recipientId || !encryptedContent) {
    res.status(400).json({ success: false, error: '缺少必要参数' });
    return;
  }

  // 好友关系校验：删除好友后双方不能通信
  const areFriends = (a: string, b: string): boolean => {
    const s = friendships.get(a);
    return !!s && s.has(b);
  };
  if (!areFriends(senderId, recipientId)) {
    res.status(403).json({ success: false, error: '你们已不是好友，无法发送消息', code: 'NOT_FRIEND' });
    return;
  }

  const message: OfflineMessage = {
    id: uuidv4(),
    senderId,
    recipientId,
    conversationId: conversationId || `${senderId}_${recipientId}`,
    encryptedPayload: encryptedContent,
    timestamp: Date.now(),
    senderName: typeof senderName === 'string' && senderName.trim() ? senderName.trim() : undefined,
    messageType: typeof messageType === 'string' && messageType.trim() ? messageType.trim() : 'TEXT',
    fileId: typeof fileId === 'string' && fileId.trim() ? fileId.trim() : undefined,
    fileName: typeof fileName === 'string' && fileName.trim() ? fileName.trim() : undefined,
    fileMime: typeof fileMime === 'string' && fileMime.trim() ? fileMime.trim() : undefined,
    fileSize: typeof fileSize === 'number' ? fileSize : (typeof fileSize === 'string' && fileSize.trim() ? parseInt(fileSize, 10) || undefined : undefined)
  };

  // 留存（用于 1 天保留策略）
  const storeArr = messageStore.get(message.conversationId) || [];
  storeArr.push(message);
  messageStore.set(message.conversationId, storeArr);

  // 检查接收者是否在线
  const recipientKey = Array.from(connectedClients.keys()).find(
    k => k.startsWith(`${recipientId}:`)
  );
  const recipient = recipientKey ? connectedClients.get(recipientKey)! : null;

  if (recipient && recipient.ws.readyState === 1 /* OPEN */) {
    recipient.ws.send(JSON.stringify({
      type: 'message',
      data: {
        id: message.id,
        senderId: message.senderId,
        senderName: message.senderName,
        conversationId: message.conversationId,
        encryptedContent: message.encryptedPayload,
        messageType: message.messageType || 'TEXT',
        timestamp: message.timestamp,
        fileId: message.fileId,
        fileName: message.fileName,
        fileMime: message.fileMime,
        fileSize: message.fileSize
      }
    }));
  } else {
    const list = offlineMessages.get(recipientId) || [];
    list.push(message);
    offlineMessages.set(recipientId, list);
  }

  res.json({ success: true, messageId: message.id });
});

// ── 文件（端到端加密密文）上传 / 下载 ──
// 客户端先把整个文件用信封加密得到「v1:...」密文，再上传；服务器只存密文，不掌握明文。

const UPLOAD_DIR = path.join(STATE_DIR, 'uploads');
if (!fs.existsSync(UPLOAD_DIR)) fs.mkdirSync(UPLOAD_DIR, { recursive: true });

// 上传：请求体为纯文本密文（v1:...），避免受 JSON body 10mb 限制
app.post('/api/files/upload', express.text({ type: '*/*', limit: '200mb' }), (req: Request, res: Response) => {
  const decoded = decodeJwt((req.headers['authorization'] || '').replace(/^Bearer\s+/i, ''));
  if (!decoded || !decoded.userId) {
    res.status(401).json({ success: false, error: '未授权' });
    return;
  }
  const content = typeof req.body === 'string' ? req.body : '';
  if (!content || !content.startsWith('v1:')) {
    res.status(400).json({ success: false, error: '无效的文件密文' });
    return;
  }
  const fileId = uuidv4();
  try {
    fs.writeFileSync(path.join(UPLOAD_DIR, `${fileId}.enc`), content, 'utf8');
    logAudit('file_upload', `用户 ${decoded.userId} 上传文件密文 ${fileId}`);
    res.json({ success: true, fileId });
  } catch (e: any) {
    res.status(500).json({ success: false, error: '写入失败' });
  }
});

// 下载：返回密文字节（application/octet-stream），客户端本地解密
app.get('/api/files/:id', (req: Request, res: Response) => {
  const decoded = decodeJwt((req.headers['authorization'] || '').replace(/^Bearer\s+/i, ''));
  if (!decoded || !decoded.userId) {
    res.status(401).json({ success: false, error: '未授权' });
    return;
  }
  const id = req.params.id || '';
  if (!/^[a-zA-Z0-9_-]+$/.test(id)) {
    res.status(400).json({ success: false, error: '非法文件标识' });
    return;
  }
  const filePath = path.join(UPLOAD_DIR, `${id}.enc`);
  if (!fs.existsSync(filePath)) {
    res.status(404).json({ success: false, error: '文件不存在' });
    return;
  }
  res.setHeader('Content-Type', 'application/octet-stream');
  res.send(fs.readFileSync(filePath));
});

// ── 联系人列表（仅已互为好友）──

app.get('/api/contacts', (req: Request, res: Response) => {
  const decoded = decodeJwt((req.headers['authorization'] || '').replace(/^Bearer\s+/i, ''));
  if (!decoded || !decoded.userId) {
    res.status(401).json({ success: false, error: '未授权' });
    return;
  }
  const friendIds = friendships.get(decoded.userId);
  const friends = friendIds
    ? Array.from(friendIds).map(id => getUserById(id)).filter(Boolean as any)
    : [];
  res.json(friends.map(u => ({
    id: u!.id,
    displayName: u!.displayName,
    publicKey: u!.publicKey,
    keyEpoch: u!.keyEpoch || 0,
    avatarUrl: u!.avatarUrl,
    online: !!Array.from(connectedClients.keys()).find(k => k.startsWith(`${u!.id}:`))
  })));
});

// ── 发送好友请求（targetId 可为用户名或 userId）──
app.post('/api/contacts/request', (req: Request, res: Response) => {
  const decoded = decodeJwt((req.headers['authorization'] || '').replace(/^Bearer\s+/i, ''));
  if (!decoded || !decoded.userId) {
    res.status(401).json({ success: false, error: '未授权' });
    return;
  }
  const targetId = (req.body as Record<string, any>)?.targetId;
  if (!targetId) {
    res.status(400).json({ success: false, error: '缺少目标用户' });
    return;
  }
  const self = decoded.userId;
  const target = resolveUser(targetId);
  if (!target) {
    res.status(404).json({ success: false, error: '用户不存在' });
    return;
  }
  if (target.id === self) {
    res.status(400).json({ success: false, error: '不能添加自己为好友' });
    return;
  }
  const myFriends = friendships.get(self);
  if (myFriends && myFriends.has(target.id)) {
    res.status(400).json({ success: false, error: '已是好友' });
    return;
  }
  const list = friendRequests.get(target.id) || [];
  if (list.some(r => r.fromId === self)) {
    res.status(400).json({ success: false, error: '好友请求已发送' });
    return;
  }
  list.push({
    fromId: self,
    fromName: getUserById(self)?.displayName || self,
    timestamp: Date.now()
  });
  friendRequests.set(target.id, list);
  console.log(`[FRIEND] Request ${self} -> ${target.id}`);
  scheduleSave();
  res.json({ success: true });
});

// ── 待处理好友请求（当前用户收到的）──
app.get('/api/contacts/requests', (req: Request, res: Response) => {
  const decoded = decodeJwt((req.headers['authorization'] || '').replace(/^Bearer\s+/i, ''));
  if (!decoded || !decoded.userId) {
    res.status(401).json({ success: false, error: '未授权' });
    return;
  }
  const list = friendRequests.get(decoded.userId) || [];
  res.json({
    requests: list.map(r => ({
      fromId: r.fromId,
      fromName: r.fromName,
      timestamp: r.timestamp
    }))
  });
});

// ── 响应好友请求（accept / reject）──
app.post('/api/contacts/respond', (req: Request, res: Response) => {
  const decoded = decodeJwt((req.headers['authorization'] || '').replace(/^Bearer\s+/i, ''));
  if (!decoded || !decoded.userId) {
    res.status(401).json({ success: false, error: '未授权' });
    return;
  }
  const { fromId, action } = req.body as Record<string, any>;
  const self = decoded.userId;
  const list = friendRequests.get(self) || [];
  const idx = list.findIndex(r => r.fromId === fromId);
  if (idx < 0) {
    res.status(404).json({ success: false, error: '请求不存在' });
    return;
  }
  list.splice(idx, 1);
  friendRequests.set(self, list);
  if (action === 'accept') {
    addFriendMutual(self, fromId);
    console.log(`[FRIEND] ${self} <-> ${fromId} became friends`);
    scheduleSave();
  }
  res.json({ success: true });
});

// ── 删除好友（双向解除，持久化）──
app.post('/api/contacts/remove', (req: Request, res: Response) => {
  const decoded = decodeJwt((req.headers['authorization'] || '').replace(/^Bearer\s+/i, ''));
  if (!decoded || !decoded.userId) {
    res.status(401).json({ success: false, error: '未授权' });
    return;
  }
  const targetId = (req.body as Record<string, any>)?.targetId;
  if (!targetId) {
    res.status(400).json({ success: false, error: '缺少目标用户' });
    return;
  }
  const self = decoded.userId;
  const target = resolveUser(targetId);
  if (!target) {
    res.status(404).json({ success: false, error: '用户不存在' });
    return;
  }
  if (target.id === self) {
    res.status(400).json({ success: false, error: '不能删除自己' });
    return;
  }
  removeFriendMutual(self, target.id);
  console.log(`[FRIEND] ${self} removed ${target.id}`);
  scheduleSave();
  res.json({ success: true });
});

// ── 修改显示昵称（用于「修改昵称」对其他成员即时生效）──

app.post('/api/users/me/display-name', (req: Request, res: Response) => {
  const authHeader = (req.headers['authorization'] as string) || '';
  const token = authHeader.replace(/^Bearer\s+/i, '');
  const decoded = decodeJwt(token);
  if (!decoded || !decoded.userId) {
    res.status(401).json({ success: false, error: '未授权' });
    return;
  }

  const body = req.body as Record<string, any>;
  const displayName = typeof body?.displayName === 'string' ? body.displayName.trim() : '';
  if (!displayName) {
    res.status(400).json({ success: false, error: '昵称不能为空' });
    return;
  }

  const user = getUserById(decoded.userId);
  if (!user) {
    res.status(404).json({ success: false, error: '用户不存在' });
    return;
  }

  user.displayName = displayName;
  console.log(`[API] display-name updated: ${user.id} -> ${user.displayName}`);
  scheduleSave();
  res.json({ success: true, userId: user.id, displayName: user.displayName });
});

// ── 上传头像（base64 JSON，服务端转存为 /avatars/<userId>.jpg）──
app.post('/api/users/me/avatar', (req: Request, res: Response) => {
  const authHeader = (req.headers['authorization'] as string) || '';
  const token = authHeader.replace(/^Bearer\s+/i, '');
  const decoded = decodeJwt(token);
  if (!decoded || !decoded.userId) {
    res.status(401).json({ success: false, error: '未授权' });
    return;
  }

  const body = req.body as Record<string, any>;
  const avatarBase64 = typeof body?.avatar === 'string' ? body.avatar : '';
  const mime = typeof body?.mime === 'string' ? body.mime : 'image/jpeg';
  if (!avatarBase64) {
    res.status(400).json({ success: false, error: '缺少头像数据' });
    return;
  }
  if (!/^image\//.test(mime)) {
    res.status(400).json({ success: false, error: '仅支持图片格式' });
    return;
  }

  let buf: Buffer;
  try {
    buf = Buffer.from(avatarBase64, 'base64');
  } catch (_) {
    res.status(400).json({ success: false, error: '头像数据无效' });
    return;
  }
  if (buf.length > 2 * 1024 * 1024) {
    res.status(413).json({ success: false, error: '头像过大（解码后 ≤ 2MB）' });
    return;
  }

  const user = getUserById(decoded.userId);
  if (!user) {
    res.status(404).json({ success: false, error: '用户不存在' });
    return;
  }

  try {
    const filePath = path.join(AVATAR_DIR, `${user.id}.jpg`);
    fs.writeFileSync(filePath, buf);
    user.avatarUrl = `/avatars/${user.id}.jpg`;
    console.log(`[AVATAR] updated: ${user.id} -> ${user.avatarUrl} (${buf.length} bytes)`);
    scheduleSave();
    res.json({ success: true, userId: user.id, avatarUrl: user.avatarUrl });
  } catch (_) {
    res.status(500).json({ success: false, error: '头像保存失败' });
  }
});

// ── 退出登录（使当前 deviceToken 失效，可选）──
app.post('/api/logout', (req: Request, res: Response) => {
  const decoded = decodeJwt((req.headers['authorization'] || '').replace(/^Bearer\s+/i, ''));
  if (!decoded || !decoded.userId) {
    res.status(401).json({ success: false, error: '未授权' });
    return;
  }
  // 移除在线连接（若有）
  const key = Array.from(connectedClients.keys()).find(k => k.startsWith(`${decoded.userId}:`));
  if (key) {
    connectedClients.get(key)?.ws.close(4000, 'Logged out');
    connectedClients.delete(key);
  }
  res.json({ success: true });
});

// ── WebSocket 服务 ──

const wss = new WebSocketServer({
  server,
  path: '/ws/push'
});

wss.on('connection', (ws: ExtWebSocket, req: http.IncomingMessage) => {
  const urlStr = req.url || '';
  const url = new URL(urlStr, `http://${HOST}`);
  const token = url.searchParams.get('token') || '';

  const decoded = decodeJwt(token);
  if (!decoded || !decoded.userId || !decoded.deviceId) {
    ws.close(4001, 'Invalid or missing token');
    return;
  }

  const { userId, deviceId } = decoded;
  const clientKey = `${userId}:${deviceId}`;
  ws.clientUserId = userId;
  ws.clientDeviceId = deviceId;

  // 踢掉旧连接
  const existingKey = Array.from(connectedClients.keys()).find(
    k => k.startsWith(`${userId}:`)
  );
  if (existingKey) {
    const existing = connectedClients.get(existingKey)!;
    existing.ws.close(4002, 'New device connected');
    connectedClients.delete(existingKey);
  }

  const client: ClientConnection = {
    userId,
    deviceId,
    ws,
    connectedAt: new Date(),
    lastPingAt: new Date()
  };
  connectedClients.set(clientKey, client);

  console.log(`[WS] Client connected: ${userId} (${deviceId}), total: ${connectedClients.size}`);

  ws.send(JSON.stringify({
    type: 'connected',
    data: { userId, message: '连接成功' }
  }));

  // 推送离线消息
  const pendingMessages = offlineMessages.get(userId) || [];
  if (pendingMessages.length > 0) {
    ws.send(JSON.stringify({
      type: 'offline_sync',
      data: { messages: pendingMessages }
    }));
    offlineMessages.delete(userId);
  }

  // 推送离线已读回执（对方在读消息后离线，上线后告知本端哪些消息已被读）
  const pendingRR = pendingReadReceipts.get(userId) || [];
  if (pendingRR.length > 0) {
    pendingRR.forEach(rr => {
      ws.send(JSON.stringify({
        type: 'read_receipt',
        data: { conversationId: rr.conversationId, readerId: rr.readerId }
      }));
    });
    pendingReadReceipts.delete(userId);
  }

  // 心跳
  ws.isAlive = true;
  ws.on('pong', () => {
    client.lastPingAt = new Date();
    ws.isAlive = true;
  });

  // 消息
  ws.on('message', (data: Buffer | string) => {
    try {
      const msg = JSON.parse(data.toString());

      switch (msg.type) {
        case 'pong':
          client.lastPingAt = new Date();
          ws.isAlive = true;
          break;
        case 'message_ack':
          console.log(`[ACK] ${userId} acknowledged message`);
          break;
        case 'read_receipt': {
          // 收件人已读：通知消息发送方（senderId）其发出的消息已被读
          const conversationId = msg.conversationId as string;
          const readerId = msg.readerId as string;
          const senderId = msg.senderId as string;
          if (senderId && conversationId) {
            forwardReadReceipt(senderId, conversationId, readerId || userId);
          }
          break;
        }
        case 'call_signal': {
          // WebRTC 信令转发：读出 data.to，原样转发给目标；
          // 目标离线/不存在则向发起方回 call_missed。
          // 以鉴权身份覆盖 from，防止客户端伪造来源。
          const data = (msg.data || {}) as Record<string, any>;
          const to = data.to as string | undefined;
          if (!to) {
            console.warn('[CALL] call_signal missing data.to, ignored');
            break;
          }
          data.from = userId;

          const targetKey = Array.from(connectedClients.keys()).find(
            k => k.startsWith(`${to}:`)
          );
          const target = targetKey ? connectedClients.get(targetKey)! : null;

          if (target && target.ws.readyState === 1 /* OPEN */) {
            target.ws.send(JSON.stringify({ type: 'call_signal', data }));
            console.log(`[CALL] signal ${data.action} ${userId} -> ${to}`);
          } else {
            ws.send(JSON.stringify({
              type: 'call_missed',
              data: { callId: data.callId, to }
            }));
            console.log(`[CALL] target ${to} offline/unreachable, call_missed -> ${userId}`);
          }
          break;
        }
      }
    } catch (e) {
      console.error('[WS] Failed to parse message:', e);
    }
  });

  // 关闭
  ws.on('close', (code: number, reason: Buffer) => {
    // 仅当当前注册的仍是本连接时才删除，避免旧连接延迟关闭时误删新连接（同 deviceId 重连场景）
    if (connectedClients.get(clientKey)?.ws === ws) {
      connectedClients.delete(clientKey);
    }
    console.log(`[WS] Client disconnected: ${userId}, reason: ${reason.toString()}`);
  });

  ws.on('error', (error: Error) => {
    console.error(`[WS] Error for ${userId}:`, error.message);
    if (connectedClients.get(clientKey)?.ws === ws) {
      connectedClients.delete(clientKey);
    }
  });
});

// ── 心跳检测 ──

const heartbeatInterval = setInterval(() => {
  connectedClients.forEach((client, key) => {
    if ((client.ws as ExtWebSocket).isAlive === false) {
      console.log(`[Heartbeat] Killing stale: ${key}`);
      client.ws.terminate();
      connectedClients.delete(key);
      return;
    }
    (client.ws as ExtWebSocket).isAlive = false;
    client.ws.ping();
  });
}, 30000);

// ── 数据保留：聊天记录 1 天后自动删除 ──

const retentionInterval = setInterval(() => {
  sweepRetention();
}, 60 * 60 * 1000);

// ── 优雅关闭 ──

process.on('SIGTERM', () => {
  console.log('[Shutdown] Shutting down gracefully...');
  clearInterval(heartbeatInterval);
  clearInterval(retentionInterval);
  if (messagePersistInterval) clearInterval(messagePersistInterval);
  persistMessagesNow();
  wss.close(() => {
    server.close(() => {
      console.log('[Shutdown] Server closed.');
      process.exit(0);
    });
  });
});

// ════════════════════════════════════════
// 管理后台（Web Admin Console）
// ════════════════════════════════════════

const ADMIN_DIR = path.join(__dirname, '..', 'public', 'admin');

// 静态后台页面（登录页 + SPA 都在 public/admin/index.html）
app.use('/admin', express.static(ADMIN_DIR, { index: 'index.html' }));

// 解析 Cookie（sc_admin 会话令牌），供后台鉴权使用
app.use((req: Request, _res, next) => {
  const raw = (req.headers['cookie'] as string) || '';
  const cookies: Record<string, string> = {};
  raw.split(';').forEach(c => {
    const idx = c.indexOf('=');
    if (idx > 0) {
      const k = c.slice(0, idx).trim();
      const v = c.slice(idx + 1).trim();
      cookies[k] = decodeURIComponent(v);
    }
  });
  (req as any).cookies = cookies;
  next();
});

function requireAdmin(req: Request, res: Response, next: any) {
  const token = (req as any).cookies?.sc_admin || (req.headers['x-admin-token'] as string) || '';
  if (!verifyAdminToken(token)) {
    res.status(401).json({ success: false, error: '未授权，请先登录' });
    return;
  }
  next();
}

// ── 登录 / 登出 / 改密 ──
app.post('/admin/api/login', (req: Request, res: Response) => {
  const { username, password } = req.body as Record<string, any>;
  if (username !== config.adminUsername || !verifyPassword(password || '', config.adminPasswordHash, config.adminPasswordSalt)) {
    res.status(401).json({ success: false, error: '管理员账号或密码错误' });
    return;
  }
  const token = signAdminToken();
  res.set('Set-Cookie', `sc_admin=${token}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${60 * 60 * 24 * 7}`);
  logAudit('admin_login', `管理员 ${username} 登录成功`);
  res.json({ success: true, mustReset: config.adminMustReset });
});

app.post('/admin/api/logout', (_req: Request, res: Response) => {
  logAudit('admin_logout', '管理员登出');
  res.set('Set-Cookie', 'sc_admin=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0');
  res.json({ success: true });
});

app.post('/admin/api/reset-password', (req: Request, res: Response) => {
  const token = (req as any).cookies?.sc_admin || '';
  if (!verifyAdminToken(token)) {
    res.status(401).json({ success: false, error: '未授权' });
    return;
  }
  const { oldPassword, newPassword } = req.body as Record<string, any>;
  if (!verifyPassword(oldPassword || '', config.adminPasswordHash, config.adminPasswordSalt)) {
    res.status(400).json({ success: false, error: '原密码错误' });
    return;
  }
  if (!newPassword || newPassword.length < 6) {
    res.status(400).json({ success: false, error: '新密码至少 6 位' });
    return;
  }
  const { hash, salt } = hashPassword(newPassword);
  config.adminPasswordHash = hash;
  config.adminPasswordSalt = salt;
  config.adminMustReset = false;
  saveConfig();
  logAudit('admin_reset_password', '管理员修改了后台密码');
  const newToken = signAdminToken();
  res.set('Set-Cookie', `sc_admin=${newToken}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${60 * 60 * 24 * 7}`);
  res.json({ success: true });
});

// ── 配置（域名绑定 / 保留天数 / 服务端口）──
app.get('/admin/api/config', requireAdmin, (_req: Request, res: Response) => {
  res.json({
    success: true,
    serverDomain: config.serverDomain,
    retentionDays: config.retentionDays,
    serverPort: config.serverPort || PORT,
    adminUsername: config.adminUsername,
    adminMustReset: config.adminMustReset
  });
});

app.post('/admin/api/config', requireAdmin, (req: Request, res: Response) => {
  const { serverDomain, retentionDays, serverPort } = req.body as Record<string, any>;
  let portChanged = false;
  if (serverDomain !== undefined && typeof serverDomain === 'string') {
    config.serverDomain = serverDomain.trim();
  }
  if (retentionDays != null) {
    const days = parseInt(String(retentionDays), 10);
    if (!isNaN(days)) config.retentionDays = Math.max(1, Math.min(365, days));
  }
  if (serverPort != null) {
    const p = parseInt(String(serverPort), 10);
    if (!isNaN(p) && p > 0 && p <= 65535 && p !== (config.serverPort || PORT)) {
      config.serverPort = p;
      portChanged = true;
    }
  }
  saveConfig();
  logAudit('config_update', `domain=${config.serverDomain} retention=${config.retentionDays}d port=${config.serverPort || PORT}`);
  if (portChanged) {
    res.json({ success: true, restarted: true, port: config.serverPort });
    // 给响应刷新一点时间后退出，看门狗会以新端口重启进程
    setTimeout(() => process.exit(0), 400);
    return;
  }
  res.json({ success: true, serverDomain: config.serverDomain, retentionDays: config.retentionDays, serverPort: config.serverPort || PORT });
});

// ── 概览统计 ──
function countMessages(): number {
  let n = 0;
  messageStore.forEach(a => n += a.length);
  offlineMessages.forEach(a => n += a.length);
  return n;
}

app.get('/admin/api/stats', requireAdmin, (_req: Request, res: Response) => {
  const memMB = process.memoryUsage().heapUsed / 1024 / 1024;
  res.json({
    success: true,
    uptime: process.uptime(),
    onlineClients: connectedClients.size,
    userCount: USERS.size,
    messageCount: countMessages(),
    retentionDays: config.retentionDays,
    serverDomain: config.serverDomain,
    memoryMB: Math.round(memMB * 10) / 10,
    version: process.env.npm_package_version || '1.0.0',
    host: HOST,
    port: PORT
  });
});

// ── OTA 版本信息 ──
app.get('/admin/api/ota', requireAdmin, (_req: Request, res: Response) => {
  try {
    if (!fs.existsSync(UPDATE_JSON_PATH)) {
      res.json({ success: true, exists: false });
      return;
    }
    const info = JSON.parse(fs.readFileSync(UPDATE_JSON_PATH, 'utf-8'));
    res.json({ success: true, exists: true, ...info });
  } catch (e: any) {
    res.status(500).json({ success: false, error: e?.message });
  }
});

// ── 用户管理 ──
function isOnline(userId: string): boolean {
  return !!Array.from(connectedClients.keys()).find(k => k.startsWith(`${userId}:`));
}

app.get('/admin/api/users', requireAdmin, (_req: Request, res: Response) => {
  const users = Array.from(USERS.values()).map(u => ({
    id: u.id,
    username: u.username,
    displayName: u.displayName,
    enabled: u.enabled !== false,
    createdAt: u.createdAt || 0,
    online: isOnline(u.id),
    friendCount: friendships.get(u.id)?.size || 0,
    hasKey: !!u.publicKey,
    avatarUrl: u.avatarUrl
  }));
  res.json({ success: true, users });
});

app.post('/admin/api/users', requireAdmin, (req: Request, res: Response) => {
  const { username, password, displayName } = req.body as Record<string, any>;
  if (!username || !password || !displayName) {
    res.status(400).json({ success: false, error: '缺少用户名/密码/昵称' });
    return;
  }
  if (usernameIndex.has(username)) {
    res.status(409).json({ success: false, error: '用户名已存在' });
    return;
  }
  const id = `user-${++userCounter}`;
  USERS.set(id, { id, username, password, displayName: String(displayName).trim(), publicKey: '', enabled: true, createdAt: Date.now() });
  usernameIndex.set(username, id);
  scheduleSave();
  logAudit('user_create', `创建用户 ${username} (${id})`);
  res.json({ success: true, userId: id });
});

app.post('/admin/api/users/:id/disable', requireAdmin, (req: Request, res: Response) => {
  const u = getUserById(req.params.id);
  if (!u) { res.status(404).json({ success: false, error: '用户不存在' }); return; }
  u.enabled = false;
  const key = Array.from(connectedClients.keys()).find(k => k.startsWith(`${u.id}:`));
  if (key) { connectedClients.get(key)?.ws.close(4003, 'Disabled by admin'); connectedClients.delete(key); }
  scheduleSave();
  logAudit('user_disable', `禁用用户 ${u.username} (${u.id})`);
  res.json({ success: true });
});

app.post('/admin/api/users/:id/enable', requireAdmin, (req: Request, res: Response) => {
  const u = getUserById(req.params.id);
  if (!u) { res.status(404).json({ success: false, error: '用户不存在' }); return; }
  u.enabled = true;
  scheduleSave();
  logAudit('user_enable', `启用用户 ${u.username} (${u.id})`);
  res.json({ success: true });
});

app.post('/admin/api/users/:id/reset-password', requireAdmin, (req: Request, res: Response) => {
  const u = getUserById(req.params.id);
  if (!u) { res.status(404).json({ success: false, error: '用户不存在' }); return; }
  const { newPassword } = req.body as Record<string, any>;
  if (!newPassword || newPassword.length < 6) { res.status(400).json({ success: false, error: '新密码至少 6 位' }); return; }
  u.password = newPassword;
  scheduleSave();
  logAudit('user_reset_password', `重置用户密码 ${u.username} (${u.id})`);
  res.json({ success: true });
});

app.post('/admin/api/users/:id/delete', requireAdmin, (req: Request, res: Response) => {
  const u = getUserById(req.params.id);
  if (!u) { res.status(404).json({ success: false, error: '用户不存在' }); return; }
  usernameIndex.delete(u.username);
  USERS.delete(u.id);
  friendships.delete(u.id);
  friendships.forEach(s => s.delete(u.id));
  offlineMessages.delete(u.id);
  messageStore.forEach((arr, cid) => {
    const kept = arr.filter(m => m.senderId !== u.id && m.recipientId !== u.id);
    if (kept.length > 0) messageStore.set(cid, kept); else messageStore.delete(cid);
  });
  const key = Array.from(connectedClients.keys()).find(k => k.startsWith(`${u.id}:`));
  if (key) { connectedClients.get(key)?.ws.close(4004, 'Deleted by admin'); connectedClients.delete(key); }
  scheduleSave();
  persistMessagesNow();
  logAudit('user_delete', `删除用户 ${u.username} (${u.id})`);
  res.json({ success: true });
});

// ── 消息记录（只读查看，服务端仅存密文，不在后台解密）──
app.get('/admin/api/messages', requireAdmin, (req: Request, res: Response) => {
  const page = Math.max(0, parseInt(req.query.page as string || '0', 10));
  const size = Math.min(200, parseInt(req.query.size as string || '50', 10));
  const q = (req.query.q as string || '').toLowerCase();
  let all: OfflineMessage[] = [];
  messageStore.forEach(arr => all.push(...arr));
  offlineMessages.forEach(arr => all.push(...arr));
  all.sort((a, b) => b.timestamp - a.timestamp);
  if (q) {
    all = all.filter(m =>
      (m.senderId || '').toLowerCase().includes(q) ||
      (m.recipientId || '').toLowerCase().includes(q) ||
      (m.conversationId || '').toLowerCase().includes(q)
    );
  }
  const total = all.length;
  const slice = all.slice(page * size, page * size + size);
  res.json({
    success: true,
    total,
    page,
    size,
    messages: slice.map(m => ({
      id: m.id,
      senderId: m.senderId,
      recipientId: m.recipientId,
      conversationId: m.conversationId,
      timestamp: m.timestamp,
      messageType: m.messageType || 'TEXT',
      encrypted: true,
      preview: (m.encryptedPayload || '').slice(0, 64)
    }))
  });
});

// ── 立即清理超出保留天数的消息 ──
app.post('/admin/api/messages/purge', requireAdmin, (_req: Request, res: Response) => {
  const before = countMessages();
  sweepRetention();
  const after = countMessages();
  logAudit('messages_purge', `手动清理 ${before - after} 条过期消息，剩余 ${after} 条`);
  res.json({ success: true, removed: before - after, remaining: after });
});

// ── 操作审计日志 ──
app.get('/admin/api/audit', requireAdmin, (req: Request, res: Response) => {
  const limit = Math.min(200, Math.max(1, parseInt(req.query.limit as string || '50', 10)));
  const offset = Math.max(0, parseInt(req.query.offset as string || '0', 10));
  const action = (req.query.action as string || '').trim();
  const list = getAudit(limit, offset, action);
  res.json({ success: true, entries: list, limit, offset });
});

// ── 系统公告（广播）──
app.get('/admin/api/announcements', requireAdmin, (_req: Request, res: Response) => {
  const list = loadAnnouncements().slice().reverse();
  res.json({ success: true, announcements: list });
});

app.post('/admin/api/announcement', requireAdmin, (req: Request, res: Response) => {
  const { title, content } = req.body as Record<string, any>;
  if (!title || !content || !String(title).trim() || !String(content).trim()) {
    res.status(400).json({ success: false, error: '标题与内容均不能为空' });
    return;
  }
  const arr = loadAnnouncements();
  const ann = {
    id: `ann-${Date.now()}`,
    title: String(title).trim(),
    content: String(content).trim(),
    timestamp: Date.now()
  };
  arr.push(ann);
  if (arr.length > 50) arr.shift(); // 仅保留最近 50 条
  saveAnnouncements(arr);
  logAudit('announcement_post', `发布系统公告「${ann.title}」`);
  res.json({ success: true, announcement: ann });
});

app.delete('/admin/api/announcement/:id', requireAdmin, (req: Request, res: Response) => {
  const arr = loadAnnouncements();
  const idx = arr.findIndex(a => a.id === req.params.id);
  if (idx < 0) { res.status(404).json({ success: false, error: '公告不存在' }); return; }
  const removed = arr[idx];
  arr.splice(idx, 1);
  saveAnnouncements(arr);
  logAudit('announcement_delete', `删除系统公告「${removed.title}」`);
  res.json({ success: true });
});

// ── 启动服务 ──

loadState();
loadConfig();
loadMessages();
messagePersistInterval = setInterval(persistMessagesNow, 5 * 60 * 1000);

// 应用配置中的监听端口（若与默认不同）
if (config.serverPort && config.serverPort > 0) {
  PORT = config.serverPort;
}

function startServer() {
  server.on('error', (err: any) => {
    if ((err.code === 'EADDRINUSE' || err.code === 'EACCES') && PORT !== 8080) {
      console.warn(`[Server] Port ${PORT} unavailable (${err.code}), falling back to 8080`);
      PORT = 8080;
      config.serverPort = 8080;
      saveConfig();
      // 移除旧 error 监听，避免递归叠加
      server.removeAllListeners('error');
      startServer();
    } else {
      console.error('[Server] fatal listen error', err);
      process.exit(1);
    }
  });
  server.listen(PORT, HOST, () => {
    console.log(`[SecureChat Server] Listening on http://${HOST}:${PORT}`);
    console.log(`[SecureChat Server] WS: ws://${HOST}:${PORT}/ws/push`);
    console.log(`[SecureChat Server] Admin console: http://${HOST}:${PORT}/admin`);
  });
}
startServer();

// ════════════════════════════════════════
// 工具函数
// ════════════════════════════════════════

function resolveUser(identifier: string): User | null {
  if (!identifier) return null;
  // 先按 userId 精确匹配
  const byId = USERS.get(identifier);
  if (byId) return byId;
  // 再按 username 匹配
  const byName = usernameIndex.get(identifier);
  if (byName) return USERS.get(byName) || null;
  return null;
}

function validateCredentials(username: string, password: string): User | null {
  const id = usernameIndex.get(username);
  if (!id) return null;
  const user = USERS.get(id)!;
  if (user.enabled === false) return null;
  return user.password === password ? user : null;
}

function getUserById(userId: string): User | null {
  return USERS.get(userId) || null;
}

function getFriends(userId: string): User[] {
  const ids = friendships.get(userId);
  if (!ids) return [];
  return Array.from(ids).map(id => USERS.get(id)).filter(Boolean as any) as User[];
}

function addFriendMutual(a: string, b: string): void {
  if (!friendships.has(a)) friendships.set(a, new Set());
  if (!friendships.has(b)) friendships.set(b, new Set());
  friendships.get(a)!.add(b);
  friendships.get(b)!.add(a);
}

function removeFriendMutual(a: string, b: string): void {
  friendships.get(a)?.delete(b);
  friendships.get(b)?.delete(a);
}

// 已读回执转发：把「收件人已读」通知消息的发送方。
// 发送方在线则实时推送；否则存入 pendingReadReceipts，待其上线时下发。
function forwardReadReceipt(senderId: string, conversationId: string, readerId: string): void {
  const key = Array.from(connectedClients.keys()).find(k => k.startsWith(`${senderId}:`));
  if (key) {
    const c = connectedClients.get(key)!;
    if (c.ws.readyState === 1 /* OPEN */) {
      c.ws.send(JSON.stringify({
        type: 'read_receipt',
        data: { conversationId, readerId }
      }));
      return;
    }
  }
  const arr = pendingReadReceipts.get(senderId) || [];
  arr.push({ conversationId, readerId, ts: Date.now() });
  pendingReadReceipts.set(senderId, arr);
}

function generateJwt(payload: { userId: string, deviceId: string }): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = btoa(JSON.stringify({ ...payload, exp: Math.floor(Date.now() / 1000) + 86400 }));
  const signature = btoa('signature_placeholder');
  return `${header}.${body}.${signature}`;
}

function decodeJwt(token: string): { userId: string, deviceId: string } | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    return JSON.parse(atob(parts[1]));
  } catch {
    return null;
  }
}

function generateDeviceToken(deviceId: string, userId: string): string {
  return `dev_${uuidv4()}`;
}
