#!/usr/bin/env node
// Official-format reader/writer for `session.v4.jsonl.zstd` (D21 / OPS-11).
//
// The Android maintenance layer owns every decision (what to repair, when to write); this helper
// owns only the zstd framing, mirroring the pinned official implementation
// (`@deepseek-ai/dsh-session-persistence-jsonl/lib/types/zstd.js`, official commit 687f282e):
//
//   * the artifact is a container of concatenated, independently decodable Zstandard frames;
//   * frame 0 is exactly one newline-terminated v4 header record;
//   * later frames hold JSONL event records and may end with a torn (partially written) frame;
//   * every frame written here is checksummed (`ZSTD_c_checksumFlag = 1`) and has no content size;
//   * frame boundaries are found with a structural scan (RFC 8878), never by splitting on magic
//     bytes, so compressed block payloads that happen to contain the magic cannot mislead us.
//
// Invocation (no stdin/stdout binary protocol, so a torn pipe can never truncate session data):
//
//   node session-v4-codec.mjs <op> <request.json> <response.json>
//
// ops: decode | encode | header | stats | selftest
//
// A response is always a JSON object. Failures are typed and secret-free: `{ok:false, code, detail}`
// where detail carries at most an offset or a bounded error message, never session content or
// absolute app paths.

"use strict";

import fs from "node:fs";
import zlib from "node:zlib";

const ZSTD_MAGIC = 0xfd2fb528;
const MAX_INPUT_BYTES = 256 * 1024 * 1024;
const MAX_DETAIL = 300;
const ZSTD_MISSING =
  typeof zlib.zstdDecompressSync !== "function" || typeof zlib.zstdCompressSync !== "function";

/** Typed, catchable failure. `detail` must never contain file content. */
class CodecError extends Error {
  constructor(code, detail) {
    super(detail);
    this.code = code;
    this.detail = String(detail).slice(0, MAX_DETAIL);
  }
}

/**
 * Locate complete frames without decompressing their blocks. Exact port of the official
 * `scanZstdFrames`: invalid complete structure rejects, EOF inside the final frame is reported as
 * `tornStart` so the caller can recover the complete-record prefix and truncate the tail.
 */
function scanZstdFrames(buffer, maxFrames = Number.POSITIVE_INFINITY) {
  const frames = [];
  let offset = 0;
  while (offset < buffer.length) {
    const start = offset;
    if (buffer.length - offset < 4) return { frames, tornStart: start };
    if (buffer.readUInt32LE(offset) !== ZSTD_MAGIC) {
      throw new CodecError("INVALID_MAGIC", `invalid frame magic at byte ${offset}`);
    }
    offset += 4;
    if (offset === buffer.length) return { frames, tornStart: start };
    const descriptor = buffer.readUInt8(offset);
    offset += 1;
    if ((descriptor & 24) !== 0) {
      throw new CodecError("RESERVED_HEADER_BIT", `reserved frame-header bit at byte ${offset - 1}`);
    }
    const contentSizeFlag = descriptor >>> 6;
    const singleSegment = (descriptor & 32) !== 0;
    const checksum = (descriptor & 4) !== 0;
    const dictionaryFlag = descriptor & 3;
    const dictionaryBytes = dictionaryFlag === 3 ? 4 : dictionaryFlag;
    const contentSizeBytes = contentSizeFlag === 0 ? (singleSegment ? 1 : 0) : 1 << contentSizeFlag;
    const remainingHeaderBytes = (singleSegment ? 0 : 1) + dictionaryBytes + contentSizeBytes;
    if (buffer.length - offset < remainingHeaderBytes) return { frames, tornStart: start };
    offset += remainingHeaderBytes;
    for (;;) {
      if (buffer.length - offset < 3) return { frames, tornStart: start };
      const blockHeader = buffer.readUIntLE(offset, 3);
      offset += 3;
      const lastBlock = (blockHeader & 1) !== 0;
      const blockType = (blockHeader >>> 1) & 3;
      const blockSize = blockHeader >>> 3;
      if (blockType === 3) {
        throw new CodecError("RESERVED_BLOCK_TYPE", `reserved block type at byte ${offset - 3}`);
      }
      const payloadBytes = blockType === 1 ? 1 : blockSize;
      if (buffer.length - offset < payloadBytes) return { frames, tornStart: start };
      offset += payloadBytes;
      if (lastBlock) break;
    }
    if (checksum) {
      if (buffer.length - offset < 4) return { frames, tornStart: start };
      offset += 4;
    }
    frames.push({ start, end: offset, checksum, contentSize: null });
    if (frames.length === maxFrames) return { frames };
  }
  return { frames };
}

