---
id: open_and_search
name: Open an app and search
triggers:
  - "search in "
  - "search for "
  - "look up "
  - "find "
  - "google "
---

1. open_app(app_name) when a specific app is named; otherwise stay in the current app.
2. Observe the screen map and tap_node the search field (label contains search or a magnifier).
3. t(text) with the exact query.
4. Press enter with system_key(key="enter"), or tap_node the search button.
5. Observe results, summarize them in finish(summary="...").

If the field is already focused, skip step 2. Never claim results before step 5.
