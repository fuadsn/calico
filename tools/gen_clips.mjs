// Generates one looping pose clip per exercise into roomscan/src/main/assets/clips.
//
// A clip is a list of MediaPipe world-landmark frames, exactly what
// PoseLandmarkerResult.worldLandmarks() gives for a real video, so a clip captured from
// footage can replace a generated one without touching any app code.
//
// Each exercise is described as joint angles rather than as landmark positions. Forward
// kinematics over a canonical body turns those angles into landmarks, which keeps limb
// lengths constant across the whole cycle and keeps the files small enough to read.
//
//   node tools/gen_clips.mjs

import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const OUT_DIR = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  "..", "roomscan", "src", "main", "assets", "clips",
);

const FRAMES_PER_CYCLE = 60;

// Canonical body, metres. Roughly a 1.75 m adult, which is also what the avatar is scaled to.
const BODY = {
  hipHalf: 0.09,
  spine: 0.52,
  shoulderHalf: 0.19,
  neck: 0.18,
  head: 0.12,
  upperArm: 0.29,
  forearm: 0.25,
  hand: 0.09,
  thigh: 0.42,
  shin: 0.41,
  foot: 0.17,
  heel: 0.06,
};

// ---- small vector and quaternion helpers (x, y, z, w) ----

const rad = (deg) => (deg * Math.PI) / 180;
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const mulS = (a, s) => [a[0] * s, a[1] * s, a[2] * s];

function axisAngle(axis, angle) {
  const s = Math.sin(angle / 2);
  return [axis[0] * s, axis[1] * s, axis[2] * s, Math.cos(angle / 2)];
}
const rotX = (deg) => axisAngle([1, 0, 0], rad(deg));
const rotY = (deg) => axisAngle([0, 1, 0], rad(deg));
const rotZ = (deg) => axisAngle([0, 0, 1], rad(deg));

function qMul(a, b) {
  return [
    a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
    a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
    a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
    a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
  ];
}

function qRot(q, v) {
  const [x, y, z, w] = q;
  const u = [x, y, z];
  const dotUV = u[0] * v[0] + u[1] * v[1] + u[2] * v[2];
  const dotUU = u[0] * u[0] + u[1] * u[1] + u[2] * u[2];
  const cross = [
    u[1] * v[2] - u[2] * v[1],
    u[2] * v[0] - u[0] * v[2],
    u[0] * v[1] - u[1] * v[0],
  ];
  return add(add(mulS(u, 2 * dotUV), mulS(v, w * w - dotUU)), mulS(cross, 2 * w));
}

const DOWN = [0, -1, 0];
const FORWARD = [0, 0, 1];

// ---- forward kinematics ----

/**
 * Builds the 33 MediaPipe landmarks for one set of joint angles, in rig space:
 * metres, hip midpoint at the origin, +X to the subject's left, +Y up, +Z out of the chest.
 *
 * `side` is +1 for the subject's left limb and -1 for the right, which flips abduction so
 * a positive angle always means "away from the body".
 */
