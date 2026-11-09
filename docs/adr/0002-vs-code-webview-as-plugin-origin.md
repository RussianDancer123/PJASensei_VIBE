# A VS Code webview is trusted by scheme, not by exact origin

The embedded front end talks only to parents whose exact `http` or `https` origin is listed in `FRAME_ANCESTORS`. A VS Code webview cannot be listed that way, because its origin is `vscode-webview://` followed by an id that changes with every webview. So the entry `vscode-webview:` is a deliberate exception: the front end accepts a message from any origin of that scheme, but only when it comes from `window.parent`, and it answers to exactly that `event.origin`.

This is acceptable because the front end sends nothing secret to its parent, only `ready` and `clone`, and a token handed in by a foreign page grants that page nothing. The alternative of trusting whichever origin speaks first would have accepted any parent, of any scheme.
