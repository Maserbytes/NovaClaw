#!/usr/bin/env python3
"""Stage 1: QLoRA Supervised Fine-Tuning (SFT) for NovaClaw on Gemma 4 (E2B / E4B).

Follows google-gemma/gemma-skills guidelines:
- Freezes vision_tower & audio_tower (exclude_modules) so multimodal capabilities stay intact
- Uses prompt-completion masking (only assistant response & thought tokens receive loss)
- Logs Epoch 0 baseline validation loss (eval_on_start=True)
- Reloads 16-bit base model before merging LoRA to avoid 4-bit merge rounding errors
"""

from __future__ import annotations

import argparse
import gc
from pathlib import Path

import torch
from datasets import load_dataset
from peft import LoraConfig, PeftModel
from transformers import AutoModelForMultimodalLM, AutoProcessor, BitsAndBytesConfig
from trl import SFTConfig, SFTTrainer


def supports_bf16() -> bool:
    if torch.cuda.is_available():
        major, _ = torch.cuda.get_device_capability()
        return major >= 8
    return False


def main() -> None:
    parser = argparse.ArgumentParser(description="NovaClaw Gemma 4 SFT QLoRA trainer")
    parser.add_argument("--model", default="google/gemma-4-E2B-it")
    parser.add_argument("--train-file", default="data/sft_train.jsonl")
    parser.add_argument("--val-file", default="data/sft_val.jsonl")
    parser.add_argument("--adapter-dir", default="outputs/sft_adapter")
    parser.add_argument("--merged-dir", default="outputs/merged_sft")
    parser.add_argument("--max-length", type=int, default=2048)
    parser.add_argument("--epochs", type=int, default=2)
    parser.add_argument("--batch-size", type=int, default=2)
    parser.add_argument("--grad-accum", type=int, default=8)
    parser.add_argument("--lr", type=float, default=2e-4)
    parser.add_argument("--lora-r", type=int, default=16)
    parser.add_argument("--lora-alpha", type=int, default=32)
    parser.add_argument("--skip-merge", action="store_true")
    args = parser.parse_args()

    use_bf16 = supports_bf16()
    compute_dtype = torch.bfloat16 if use_bf16 else torch.float16

    quant_cfg = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_use_double_quant=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=compute_dtype,
        bnb_4bit_quant_storage=compute_dtype,
    )

    processor = AutoProcessor.from_pretrained(args.model)
    tokenizer = processor.tokenizer

    peft_cfg = LoraConfig(
        r=args.lora_r,
        lora_alpha=args.lora_alpha,
        lora_dropout=0.05,
        bias="none",
        target_modules=[
            "q_proj", "k_proj", "v_proj", "o_proj",
            "gate_proj", "up_proj", "down_proj",
        ],
        exclude_modules=r"model\.(vision_tower|audio_tower)\..*",
        task_type="CAUSAL_LM",
    )

    train_ds = load_dataset("json", data_files=args.train_file, split="train")
    val_ds = load_dataset("json", data_files=args.val_file, split="train")

    sft_args = SFTConfig(
        output_dir=args.adapter_dir,
        max_length=args.max_length,
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch_size,
        per_device_eval_batch_size=args.batch_size,
        gradient_accumulation_steps=args.grad_accum,
        optim="paged_adamw_8bit",
        logging_steps=10,
        save_strategy="epoch",
        eval_strategy="epoch",
        eval_on_start=True,
        load_best_model_at_end=True,
        metric_for_best_model="eval_loss",
        greater_is_better=False,
        learning_rate=args.lr,
        weight_decay=0.01,
        warmup_steps=0.05,
        lr_scheduler_type="cosine",
        max_grad_norm=1.0,
        bf16=use_bf16,
        fp16=not use_bf16,
        model_init_kwargs={"dtype": compute_dtype},
        report_to="none",
    )

    trainer = SFTTrainer(
        model=args.model,
        args=sft_args,
        train_dataset=train_ds,
        eval_dataset=val_ds,
        processing_class=tokenizer,
        quantization_config=quant_cfg,
        peft_config=peft_cfg,
    )

    trainer.train()
    trainer.save_model(args.adapter_dir)
    processor.save_pretrained(args.adapter_dir)

    if args.skip_merge:
        return

    del trainer
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()

    # Reload 16-bit base checkpoint before merging to avoid 4-bit rounding loss
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
    print(f"Saved merged SFT checkpoint to {args.merged_dir}")


if __name__ == "__main__":
    main()
