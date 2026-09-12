"""Extract benchmark poses with the exact model asset used by the Android counter.

Usage: py tools/extract_motion.py bench/SQUAT-front_NA.mp4 SQUAT --end 15
Writes a trace for review; does not silently replace a demo with an unreviewed capture.
"""
import argparse
import hashlib
import json
from pathlib import Path

import cv2
import mediapipe as mp

p = argparse.ArgumentParser()
p.add_argument('video', type=Path)
p.add_argument('exercise')
p.add_argument('--end', type=float, default=60)
p.add_argument('--output', type=Path, default=Path('build/video-review'))
args = p.parse_args()
root = Path(__file__).resolve().parents[1]
model = root / 'app/src/main/assets/pose_landmarker_lite.task'
cap = cv2.VideoCapture(str(args.video))
fps = cap.get(cv2.CAP_PROP_FPS)
assert fps > 0, 'Cannot read video'
step = max(1, round(fps / 15))
opts = mp.tasks.vision.PoseLandmarkerOptions(
    base_options=mp.tasks.BaseOptions(model_asset_path=str(model)),
    running_mode=mp.tasks.vision.RunningMode.VIDEO)
samples = []
with mp.tasks.vision.PoseLandmarker.create_from_options(opts) as detector:
    for i in range(int(args.end * fps)):
        if not cap.grab():
            break
        if i % step:
            continue
        ok, bgr = cap.retrieve()
        if not ok:
            break
        ts = round(i * 1000 / fps)
        result = detector.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB,
            data=cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)), ts)
        if result.pose_world_landmarks:
            world, image = result.pose_world_landmarks[0], result.pose_landmarks[0]
            samples.append(dict(ms=ts,
                world=[v for j in world for v in (j.x, j.y, j.z)],
                normalized=[v for j in image for v in (j.x, j.y, j.z)],
                visibility=[j.visibility for j in image]))
cap.release()
trace = dict(exercise=args.exercise, sourceVideo=args.video.name,
    sourceSha256=hashlib.sha256(args.video.read_bytes()).hexdigest(),
    modelSha256=hashlib.sha256(model.read_bytes()).hexdigest(),
    runtime=f'mediapipe-python-{mp.__version__}', samples=samples)
args.output.mkdir(parents=True, exist_ok=True)
(args.output / f'{args.exercise}-pose.json').write_text(json.dumps(trace), encoding='utf-8')
print(args.exercise, 'samples', len(samples), 'fully visible',
    sum(min(s['visibility'][11:]) >= .5 for s in samples), flush=True)
