# NovaClaw Mobile Agent Fine-Tuning Pipeline (Gemma 4 E2B / E4B)

This folder implements a 3-stage fine-tuning and mobile-export pipeline aligned with Google's official Gemma 4 Fine-Tuning Hub (`google-gemma/gemma-skills`) and NovaClaw's exact on-device `TaskDecision` / `ScreenSnapshot` / `SafetyShield` architecture.

> **Hardware note:** Training and `.litertlm` conversion require a Linux/Colab CUDA GPU (16 GB VRAM minimum for E2B QLoRA + GRPO, e.g. Colab T4, L4, or A100). Dataset generation and verifier tests run locally on any CPU machine with Python.

## Pipeline Architecture

1. **Dataset Generation (`build_dataset.py`)**
   Produces isolated `sft_train.jsonl`, `sft_val.jsonl`, `grpo_train.jsonl`, and `grpo_eval.jsonl` splits covering:
   - Exact NovaClaw prompts (`PromptComposer`, `TaskDecision.instructions`, `ScreenSnapshot.compactPrompt`, `Recent tool results`)
   - Android OS & app workflow grounding (Launcher, Clock, Calculator, WhatsApp/Messages, Chrome, Maps, YouTube, Gmail, Calendar, Files, Contacts)
   - **Self-healing error recovery** mapped 1:1 to NovaClaw's runtime failure messages:
     - Stale snapshot / moved node (`"The active screen changed..."`, `"Unknown node..."`, `"not clickable"`) → re-ground on fresh `SCREEN [gen]`
     - Unconfirmed / missed tap (`"Android did not confirm the tap"`) → pick alternate clickable target instead of repeating
     - Unfocused input (`"Editable field not found"`) → tap field before calling `t(...)`
     - Rejected IME Enter (`"Use a visible submit or search control"`) → tap visible Search/Send/Go button
     - Unexpected permission / consent / rating / ANR popups → dismiss/allow and resume goal
     - Missing items → scroll down; bottom reached → pivot to search or emit `blocked`
     - Wrong submenu → `system_key({"key":"back"})`
     - `SafetyShield` protected-app boundaries & PokeClaw #2 `Send` vs `Voice/Video call` trap
     - Untrusted screen prompt-injection defense
   - High-level device tools (`get_device_info`, `get_notifications`, `get_clipboard`, `kb_write`) + ~10% general chat replay buffer.

2. **Stage 1 — QLoRA SFT (`train_sft.py`)**
   - Loads `google/gemma-4-E2B-it` (or `google/gemma-4-E4B-it`) in 4-bit NF4.
   - Freezes `vision_tower` & `audio_tower` (`exclude_modules`) to preserve multimodal capabilities.
   - Uses prompt-completion masking (`SFTTrainer`) and merges adapters back into 16-bit weights before Stage 2.

3. **Stage 2 — Verifiable-Rewards RL (`train_grpo.py`)**
   - Runs `GRPOTrainer` (`beta=0.0`, `num_generations=4`) with three deterministic verifiers from `novaclaw_spec.py`:
     - `reward_format_and_thinking`: closed concise `<|channel>thought` + valid `TaskDecision` JSON
     - `reward_grounding_and_safety`: grounded `TAP [node_id]`, no repeated failed actions, no forbidden/injection/protected taps
     - `reward_expected_action`: exact recovery/action match

4. **Evaluation & LiteRT-LM Export (`eval_agent.py`, `export_litertlm.sh`)**
   - Benchmarks base vs fine-tuned checkpoint on held-out `grpo_eval.jsonl`.
   - Exports merged weights to `.litertlm` via `litert-torch export_hf --externalize_embedder` and verifies with `litert-lm run`.

## Quick Start

### Local Dataset Build & Verification (CPU)
```bash
cd training
python build_dataset.py --out-dir data
python -m unittest test_training_suite.py -v
```

### GPU Fine-Tuning & Mobile Export (Colab / Linux CUDA GPU)
Either open `NovaClaw_Gemma4_Finetune.ipynb` in Google Colab (T4 / L4 / A100), or run:
```bash
pip install -r requirements.txt
huggingface-cli login   # accept Gemma 4 license on Hugging Face first

python build_dataset.py --out-dir data
python eval_agent.py --model google/gemma-4-E2B-it --load-4bit --out-report outputs/eval_before.json
python train_sft.py --model google/gemma-4-E2B-it --epochs 2
python train_grpo.py --model outputs/merged_sft --max-steps 80
python eval_agent.py --model outputs/merged_final --load-4bit --out-report outputs/eval_after.json
bash export_litertlm.sh outputs/merged_final outputs/litert_out E2B
```