/** Absolute on Android (POSIX) or on a Windows JVM test host; relative paths are refused. */
function isAbsolutePath(value) {
  return typeof value === "string" && (value.startsWith("/") || /^[A-Za-z]:[\\/]/.test(value));
}

/** Read one bounded file. */
function readBounded(path) {
  if (!isAbsolutePath(path)) {
    throw new CodecError("INVALID_REQUEST", "input path must be absolute");
  }
  let stat;
  try {
    stat = fs.statSync(path);
  } catch {
    throw new CodecError("INPUT_MISSING", "input file is not readable");
  }
  if (!stat.isFile()) throw new CodecError("INPUT_MISSING", "input is not a regular file");
  if (stat.size > MAX_INPUT_BYTES) throw new CodecError("INPUT_TOO_LARGE", "input exceeds the byte budget");
  return fs.readFileSync(path);
}

/** Decompress exactly one structurally complete frame; validates the frame checksum. */
function decodeFrame(bytes, frame) {
  try {
    return zlib.zstdDecompressSync(bytes.subarray(frame.start, frame.end));
  } catch (error) {
    throw new CodecError(
      "FRAME_CORRUPT",
      `frame at byte ${frame.start} failed validation: ${error && error.message ? error.message : "unknown"}`
    );
  }
}

/** Recover complete newline-terminated records from a structurally torn final frame. */
function recoverTornPrefix(bytes, tornStart) {
  if (typeof tornStart !== "number") return { text: "", lines: 0 };
  let plaintext;
  try {
    plaintext = zlib.zstdDecompressSync(bytes.subarray(tornStart), {
      finishFlush: zlib.constants.ZSTD_e_flush
    });
  } catch {
    return { text: "", lines: 0 };
  }
  const text = Buffer.from(plaintext).toString("utf8");
  const newline = text.lastIndexOf("\n");
  if (newline === -1) return { text: "", lines: 0 };
  const prefix = text.slice(0, newline + 1);
  return { text: prefix, lines: prefix.split("\n").filter((line) => line.length > 0).length };
}

/** Parse the exact v4 header contract: one record, newline terminated, type/session + numeric version. */
function parseHeader(text) {
  if (typeof text !== "string" || text.length === 0 || !text.endsWith("\n")) {
    throw new CodecError("HEADER_INVALID", "first frame is not one newline-terminated record");
  }
  if (text.indexOf("\n") !== text.length - 1) {
    throw new CodecError("HEADER_INVALID", "first frame contains more than the header record");
  }
  let value;
  try {
    value = JSON.parse(text);
  } catch {
    throw new CodecError("HEADER_INVALID", "header record is not valid JSON");
  }
  if (value === null || typeof value !== "object") {
    throw new CodecError("HEADER_INVALID", "header record is not an object");
  }
  if (value.type !== "session") throw new CodecError("HEADER_INVALID", "header record type is not session");
  const version = typeof value.version === "number" ? value.version : 0;
  if (!Number.isInteger(version) || version < 1 || version > 4) {
    throw new CodecError("UNKNOWN_FORMAT", "header record version is not supported");
  }
  return { type: "session", version, id: typeof value.id === "string" ? value.id : "" };
}

/** Split complete JSONL records out of a decoded frame; blank lines are not records. */
function splitRecords(text) {
  return text.split("\n").filter((line) => line.trim().length > 0);
}

