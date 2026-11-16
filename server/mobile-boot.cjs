// 手机 App 的 Node 入口（android/NodeRunner 启动的就是这个文件）。
//
// 1. 把 Node 的 stdout / stderr 同时写进 HONGGUO_LOG_FILE：nodejs-mobile 自己会把 stdio 接到 logcat，
//    App 内的「诊断页」读不到 logcat，只能读这个文件。
// 2. 用 CommonJS 再 import() 真正的入口：index.mjs 里任何模块加载失败（缺依赖等）都在这里接住，
//    而不是让 Node 退出——Node 和 App 同一个进程，退出就是闪退。
// 3. 加载失败时在同一端口起一个只显示错误的页面，WebView 打开就能看到原因。
"use strict";

const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");

const logFile = process.env.HONGGUO_LOG_FILE || "";
let logFd = null;
if (logFile) {
  try { logFd = fs.openSync(logFile, "a"); } catch { logFd = null; }
}
function mirror(stream) {
  const original = stream.write.bind(stream);
  stream.write = function (chunk, encoding, cb) {
    if (logFd !== null) {
      try { fs.writeSync(logFd, typeof chunk === "string" ? chunk : Buffer.from(chunk)); } catch {}
    }
    return original(chunk, encoding, cb);
  };
}
mirror(process.stdout);
mirror(process.stderr);

console.log(JSON.stringify({ event: "mobile_boot", node: process.version, arch: process.arch, platform: process.platform }));

function showError(error) {
  const text = String((error && error.stack) || error);
  console.error(JSON.stringify({ event: "boot_failed", message: text }));
  const port = Number(process.env.PORT || 38787);
  const html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
    "<body style='background:#000;color:#ddd;font:14px/1.5 sans-serif;padding:24px'>" +
    "<h3>本地服务启动失败</h3><p>请把下面的内容发给开发者：</p>" +
    "<pre style='white-space:pre-wrap;font-size:12px;color:#f88'>" +
    text.replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" })[c]) + "</pre></body>";
  http.createServer((req, res) => {
    if (req.url && req.url.startsWith("/health")) {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ ok: false, bootFailed: true }));
      return;
    }
    res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
    res.end(html);
  }).listen(port, process.env.HOST || "127.0.0.1");
}

import(path.join(__dirname, "index.mjs")).catch(showError);
