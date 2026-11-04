# Plugin contract

What an IDE plugin has to do to work with PJASensei. `/plugin-mock` (`mocks/plugin-mock.html`) is a working example.

## What the plugin has

- A sign-in button, using authorization code with PKCE on the identity provider's own page. The plugin holds the token and renews it.
- An Assignments tab, which is the front end in an iframe.
- A Chat tab, which is the plugin's own and separate from the front end.

## Embedding the front end

The iframe URL is the address of the front end with the query parameter `embedded`, for example `https://pjasensei.example/?embedded=1`. In this mode the front end starts no sign-in of its own, has no sign-in or sign-out buttons, and keeps the token in memory only.

To be allowed to embed it, the plugin's exact origin must be listed in `FRAME_ANCESTORS`, for example `FRAME_ANCESTORS="https://plugin.example"`. To call Spring from that origin, it must also be in `CORS_ALLOWED_ORIGINS`. A wildcard in `FRAME_ANCESTORS` lets a page embed the front end, but the front end will not talk to it.

## Messages

All messages use `window.postMessage`.

| Direction           | Message                                   | When                                                                                 |
| ------------------- | ----------------------------------------- | ------------------------------------------------------------------------------------ |
| Front end to plugin | `{ type: "ready" }`                       | Each time the front end loads, including after every reload                          |
| Plugin to front end | `{ type: "token", accessToken }`          | In answer to `ready`, if signed in, and again whenever the token is renewed          |
| Front end to plugin | `{ type: "clone", sshUrl, assignmentId }` | When someone clicks Get: a Student on their Assignment, an Instructor on a recipient |

- A plugin that is not signed in does not answer `ready`, and the front end waits. Once signed in, the plugin sends the token without waiting for another `ready`, or reloads the iframe.
- A renewed token replaces the old one and leaves the page as it is. To change to a different person, reload the iframe, and answer the new `ready` with their token.
- On `clone`, the plugin clones `sshUrl` with the git of the IDE. The address is an ordinary one, like `ssh://git@host:2222/owner/repository.git`. `assignmentId` says which Assignment the address belongs to. For an Instructor, several recipients share it.

## Checks on both sides

A message is accepted only if it comes from the expected window and origin, so that no other page can read a token or start a clone:

- **The front end** checks that `event.source` is `window.parent`, and that `event.origin` is an origin in `FRAME_ANCESTORS` (`'self'` means its own). It posts only to those origins.
- **The plugin** checks that `event.source` is the `contentWindow` of its iframe, and that `event.origin` is the origin of the front end. It posts with that origin as `targetOrigin`, never `*`.

The front end learns `FRAME_ANCESTORS` from `/config.json`, written by Caddy.

## Through Spring

Sent with `Authorization: Bearer <accessToken>`:

- `POST /api/ssh-keys` with `{ "publicKey" }` registers the SSH public key of the IDE. Adding the same key again changes nothing, so a plugin can do it at every start. See [Student work](../README.md#student-work) in the README for the answers.

A Student or Instructor Refresh has not added yet gets a 403 here, as everywhere else.
