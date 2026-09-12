# Voice and model update

The default model is Qwen3-4B-Instruct-2507 Q4_K_M, Apache 2.0, using the existing ARM64 llama.cpp runtime. It uses ChatML without thinking blocks, a 2,048-token context, six CPU threads for prompt processing and four for token generation. UI streaming is coalesced and replies use the existing brief-answer limit.

Source: https://huggingface.co/lmstudio-community/Qwen3-4B-Instruct-2507-GGUF

- Revision: `4edb920b6f14e3b9284d4502a6485103d72cde05`
- File: `Qwen3-4B-Instruct-2507-Q4_K_M.gguf`
- Size: 2,497,280,448 bytes
- SHA-256: `8cdb57cbb880d313736a9bc4e3d3d2485f145b5e19cf33783746e753e82641fc`

Commands run before model inference. Generic start requests choose today's saved plan; bare start resumes an active workout or starts today's plan. Stop pauses and saves the workout. The coach page can control a still-open workout. Polite forms, articles, synonyms and unique minor button-name spelling errors are supported. Negation, advice questions and ambiguous matches do not trigger guessed actions.

Voice remains available for follow-ups while its screen or orb is open. Silence restarts recognition; transient audio/busy errors retry twice. Input pauses during TTS and releases on navigation so foreground wake listening can resume. Background and locked-phone listening remain disabled.

A workout pose-detector shutdown race found during navigation testing is fixed. AR scanning and mannequin data are unchanged. Earlier measurements in offline-coach.md describe the previous 1.7B model, not this model's performance.

If the larger file is not installed yet, an existing checksum-verified Qwen3-1.7B file remains usable. Once the new verified file is installed, the next request reloads the preferred model. Each model uses its appropriate chat template.

Validation so far: 99 unit tests pass; device tests verify generic start, stop from workout and coach screens, microphone release on navigation, voice navigation and workout-orb controls. The physical wake-word and speech services remain sensitive to audio conditions; these tests do not guarantee recognition of every spoken phrase.

Fallback-model tuning probe: first-token times changed from 10.61/7.64/9.04 seconds with four prompt threads to 8.92/5.98/8.15 seconds with six, with identical outputs. These are three on-device observations during a download, not general latency guarantees. Both runs passed cancellation. Reports: build/coach-fallback-result.json and build/coach-six-thread-result.json.
