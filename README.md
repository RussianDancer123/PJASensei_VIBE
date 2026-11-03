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

An Instructor's **Refresh** button (`POST /api/refresh`) is the only thing that pulls data from the identity provider. It needs a valid token with an Instructor group, not a row in the database, and it adds the caller to the database. For each Instructor group in the token, such as `17w`, it reads the paired ClassGroup, `17c`, and replaces that group's members, then makes any missing technical Gitea account. The response lists everything that failed, in `failures`, and everything else is kept. `GET /api/class-groups` then shows the ClassGroups the caller teaches.

Two seeded cases show failures on purpose. Refresh as `anowak` lists `s18003`, who has no email. Refresh as `s20001` lists `20c`, a group nobody belongs to and which therefore does not exist in the mock.

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

Annotate a test class with `@IntegrationTest` and inject a `RestTestClient` to exercise the HTTP API, a `TestIdentityProvider` to sign tokens for it, a `TestCognito` to set what the Cognito API answers, and a `TestGitea` to look at Gitea from outside Spring. The test Gitea uses the same `docker/gitea` config and provisioning script as the stack.
