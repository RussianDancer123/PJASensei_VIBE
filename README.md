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
| `/mock-idp`    | Identity provider mock (development only)  |

Only Caddy's HTTP port and Gitea's SSH port (`2222`) are published. Of the running services, only Spring reaches the Gitea API and Postgres. `gitea-provision`, a one-off job at every start, leaves Spring's Gitea API token in a volume it shares only with Spring.

`compose.dev.yaml` adds the mocks and their routes. `COMPOSE_FILE` in `.env.example` turns it on, so an environment file without that line runs no mocks.

To start again with empty data, run `docker compose --env-file .env.example down -v`.

## Run the tests

Tests need Docker too: they start Spring against real Postgres and real Gitea with Testcontainers.

```sh
./gradlew test
```

Annotate a test class with `@IntegrationTest` and inject a `RestTestClient` to exercise the HTTP API. The test Gitea uses the same `docker/gitea` config and provisioning script as the stack.
