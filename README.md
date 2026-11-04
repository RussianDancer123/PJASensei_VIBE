<img src="assets/icon.svg" width="64" height="64" alt="">

# PJASensei_VIBE

Created in [T3 Code](https://t3.codes).

## Run the stack

Needs Docker. One command starts everything, using the values in `.env.example`:

```sh
docker compose --env-file .env.example up --build
```

To change a value, copy `.env.example` to `.env`, edit it, and run `docker compose up --build`.

Everything is reached through <http://localhost:8080>:

| Path           | Service                                    |
| -------------- | ------------------------------------------ |
| `/`            | Static front end                           |
| `/api`         | Spring; `/api/status` shows what it reaches |
| `/plugin-mock` | IDE plugin mock (development only)         |
| `/mock-idp/`   | Identity provider mock (development only); start at `/mock-idp/.well-known/openid-configuration` |
| `/config.json` | Identity provider settings for the front end, written by Caddy |

Only Caddy's HTTP port and Gitea's SSH port (`2222`) are published. Of the running services, only Spring reaches the Gitea API and Postgres. `gitea-provision`, a one-off job at every start, leaves Spring's Gitea API token in a volume it shares only with Spring.

`compose.dev.yaml` adds the mocks and their routes. `COMPOSE_FILE` in `.env.example` turns it on, so an environment file without that line runs no mocks.

To start again with empty data, run `docker compose --env-file .env.example down -v`.

## Sign in

The **Sign in** button on the front end goes to the identity provider's own page and comes back with a token. Locally that provider is the mock, with these seeded users from `mocks/mock-idp-users.json`. The password of each is `password`.

| Username                                 | Groups                     | Roles                |
| ---------------------------------------- | -------------------------- | -------------------- |
| `jkowalski`                              | `17w`                      | Instructor           |
| `anowak`                                 | `18w`, `19w`               | Instructor           |
| `s17001`, `s17002`                       | `17c`                      | Student              |
| `s18001`                                 | `18c`                      | Student              |
| `s18002`                                 | `18c`, a provider group    | Student              |
| `s18003` (no email)                      | `18c`                      | Student              |
| `s19001`                                 | `19c`                      | Student              |
| `s20001`                                 | `20w`, `17c`               | Instructor, Student  |
| `outsider`                               | a provider group           | none                 |
| `nogroups`                               | none                       | none                 |

The mock's signing key is new at every start, so restarting it signs everyone out. Open the front end at the address in `IDENTITY_ISSUER` (`localhost`, not `127.0.0.1`), because the mock only accepts redirects back to its own origin.

Spring accepts any token the configured provider signed for the configured client. `/api/status` needs no token and `/api/me` answers from the token alone. Everything else is also refused with a 403 for a user missing from the database, by `KnownUserRule`, which covers new endpoints automatically.

## Refresh

An Instructor's **Refresh** button (`POST /api/refresh`) is the only thing that pulls data from the identity provider. It needs a valid token with an Instructor group, not a row in the database, and it adds the caller to the database. For each Instructor group in the token, such as `17w`, it reads the paired ClassGroup, `17c`, and replaces that ClassGroup's members, then makes any missing technical Gitea account. The response lists everything that failed, in `failures`, and everything else is kept. `GET /api/class-groups` then shows the ClassGroups the caller teaches, leaving out any with no members.

A saved Student never changes: Refresh only adds new Students and changes memberships. Their email and Student Number stay even if the identity provider now gives something different or unusable, with no failure listed, and a Student who leaves every ClassGroup stays in the database. Only a person not yet saved is skipped and listed for having no usable email. A ClassGroup that could not be read keeps its previous members.

Two seeded cases show failures on purpose. Refresh as `anowak` lists `s18003`, who has no email. Refresh as `s20001` lists `20c`, a ClassGroup nobody belongs to and which therefore does not exist in the mock.

## Send an Assignment

An Instructor enters a title and the HTTPS URL of a public repository on a host in `SOURCE_REPOSITORY_HOSTS`, chooses Students of the ClassGroups they teach, and sends. The front end makes two requests:

1. `POST /api/assignments`, with `{ "title", "sourceUrl", "recipients": [<sub>] }`, copies the SourceRepository once into a private snapshot in Gitea and keeps the Assignment. If the copy fails, it answers with an error and keeps nothing. Refusals explain themselves in `detail`.
2. `POST /api/assignments/{id}/repositories`, allowed only for the Instructor who sent it, makes each StudentRepository from the snapshot with a clean history, with up to three attempts for each. It answers `{ "failures": [{ "sub", "studentNumber" }] }`, naming the Students still without one, and the front end shows them in a prompt.

The second request is safe to repeat: it handles only recipients without a StudentRepository. A repository Gitea already has, for example after a timeout on an attempt that actually worked, counts as made, and access is still given. There is no retry control and nothing runs in the background, so a recipient who was not handled simply has no repository.

Spring's own Gitea account owns every snapshot and StudentRepository. A repository is named like `assignment-17-s12345`, is private, and has two collaborators: the Student with write access and the sending Instructor with read access.

## Instructor browsing

An Instructor's own routes need the Instructor role, and an Assignment sent by someone else answers with a 404:

| Route                       | Answer                                                                                              |
| --------------------------- | --------------------------------------------------------------------------------------------------- |
| `GET /api/assignments`      | `{ "assignments": [{ "id", "title" }] }`, the newest first                                          |
| `GET /api/assignments/{id}` | `{ "id", "title", "recipients": [{ "sub", "studentNumber", "sshUrl" }] }`, by Student Number        |

`sshUrl` is `null` for a recipient with no StudentRepository, and the front end shows "no repository". There are no pulled, submitted or done statuses. With an SSH key registered (see below), the Instructor can clone any StudentRepository of their Assignments, but not push to it.

## Student work

Anyone in the database can add SSH public keys, on the front end or with `POST /api/ssh-keys` and `{ "publicKey" }`. A person may have several. A key goes on their own technical Gitea account, which is made if missing. Adding the same key again changes nothing, so a plugin can do it at every start. A key must be one line, as in a `.pub` file. Anything else, or an invalid key, is refused with a 400, and a key already registered to someone else with a 409.

A Student's own routes need the Student role, and an Assignment without a StudentRepository of theirs answers with a 404:

| Route                                         | Answer                                                                          |
| --------------------------------------------- | ------------------------------------------------------------------------------- |
| `GET /api/student/assignments`                | `{ "assignments": [{ "id", "title" }] }`, the newest first                      |
| `GET /api/student/assignments/{id}`           | `{ "id", "title", "readme" }`, the README as raw text, read from Gitea through Spring |
| `GET /api/student/assignments/{id}/ssh-address` | `{ "sshUrl" }`, which the **Get** button shows as a `git clone` command to copy |

`readme` is the file at the top of the repository named `README` in any case, with no extension or with a text one (`md`, `markdown`, `txt`, `rst`, `adoc`, `org`), preferring `README.md`. It is `null` when there is none, and also when it is over 1 MiB, because a Student can push anything.

Git then works as usual, on the published SSH port, with the key that was registered:

```sh
git clone ssh://git@localhost:2222/pjasensei-spring/assignment-1-s17001.git
```

## Switch to real Cognito

Spring has no code specific to the mock, so this takes environment variables alone. In your `.env`:

| Variable                                                  | Value                                                             |
| --------------------------------------------------------- | ----------------------------------------------------------------- |
| `COMPOSE_FILE`, `COMPOSE_PATH_SEPARATOR`, `MOCKS_ADDRESS` | Remove, so that no mocks run                                      |
| `IDENTITY_ISSUER`                                         | `https://cognito-idp.<region>.amazonaws.com/<user pool ID>`       |
| `IDENTITY_JWK_SET_URI`                                    | `<issuer>/.well-known/jwks.json`                                  |
| `IDENTITY_CLIENT_ID`                                      | The ID of the app client                                          |
| `COGNITO_REGION`                                          | The region of the user pool                                       |
| `COGNITO_USER_POOL_ID`                                    | The ID of the user pool                                           |
| `COGNITO_ACCESS_KEY_ID`, `COGNITO_SECRET_ACCESS_KEY`      | Credentials allowed `cognito-idp:ListUsersInGroup` and `cognito-idp:AdminGetUser` on the pool |
| `COGNITO_ENDPOINT_OVERRIDE`                               | Empty                                                             |

The front end finds the address of the Cognito domain through OIDC discovery on the issuer, so it needs no setting of its own. The app client has to be a public one, without a secret, allowing the authorization code grant, the `openid` scope and the address of the front end as a callback URL.

## Run the tests

Tests need Docker too: they start Spring against real Postgres and real Gitea with Testcontainers.

```sh
./gradlew test
```

They also need `git` and OpenSSH (`ssh`, `ssh-keygen`) on the `PATH`: `GitOverSshTest` clones and pushes for real.

Annotate a test class with `@IntegrationTest` and inject a `RestTestClient` to exercise the HTTP API, a `TestIdentityProvider` to sign tokens for it, a `TestCognito` to set what the Cognito API answers, a `TestGitea` to look at Gitea from outside Spring, and a `TestGiteaBoundary` to make Gitea fail for Spring's requests, which all pass through it. `TestGitea.newGitUser` gives someone a key pair and real git, and `Classroom` gets people and Assignments ready through the API. The test Gitea uses the same `docker/gitea` config and provisioning script as the stack. It imports only from `source.test`, an HTTPS proxy in front of itself, so a test can create a SourceRepository with `TestGitea` and change it later. To use it, a test sets `pjasensei.assignments.allowed-source-hosts=source.test`.