function fk(c) {
  const p = new Array(33).fill(null).map(() => [0, 0, 0]);

  const torso = rotX(c.torso);
  const hipL = [BODY.hipHalf, 0, 0];
  const hipR = [-BODY.hipHalf, 0, 0];
  const spineTop = qRot(torso, [0, BODY.spine, 0]);
  const shoulderL = add(spineTop, qRot(torso, [BODY.shoulderHalf, 0, 0]));
  const shoulderR = add(spineTop, qRot(torso, [-BODY.shoulderHalf, 0, 0]));
  const neck = add(spineTop, qRot(torso, [0, BODY.neck, 0]));
  const headTop = add(neck, qRot(torso, [0, BODY.head, 0]));

  // Head cluster. Only the ears and the nose are read by the rig; the rest are filled in
  // so the file is a complete 33-landmark frame.
  const faceFwd = qRot(torso, [0, 0, 1]);
  const faceLeft = qRot(torso, [1, 0, 0]);
  p[0] = add(headTop, mulS(faceFwd, 0.10));
  p[1] = add(add(headTop, mulS(faceFwd, 0.08)), mulS(faceLeft, 0.02));
  p[2] = add(add(headTop, mulS(faceFwd, 0.08)), mulS(faceLeft, 0.035));
  p[3] = add(add(headTop, mulS(faceFwd, 0.08)), mulS(faceLeft, 0.05));
  p[4] = add(add(headTop, mulS(faceFwd, 0.08)), mulS(faceLeft, -0.02));
  p[5] = add(add(headTop, mulS(faceFwd, 0.08)), mulS(faceLeft, -0.035));
  p[6] = add(add(headTop, mulS(faceFwd, 0.08)), mulS(faceLeft, -0.05));
  p[7] = add(headTop, mulS(faceLeft, 0.075));
  p[8] = add(headTop, mulS(faceLeft, -0.075));
  p[9] = add(add(headTop, mulS(faceFwd, 0.08)), mulS(faceLeft, 0.025));
  p[10] = add(add(headTop, mulS(faceFwd, 0.08)), mulS(faceLeft, -0.025));

  for (const side of [1, -1]) {
    const arm = side > 0 ? c.armL : c.armR;
    const shoulder = side > 0 ? shoulderL : shoulderR;
    // Abduction swings the arm out in the frontal plane, flexion swings it forward.
    const qUpper = qMul(torso, qMul(rotZ(side * arm.abduct), rotX(-arm.flex)));
    const elbow = add(shoulder, mulS(qRot(qUpper, DOWN), BODY.upperArm));
    const qFore = qMul(qUpper, rotX(-arm.elbow));
    const wrist = add(elbow, mulS(qRot(qFore, DOWN), BODY.forearm));
    const qHand = qMul(qFore, qMul(rotX(-(arm.wrist ?? 0)), rotY(side * (arm.twist ?? 0))));
    const handDir = qRot(qHand, DOWN);
    const index = add(wrist, mulS(handDir, BODY.hand));
    const across = qRot(qHand, [side * 0.04, 0, 0]);

    const s = side > 0 ? 0 : 1;
    p[11 + s] = shoulder;
    p[13 + s] = elbow;
    p[15 + s] = wrist;
    p[17 + s] = add(index, mulS(across, -1));  // pinky
    p[19 + s] = index;
    p[21 + s] = add(add(wrist, mulS(handDir, BODY.hand * 0.5)), across);  // thumb
  }

  for (const side of [1, -1]) {
    const leg = side > 0 ? c.legL : c.legR;
    const hip = side > 0 ? hipL : hipR;
    const qThigh = qMul(rotZ(side * leg.abduct), rotX(-leg.flex));
    const knee = add(hip, mulS(qRot(qThigh, DOWN), BODY.thigh));
    const qShin = qMul(qThigh, rotX(leg.knee));
    const ankle = add(knee, mulS(qRot(qShin, DOWN), BODY.shin));
    const qFoot = leg.planted ? rotY(side * 8) : qMul(qShin, rotX(-leg.ankle));
    const sole = add(ankle, qRot(qFoot, [0, -0.055, 0]));
    const toe = add(sole, mulS(qRot(qFoot, FORWARD), BODY.foot));
    const heel = add(sole, mulS(qRot(qFoot, FORWARD), -BODY.heel));

    const s = side > 0 ? 0 : 1;
    p[23 + s] = hip;
    p[25 + s] = knee;
    p[27 + s] = ankle;
    p[29 + s] = heel;
    p[31 + s] = toe;
  }

  // Whole-body orientation last, so every exercise can be authored standing up.
  const world = qMul(rotY(c.yaw), rotX(c.pitch));
  return p.map((v) => qRot(world, v));
}

// ---- exercise definitions ----

const ARM_REST = { abduct: 8, flex: 0, elbow: 5, wrist: 5, twist: 0 };
const LEG_REST = { abduct: 3, flex: 0, knee: 2, ankle: 15 };

const arm = (o) => ({ ...ARM_REST, ...o });
const leg = (o) => ({ ...LEG_REST, ...o });

/** A pose with both arms and both legs the same. */
const symmetric = (o, a, l) => ({ torso: 0, yaw: 0, pitch: 0, ...o, armL: a, armR: a, legL: l, legR: l });

