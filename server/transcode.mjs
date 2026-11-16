// H.266/VVC 服务端转码：浏览器的解码器里没有 VVC（Chrome/Edge/Firefox 都没有），
// 所以红果 360P–720P 这几档官方 VVC 流送进 <video> 只能黑屏。
// 这里把服务端已解密的 VVC 分片重排为 Annex B，交给 ffmpeg 转成 H.264；
// 封装由 hls-tc.mjs 用 fMP4 分片（HLS）完成，播放器因此有真实时长、可拖动进度。
//
// 上游的 VVC 文件有两个坑（实测）：
//   1. 采样描述里的 4CC 写的是 hvc1（HEVC），实际码流是 VVC → 必须自己按 stsz/stco/stsc 剥出 NAL；
//   2. 配置盒是 bv2C（非标准），ffmpeg 会当成 extradata 解析失败 → 只取码流，不依赖容器。
// 音轨另存一份“去掉视频轨”的 MP4 让 ffmpeg 直接读，避免自己实现 ADTS 封装。
import { spawn, execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fail } from "./errors.mjs";

const FFMPEG = process.env.FFMPEG_PATH || "ffmpeg";
const MAX_INPUT = Number(process.env.TRANSCODE_MAX_INPUT_BYTES || 96 * 1024 * 1024);
let availability = null;

// ffmpeg 是否存在只在第一次用到时探一次；没有就明确告知前端，而不是给一段黑屏。
export function transcodeAvailable() {
  if (availability !== null) return availability;
  try {
    execFileSync(FFMPEG, ["-hide_banner", "-version"], { timeout: 5000, stdio: "ignore" });
    availability = true;
  } catch {
    availability = false;
  }
  return availability;
}

export function transcodeStats() {
  return { available: transcodeAvailable() };
}

function boxes(buf, start, end) {
  const list = [];
  let off = start;
  while (off + 8 <= end) {
    let size = buf.readUInt32BE(off);
    const type = buf.toString("latin1", off + 4, off + 8);
    let hdr = 8;
    if (size === 1) {
      size = Number(buf.readBigUInt64BE(off + 8));
      hdr = 16;
    } else if (size === 0) size = end - off;
    if (size < 8 || off + size > end) break;
    list.push({ type, start: off, body: off + hdr, size });
    off += size;
  }
  return list;
}

function stblOf(buf, trak) {
  const mdia = boxes(buf, trak.body, trak.start + trak.size).find((b) => b.type === "mdia");
  if (!mdia) return null;
  const minf = boxes(buf, mdia.body, mdia.start + mdia.size).find((b) => b.type === "minf");
  if (!minf) return null;
  return boxes(buf, minf.body, minf.start + minf.size).find((b) => b.type === "stbl") || null;
}

// 解析 moov：分出视频/音频轨，返回重建音轨 MP4 与视频 Annex B 所需的一切。
function analyse(buf) {
  const top = boxes(buf, 0, buf.length);
  const moov = top.find((b) => b.type === "moov");
  if (!moov) throw new Error("不是 MP4（缺 moov）");
  const kids = boxes(buf, moov.body, moov.start + moov.size);
  const traks = kids.filter((b) => b.type === "trak");
  let video = null;
  let audio = null;
  for (const trak of traks) {
    const stbl = stblOf(buf, trak);
    if (!stbl) continue;
    const stsd = boxes(buf, stbl.body, stbl.start + stbl.size).find((b) => b.type === "stsd");
    if (!stsd) continue;
    const entry = buf.toString("latin1", stsd.body + 12, stsd.body + 16);
    if (/^(hvc1|hev1|vvc1|bv2C)$/.test(entry)) video = { trak, stbl, entry };
    else if (/^mp4a$/.test(entry)) audio = { trak, stbl, entry };
  }
  if (!video) throw new Error("找不到视频轨");
  return { moov, kids, video, audio };
}

function audioOnlyMp4(buf, { moov, kids, audio }) {
  if (!audio) return null;
  const keep = kids.filter((k) => k.type !== "trak" || k.start === audio.trak.start);
  const inner = keep.reduce((n, k) => n + k.size, 0);
  // 关键：moov 在 mdat 前面（faststart），音轨的 stco/co64 是**绝对文件偏移**。
  // 去掉视频轨后 moov 变短，mdat 会前移 → 音轨偏移全部指错 → ffmpeg 读到垃圾 AAC
  // （症状：invalid band type / channel element not allocated）。
  // 所以用 free 盒把 moov 总长度补回原值，mdat 位置不变，音轨样本表无需改动。
  const pad = moov.size - (8 + inner);
  if (pad < 8) throw new Error("音轨重建失败：moov 余量不足（" + pad + " 字节）");
  const size = 8 + inner + pad;
  const head = Buffer.alloc(size);
  head.writeUInt32BE(size, 0);
  head.write("moov", 4, "latin1");
  let off = 8;
  for (const k of keep) {
    buf.copy(head, off, k.start, k.start + k.size);
    off += k.size;
  }
  head.writeUInt32BE(pad, off);      // free 盒：垫齐 moov 原始长度
  head.write("free", off + 4, "latin1");
  return Buffer.concat([buf.subarray(0, moov.start), head, buf.subarray(moov.start + moov.size)]);
}

