"""Promote an explicitly reviewed, complete repetition from a benchmark pose trace.
Example: py tools/import_motion.py build/video-review/SQUAT-pose.json --start 7861 --end 10392
The same file holds world motion and normalized counter input, with matching timestamps.
"""
import argparse
import json
import hashlib
from pathlib import Path
import numpy as np

p = argparse.ArgumentParser()
p.add_argument('trace', type=Path)
p.add_argument('--start', type=int, required=True)
p.add_argument('--end', type=int, required=True)
p.add_argument('--arm-fallback', action='store_true', help='Explicitly use authored arms when 3D arm lengths are unreliable')
args = p.parse_args()
trace = json.loads(args.trace.read_text(encoding='utf-8'))
samples = [s for s in trace['samples'] if args.start <= s['ms'] < args.end]
assert len(samples) >= 16 and args.end - args.start >= 1000, 'Need a complete repetition'
assert all(min(s['visibility'][11:]) >= .5 for s in samples), 'Occluded body or extremities'
assert all(0 < b['ms'] - a['ms'] <= 100 for a, b in zip(samples, samples[1:])), 'Detection gap'
root = Path(__file__).resolve().parents[1]
model = root / 'app/src/main/assets/pose_landmarker_lite.task'
assert hashlib.sha256(model.read_bytes()).hexdigest() == trace['modelSha256'], 'Detector model changed; re-extract'
video = root / 'bench' / Path(trace['sourceVideo']).name
assert video.is_file() and hashlib.sha256(video.read_bytes()).hexdigest() == trace['sourceSha256'], 'Source video mismatch'
name = trace['exercise']
assert name.isupper() and name.replace('_', '').isalpha()
# Circular three-sample smoothing reduces landmark jitter without changing rep timing.
frames = [[round(sum(samples[(f + d) % len(samples)]['world'][i] for d in (-1, 0, 1)) / 3, 6)
           for i in range(99)] for f in range(len(samples))]
raw_world = np.array(frames).reshape(-1, 33, 3)
assert np.isfinite(raw_world).all(), 'Non-finite world coordinates'
chains = [(11, 13), (13, 15), (12, 14), (14, 16), (23, 25), (25, 27), (24, 26), (26, 28)]
lengths = np.array([np.linalg.norm(raw_world[:, a] - raw_world[:, b], axis=1) for a, b in chains])
variation = (lengths.max(axis=1) - lengths.min(axis=1)) / lengths.mean(axis=1)
assert max(variation[4:]) <= .3, 'Unreliable leg reconstruction; do not promote'
bad_arms = max(variation[:4]) > .3
assert not bad_arms or args.arm_fallback, 'Unreliable arms: review footage or explicitly select --arm-fallback'
fallback_indices = []
if bad_arms:
    authored = json.loads((root / f'roomscan/src/main/assets/clips/{name}.json').read_text())
    a_frames = np.array(authored['frames']).reshape(-1, 33, 3)
    clean = raw_world.copy()
    for f, target in enumerate(clean):
        position = f * len(a_frames) / len(clean)
        a = a_frames[int(position) % len(a_frames)]
        b = a_frames[(int(position) + 1) % len(a_frames)]
        source = a + (b - a) * (position % 1)
        u = source[[11, 12]].mean(axis=0) - source[[23, 24]].mean(axis=0)
        v = target[[11, 12]].mean(axis=0) - target[[23, 24]].mean(axis=0)
        u /= np.linalg.norm(u); v /= np.linalg.norm(v)
        cross = np.cross(u, v); cosine = np.dot(u, v)
        assert cosine > -.99, 'Inconsistent torso orientation'
        k = np.array([[0, -cross[2], cross[1]], [cross[2], 0, -cross[0]], [-cross[1], cross[0], 0]])
        rotation = np.eye(3) + k + k @ k / (1 + cosine)
        for side in (0, 1):
            shoulder = 11 + side
            for joint in range(13 + side, 23, 2):
                target[joint] = target[shoulder] + rotation @ (source[joint] - source[shoulder])
    frames = clean.reshape(-1, 99).round(6).tolist()
    fallback_indices = list(range(13, 23))
clip = {k: v for k, v in trace.items() if k != 'samples'}
clip.update(source='benchmark_video', startMs=args.start, endMs=args.end,
    fps=len(samples) * 1000 / (args.end - args.start), loop=True, space='mediapipe_world',
    smoothing='circular_3_sample_mean_world_only', frames=frames,
    normalizedFrames=[s['normalized'] for s in samples],
    visibilityFrames=[s['visibility'] for s in samples],
    sampleTimesMs=[s['ms'] for s in samples])
clip.update(sourceWorldFrames=raw_world.reshape(-1, 99).tolist(),
    authoredFallbackLandmarks=fallback_indices,
    quality=dict(limbLengthVariation=variation.tolist(), maxAcceptedLegVariation=.3,
        arms='authored_fallback' if bad_arms else 'recorded', legs='recorded',
        note='Visibility and geometric checks are not biomechanical ground truth'))
folder = root / 'roomscan/src/main/assets/recorded-clips'
folder.mkdir(exist_ok=True)
(folder / f'{name}.json').write_text(json.dumps(clip, separators=(',', ':')), encoding='utf-8')
print(name, 'imported', len(samples), 'frames from', trace['sourceVideo'])
