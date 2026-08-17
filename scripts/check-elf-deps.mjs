#!/usr/bin/env node
/**
 * check-elf-deps.mjs — inspect ELF DT_NEEDED / DT_SONAME entries.
 *
 * Usage:
 *   node check-elf-deps.mjs <binary>            # print its DT_NEEDED list
 *   node check-elf-deps.mjs --sonames <dir>     # print SONAME of every .so in dir
 *   node check-elf-deps.mjs --link <binary> <libdir> [--apply]
 *       # print missing aliases; with --apply, create symlink-style copies so
 *       # every DT_NEEDED name resolves inside <libdir>.
 */
import { readFileSync, existsSync, copyFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const SHT_DYNAMIC = 6;
const PT_DYNAMIC = 2;
const DT_NEEDED = 1;
const DT_STRTAB = 5;
const DT_SONAME = 14;

function elf64(buf) {
  const is64 = buf[4] === 2;
  if (is64) {
    const phOff = Number(buf.readBigUInt64LE(32));
    const phEntSize = buf.readUInt16LE(54);
    const phNum = buf.readUInt16LE(56);
    const shOff = Number(buf.readBigUInt64LE(40));
    const shEntSize = buf.readUInt16LE(58);
    const shNum = buf.readUInt16LE(60);
    const shStrIdx = buf.readUInt16LE(62);
    return { is64, phOff, phEntSize, phNum, shOff, shEntSize, shNum, shStrIdx };
  }
  const phOff = buf.readUInt32LE(28);
  const phEntSize = buf.readUInt16LE(42);
  const phNum = buf.readUInt16LE(44);
  const shOff = buf.readUInt32LE(32);
  const shEntSize = buf.readUInt16LE(46);
  const shNum = buf.readUInt16LE(48);
  const shStrIdx = buf.readUInt16LE(50);
  return { is64, phOff, phEntSize, phNum, shOff, shEntSize, shNum, shStrIdx };
}

function sectionHeader(buf, h, i) {
  const off = h.shOff + i * h.shEntSize;
  if (h.is64) {
    return {
      name: buf.readUInt32LE(off),
      type: buf.readUInt32LE(off + 4),
      addr: Number(buf.readBigUInt64LE(off + 16)),
      offset: Number(buf.readBigUInt64LE(off + 24)),
      size: Number(buf.readBigUInt64LE(off + 32)),
    };
  }
  return {
    name: buf.readUInt32LE(off),
    type: buf.readUInt32LE(off + 4),
    addr: buf.readUInt32LE(off + 12),
    offset: buf.readUInt32LE(off + 16),
    size: buf.readUInt32LE(off + 20),
  };
}

function strtab(buf, offset) {
  let end = offset;
  while (end < buf.length && buf[end] !== 0) end++;
  return buf.toString("utf8", offset, end);
}

function readDynamic(buf, h) {
  // Locate .dynamic through section headers; resolve DT_STRTAB's virtual
  // address to a file offset via the section address table.
  let dyn = null;
  let sections = [];
  for (let i = 0; i < h.shNum; i++) {
    const sh = sectionHeader(buf, h, i);
    sections.push(sh);
    if (sh.type === SHT_DYNAMIC) dyn = sh;
  }
  if (!dyn) return { needed: [], soname: null };
  const count = Math.floor(dyn.size / (h.is64 ? 16 : 8));
  const entries = [];
  for (let j = 0; j < count; j++) {
    const off = dyn.offset + j * (h.is64 ? 16 : 8);
    const tag = h.is64 ? Number(buf.readBigInt64LE(off)) : buf.readInt32LE(off);
    const val = h.is64 ? Number(buf.readBigUInt64LE(off + 8)) : buf.readUInt32LE(off + 4);
    entries.push({ tag, val });
  }
  const strtabEntry = entries.find((d) => d.tag === DT_STRTAB);
  if (!strtabEntry) return { needed: [], soname: null };
  // vaddr -> file offset: the .dynstr section usually carries the strtab.
  const strSection = sections.find((s) => s.addr === strtabEntry.val);
  const strOff = strSection ? strSection.offset : strtabEntry.val;
  const needed = [];
  let soname = null;
  for (const d of entries) {
    if (d.tag === DT_NEEDED) needed.push(strtab(buf, strOff + d.val));
    else if (d.tag === DT_SONAME) soname = strtab(buf, strOff + d.val);
  }
  return { needed, soname };
}

const [,, ...argv] = process.argv;

if (argv[0] === "--sonames") {
  const dir = argv[1];
  for (const f of readdirSync(dir).sort()) {
    const p = join(dir, f);
    if (!statSync(p).isFile()) continue;
    const buf = readFileSync(p);
    if (buf.length < 64 || buf[0] !== 0x7f || buf[1] !== 0x45) continue;
    const h = elf64(buf);
    const { soname } = readDynamic(buf, h);
    console.log(`${f}${soname ? `  ->  SONAME: ${soname}` : ""}`);
  }
  process.exit(0);
}

const binary = argv[0];
const libDir = argv[1];
const apply = argv.includes("--apply");
if (!binary) {
  console.error("usage: node check-elf-deps.mjs <binary> [libdir] [--apply]");
  process.exit(1);
}

const buf = readFileSync(binary);
const h = elf64(buf);
const { needed } = readDynamic(buf, h);
console.log(`DT_NEEDED of ${binary}:`);
for (const n of needed) console.log(`  ${n}`);

if (libDir && existsSync(libDir)) {
  // The Android (bionic) linker resolves a DT_NEEDED entry by searching for a
  // FILE with that exact name in LD_LIBRARY_PATH and the linker search path;
  // it does NOT fall back to matching a library's embedded SONAME against a
  // differently-named file. So every non-bionic DT_NEEDED name must exist as
  // a real file (Termux ships fully-versioned names like libicuuc.so.78.3,
  // while node needs libicuuc.so.78 — create the exact-name copy).
  const BIONIC = new Set(["libc.so", "libm.so", "libdl.so", "liblog.so"]);
  const files = readdirSync(libDir).filter((f) => statSync(join(libDir, f)).isFile());
  const byBasename = new Set(files);
  const sonameOf = (f) => {
    const b = readFileSync(join(libDir, f));
    if (b.length < 64 || b[0] !== 0x7f || b[1] !== 0x45) return null;
    return readDynamic(b, elf64(b)).soname;
  };
  let missing = 0;
  for (const n of needed) {
    if (BIONIC.has(n)) {
      console.log(`  [system] ${n} (provided by Android)`);
      continue;
    }
    if (byBasename.has(n)) {
      console.log(`  [ok] ${n}`);
      continue;
    }
    // Pick a candidate real file: same SONAME, or name-prefix match
    // (libz.so.1.3.2 for libz.so.1, libicuuc.so.78.3 for libicuuc.so.78).
    let cand = files.find((f) => sonameOf(f) === n);
    if (!cand) cand = files.find((f) => f.startsWith(n + "."));
    if (!cand) cand = files.find((f) => n.startsWith(f.replace(/\.so(\.\d+)*$/, ".so") + "."));
    if (cand) {
      if (apply && !existsSync(join(libDir, n))) {
        copyFileSync(join(libDir, cand), join(libDir, n));
        console.log(`  [aliased] ${n} <- ${cand}`);
      } else {
        console.log(`  [candidate] ${n} <- ${cand} (run with --apply to create)`);
      }
    } else {
      missing++;
      console.log(`  [MISSING] ${n}`);
    }
  }
  process.exit(missing && !apply ? 1 : 0);
}
