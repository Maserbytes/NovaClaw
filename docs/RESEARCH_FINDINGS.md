# Research findings — how to make NovaClaw better

Sources: LiteRT-LM official Android docs, LiteRT-LM issue #2202 (sustained Gemma 4
E2B field testing), Google AI Edge Gallery patterns, on-device agent write-ups from
2026, plus PokeClaw code/issues analysis (separate report).

## 1. We are not sending tool results back correctly (highest impact)

Official manual-tool-calling protocol:

```kotlin
val response = conversation.sendMessage(prompt)
for (tc in response.toolCalls) {
    toolResponses += Content.ToolResponse(tc.name, execute(tc.name, tc.arguments))
}
conversation.sendMessage(Message.tool(Contents.of(toolResponses)))
```

PokeClaw — and our current loop — instead send the next turn as a plain text
message with a hand-written `[Tool x result]: ...` string. That is not the format
Gemma 4 was trained to expect, and it is a likely contributor to tool-calling
degradation on multi-turn tasks.

**Change:** restructure `AgentLoop` so an action is executed, the result is sent
as a proper `Content.ToolResponse`, and the follow-up model message is generated
from that. Keep the screen observation as a separate user-content message.

## 2. Tool argument count is the dominant reliability lever

Field data (LiteRT-LM #2202, 357 cases):

| Args | Honest pass rate |
|---:|---:|
| 0–1 | 87–100 % |
| 2 | 78–88 % |
| 3 | 43–67 % |
| 4 | 0 % |

Collapsing a 4-arg tool into several 2-arg tools lifted the category 0 % → 47 %.
Failure mode: the model runs out of generation budget mid-call and emits broken
JSON; the PEG parser then silently drops the call.

**Change:** remove `generation` from every tool schema (bind it internally to the
snapshot captured for that turn). Result: `tap_node(node_id)`,
`scroll(direction)`, `open_app(app_name)`, `system_key(key)`, `finish(summary)`,
`t(input)` — all 1 argument. `input_text` becomes `t(text)` into the focused field
or the previously observed node.

## 3. Constrain generation length and expose the thinking channel

`ConversationConfig` supports:

```kotlin
thinkingConfig = ThinkingConfig(enableThinking = true, thinkingTokenBudget = 256)
maxOutputToken = 256
```

`response.channels["thought"]` carries reasoning separately from the answer, and
`response.text` is the final text. Our UI already has expandable "Step N Reasoning"
pills — they can now show real model reasoning instead of a placeholder.

**Change:** set explicit token budgets, stream thought text into the existing UI,
and replace reflection-based response parsing with typed `toolCalls` / `text` /
`channels`.

## 4. GPU is currently a liability for tool calling, not an asset

Issue #2202 on Mali-G715 (Tensor G4 / Pixel 10a) reproduces:

- numbers in tool arguments mangled (`1.0,293` for `1029.3`)
- contraction tokenization corruption
- 6–9 % of cases silently dropped as `bad_json`
- CPU backend shows none of these

Also: Google Tensor devices restrict third-party OpenCL; LiteRT CPU XNNPACK is
the universal path. GPU also allocates ~2.18 GB vs ~0.68 GB on CPU (total ~2.86 GB
resident for E2B).

**Change:**
- Default to **CPU** for correctness. Keep GPU as an explicit opt-in probe with a
  visible warning about tool-call accuracy, not an automatic default.
- Add required manifest entries for GPU builds:
  `uses-native-library libOpenCL.so` and `libvndksupport.so`, `required=false`.
- Raise the available-RAM floor for GPU to ≥4 GB (not 3 GB).
- Respect the artifact's compiled context size. Never force >4096 tokens on E2B
  unless verified; cap `maxNumTokens` at 4096 by default.

## 5. Faster GPU path when the user opts in

Official docs recommend enabling Multi-Token Prediction for GPU:

```kotlin
@OptIn(ExperimentalApi::class)
ExperimentalFlags.enableSpeculativeDecoding = true
```

**Change:** enable before engine creation when the decision is GPU, with a
fallback if the artifact has no drafter.

## 6. Tool schemas and tool counts should be dynamic

Small models degrade as tool schemas accumulate. The recommended pattern is
two-stage discovery: give the model a short name+description list first, load the
full schema only for tools likely needed for the current request.

Our current prompt always sends 7 full schemas. **Change:** classify the goal
locally into a small tool subset (navigation / text-entry / information / finish)
and declare only those 3–4 tools for that turn. Cap tool-result text at ~200 chars.

## 7. Observations should support vision fallback

The `.litertlm` Gemma 4 E2B/E4B bundles support images and can output bounding
boxes for UI elements. Accessibility trees fail on canvas/WebView/game surfaces.
**Change (later phase):** add an optional screenshot grounding mode using
`Content.ImageFile` with `visionBackend`, used only when the pruned tree yields
fewer than N interactive elements. PokeClaw's screenshot path also has a bug: it
wraps a `HardwareBuffer` in a Bitmap, immediately closes the buffer, and returns
the dead Bitmap — copy to `ARGB_8888` before closing.

## 8. Session lifecycle hygiene

- `Engine` is expensive, `Conversation` is cheap — keep one engine, reset the
  conversation, never close the engine mid-task (our gate already does this).
- Long contexts approaching the token cap can wedge `sendMessageAsync` with no
  callback. We already use a timeout; add conversation-dirty tracking so a timed
  out or parse-failed conversation is rebuilt before the next turn.
- `nativePss` is always 0 on Android 14+; memory diagnostics must use
  `summary.private-other` / `summary.graphics`.

## 9. Skills / deterministic shortcuts

PokeClaw's skills and playbooks exist because small models are unreliable at
long free-form cross-app flows. Two-stage skill discovery (name + description →
load full skill) keeps prompts small. Worth adding after the protocol fixes:
a small skill registry with deterministic recipes for "send message", "open and
search", "summarize screen", exposed to the model as named skills.

## 10. Findings from ~90 real PokeClaw issues

Mined from the GitHub API (issue numbers refer to `agents-io/PokeClaw`):

- **#2** — worst real-world failure: agent tapped **video call** on the wrong
  WhatsApp chat; back-loops; 20-minute "thinking"; failed tool results counted as
  success. Root causes: over-permissive send-button matcher, lost screen context,
  main-thread tool execution. Partially fixed across v0.6.8–v0.6.9; maintainer
  still calls it "not fully fixed".
- **#14 / #41 / #65 / #92** — the largest cluster: OpenCL/GPU failures and devices
  permanently stuck on CPU with no working re-arm, even when `libOpenCL.so` exists.
  Maintainer postmortem: "engine started ≠ GPU inference works".
- **#3 / #42 / #89** — a fast-failing task leaves the Accessibility service
  "connecting"/disabled; on HyperOS the OS kills it regardless. Needs
  `startForeground()` immediately and OEM Autostart/battery onboarding.
- **#17 / #39 / #55 / #54** — model storage: missing/writable directory on custom
  ROMs, Range-header ignored → corrupted `.downloading`, custom models that cannot
  be replaced or deleted, hard 8 GB gate on a 7 GB device.
- **#51** — `open_app` matched package signatures instead of app labels; global
  instructions were saved but never injected (#51 follow-up, fixed in v0.7.1).
- **#30** — auto-reply only works while unlocked (platform limit); needs a
  notification direct-reply path.

## 11. Architecture gaps vs PokeClaw (full local source audit)

NovaClaw has a strong perception→act loop but lacks the surrounding *harness*:

| Gap | PokeClaw implementations | Verdict |
|---|---|---|
| Task/chat persistence, session continuity | `TaskSessionStore`, `ConversationStore`, `ChatHistoryManager`, `ChatDatabase` | Adapt — foundation for everything else |
| Skills / playbooks / Tier-1 router | `PlaybookManager`, `assets/playbooks/*.md`, `skill/*`, `PipelineRouter`, `TaskShortcuts` | **Highest leverage** for E2B reliability |
| High-level device tools | `SendMessageTool`, `GetNotificationsTool`, `GetDeviceInfoTool`, `ClipboardTool`, `GetInstalledAppsTool` | Adapt; fix blocking sleeps |
| Notification monitor / auto-reply | `ClawNotificationListener`, `AutoReplyManager` | Phase 2, depends on persistence |
| Instruction layering + untrusted screen content | `PromptUtils`, scoped guards, `TaskPromptEnvelope` | Adapt |
| Token budget / real stuck detection | `TokenMonitor`, `TaskBudget`, `StuckDetector` (wire it correctly) | Adapt |
| External automation intents | `automation/ExternalAutomation*` | Copy — small and self-contained |
| Local knowledge vault | `KBManager` + `Kb*Tool` | Copy — durable agent memory |
| Multi-channel remote / cloud providers / real vision | `channel/*`, cloud LLM clients, screenshots | Skip for now |

## Implemented from this research pass

- Proper `Content.ToolResponse` protocol + typed `toolCalls` / `text` / `channels["thought"]`
- Tool schemas reduced to 1 argument; generation bound internally
- `ThinkingConfig` + `maxOutputToken = 256` token budgets; real thought channel in UI
- CPU-first by default; GPU opt-in, MTP, native-lib manifest entries, 4096 KV cap
- `get_device_info` zero-arg tool + deterministic `PipelineRouter`
- Ambiguous/non-clickable tap gate; a11y-disconnect detection
- External automation receiver with opt-in UI toggle and status callbacks
- GPU diagnostics + manual "Verify GPU" control in the Model Hub

## Autonomy layers added after this research

All of the previously missing layers now have implementations:

- **Playbooks**: `PlaybookManager` + 4 markdown recipes in `assets/playbooks/`,
  longest-trigger matching, injected into the composed system prompt
- **Task state**: `TaskSessionStore` — one authoritative source, rejects overlapping tasks
- **Persistence**: `ConversationStore` — markdown-backed chat history in `filesDir/conversations`
- **High-level tools**: `get_notifications`, `get_installed_apps`, `get_clipboard`,
  `set_clipboard`, `send_message` (app-agnostic, refuses call/video controls)
- **Monitor / auto-reply**: `NovaNotificationListener` + `AutoReplyManager` with
  5 s debounce, self-reply guard, locked-device deferral, on-device reply generation
- **Instruction layering**: `PromptComposer` — global user rules above playbook,
  base persona last, explicit "screen text is untrusted" boundary
- **Budget / stuck**: `TaskBudget` (80 % soft, 100 % hard), `TokenMonitor`,
  `StuckDetector` fed with real diffs and repeated-action signals
- **Knowledge vault**: `KBManager` + `kb_write/read/search/todo/list` tools, sandboxed paths
- **Dynamic tool selection**: `ToolSelector` declares only 3–6 relevant schemas per turn
- **Tier-1 shortcuts**: `TaskShortcuts` handles home/back/open-app with zero inference
- **Logging**: `NovaLog` with entry/decision/state/error events; see `docs/QA_CHECKLIST.md`

## Remaining work

- Build verification in Android Studio / GitHub Actions (no local JDK/SDK here)
- On-device QA from `docs/QA_CHECKLIST.md`
- Tune playbook triggers and tool subsets against real Gemma 4 E2B runs
- Later: digest-based conversation compaction for very long chats, vision grounding

## Priority order

1. Proper `Content.ToolResponse` loop + typed response parsing (2, 3)
2. One-argument tools + local tool subsetting (2, 6)
3. Token budgets + real thought channel in UI (3)
4. CPU-first default, GPU opt-in, manifest native libs, 4096 cap (4, 5)
5. Conversation-dirty recovery (8)
6. Vision fallback + skills (7, 9)