/**
 * Message counts over the official v4 event vocabulary.
 *
 * - A visible user message is a `user/message` event whose `data.source.kind` is `user`;
 *   runtime-context / time-context / model-selection notices are also `user/message` records but
 *   are not messages the user wrote, so counting the raw type would overstate the session.
 * - A visible assistant message is one `assistant/attempt` per (turn, step); retries append a new
 *   attempt with the same coordinates and supersede the earlier one, so counting raw attempts
 *   would overstate it as well.
 */
function countEvents(events) {
  let userMessages = 0;
  let eventCount = 0;
  const assistantSteps = new Set();
  for (const record of events) {
    eventCount += 1;
    const type = record && record.type;
    const data = record && record.data && typeof record.data === "object" ? record.data : {};
    if (type === "user/message") {
      const source = data.source && typeof data.source === "object" ? data.source : null;
      const kind = source && typeof source.kind === "string" ? source.kind : "user";
      if (kind === "user") userMessages += 1;
    } else if (type === "assistant/attempt") {
      const turn = typeof data.turn === "number" ? data.turn : 0;
      const step = typeof data.step === "number" ? data.step : 0;
      assistantSteps.add(`${turn}/${step}`);
    }
  }
  return {
    eventCount,
    userMessages,
    assistantMessages: assistantSteps.size,
    messageCount: userMessages + assistantSteps.size
  };
}

/** Decode a whole artifact into per-frame plaintext plus the torn-tail metadata. */
function decodeArtifact(bytes, maxFrames) {
  const { frames, tornStart } = scanZstdFrames(bytes, maxFrames);
  if (frames.length === 0) throw new CodecError("EMPTY_LOG", "no complete frame was found");
  const first = decodeFrame(bytes, frames[0]);
  const header = parseHeader(first.toString("utf8"));
  const outFrames = [{ index: 0, start: frames[0].start, end: frames[0].end, text: first.toString("utf8") }];
  for (let index = 1; index < frames.length; index += 1) {
    const plaintext = decodeFrame(bytes, frames[index]);
    outFrames.push({
      index,
      start: frames[index].start,
      end: frames[index].end,
      text: plaintext.toString("utf8")
    });
  }
  const torn = recoverTornPrefix(bytes, tornStart);
  return {
    formatId: "session.v4.jsonl.zstd",
    header,
    frames: outFrames,
    tornTail:
      typeof tornStart === "number"
        ? { start: tornStart, bytes: bytes.length - tornStart, recoveredLines: torn.lines }
        : null
  };
}

/** op: decode -- full plaintext of every complete frame (repair planning). */
function opDecode(request) {
  const bytes = readBounded(request.input);
  const decoded = decodeArtifact(bytes, Number.isFinite(request.maxFrames) ? request.maxFrames : undefined);
  return { ok: true, ...decoded };
}

/** op: header -- bounded metadata-only read (self-check and console). */
function opHeader(request) {
  const bytes = readBounded(request.input);
  const scan = scanZstdFrames(bytes, 1);
  if (scan.frames.length === 0) throw new CodecError("EMPTY_LOG", "no complete frame was found");
  const text = decodeFrame(bytes, scan.frames[0]).toString("utf8");
  const header = parseHeader(text);
  return {
    ok: true,
    header,
    headerBytes: scan.frames[0].end - scan.frames[0].start,
    fileBytes: bytes.length,
    tornTail: typeof scan.tornStart === "number"
  };
}

