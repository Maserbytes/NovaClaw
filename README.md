# NovaClaw — on-device agentic phone operator

A fresh Android implementation of the PokeClaw architecture with the NovaClaw
ChatGPT-style UI, rebuilt around verified actions and adaptive local inference.

## Status

**Alpha — full harness, awaiting build/device QA.** Implemented: verified agent
loop, accessibility harness, adaptive GPU/CPU selector, model manager, playbooks,
task/chat persistence, high-level device tools, notification monitor with
auto-reply, instruction layering, budgets, knowledge vault, external automation,
and the offline glassy UI. Build and on-device QA are the next step
(see `docs/QA_CHECKLIST.md`).

## What is different from PokeClaw

See [`docs/POKECLAW_FAILURE_ANALYSIS.md`](docs/POKECLAW_FAILURE_ANALYSIS.md) for the
code-level findings. Short version:

- Every gesture has a background callback thread and a duration-derived timeout.
- Observations are immutable, generation-stamped snapshots. Stale node IDs are
  rejected instead of tapping the wrong element.
- Actions report before/after fingerprints. "No screen change" is an explicit
  failure with self-correction, not a silent success.
- Text entry is read back before it is reported as successful.
- The stuck detector receives real diffs and real tool errors.
- GPU is selected through a bounded probe with crash markers and truthful
  fallback, not a permanent CPU lock-in on MediaTek/Xiaomi.
- The LiteRT engine is behind a read/write gate; conversations are never closed
  underneath a live native call.
- RAM estimates have no artificial +1 GB; KV cache size is device-aware.
- Model downloads use real `.litertlm` files, resume, tight size checks and a
  recorded SHA-256, not the GGUF files the old UI tried to use.

## Layout

```
app/src/main/
  assets/                     NovaClaw web UI (offline, Tailwind classes compiled locally)
  playbooks/                  4 markdown recipes for the local model
  java/ai/novaclaw/
    a11y/                     Screen snapshots + verified gesture dispatch
    automation/               Tasker/MacroDroid intent entry point
    engine/                   ReAct loop, tools, playbooks, router, budgets, KB tools
    knowledge/                Sandboxed local markdown vault
    model/                    LiteRT engine, backend selector, model download
    safety/                   Deterministic guard rails
    service/                  Foreground service, kill switch, notification monitor, auto-reply
    state/                    Task session + conversation persistence
    ui/                       WebView host + JavaScript bridge
    util/                     Traceable logging
```

## Build

1. Open `NovaClaw/` in Android Studio (Giraffe+ / AGP 8.11). Let Android Studio
   generate the Gradle wrapper if it asks; no wrapper binary is committed here.
2. Sync Gradle, then build the debug APK.
3. Install on an arm64 Android 9+ phone (12+ recommended).
4. Enable **NovaClaw Accessibility Service** in Settings → Accessibility.
   On Android 14: App Info → 3-dot menu → Allow restricted settings first.
5. Open Model Hub in the app and download **Gemma 4 E2B** (~2.58 GB, 8 GB RAM min).
6. Send a task in chat. The thought/action/verification timeline shows every step.

## Attribution

Architecture and model/tool ideas are derived from
[PokeClaw](https://github.com/agents-io/PokeClaw) (Apache-2.0). PokeClaw is a
trademark of its owner; this project is an independent reimplementation under a
different name. On-device inference uses
[Gemma 4 / LiteRT-LM](https://ai.google.dev/edge/litert/llm/overview) by Google.