/**
 * Every exercise is two poses, `top` and `bottom`, that the cycle eases between.
 * `alternate` swaps which side is at `bottom`, for the moves that work one limb at a time.
 */
const EXERCISES = {
  INCLINE_PUSHUP: {
    top: symmetric({ pitch: 55 }, arm({ abduct: 28, flex: 88, elbow: 4 }), leg({ ankle: 35 })),
    bottom: symmetric({ pitch: 55 }, arm({ abduct: 55, flex: 78, elbow: 92 }), leg({ ankle: 35 })),
  },
  PUSHUP: {
    top: symmetric({ pitch: 90 }, arm({ abduct: 28, flex: 88, elbow: 4 }), leg({ ankle: 45 })),
    bottom: symmetric({ pitch: 90 }, arm({ abduct: 55, flex: 78, elbow: 92 }), leg({ ankle: 45 })),
  },
  PIKE_PUSHUP: {
    // Hips high, torso angled down over the hands.
    top: symmetric({ pitch: 128 }, arm({ abduct: 18, flex: 150, elbow: 6 }), leg({ flex: 72, ankle: 35 })),
    bottom: symmetric({ pitch: 128 }, arm({ abduct: 40, flex: 138, elbow: 95 }), leg({ flex: 72, ankle: 35 })),
  },
  DIP: {
    top: symmetric({}, arm({ abduct: 9, flex: -18, elbow: 5 }), leg({ flex: 22, knee: 38 })),
    bottom: symmetric({}, arm({ abduct: 14, flex: -26, elbow: 95 }), leg({ flex: 22, knee: 38 })),
  },
  PULLUP: {
    top: symmetric({}, arm({ abduct: 158, flex: 6, elbow: 118 }), leg({ knee: 28, flex: -8 })),
    bottom: symmetric({}, arm({ abduct: 166, flex: 4, elbow: 8 }), leg({ knee: 14, flex: -4 })),
  },
  SQUAT: {
    top: symmetric({}, arm({ flex: 12 }), leg({})),
    bottom: symmetric({ torso: 28 }, arm({ flex: 78, elbow: 12 }), leg({ flex: 88, knee: 96, ankle: 38 })),
  },
  LUNGE: {
    alternate: true,
    top: symmetric({}, arm({ flex: 8, elbow: 20 }), leg({})),
    bottom: {
      torso: 8, yaw: 0, pitch: 0,
      armL: arm({ flex: 8, elbow: 20 }), armR: arm({ flex: 8, elbow: 20 }),
      legL: leg({ flex: 58, knee: 88, ankle: 22 }),
      legR: leg({ flex: -26, knee: 92, ankle: 48 }),
    },
  },
  SITUP: {
    top: symmetric({ pitch: -90, torso: 0 }, arm({ abduct: 72, flex: 18, elbow: 142 }),
      leg({ flex: 34, knee: 94, ankle: 10 })),
    bottom: symmetric({ pitch: -90, torso: 82 }, arm({ abduct: 72, flex: 18, elbow: 142 }),
      leg({ flex: 34, knee: 94, ankle: 10 })),
  },
  LEG_RAISE: {
    top: symmetric({ pitch: -90 }, arm({ abduct: 12, flex: -8 }), leg({ flex: 2, knee: 4 })),
    bottom: symmetric({ pitch: -90 }, arm({ abduct: 12, flex: -8 }), leg({ flex: 92, knee: 6 })),
  },
  MOUNTAIN_CLIMBER: {
    alternate: true,
    top: symmetric({ pitch: 90 }, arm({ abduct: 28, flex: 88, elbow: 6 }), leg({ ankle: 45 })),
    bottom: {
      torso: 0, yaw: 0, pitch: 90,
      armL: arm({ abduct: 28, flex: 88, elbow: 6 }), armR: arm({ abduct: 28, flex: 88, elbow: 6 }),
      legL: leg({ flex: 82, knee: 96, ankle: 20 }),
      legR: leg({ flex: 0, knee: 8, ankle: 45 }),
    },
  },
  HIGH_KNEES: {
    alternate: true,
    top: symmetric({}, arm({ flex: 30, elbow: 85 }), leg({ knee: 10 })),
    bottom: {
      torso: 4, yaw: 0, pitch: 0,
      armL: arm({ flex: -32, elbow: 85 }), armR: arm({ flex: 46, elbow: 85 }),
      legL: leg({ flex: 92, knee: 88, ankle: 10 }),
      legR: leg({ flex: -6, knee: 6, ankle: 28 }),
    },
  },
  JUMPING_JACK: {
    top: symmetric({}, arm({ abduct: 10, flex: 4 }), leg({ abduct: 3 })),
    bottom: symmetric({}, arm({ abduct: 164, flex: 4 }), leg({ abduct: 24 })),
  },
  ARM_RAISE: {
    top: symmetric({}, arm({ flex: 6, elbow: 4 }), leg({})),
    bottom: symmetric({}, arm({ flex: 168, elbow: 4 }), leg({})),
  },
  ARM_CIRCLE: {
    // A circle needs both arm angles moving a quarter cycle apart, so it is swept rather
    // than eased between two poses.
    sweep: (t) => symmetric(
      {},
      arm({ abduct: 92 + 64 * Math.cos(2 * Math.PI * t), flex: 40 * Math.sin(2 * Math.PI * t), elbow: 6 }),
      leg({}),
    ),
  },
  PLANK: {
    // A hold still needs to breathe, or it reads as a frozen frame.
    top: symmetric({ pitch: 90 }, arm({ abduct: 22, flex: 92, elbow: 88 }), leg({ ankle: 45 })),
    bottom: symmetric({ pitch: 90, torso: 3 }, arm({ abduct: 22, flex: 94, elbow: 88 }), leg({ ankle: 45 })),
  },
  OVERHEAD_STRETCH: {
    top: {
      torso: 0, yaw: 0, pitch: 0,
      armL: arm({ abduct: 162, flex: 10, elbow: 128 }),
      armR: arm({ abduct: 104, flex: 26, elbow: 62 }),
      legL: leg({}), legR: leg({}),
    },
    bottom: {
      torso: 0, yaw: 0, pitch: 0,
      armL: arm({ abduct: 170, flex: 14, elbow: 138 }),
      armR: arm({ abduct: 112, flex: 30, elbow: 68 }),
      legL: leg({}), legR: leg({}),
    },
  },
  CROSS_BODY_STRETCH: {
    top: {
      torso: 0, yaw: 0, pitch: 0,
      armL: arm({ abduct: -86, flex: 18, elbow: 6 }),
      armR: arm({ abduct: -18, flex: 58, elbow: 92 }),
      legL: leg({}), legR: leg({}),
    },
    bottom: {
      torso: 0, yaw: 0, pitch: 0,
      armL: arm({ abduct: -96, flex: 24, elbow: 6 }),
      armR: arm({ abduct: -24, flex: 64, elbow: 96 }),
      legL: leg({}), legR: leg({}),
    },
  },
};