/** op: stats -- per-file message/event counts without returning any content. */
function opStats(request) {
  const files = Array.isArray(request.files) ? request.files : [];
  if (files.length === 0 || files.length > 64) throw new CodecError("INVALID_REQUEST", "files must contain 1..64 paths");
  const sessions = files.map((path, index) => {
    try {
      const bytes = readBounded(path);
      const { frames, tornStart } = scanZstdFrames(bytes);
      if (frames.length === 0) throw new CodecError("EMPTY_LOG", "no complete frame was found");
      const header = parseHeader(decodeFrame(bytes, frames[0]).toString("utf8"));
      const events = [];
      for (let frameIndex = 1; frameIndex < frames.length; frameIndex += 1) {
        for (const line of splitRecords(decodeFrame(bytes, frames[frameIndex]).toString("utf8"))) {
          let record;
          try {
            record = JSON.parse(line);
          } catch {
            throw new CodecError("INVALID_JSON", `frame ${frameIndex} contains a non-JSON record`);
          }
          events.push(record);
        }
      }
      const counts = countEvents(events);
      return {
        index,
        ok: true,
        header,
        ...counts,
        tornTail: typeof tornStart === "number",
        tornTailBytes: typeof tornStart === "number" ? bytes.length - tornStart : 0
      };
    } catch (error) {
      return {
        index,
        ok: false,
        code: error instanceof CodecError ? error.code : "INTERNAL",
        detail: error instanceof CodecError ? error.detail : "session could not be counted"
      };
    }
  });
  return { ok: true, sessions };
}

/** op: encode -- one independently decodable, checksummed frame from UTF-8 input bytes. */
function opEncode(request) {
  const input = readBounded(request.input);
  if (input.length === 0) throw new CodecError("INVALID_REQUEST", "refusing to write an empty frame");
  let encoded;
  try {
    encoded = zlib.zstdCompressSync(input, {
      params: { [zlib.constants.ZSTD_c_checksumFlag]: 1 }
    });
  } catch (error) {
    throw new CodecError("ENCODE_FAILED", `frame compression failed: ${error && error.message ? error.message : "unknown"}`);
  }
  const check = scanZstdFrames(Buffer.from(encoded));
  if (check.frames.length !== 1 || typeof check.tornStart === "number" || !check.frames[0].checksum) {
    throw new CodecError("ENCODE_FAILED", "encoder produced an invalid or unchecksummed frame");
  }
  if (!isAbsolutePath(request.output)) {
    throw new CodecError("INVALID_REQUEST", "output path must be absolute");
  }
  const temp = `${request.output}.tmp`;
  fs.writeFileSync(temp, encoded);
  fs.renameSync(temp, request.output);
  return { ok: true, bytes: encoded.length, frameBytes: check.frames[0].end - check.frames[0].start };
}

/** op: selftest -- proves the runtime exposes the zstd API this helper needs. */
function opSelftest() {
  // A checksummed round trip proves both directions of the API, not just the symbol presence.
  const encoded = zlib.zstdCompressSync(Buffer.from("selftest\n", "utf8"), {
    params: { [zlib.constants.ZSTD_c_checksumFlag]: 1 }
  });
  const decoded = zlib.zstdDecompressSync(encoded).toString("utf8");
  if (decoded !== "selftest\n") throw new CodecError("ZSTD_API_UNAVAILABLE", "zstd round trip failed");
  return { ok: true, node: process.version, zstd: true };
}

function run() {
  const [op, requestPath, responsePath] = process.argv.slice(2);
  let response;
  try {
    if (ZSTD_MISSING) throw new CodecError("ZSTD_API_UNAVAILABLE", "node:zlib has no zstd support");
    if (!requestPath) throw new CodecError("INVALID_REQUEST", "missing request path");
    const request = JSON.parse(fs.readFileSync(requestPath, "utf8"));
    switch (op) {
      case "decode":
        response = opDecode(request);
        break;
      case "encode":
        response = opEncode(request);
        break;
      case "header":
        response = opHeader(request);
        break;
      case "stats":
        response = opStats(request);
        break;
      case "selftest":
        response = opSelftest();
        break;
      default:
        throw new CodecError("UNKNOWN_OP", "unknown operation");
    }
  } catch (error) {
    response = {
      ok: false,
      code: error instanceof CodecError ? error.code : "INTERNAL",
      detail: error instanceof CodecError
        ? error.detail
        : String(error && error.message ? error.message : "codec helper failed").slice(0, MAX_DETAIL)
    };
  }
  const text = JSON.stringify(response);
  if (responsePath) fs.writeFileSync(responsePath, text);
  else process.stdout.write(text);
  process.exitCode = response.ok ? 0 : 3;
}

run();
