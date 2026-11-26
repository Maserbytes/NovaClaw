#!/usr/bin/env bash
set -euo pipefail

# Exports a merged Hugging Face Gemma 4 (E2B / E4B) checkpoint to .litertlm
# for on-device Android execution in NovaClaw.
# Run on Linux x86_64 (Colab / cloud GPU / Linux workstation).

MERGED_DIR="${1:-outputs/merged_final}"
OUT_DIR="${2:-outputs/litert_out}"
VARIANT="${3:-E2B}" # E2B or E4B

if [[ "$VARIANT" == "E4B" ]]; then
  TEMPLATE_REPO="litert-community/gemma-4-E4B-it-litert-lm"
  OUT_NAME="novaclaw-gemma-4-E4B-agent.litertlm"
else
  TEMPLATE_REPO="litert-community/gemma-4-E2B-it-litert-lm"
  OUT_NAME="novaclaw-gemma-4-E2B-agent.litertlm"
fi

echo "==> Installing LiteRT-LM export & verification tools"
uv tool install --upgrade litert-torch-nightly litert-lm

echo "==> Exporting $MERGED_DIR -> $OUT_DIR ($VARIANT)"
mkdir -p "$OUT_DIR"
litert-torch export_hf \
  --model="$MERGED_DIR" \
  --output_dir="$OUT_DIR" \
  --externalize_embedder \
  --jinja_chat_template_override="$TEMPLATE_REPO"

mv "$OUT_DIR/model.litertlm" "$OUT_DIR/$OUT_NAME"

echo "==> Smoke-testing exported .litertlm bundle"
litert-lm run "$OUT_DIR/$OUT_NAME" --prompt='Return {"tool":"open_app","args":{"app_name":"Clock"}}'

echo ""
echo "Done! Exported bundle: $OUT_DIR/$OUT_NAME"
echo "To sideload onto a connected Android device for NovaClaw testing:"
echo "  adb push $OUT_DIR/$OUT_NAME /sdcard/Android/data/ai.novaclaw/files/models/gemma-4-$VARIANT-it.litertlm"