/** Seconds per full cycle. Holds move slowly, cardio moves quickly. */
const CYCLE_SECONDS = {
  INCLINE_PUSHUP: 2.2, PUSHUP: 2.2, PIKE_PUSHUP: 2.4, DIP: 2.2, PULLUP: 2.6, SQUAT: 2.4, LUNGE: 2.6,
  SITUP: 2.2, LEG_RAISE: 2.6, MOUNTAIN_CLIMBER: 1.0, HIGH_KNEES: 0.9, JUMPING_JACK: 1.1,
  ARM_RAISE: 2.2, ARM_CIRCLE: 2.0, PLANK: 4.0, OVERHEAD_STRETCH: 5.0, CROSS_BODY_STRETCH: 5.0,
};

// ---- blending and output ----

const lerp = (a, b, t) => a + (b - a) * t;

function blendJoint(a, b, t) {
  const out = {};
  for (const key of Object.keys(a)) out[key] = lerp(a[key], b[key], t);
  return out;
}

function blend(a, b, t) {
  return {
    torso: lerp(a.torso, b.torso, t),
    yaw: lerp(a.yaw, b.yaw, t),
    pitch: lerp(a.pitch, b.pitch, t),
    armL: blendJoint(a.armL, b.armL, t),
    armR: blendJoint(a.armR, b.armR, t),
    legL: blendJoint(a.legL, b.legL, t),
    legR: blendJoint(a.legR, b.legR, t),
  };
}

