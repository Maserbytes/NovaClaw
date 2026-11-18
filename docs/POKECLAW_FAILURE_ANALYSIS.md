# PokeClaw failure analysis → NovaClaw fixes

Source: code review of `agents-io/PokeClaw` @ `main` (fetched via raw.githubusercontent.com).
Line numbers are approximate; method names and behavior are exact.

## Priority 1 — silent tool failures / ANRs

| ID | Root cause in PokeClaw | NovaClaw fix |
|----|------------------------|--------------|
| P1-1 | `ClawAccessibilityService.dispatchGestureSync()` waits on a `CountDownLatch` with `handler == null`, so callbacks post to the main thread. If called from a main-thread "Tier 1" tool, the thread blocks on itself → 5 s freeze. On some ROMs callbacks never fire, so every tap burns 5 s and may report failure after the UI actually changed. `SwipeTool` allows unbounded `duration_ms` > 5 s wait. | Gestures dispatched from a dedicated `HandlerThread` callback handler; timeout derived from stroke duration + 2 s; all calls on `Dispatchers.Default`; never on main. |
| P1-2 | `getScreenTree()` clears and reassigns the global `nodeIdMap` on every call. Three separate paths (pre-warm, post-action auto-attach, finish guard) refresh it. A `[n3]` read by the model can point at a different element when `tap_node("n3")` executes → wrong tap reported as success. | Immutable `ScreenSnapshot(generation, elements, coords)`. Actions require the observed generation; stale snapshots are rejected with a clear "re-observe" error. One snapshot per model decision. |
| P1-3 | `ACTION_SET_TEXT`, `ACTION_FOCUS`, `ACTION_CLICK` return values discarded; no read-back. Apps can accept the action and ignore it → "success" without text change. | `setTextVerified()` re-queries the node and compares text; click/tap compare pre/post screen fingerprint and report "no observable change" distinctly. |
| P1-4 | `get_screen_info` returning null is always mapped to `SYSTEM_DIALOG_BLOCKED` and kills the task, even for lockscreen/transition/rebind. | Retry with backoff; distinguish `TransientError`, `Blocked` (actual dialog window present), and `Ok`. |
| P1-5 | StuckDetector inputs are dead: `screenDiffCount` = total line count (not diff), `error` hardcoded `null`, `consecutiveNoToolCalls` never incremented, `isStuckInLoop()` never called. 2 of 5 signals can never fire; another misfires on non-UI loops. | Real diff count (`added + removed`), real tool errors, repeated-state detection, and a token/step budget. Clear termination reason instead of infinite loop. |
| P1-6 | Empty LLM response branch calls `onComplete` then `continue` (should be `return`) → loop spins up to max iterations after reporting completion. | Terminal states immediately exit the loop in our `AgentLoop`. |
| P1-7 | `cancel()` deliberately avoids interrupting local inference, but `updateConfig()`/`shutdown()` call `shutdownNow()` + `llmClient.close()` anyway → native use-after-free. | Single `LocalRuntimeGate` write lock; inference rounds drained before close. |
| P1-8 | `BaseTool.wait_after` sleeps up to 10 s on the caller; `InputTextTool.setClipboardText()` posts to main looper then blocks on a latch → self-deadlock. | No sleeps on main; clipboard called directly; pacing only in the coroutine loop. |
| P1-9 | `requireAccessibilityService(timeoutMs = 20_000)` polls on every tool; single-thread executor freezes 20 s. `Runtime.exec("input keyevent").waitFor()` has no timeout. | Latch signaled from `onServiceConnected` (≤3 s); shell commands with `waitFor(timeout)` + `destroyForcibly()`. |
| P1-10 | Foreground service never started/updated by the agent loop; `START_NOT_STICKY`; in-process Handler "watchdog" dies with the process. | Foreground service started at task begin and updated per step; `START_STICKY`. |

## Priority 2 — OpenCL / GPU / memory crashes