// 按 stsc/stsz/stco 定位每个样本，把长度前缀的 NAL 改成 Annex B 起始码。
function annexB(buf, video) {
  const stbl = video.stbl;
  const find = (type) => boxes(buf, stbl.body, stbl.start + stbl.size).find((b) => b.type === type);
  const stsz = find("stsz");
  const stco = find("stco");
  const co64 = find("co64");
  const stsc = find("stsc");
  if (!stsz || (!stco && !co64) || !stsc) throw new Error("样本表不完整");
  const fixed = buf.readUInt32BE(stsz.body + 4);
  const count = buf.readUInt32BE(stsz.body + 8);
  const sizes = new Array(count);
  for (let i = 0; i < count; i++) sizes[i] = fixed || buf.readUInt32BE(stsz.body + 12 + i * 4);
  const chunkCount = buf.readUInt32BE((co64 || stco).body + 4);
  const chunkOffsets = new Array(chunkCount);
  for (let i = 0; i < chunkCount; i++)
    chunkOffsets[i] = co64
      ? Number(buf.readBigUInt64BE(co64.body + 8 + i * 8))
      : buf.readUInt32BE(stco.body + 8 + i * 4);
  const stscCount = buf.readUInt32BE(stsc.body + 4);
  const runs = [];
  for (let i = 0; i < stscCount; i++)
    runs.push({
      first: buf.readUInt32BE(stsc.body + 8 + i * 12),
      per: buf.readUInt32BE(stsc.body + 12 + i * 12),
    });
  const startCode = Buffer.from([0, 0, 0, 1]);
  const parts = [];
  let sample = 0;
  for (let ci = 0; ci < chunkCount && sample < count; ci++) {
    let per = 1;
    for (const run of runs) if (ci + 1 >= run.first) per = run.per;
    let off = chunkOffsets[ci];
    for (let k = 0; k < per && sample < count; k++, sample++) {
      const end = off + sizes[sample];
      while (off + 4 <= end) {
        const len = buf.readUInt32BE(off);
        off += 4;
        if (len <= 0 || off + len > end) break;
        parts.push(startCode, buf.subarray(off, off + len));
        off += len;
      }
      off = end;
    }
  }
  return Buffer.concat(parts);
}

async function readAll(stream) {
  const CH = 1024 * 1024;
  const total = Number(stream.total) || 0;
  if (!total) throw new Error("分片总长为 0");
  if (total > MAX_INPUT) throw fail("SOURCE_OOM", "该集文件过大，超出转码上限（" + MAX_INPUT + " 字节）");
  const parts = [];
  for (let off = 0; off < total; off += CH) {
    parts.push(await stream.chunk(off, Math.min(off + CH - 1, total - 1)));
  }
  const buf = Buffer.concat(parts);
  return buf.subarray(0, total);
}

// 把已解密的整集 mp4 拆成「Annex B 视频 + 纯音频 mp4」并落到临时目录，供 ffmpeg 读取。
// 旧的分片 MP4 直推和新的 HLS 转码共用这一段（两条路都要求同样的上游预处理）。
export async function stageVvcInput(stream) {
  const buf = await readAll(stream);
  const layout = analyse(buf);
  // hvc1 必须放行：红果的 VVC 流在 stsd 里写的就是 hvc1，按 4CC 判断会把真正需要转码的流挡在门外。
  if (!/^(vvc1|bv2C|hvc1)$/.test(layout.video.entry)) {
    throw fail("CODEC_UNSUPPORTED", "该档位不是 H.266/VVC，无需服务端转码");
  }
  const annex = annexB(buf, layout.video);
  const audio = audioOnlyMp4(buf, layout);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "hg-tc-"));
  const vFile = path.join(dir, "v.h266");
  const aFile = path.join(dir, "a.mp4");
  fs.writeFileSync(vFile, annex);
  if (audio) fs.writeFileSync(aFile, audio);
  return { dir, vFile, aFile, hasAudio: Boolean(audio), bytesIn: buf.length };
}

export function dropStage(dir) {
  if (!dir) return;
  fs.rm(dir, { recursive: true, force: true }, () => {});
}

// 转码共用的编码参数（输出封装由调用方追加）。
export function h264Args({ vFile, aFile, hasAudio }) {
  const args = ["-hide_banner", "-v", "error", "-nostdin", "-f", "vvc", "-i", vFile];
  if (hasAudio) args.push("-i", aFile);
  args.push("-map", "0:v:0");
  if (hasAudio) args.push("-map", "1:a:0");
  args.push("-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", "-c:a", "copy");
  return args;
}
