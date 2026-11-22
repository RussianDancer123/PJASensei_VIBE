# Plugin contract

What an IDE plugin has to do to work with PJASensei. `/plugin-mock` (`mocks/plugin-mock.html`) is a working example.

## What the plugin has

- A sign-in button, using authorization code with PKCE on the identity provider's own page. The plugin holds the token and renews it.
- An Assignments tab, which is the front end in an iframe.
- A Chat tab, which is the plugin's own and separate from the front end. See [Chat](#chat).

## Embedding the front end

The iframe URL is the address of the front end with the query parameter `embedded`, for example `https://pjasensei.example/?embedded=1`. In this mode the front end starts no sign-in of its own, has no sign-in or sign-out buttons, and keeps the token in memory only.

To be allowed to embed it, the plugin's exact origin must be listed in `FRAME_ANCESTORS`, for example `FRAME_ANCESTORS="https://plugin.example"`. To call Spring from that origin, it must also be in `CORS_ALLOWED_ORIGINS`. Only a source written as a full `http` or `https` origin counts for messages. Anything else in `FRAME_ANCESTORS`, such as a wildcard or a bare host, may let a page embed the front end, but the front end will not talk to it.

The one exception is a VS Code webview, whose origin is `vscode-webview://` plus an id that changes every time. Listing the entry `vscode-webview:` (for example `FRAME_ANCESTORS="'self' vscode-webview:"`) makes the front end talk to any origin of that scheme, as long as the message comes from `window.parent`, and it answers only to its parent's origin, which it reads from the browser (`location.ancestorOrigins`, available in VS Code's Chromium) before the first message and then from the messages themselves. Without the entry a webview is ignored. See [ADR 0002](adr/0002-vs-code-webview-as-plugin-origin.md).

The plugin is configured with the issuer and client ID itself. The plugin mock reads them from `/config.json` only because it shares Caddy's origin.

## Messages

All messages use `window.postMessage`.

| Direction           | Message                                   | When                                                                                 |
| ------------------- | ----------------------------------------- | ------------------------------------------------------------------------------------ |
| Front end to plugin | `{ type: "ready" }`                       | Each time the front end loads, including after every reload                          |
| Plugin to front end | `{ type: "token", accessToken }`          | In answer to `ready`, if signed in, and again whenever the token is renewed          |
| Front end to plugin | `{ type: "clone", sshUrl, assignmentId }` | When someone clicks Clone: a Student on their Assignment, an Instructor on a recipient |

- A plugin that is not signed in does not answer `ready`, and the front end waits. Once signed in, the plugin sends the token without waiting for another `ready`, or reloads the iframe.
- A renewed token replaces the old one and leaves the page as it is. To change to a different person, reload the iframe, and answer the new `ready` with their token.
- On `clone`, the plugin clones `sshUrl` with the git of the IDE. The address is an ordinary one, like `ssh://git@host:2222/owner/repository.git`. `assignmentId` says which Assignment the address belongs to. For an Instructor, several recipients share it.

## Checks on both sides

A message is accepted only if it comes from the expected window and origin, so that no other page can read a token or start a clone:

- **The front end** checks that `event.source` is `window.parent`, and that `event.origin` is an origin in `FRAME_ANCESTORS` (`'self'` means its own), or any `vscode-webview://` origin if the entry `vscode-webview:` is listed. It posts only to those origins; to a webview, only to the one that is its parent.
- **The plugin** checks that `event.source` is the `contentWindow` of its iframe, and that `event.origin` is the origin of the front end. It posts with that origin as `targetOrigin`, never `*`.

The front end learns `FRAME_ANCESTORS` from `/config.json`, written by Caddy.

## Through Spring

Sent with `Authorization: Bearer <accessToken>`:

- `POST /api/ssh-keys` with `{ "publicKey" }` registers the SSH public key of the IDE. Adding the same key again changes nothing, so a plugin can do it at every start. See [Student work](../README.md#student-work) in the README for the answers.
- `GET /api/ssh-keys` lists the caller's own keys, oldest first, as `[{ "id", "fingerprint", "createdAt" }]`, with the fingerprint in the `SHA256:...` form.
- `DELETE /api/ssh-keys/{id}` removes one of them (204). Another person's key, or one that does not exist, gives a 404.

A plugin which registers its key at every start puts a deleted key back at the next start. This is intended: removing a key in the browser stops it only until that IDE starts the plugin again, so to stop it for good, remove it from the IDE as well.

A Student or Instructor Refresh has not added yet gets a 403 here, as everywhere else.

## Chat

The plugin calls Spring directly with its access token, never the AI. `GET /api/student/assignments` gives the Assignments to choose from, each `{ "id", "title" }`. To follow the repository open in the IDE, add one `repository` parameter per git remote address of it: `GET /api/student/assignments?repository=git@host:owner/name.git&repository=...` gives only the Assignments whose StudentRepository is one of them, normally none or one. Both `ssh://user@host:port/owner/name.git` and `user@host:owner/name` work, with or without `.git`; only `owner/name` counts, without regard to letter case, and the host and port are ignored. An address that matches nothing, or cannot be read, gives an empty list rather than an error, so the plugin can let the Student choose. A TutorSession belongs to one Assignment's StudentRepository, and Spring makes it on first use.

**`GET /api/student/assignments/{id}/tutor-session/messages`** gives the history, oldest first. `files` appears only on student messages that had them:

```json
{ "messages": [
  { "role": "student", "content": "...", "files": [{ "path": "...", "content": "..." }] },
  { "role": "tutor", "content": "..." }
] }
```

**`POST /api/student/assignments/{id}/tutor-session/messages`** sends a message and gives `{ "reply": "..." }`. `files` is optional and text-only:

```json
{ "content": "...", "files": [{ "path": "...", "content": "..." }] }
```

Refusals and failures carry a readable `detail`, except a 403, which comes with no body:

| Status | Meaning                                                                                |
| ------ | -------------------------------------------------------------------------------------- |
| 400    | The message is not of the shape above                                                  |
| 403    | The caller is not a Student, or no Refresh has added them yet                          |
| 404    | The caller has no StudentRepository for this Assignment                                |
| 413    | A file is over 2 MB or the message over 10 MB, the starting values of configurable limits |
| 502    | The AI failed, which affects only this tab                                             |
