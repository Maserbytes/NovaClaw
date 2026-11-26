#!/usr/bin/env python3
"""Held-out evaluation for NovaClaw mobile-agentic workflows & error recovery.

Run both before (e.g. --model google/gemma-4-E2B-it) and after training
(--model outputs/merged_final) against data/grpo_eval.jsonl.
"""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
from typing import Any

import torch
from transformers import AutoModelForMultimodalLM, AutoProcessor, BitsAndBytesConfig

from novaclaw_spec import (
    reward_expected_action,
    reward_format_and_thinking,
    reward_grounding_and_safety,
    split_thought_and_content,
)


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate Gemma 4 on NovaClaw held-out agent suite")
    parser.add_argument("--model", required=True)
    parser.add_argument("--eval-file", default="data/grpo_eval.jsonl")
    parser.add_argument("--max-new-tokens", type=int, default=256)
    parser.add_argument("--enable-thinking", action="store_true")
    parser.add_argument("--load-4bit", action="store_true")
    parser.add_argument("--out-report", default="")
    args = parser.parse_args()

    rows = [json.loads(line) for line in Path(args.eval_file).read_text(encoding="utf-8").splitlines() if line.strip()]
    processor = AutoProcessor.from_pretrained(args.model)
    tokenizer = processor.tokenizer
    tokenizer.padding_side = "left"

    model_kwargs: dict[str, Any] = {"device_map": "auto", "dtype": "auto"}
    if args.load_4bit:
        compute_dtype = torch.bfloat16 if (torch.cuda.is_available() and torch.cuda.get_device_capability()[0] >= 8) else torch.float16
        model_kwargs["quantization_config"] = BitsAndBytesConfig(
            load_in_4bit=True,
            bnb_4bit_use_double_quant=True,
            bnb_4bit_quant_type="nf4",
            bnb_4bit_compute_dtype=compute_dtype,
        )

    model = AutoModelForMultimodalLM.from_pretrained(args.model, **model_kwargs)
    model.eval()
    stop_ids = [tid for tid in [tokenizer.eos_token_id, tokenizer.convert_tokens_to_ids("<turn|>")] if tid is not None]

    completions: list[str] = []
    for row in rows:
        prompt_txt = tokenizer.apply_chat_template(
            row["prompt"],
            tokenize=False,
            add_generation_prompt=True,
            enable_thinking=args.enable_thinking,
        )
        inputs = tokenizer(prompt_txt, return_tensors="pt").to(model.device)
        prompt_len = inputs["input_ids"].shape[-1]
        with torch.no_grad():
            out = model.generate(
                **inputs,
                max_new_tokens=args.max_new_tokens,
                do_sample=False,
                eos_token_id=stop_ids,
            )
        decoded = tokenizer.decode(out[0][prompt_len:], skip_special_tokens=False)
        decoded = decoded.split("<turn|>")[0].split("<eos>")[0].strip()
        completions.append(decoded)

    fmt_scores = reward_format_and_thinking(completions, allowed_tools=[r["allowed_tools"] for r in rows])
    grd_scores = reward_grounding_and_safety(
        completions,
        tap_nodes=[r["tap_nodes"] for r in rows],
        package_name=[r["package_name"] for r in rows],
        read_screen_allowed=[r["read_screen_allowed"] for r in rows],
        action_succeeded=[r["action_succeeded"] for r in rows],
        last_failed_action=[r["last_failed_action"] for r in rows],
        forbidden_node_ids=[r["forbidden_node_ids"] for r in rows],
        allowed_tools=[r["allowed_tools"] for r in rows],
    )
    act_scores = reward_expected_action(
        completions,
        expected_actions=[r["expected_actions"] for r in rows],
        acceptable_tools=[r["acceptable_tools"] for r in rows],
        tap_nodes=[r["tap_nodes"] for r in rows],
        last_failed_action=[r["last_failed_action"] for r in rows],
        forbidden_node_ids=[r["forbidden_node_ids"] for r in rows],
        read_screen_allowed=[r["read_screen_allowed"] for r in rows],
        action_succeeded=[r["action_succeeded"] for r in rows],
        allowed_tools=[r["allowed_tools"] for r in rows],
    )

    by_cat: dict[str, list[float]] = defaultdict(list)
    closed_cnt = 0
    for row, comp, a_score in zip(rows, completions, act_scores):
        _, _, closed = split_thought_and_content(comp)
        if closed:
            closed_cnt += 1
        by_cat[row["category"]].append(1.0 if a_score >= 2.5 else 0.0)

    n = len(rows)
    valid_json_pct = sum(1 for s in fmt_scores if s > 0) / n * 100
    safe_pct = sum(1 for s in grd_scores if s > 0) / n * 100
    exact_pct = sum(1 for s in act_scores if s >= 2.5) / n * 100
    recovery_rows = [1.0 if a >= 2.5 else 0.0 for r, a in zip(rows, act_scores) if r["category"].startswith("recovery_")]
    recovery_pct = (sum(recovery_rows) / len(recovery_rows) * 100) if recovery_rows else 0.0

    summary = {
        "model": args.model,
        "samples": n,
        "valid_json_pct": round(valid_json_pct, 1),
        "safe_grounded_pct": round(safe_pct, 1),
        "exact_action_pct": round(exact_pct, 1),
        "recovery_exact_pct": round(recovery_pct, 1),
        "closed_thought_pct": round(closed_cnt / n * 100, 1),
        "by_category_exact_pct": {k: round(sum(v) / len(v) * 100, 1) for k, v in sorted(by_cat.items())},
    }
    print(json.dumps(summary, indent=2))
    if args.out_report:
        Path(args.out_report).write_text(json.dumps(summary, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
