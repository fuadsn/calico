// Checks every generated clip against the thresholds the live rep counter uses.
//
// The clips only have to look right, but if a clip cannot be counted by the same code that
// counts the user, the demonstration is teaching a rep the app would not accept. Thresholds
// are read straight out of RepCounter.kt so the two cannot drift apart.
//
//   node tools/check_clips.mjs

import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const CLIPS = path.join(ROOT, "roomscan", "src", "main", "assets", "clips");
const COUNTER = path.join(ROOT, "app", "src", "main", "java", "com", "hackathon", "calico", "RepCounter.kt");

const source = fs.readFileSync(COUNTER, "utf8");
// Landmark definitions, as well as thresholds, come from the counter itself.
const JOINTS = Object.fromEntries([...source.matchAll(/private val (\w+) = intArrayOf\((\d+), (\d+), (\d+)\)/g)]
  .map(m => [m[1], m.slice(2).map(Number)]));
const exercises = [...source.matchAll(
  /^\s{4}([A-Z_]+)\((\w+)_L, \w+_R, ([\d.]+)f, ([\d.]+)f,.*?(holdSec = (\d+))?\)/gm,
)].map((m) => ({ name: m[1], joint: m[2], down: +m[3], up: +m[4], hold: m[6] ? +m[6] : 0 }));

function angle(frame, [a, b, c]) {
  const at = (i) => [frame[i * 3], frame[i * 3 + 1], frame[i * 3 + 2]];
  const [pa, pb, pc] = [at(a), at(b), at(c)];
  const u = pa.map((v, i) => v - pb[i]);
  const v = pc.map((x, i) => x - pb[i]);
  const dot = u.reduce((s, x, i) => s + x * v[i], 0);
  const len = Math.hypot(...u) * Math.hypot(...v);
  return (Math.acos(Math.max(-1, Math.min(1, dot / len))) * 180) / Math.PI;
}

let failures = 0;
console.log("exercise             joint      range        needs            verdict");
for (const e of exercises) {
  const file = path.join(CLIPS, `${e.name}.json`);
  if (!fs.existsSync(file)) {
    console.log(`${e.name.padEnd(20)} ${"-".padEnd(10)} no clip`);
    failures++;
    continue;
  }
  const clip = JSON.parse(fs.readFileSync(file, "utf8"));
  const angles = clip.frames.map((f) => angle(f, JOINTS[`${e.joint}_L`]));
  const lo = Math.min(...angles);
  const hi = Math.max(...angles);
  // A rep clip has to cross both thresholds; a hold has to sit inside its band the
  // whole time, since any frame outside it fires a correction cue at the user.
  const ok = e.hold ? lo >= e.down && hi <= e.up : lo < e.down && hi > e.up;
  const needs = e.hold ? `stay in ${e.down}..${e.up}` : `cross ${e.down} and ${e.up}`;
  console.log(
    `${e.name.padEnd(20)} ${e.joint.padEnd(10)} ${`${lo.toFixed(0)}..${hi.toFixed(0)}`.padEnd(12)} ` +
    `${needs.padEnd(16)} ${ok ? "ok" : "FAIL"}`,
  );
  if (!ok) failures++;
}

console.log(failures === 0 ? "\nall clips countable" : `\n${failures} clip(s) the rep counter would not accept`);
process.exit(failures === 0 ? 0 : 1);
