---
id: open_and_navigate
name: Open and navigate settings
triggers:
  - "open "
  - "go to "
  - "navigate "
  - "turn on "
  - "turn off "
  - "enable "
  - "disable "
---

1. open_app(app_name) if an app is named.
2. Observe the screen map. Choose the single element whose label best matches the target.
3. tap_node(node_id). Observe again after every action — node ids change.
4. Repeat until the setting is visible. Only tap a toggle when its label clearly matches the requested setting.
5. Verify the toggle state changed, then finish(summary="...").

If no matching element after 3 scrolls, finish with a clear failure summary. Never guess a tap on an unlabeled element.
