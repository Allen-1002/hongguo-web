import fs from "node:fs";
import path from "node:path";
import { fail } from "./errors.mjs";

// 手机 App 模式（MOBILE=1）的个人数据存储：单用户、免登录、纯 JSON 文件。
// 接口与 UserStore（SQLite）保持一致，http.mjs / recommend.mjs 不需要区分。
// 用它而不是 node:sqlite 的原因：手机内嵌的 nodejs-mobile 是 Node 18，没有 node:sqlite。
//
// 安全边界：App 模式只监听 127.0.0.1，且所有 /api/* 已由全局 TOKEN（App 每次安装随机生成）把关，
// 因此这里任何非空用户令牌都视为本机唯一用户。

const RATES = [0.5, 0.75, 1, 1.25, 1.5, 2];
const HISTORY_MAX = 200;
const LOCAL_USER = { id: 1, username: "本机" };

function str(value, max = 200) {
  return String(value == null ? "" : value).slice(0, max);
}
function num(value, def = 0) {
  const n = Number(value);
  return Number.isFinite(n) ? n : def;
}
function keyOf(source, id) {
  return str(source, 64) + "|" + str(id, 128);
}

function emptyData() {
  return { version: 1, history: [], favorites: [], follows: [], prefs: { rate: null, updatedAt: 0 } };
}

function normalize(raw) {
  const data = emptyData();
  if (!raw || typeof raw !== "object") return data;
  for (const k of ["history", "favorites", "follows"]) if (Array.isArray(raw[k])) data[k] = raw[k].filter((x) => x && x.id);
  if (raw.prefs && typeof raw.prefs === "object") {
    const rate = num(raw.prefs.rate, NaN);
    data.prefs = { rate: RATES.indexOf(rate) === -1 ? null : rate, updatedAt: num(raw.prefs.updatedAt) };
  }
  return data;
}

export class LocalUserStore {
  constructor(dataDir) {
    fs.mkdirSync(dataDir, { recursive: true });
    this.file = path.join(dataDir, "local-user.json");
    this.data = emptyData();
    this.timer = null;
    try {
      this.data = normalize(JSON.parse(fs.readFileSync(this.file, "utf8")));
    } catch (error) {
      if (error && error.code !== "ENOENT") {
        // 文件损坏时保留一份现场，避免下一次写入把它彻底覆盖。
        try { fs.renameSync(this.file, this.file + ".broken-" + Date.now()); } catch {}
        console.error(JSON.stringify({ event: "local_store_corrupt", message: String((error && error.message) || error) }));
      }
    }
  }

  // ---- 持久化：合并 300ms 内的多次写入，原子替换文件 ----
  save() {
    if (this.timer) return;
    this.timer = setTimeout(() => {
      this.timer = null;
      this.flush();
    }, 300);
  }
  flush() {
    if (this.timer) { clearTimeout(this.timer); this.timer = null; }
    const tmp = this.file + ".tmp";
    try {
      fs.writeFileSync(tmp, JSON.stringify(this.data));
      fs.renameSync(tmp, this.file);
    } catch (error) {
      console.error(JSON.stringify({ event: "local_store_write_failed", message: String((error && error.message) || error) }));
    }
  }
  close() { this.flush(); }

  // ---- 账号：本机单用户，注册 / 登录都直接返回本机用户 ----
  register() { return { token: "local", user: { ...LOCAL_USER } }; }
  login() { return { token: "local", user: { ...LOCAL_USER } }; }
  issueToken() { return "local"; }
  auth(token) {
    return String(token || "").trim() ? { ...LOCAL_USER, token: "local" } : null;
  }
  logout() { return { ok: true }; }

  // ---- 观看历史 ----
  listHistory(_userId, limit = 100) {
    const n = Math.min(HISTORY_MAX, Math.max(1, Math.floor(Number(limit) || 100)));
    return [...this.data.history].sort((a, b) => b.at - a.at).slice(0, n).map((r) => ({ ...r }));
  }

  allHistory() {
    return [{ userId: LOCAL_USER.id, items: this.listHistory(LOCAL_USER.id, HISTORY_MAX) }];
  }

  putHistory(_userId, item) {
    const vodId = str(item.id, 128);
    if (!vodId) throw fail("BAD_INPUT", "缺少剧集 id");
    const rawAt = num(item.at, NaN);
    const at = Number.isFinite(rawAt)
      ? Math.min(Date.now() + 5 * 60 * 1000, Math.max(0, Math.floor(rawAt)))
      : Date.now();
    const row = {
      source: str(item.source, 64), id: vodId, title: str(item.title), cover: str(item.cover, 500),
      lineIdx: Math.max(0, Math.floor(num(item.lineIdx))), lineName: str(item.lineName),
      epIdx: Math.max(0, Math.floor(num(item.epIdx))), epName: str(item.epName),
      pos: Math.max(0, num(item.pos)), dur: Math.max(0, num(item.dur)), at,
    };
    const k = keyOf(row.source, row.id);
    const i = this.data.history.findIndex((r) => keyOf(r.source, r.id) === k);
    // 与 SQLite 版一致：旧进度不覆盖新进度。
    if (i !== -1 && this.data.history[i].at > at) return { ok: true };
    if (i !== -1) this.data.history.splice(i, 1);
    this.data.history.unshift(row);
    if (this.data.history.length > HISTORY_MAX) this.data.history.length = HISTORY_MAX;
    this.save();
    return { ok: true };
  }

