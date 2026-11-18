---
id: screen_question
name: Answer from the current screen
triggers:
  - "what's on"
  - "what is on"
  - "what can you see"
  - "read "
  - "summarize"
  - "explain "
---

1. Use the current screen map as the answer source. Do not navigate away.
2. If the answer is in a long list, scroll(direction="down") and collect more text (max 3 scrolls).
3. finish(summary="...", is_conversational=true) with a concise answer grounded only in observed text.
4. If the screen does not contain the answer, say so explicitly. Never invent content.
