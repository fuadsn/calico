# Voice and model update

The default model is Qwen3-4B-Instruct-2507 Q4_0, Apache 2.0, running entirely on the phone's
Hexagon NPU. It uses ChatML without thinking blocks, a 2,048-token context, a 512-token batch and
flash attention. UI streaming is coalesced and replies use the existing brief-answer limit.

The quantisation is Q4_0 rather than Q4_K_M because the Hexagon backend accepts Q4_0, Q8_0, MXFP4
and F16 weights only. See [offline coach](offline-coach.md) for the runtime and build details.

Source: https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF

- Revision: `a06e946bb6b655725eafa393f4a9745d460374c9`
- File: `Qwen3-4B-Instruct-2507-Q4_0.gguf`
- Size: 2,375,773,280 bytes
- SHA-256: `e0ba675d86ab277c61701c6793659b2ae801d95e3be791464c321e6fbf613be2`

Commands run before model inference. Generic start requests choose today's saved plan; bare start resumes an active workout or starts today's plan. Stop pauses and saves the workout. The coach page can control a still-open workout. Polite forms, articles, synonyms and unique minor button-name spelling errors are supported. Negation, advice questions and ambiguous matches do not trigger guessed actions.

Voice remains available for follow-ups while its screen or orb is open. Silence restarts recognition; transient audio/busy errors retry twice. Input pauses during TTS and releases on navigation so foreground wake listening can resume. Background and locked-phone listening remain disabled.

A workout pose-detector shutdown race found during navigation testing is fixed. AR scanning and mannequin data are unchanged. Earlier measurements in offline-coach.md describe the previous 1.7B model, not this model's performance.

The Qwen3-1.7B fallback is gone: Q4_K files cannot run on the NPU, so an installed Q4_K model is
deleted once the Q4_0 file verifies. Only the one chat template remains.

Validation so far: 99 unit tests pass; device tests verify generic start, stop from workout and coach screens, microphone release on navigation, voice navigation and workout-orb controls. The physical wake-word and speech services remain sensitive to audio conditions; these tests do not guarantee recognition of every spoken phrase.

Fallback-model tuning probe: first-token times changed from 10.61/7.64/9.04 seconds with four prompt threads to 8.92/5.98/8.15 seconds with six, with identical outputs. These are three on-device observations during a download, not general latency guarantees. Both runs passed cancellation. Reports: build/coach-fallback-result.json and build/coach-six-thread-result.json.
