# trade-service

The swapr service that owns offers: making them, accepting and declining them, and cancelling them automatically. It started as a copy of `user-service`, so its build, lint, configuration, authentication and delivery pipeline match that reference service.

Stack: Java 25, Spring Boot 4, Maven, Flyway, Checkstyle (Google rules, 120 columns), deployed to Render as a GraalVM native image.

## Running locally

Prerequisites: JDK 25, and Docker for local runs.

Once, copy `.env.example` to `.env` and fill in the QA Supabase project URL and any non-empty local internal token. `.env` is gitignored.

```sh
./mvnw spring-boot:run
```

With no profile set, the `local` profile is active. It reads `.env` from the repo root, and Spring Boot's Docker Compose support starts the PostGIS database from `docker-compose.yml` and wires the connection in. Local auth points at the QA Supabase project, so sign in through the QA project to get a JWT. Real environment variables override the values in `.env`.

Run the linter and unit tests with:

```sh
./mvnw verify
```

The unit tests are plain JUnit with Mockito: each test exercises one class and mocks the layer below it, so they need no Docker, network or database. Behaviour that lives only in SQL is tested by the integration suite against QA.

## Environment variables

| Variable | Purpose | Default |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local`, `qa` or `prod`. Render sets it per service. | `local` |
| `PORT` | HTTP port. Render sets it. Local runs listen on 8083. | `8083` |
| `DATABASE_URL` | JDBC URL, for example `jdbc:postgresql://<host>/<db>?sslmode=require`. Neon needs `sslmode=require`. | none (local uses Docker Compose) |
| `DATABASE_USERNAME` | Database user. | none |
| `DATABASE_PASSWORD` | Database password. Secret. | none |
| `SUPABASE_URL` | Supabase project URL, for example `https://<ref>.supabase.co`. The JWT issuer and JWKS address are built from it. | none (`.env` locally) |
| `INTERNAL_TOKEN` | Shared service-to-service secret for `/internal/**`. Secret. Startup fails when it is empty. | none (`.env` locally) |
| `CORS_ALLOWED_ORIGINS` | Comma-separated origins allowed to call `/v1/**` from a browser. | none (`.env` locally) |

YAML holds defaults only and reads every per-environment value from an environment variable, including for local runs. Locally those come from `.env`; in QA and prod they are set in Render. Secrets are never committed.

## Endpoints and security

- `/actuator/health` reports overall health including the `db` component. Use it to confirm a deployed service can reach Neon.
- `/actuator/health/liveness` ignores the database. Render's health check uses it, so a slow Neon wake-up does not fail deploys or restart the instance.
- `/v3/api-docs` serves the OpenAPI document without auth.
- `/v1/**` needs a Supabase JWT: issuer `<SUPABASE_URL>/auth/v1`, audience `authenticated`, signed by a key from the project's JWKS. The `sub` claim is the user's UUID and the principal name.
- `/internal/**` needs the `X-Internal-Token` header. A missing or wrong token gets 401. A request with the right token that also carries an `Authorization` header gets 403, so user JWTs never reach internal endpoints.
- Every other path is denied. Authentication and access errors are RFC 9457 Problem Details.

## Pipeline

`.github/workflows/pipeline.yml` has five jobs:

1. `verify` runs `./mvnw verify` (Checkstyle and unit tests) on every push to any branch and on pull requests. Any Checkstyle warning or failing test fails the run.
2. `image` runs on pushes to `main` only. It builds the native image from the `Dockerfile` and pushes `ghcr.io/<org>/trade-service:<commit-sha>`. There is no `latest` tag.
3. `deploy-qa` deploys that exact image to QA through the Render deploy hook in the GitHub Environment `qa`, and waits until the deploy is live.
4. `e2e` runs the whole integration test suite against QA by calling `integration-test-suite`'s reusable workflow. A failing suite skips `deploy-prod`, so no prod approval is offered.
5. `deploy-prod` waits for approval on the GitHub Environment `prod`, then deploys the same image to prod and waits until it is live. It never rebuilds, so prod runs only what passed in QA.

Both deploy jobs run `.github/scripts/render-deploy.sh`. It triggers the deploy hook, reads the deploy ID from the response, sleeps 5 minutes, then polls the Render API every 15 seconds until the deploy is `live`. A failed, cancelled or replaced deploy, an error response, or no live deploy 15 minutes after the trigger fails the job with an `::error::` annotation naming the environment, the deploy ID and the last status. Render 5xx responses are retried until the limit.

The workflow has one concurrency group per branch, so runs on `main` go one at a time from QA deploy through prod deploy, and a later merge can never replace the QA build while an earlier one is being tested. A run waiting for prod approval holds the group, so approve or reject it to let the next merge deploy. Only the newest queued run is kept. After a deploy, check `/actuator/health` by hand and confirm `db` is `UP`.

Build the native image locally with `docker build -t trade-service .`, or without Docker using `./mvnw -Pnative native:compile` on a GraalVM JDK 25.