/** Swaps left and right, for the moves that alternate sides each half cycle. */
function mirror(pose) {
  return { ...pose, armL: pose.armR, armR: pose.armL, legL: pose.legR, legR: pose.legL };
}

function controlsAt(spec, t) {
  if (spec.sweep) return spec.sweep(t);
  if (spec.alternate) {
    // Two half cycles, the second one mirrored, so left and right take turns.
    const half = t < 0.5 ? t * 2 : (t - 0.5) * 2;
    const ease = (1 - Math.cos(2 * Math.PI * half)) / 2;
    const pose = blend(spec.top, spec.bottom, ease);
    return t < 0.5 ? pose : mirror(pose);
  }
  return blend(spec.top, spec.bottom, (1 - Math.cos(2 * Math.PI * t)) / 2);
}

// Secondary motion is authored, not captured training data.
function articulate(name, c, t) {
  const planted = ["SQUAT", "ARM_RAISE", "ARM_CIRCLE", "OVERHEAD_STRETCH", "CROSS_BODY_STRETCH"];
  for (const [a, l, side] of [[c.armL, c.legL, 1], [c.armR, c.legR, -1]]) {
    const phase = 2 * Math.PI * t;
    a.wrist = 7 + 9 * Math.sin(phase - 0.5) + 0.07 * a.flex;
    a.twist = 10 * Math.sin(phase - 0.3) * side;
    if (["PUSHUP", "PIKE_PUSHUP", "MOUNTAIN_CLIMBER"].includes(name)) {
      a.wrist = c.pitch + c.torso - a.flex - a.elbow + 90;
      a.twist = 0;
    }
    if (name === "PULLUP") { a.wrist = 12 + 0.08 * a.elbow; a.twist = 15; }
    if (name === "PLANK") { a.wrist = 4; a.twist = 0; }
    l.planted = planted.includes(name) || (name === "LUNGE" && l.flex >= 0);
    if (name === "HIGH_KNEES") {
      a.wrist = 4 + 4 * Math.sin(phase - 0.5);
      a.twist = 85; // Neutral running grip: palms toward the body, not the floor.
      l.planted = l.flex < 12;
      if (!l.planted) l.ankle = l.knee - l.flex - 8;
    }
    if (name === "JUMPING_JACK") l.ankle = l.knee - l.flex - 12 * Math.sin(phase) ** 2;
    if (name === "LEG_RAISE") l.ankle = 12 + 8 * Math.sin(phase - 0.4);
  }
}

function buildClip(name, spec) {
  const frames = [];
  for (let f = 0; f < FRAMES_PER_CYCLE; f++) {
    const t = f / FRAMES_PER_CYCLE;
    const controls = controlsAt(spec, t);
    articulate(name, controls, t);
    const points = fk(controls);
    const flat = [];
    // Rig space is y-up and z-out-of-chest; MediaPipe world landmarks are y-down and
    // z-away-from-camera, so the last two axes flip on the way out.
    for (const [x, y, z] of points) flat.push(round(x), round(-y), round(-z));
    frames.push(flat);
  }
  return {
    exercise: name,
    fps: Number((FRAMES_PER_CYCLE / CYCLE_SECONDS[name]).toFixed(3)),
    loop: true,
    space: "mediapipe_world",
    frames,
  };
}

const round = (v) => Number(v.toFixed(4));

fs.mkdirSync(OUT_DIR, { recursive: true });
let written = 0;
for (const [name, spec] of Object.entries(EXERCISES)) {
  const clip = buildClip(name, spec);
  // One frame per line keeps the diff readable when a clip is retuned or re-recorded.
  const body = clip.frames.map((f) => "    " + JSON.stringify(f)).join(",\n");
  const json = `{
  "exercise": ${JSON.stringify(clip.exercise)},
  "fps": ${clip.fps},
  "loop": ${clip.loop},
  "space": ${JSON.stringify(clip.space)},
  "source": "procedural_fk_v2",
  "frames": [
${body}
  ]
}
`;
  fs.writeFileSync(path.join(OUT_DIR, `${name}.json`), json);
  written++;
}
console.log(`wrote ${written} clips to ${OUT_DIR}`);
