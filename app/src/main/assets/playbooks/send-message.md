---
id: send_message
name: Send a message
triggers:
  - "send a message"
  - "send message"
  - "send hi"
  - "tell "
  - "message "
  - "reply to"
---

When the user clearly wants a message delivered to another person:

1. open_app(app_name) — open the requested app (default WhatsApp).
2. Observe the new screen map. Find the contact by label; if absent, scroll(direction="down") and observe again (up to 5 scrolls).
3. tap_node(node_id) on the exact contact row — never tap a profile image or call button.
4. Observe the chat. t(text) into the message composer.
5. Find the send control by label (send / arrow) and tap_node it. Never use a call/video button.
6. Verify the composer is empty and the message bubble appears. Then finish(summary="Sent ... to ...").

Extract: contact = person name, message = what to say, app = WhatsApp unless another is named.
Do NOT use this playbook for "say hi", "tell me", "what do you think?" — those are questions.