| ID | Root cause in PokeClaw | NovaClaw fix |
|----|------------------------|--------------|
| P2-1 | `LocalBackendHealth.shouldStartCpuConservatively()` forces CPU on all MediaTek/Dimensity/Xiaomi/Redmi/Poco devices. GPU verification requires creating a GPU engine, which conservative mode prevents → permanent CPU lock-in. Also `"mt"` substring matches unrelated hardware. Re-arm mismatch: quarantine reason `gpu_failure` vs `gpu_init_crash` check. | Explicit one-shot GPU probe (opt-in/auto after RAM screen) rather than permanent blocklist; stable device key; re-arm on both reason prefixes; word-boundary hardware hints. |
| P2-2 | Shared `Engine` is closed by `forceCpuEngine()`/`resetSharedEngine()` while other conversations are live (chat, task, single-shot) → native use-after-free / SIGSEGV. | `LocalRuntimeGate` read/write lock covering engine creation, conversation open, and close; active lease count; no close while leases active. |
| P2-3 | GPU failure detection is message-string-only; no supervision around `conv.sendMessage()`, no timeout. OOM/SIGSEGV throws no Java exception → process dies; retries repeat against same broken engine. | Broadened detection (OOM, bad_alloc, dlopen, delegate, allocation), crash marker around both init and inference, `withTimeout` around inference, quarantine after N failures. |
| P2-4 | `getDeviceRamGb()` adds artificial `+1`, so 11 GB reports 12 GB and selects the 3.6 GB E4B model. Ignores `availMem`/`lowMemory`. | No `+1`; consider available memory and low-memory flag; recommend E2B by default, E4B only with ≥12 GB real RAM and ≥6 GB available. |
| P2-5 | `maxNumTokens = 8192` hardcoded for all devices/backends → KV-cache OOM on 8 GB phones, especially GPU. | Scale KV cache: 4096 on GPU / low-RAM, 8192 only on ≥12 GB CPU; writable roomy cache dir. |
| P2-6 | Size-only model validation with ~5 % tolerance accepts downloads truncated by 100+ MB → native crash on load. Custom models accept any file >1 MB. | Exact expected size with small tolerance when `Content-Length` known; resume via Range; SHA-256 recorded on first successful load and required thereafter; corrupt partial cleanup. |
| P2-7 | `Bitmap.wrapHardwareBuffer()` then `result.getHardwareBuffer().close()` → returned Bitmap references a dead buffer. New executor per screenshot leaks threads. | Copy to `ARGB_8888` before closing the buffer; one shared executor. |
| P2-8 | Conversation replay every 8 sends drops all `AiMessage` turns → context loss, token blow-up. | Condensed summary on recreate; no blind replay of stale screen trees. |
| P2-9 | Device key = `Build.FINGERPRINT` (changes on OTA) + PID equality (PIDs recycled) → crashes recur after OTA or stale markers ignored. | Stable key from BOARD/DEVICE/HARDWARE/BRAND + boot-instance epoch. |
| P2-10 | `singleShotLocal` races with running task on the same engine; only post-hoc string matching. | Serialized conversation creation via gate; clean "engine busy" result. |

## README claims that do not match PokeClaw code

- "Five stuck-detection signals" — two inert, one misfiring, no token signal.
- "GPU verified on Dimensity/Snapdragon" — all MediaTek/Dimensity devices forced to CPU with no path to verification.
- "Direct tool execution no longer blocks the app thread" — multiple up-to-10 s main-thread blocking paths remain.
- "Foreground service starts as soon as a task begins" — the agent loop never calls it.
- "take_screenshot works on Android 9+" — implementation returns null below API 30.
- "Local chat continuity survives relaunch" — assistant turns dropped on conversation recreate.

## Additional constraint found in NovaClaw UI

The generated UI's model catalog downloads **GGUF** files (`gemma-2-2b-it-Q4_K_M.gguf`, Llama GGUF), but LiteRT-LM loads **`.litertlm`** files. NovaClaw's catalog is corrected to the real models:

- Gemma 4 E2B: `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm` (~2.58 GB, 8 GB RAM min)
- Gemma 4 E4B: `https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm` (~3.65 GB, 10 GB RAM min)

## Licensing note

PokeClaw is Apache-2.0 but its README states forks must be renamed before distribution and reserves the PokeClaw name/logo. NovaClaw is an independent reimplementation of the architecture, keeps its own name, and credits PokeClaw/Gemma/LiteRT in its README.
