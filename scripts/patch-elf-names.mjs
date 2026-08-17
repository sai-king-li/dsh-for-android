#!/usr/bin/env node
/**
 * patch-elf-names.mjs — rename DT_NEEDED / DT_SONAME strings in place.
 *
 * The Android linker resolves a NEEDED entry by exact FILE name, and aapt2
 * only packages jniLibs entries whose names end in `.so`. Termux libraries
 * use fully-versioned names (libicuuc.so.78.3 / NEEDED libicuuc.so.78), so we
 * rename every dependency to a plain `.so` name and patch the ELF string
 * tables accordingly. New names are always SHORTER than or equal to the old
 * ones, so each entry is overwritten in place (NUL-padded) — no table shifts.
 *
 * Usage:
 *   node patch-elf-names.mjs <file> old=new [old=new ...]
 *
 * The file's .dynstr is patched in place (all occurrences), and files in the
 * same directory are renamed when their basename equals an old name.
 */
import { readFileSync, writeFileSync, renameSync, readdirSync, existsSync } from "node:fs";
import { basename, dirname, join } from "node:path";

const [, , file, ...pairs] = process.argv;
if (!file || pairs.length === 0) {
  console.error("usage: node patch-elf-names.mjs <file> old=new [old=new ...]");
  process.exit(1);
}

const mapping = new Map();
for (const p of pairs) {
  if (!p.includes("=")) continue; // flags like --no-rename
  const eq = p.indexOf("=");
  const oldName = p.slice(0, eq);
  const newName = p.slice(eq + 1);
  if (newName.length > oldName.length) {
    throw new Error(`new name '${newName}' is longer than '${oldName}' (in-place patch requires <=)`);
  }
  mapping.set(oldName, newName);
}

// --- patch the string table ------------------------------------------------
const buf = readFileSync(file);
const is64 = buf[4] === 2;
const phOff = is64 ? Number(buf.readBigUInt64LE(32)) : buf.readUInt32LE(28);
const phEnt = is64 ? buf.readUInt16LE(54) : buf.readUInt16LE(42);
const phNum = is64 ? buf.readUInt16LE(56) : buf.readUInt16LE(44);
const shOff = is64 ? Number(buf.readBigUInt64LE(40)) : buf.readUInt32LE(32);
const shEnt = is64 ? buf.readUInt16LE(58) : buf.readUInt16LE(46);
const shNum = is64 ? buf.readUInt16LE(60) : buf.readUInt16LE(48);

const readU32 = (off) => buf.readUInt32LE(off);
const readU64 = (off) => Number(buf.readBigUInt64LE(off));

// Locate .dynstr through the PT_DYNAMIC segment's DT_STRTAB, mapping the
// virtual address to a file offset via PT_LOAD segments.
const segs = [];
for (let i = 0; i < phNum; i++) {
  const off = phOff + i * phEnt;
  const type = readU32(off);
  segs.push({
    type,
    // 64-bit phdr: type@0 flags@4 offset@8 vaddr@16 paddr@24 filesz@32 memsz@40 align@48
    // 32-bit phdr: type@0 offset@4 vaddr@8 paddr@12 filesz@16 memsz@20 flags@24 align@28
    vaddr: is64 ? readU64(off + 16) : readU32(off + 8),
    fileOff: is64 ? readU64(off + 8) : readU32(off + 4),
    fileSize: is64 ? readU64(off + 32) : readU32(off + 16),
  });
}
const dynSeg = segs.find((s) => s.type === 2 /* PT_DYNAMIC */);
const loadSegs = segs.filter((s) => s.type === 1 /* PT_LOAD */);
if (!dynSeg) throw new Error("no PT_DYNAMIC found");
const vaddrToOffset = (vaddr) => {
  for (const s of loadSegs) {
    if (vaddr >= s.vaddr && vaddr < s.vaddr + s.fileSize) {
      return s.fileOff + (vaddr - s.vaddr);
    }
  }
  return vaddr; // identity fallback
};
let strtabVaddr = -1;
for (let off = dynSeg.fileOff; off < dynSeg.fileOff + dynSeg.fileSize - 8; off += (is64 ? 16 : 8)) {
  const tag = is64 ? Number(buf.readBigInt64LE(off)) : buf.readInt32LE(off);
  const val = is64 ? readU64(off + 8) : readU32(off + 4);
  if (tag === 5 /* DT_STRTAB */) { strtabVaddr = val; break; }
}
if (strtabVaddr < 0) throw new Error("no DT_STRTAB found");
const dynstrOff = vaddrToOffset(strtabVaddr);
const dynstrSize = Math.min(buf.length - dynstrOff, 8 * 1024 * 1024);

let patched = 0;
for (const [oldName, newName] of mapping) {
  const needle = Buffer.from(oldName + "\0", "latin1");
  const start = dynstrOff;
  const end = Math.min(dynstrOff + dynstrSize, buf.length - needle.length);
  let idx = buf.indexOf(needle, start);
  let hits = 0;
  while (idx !== -1 && idx <= end) {
    const replacement = Buffer.from(newName + "\0".repeat(oldName.length - newName.length), "latin1");
    replacement.copy(buf, idx);
    hits++;
    patched++;
    idx = buf.indexOf(needle, idx + needle.length);
  }
  if (hits === 0) console.log(`  ${basename(file)}: '${oldName}' not present (ok)`);
  else console.log(`  ${basename(file)}: '${oldName}' -> '${newName}' (${hits} hit(s))`);
}

if (patched > 0) {
  writeFileSync(file, buf);
}

// --- rename files in the same directory --------------------------------------
// (skipped with --no-rename; the caller may want to control rename timing)
if (!process.argv.includes("--no-rename")) {
  const dir = dirname(file);
  for (const f of readdirSync(dir)) {
    if (mapping.has(f) && f !== file) {
      const target = join(dir, mapping.get(f));
      if (!existsSync(target)) {
        renameSync(join(dir, f), target);
        console.log(`  renamed ${f} -> ${mapping.get(f)}`);
      }
    }
  }
}
console.log(`done: ${file}`);