  deleteHistory(_userId, source, vodId) {
    const k = keyOf(source, vodId);
    this.data.history = this.data.history.filter((r) => keyOf(r.source, r.id) !== k);
    this.save();
    return { ok: true };
  }

  clearHistory() {
    this.data.history = [];
    this.save();
    return { ok: true };
  }

  // ---- 收藏 ----
  listFavorites() {
    return [...this.data.favorites].sort((a, b) => b.at - a.at).map((r) => ({ ...r }));
  }

  addFavorite(_userId, item) {
    const vodId = str(item.id, 128);
    if (!vodId) throw fail("BAD_INPUT", "缺少剧集 id");
    const source = str(item.source, 64);
    const k = keyOf(source, vodId);
    const prev = this.data.favorites.find((r) => keyOf(r.source, r.id) === k);
    const row = { source, id: vodId, title: str(item.title), cover: str(item.cover, 500), remarks: str(item.remarks), at: prev ? prev.at : Date.now() };
    this.data.favorites = this.data.favorites.filter((r) => keyOf(r.source, r.id) !== k);
    this.data.favorites.push(row);
    this.save();
    return { ok: true };
  }

  removeFavorite(_userId, source, vodId) {
    const k = keyOf(source, vodId);
    this.data.favorites = this.data.favorites.filter((r) => keyOf(r.source, r.id) !== k);
    this.save();
    return { ok: true };
  }

  // ---- 追剧 ----
  listFollows() {
    return [...this.data.follows].sort((a, b) => b.at - a.at).map((r) => ({
      source: r.source, id: r.id, title: r.title, cover: r.cover,
      epCount: Number(r.epCount) || 0, lastChecked: Number(r.lastChecked) || 0, at: r.at,
    }));
  }

  addFollow(_userId, item) {
    const vodId = str(item.id, 128);
    if (!vodId) throw fail("BAD_INPUT", "缺少剧集 id");
    const source = str(item.source, 64);
    const k = keyOf(source, vodId);
    const prev = this.data.follows.find((r) => keyOf(r.source, r.id) === k);
    if (prev) {
      prev.title = str(item.title);
      prev.cover = str(item.cover, 500);
    } else {
      this.data.follows.push({ source, id: vodId, title: str(item.title), cover: str(item.cover, 500), epCount: 0, lastChecked: 0, at: Date.now() });
    }
    this.save();
    return { ok: true };
  }

  removeFollow(_userId, source, vodId) {
    const k = keyOf(source, vodId);
    this.data.follows = this.data.follows.filter((r) => keyOf(r.source, r.id) !== k);
    this.save();
    return { ok: true };
  }

  touchFollow(_userId, source, vodId, epCount) {
    const k = keyOf(source, vodId);
    const row = this.data.follows.find((r) => keyOf(r.source, r.id) === k);
    if (!row) return;
    row.epCount = Math.max(0, Math.floor(epCount));
    row.lastChecked = Date.now();
    this.save();
  }

  // ---- 偏好 ----
  getPrefs() {
    return { ...this.data.prefs };
  }

  setPrefs(_userId, item) {
    const rate = num(item && item.rate, NaN);
    if (!Number.isFinite(rate) || RATES.indexOf(rate) === -1)
      throw fail("BAD_INPUT", "倍速取值无效（0.5 / 0.75 / 1 / 1.25 / 1.5 / 2）");
    this.data.prefs = { rate, updatedAt: Date.now() };
    this.save();
    return { ...this.data.prefs };
  }

  // ---- 备份：导出 / 导入（App 里没有云同步，靠这个换机或重装） ----
  exportData() {
    return { app: "hongguo-web", exportedAt: Date.now(), ...JSON.parse(JSON.stringify(this.data)) };
  }

  importData(raw) {
    if (!raw || typeof raw !== "object") throw fail("BAD_INPUT", "备份文件格式不对");
    const incoming = normalize(raw);
    // 合并而不是覆盖：同一部剧按时间取新。
    for (const row of incoming.history) this.putHistory(LOCAL_USER.id, row);
    for (const row of incoming.favorites) {
      const k = keyOf(row.source, row.id);
      if (!this.data.favorites.some((r) => keyOf(r.source, r.id) === k)) this.data.favorites.push({ ...row, at: num(row.at, Date.now()) });
    }
    for (const row of incoming.follows) {
      const k = keyOf(row.source, row.id);
      if (!this.data.follows.some((r) => keyOf(r.source, r.id) === k)) this.data.follows.push({ ...row, at: num(row.at, Date.now()) });
    }
    if (incoming.prefs.rate != null && incoming.prefs.updatedAt > this.data.prefs.updatedAt) this.data.prefs = incoming.prefs;
    this.save();
    return {
      ok: true,
      counts: { history: incoming.history.length, favorites: incoming.favorites.length, follows: incoming.follows.length },
    };
  }
}
