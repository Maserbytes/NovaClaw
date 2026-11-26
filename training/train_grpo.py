#!/usr/bin/env python3
"""Stage 2: Verifiable-Rewards Reinforcement Learning (GRPO) for NovaClaw.

Optimizes the SFT-merged Gemma 4 checkpoint against NovaClaw's exact deterministic
verifiers (strict TaskDecision JSON, grounded TAP node IDs, non-repeating
error recovery, SafetyShield boundaries, and concise thought blocks).
"""

from __future__ import annotations

import argparse
import gc
from pathlib import Path

import torch
from datasets import load_dataset
from peft import LoraConfig, PeftModel
from transformers import AutoModelForMultimodalLM, AutoProcessor, BitsAndBytesConfig
from trl import GRPOConfig, GRPOTrainer

from novaclaw_spec import (
    reward_expected_action,
    reward_format_and_thinking,
    reward_grounding_and_safety,
)


def supports_bf16() -> bool:
    if torch.cuda.is_available():
        major, _ = torch.cuda.get_device_capability()
        return major >= 8
    return False


def main() -> None:
    parser = argparse.ArgumentParser(description="NovaClaw Gemma 4 GRPO RLVR trainer")
    parser.add_argument("--model", default="outputs/merged_sft", help="Base or SFT-merged checkpoint")
    parser.add_argument("--train-file", default="data/grpo_train.jsonl")
    parser.add_argument("--adapter-dir", default="outputs/grpo_adapter")
    parser.add_argument("--merged-dir", default="outputs/merged_final")
    parser.add_argument("--max-steps", type=int, default=80)
    parser.add_argument("--num-generations", type=int, default=4)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--lr", type=float, default=5e-5)
    parser.add_argument("--lora-r", type=int, default=16)
    parser.add_argument("--lora-alpha", type=int, default=32)
    parser.add_argument("--use-vllm", action="store_true", help="Enable colocated vLLM on Linux GPU")
    parser.add_argument("--skip-merge", action="store_true")
    args = parser.parse_args()

    use_bf16 = supports_bf16()
    compute_dtype = torch.bfloat16 if use_bf16 else torch.float16

    bnb_cfg = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_use_double_quant=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=compute_dtype,
        bnb_4bit_quant_storage=compute_dtype,
    )

    processor = AutoProcessor.from_pretrained(args.model)
    tokenizer = processor.tokenizer

    model = AutoModelForMultimodalLM.from_pretrained(
        args.model,
        dtype=compute_dtype,
        device_map="auto",
        quantization_config=bnb_cfg,
    )

    peft_cfg = LoraConfig(
        r=args.lora_r,
        lora_alpha=args.lora_alpha,
        lora_dropout=0.0,
        bias="none",
        target_modules=[
            "q_proj", "k_proj", "v_proj", "o_proj",
            "gate_proj", "up_proj", "down_proj",
        ],
        exclude_modules=r"model\.(vision_tower|audio_tower)\..*",
        task_type="CAUSAL_LM",
    )

    train_ds = load_dataset("json", data_files=args.train_file, split="train")

    grpo_kwargs = dict(
        output_dir=args.adapter_dir,
        learning_rate=args.lr,
        lr_scheduler_type="cosine",
        warmup_steps=0.05,
        beta=0.0,
        num_generations=args.num_generations,
        max_completion_length=256,
        temperature=0.9,
        chat_template_kwargs={"enable_thinking": True},
        per_device_train_batch_size=args.batch_size,
        gradient_accumulation_steps=1,
        gradient_checkpointing=True,
        max_steps=args.max_steps,
        optim="adamw_bnb_8bit",
        max_grad_norm=0.5,
        bf16=use_bf16,
        fp16=not use_bf16,
        logging_steps=5,
        save_strategy="steps",
        save_steps=max(20, args.max_steps // 2),
        report_to="none",
    )
    if args.use_vllm:
        grpo_kwargs["use_vllm"] = True
        grpo_kwargs["vllm_mode"] = "colocate"

    trainer = GRPOTrainer(
        model=model,
        reward_funcs=[
            reward_format_and_thinking,
            reward_grounding_and_safety,
            reward_expected_action,
        ],
        args=GRPOConfig(**grpo_kwargs),
        train_dataset=train_ds,
        peft_config=peft_cfg,
        processing_class=tokenizer,
    )

    trainer.train()
    trainer.save_model(args.adapter_dir)
    processor.save_pretrained(args.adapter_dir)

    if args.skip_merge:
        return

    del trainer, model
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()

    base_model = AutoModelForMultimodalLM.from_pretrained(
        args.model,
        low_cpu_mem_usage=True,
        dtype=compute_dtype,
        device_map="auto",
    )
    peft_model = PeftModel.from_pretrained(base_model, args.adapter_dir)
    merged = peft_model.merge_and_unload()
    Path(args.merged_dir).mkdir(parents=True, exist_ok=True)
    merged.save_pretrained(args.merged_dir, safe_serialization=True, max_shard_size="2GB")
    processor.save_pretrained(args.merged_dir)
    print(f"Saved final merged GRPO checkpoint to {args.merged_dir}")


if __name__ == "__main__":
    main()
