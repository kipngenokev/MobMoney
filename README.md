# MobMoney — Mobile Money Transfer Microservices

A production-shaped reference implementation of a mobile-money transfer platform,
built to demonstrate **correct money handling under concurrency and partial
failure**. It consists of two Spring Boot services, a Next.js web app, and a full
local ops stack (Docker, Kubernetes/Minikube, Prometheus + Grafana, GitHub
Actions CI).

The interesting parts are not the CRUD — they are **how the system stays
consistent** when two transfers race for the same account, when a client retries
a request, and when the partner bank times out mid-settlement. Those decisions
are explained in [Design decisions](#design-decisions).

---

## Table of contents
- [Architecture](#architecture)
- [Tech stack](#tech-stack)
- [Repository layout](#repository-layout)
- [Quick start (Docker Compose)](#quick-start-docker-compose)
- [Running locally for development](#running-locally-for-development)
- [API reference](#api-reference)
- [Design decisions](#design-decisions)
  - [Money & data modelling](#money--data-modelling)
  - [Consistency: ACID + concurrency control](#consistency-acid--concurrency-control)
  - [Idempotency (the claim-first protocol)](#idempotency-the-claim-first-protocol)
  - [Failure handling & the external-settlement saga](#failure-handling--the-external-settlement-saga)
  - [Authentication & authorization](#authentication--authorization)
- [Testing strategy](#testing-strategy)
- [Observability](#observability)
- [Kubernetes / Minikube](#kubernetes--minikube)
- [CI/CD](#cicd)
- [Known limitations & next steps](#known-limitations--next-steps)

---

## Architecture

```
                    ┌──────────────────────┐
                    │   Next.js frontend    │  (login, transfer flow,
                    │   :3000               │   history, live balance)
                    └───────────┬──────────┘
                                │ HTTPS + Bearer JWT
                                ▼
   ┌─────────────────────────────────────────────────┐
   │  transfer-service  (Spring Boot, :8080)           │
   │  • OAuth2 resource server (RS256 JWT)             │
   │  • Accounts / transfers REST API                  │
   │  • ACID balance updates (pessimistic row locks)   │
   │  • Idempotency-Key handling (claim-first)         │
   │  • Prometheus metrics                             │
   └───────┬─────────────────────────────┬────────────┘
           │ JDBC                         │ HTTP (idempotent on reference)
           ▼                              ▼
   ┌───────────────┐          ┌──────────────────────────────┐
   │   MySQL 8     │          │ partner-bank-service (:8081)   │
   │  accounts,    │          │ Apache Camel REST route        │
   │  transactions,│          │ simulates a partner bank:      │
   │  idempotency  │          │ latency, transient faults,     │
   └───────────────┘          │ deterministic rejections,      │
                              │ idempotent settlement          │
                              └──────────────────────────────┘

        Prometheus (:9090) scrapes both services → Grafana (:3001)
```

Two transfer routes:

- **Internal → internal**: both accounts live in this service. Settled in a
  single ACID transaction.
- **Internal → external**: destination is held at the partner bank. Settled with
  a **saga** (debit locally, call partner over Camel, then complete or
  compensate). See [the saga section](#failure-handling--the-external-settlement-saga).

---

## Tech stack

| Concern            | Choice                                                   |
|--------------------|----------------------------------------------------------|
| Language / runtime | Java 21, Spring Boot 3.3                                  |
| Persistence        | MySQL 8, Spring Data JPA/Hibernate, Flyway migrations    |
| Auth               | Spring Security OAuth2 Resource Server, RS256 JWT        |
| Partner integration| Apache Camel 4.8 (REST DSL over platform-http)           |
| Frontend           | Next.js 14 (App Router), TypeScript, Tailwind, SWR       |
| Tests              | JUnit 5, Mockito, Testcontainers (MySQL), AssertJ        |
| Observability      | Micrometer → Prometheus, Grafana                         |
| Packaging          | Docker (multi-stage), Docker Compose                     |
| Orchestration      | Kubernetes manifests (Minikube)                          |
| CI                 | GitHub Actions (unit + integration tests, image builds)  |

---

## Repository layout

```
.
├── backend/
│   ├── transfer-service/        # core service: accounts, transfers, auth, idempotency
│   └── partner-bank-service/    # Apache Camel app simulating a partner bank
├── frontend/                    # Next.js + TypeScript web app
├── k8s/                         # Kubernetes manifests (namespaced "mobmoney")
├── monitoring/                  # Prometheus config + Grafana provisioning/dashboard
├── scripts/                     # minikube-deploy.sh, smoke-test.sh
├── .github/workflows/ci.yml     # CI pipeline
└── docker-compose.yml           # full local stack
```

---

## Quick start (Docker Compose)

```bash
docker compose up --build
```

Then:

| Service          | URL                              | Notes                          |
|------------------|----------------------------------|--------------------------------|
| Frontend         | http://localhost:3000            | login: `alice` / `Password123!`|
| Transfer API     | http://localhost:8080            | Swagger: `/swagger-ui.html`    |
| Partner bank     | http://localhost:8081            | Camel route                    |
| Prometheus       | http://localhost:9090            |                                |
| Grafana          | http://localhost:3001            | `admin` / `admin`, dashboard "MobMoney Overview" |

Seed users (both password `Password123!`): **alice** (accounts `ACC-ALICE-001`,
`ACC-ALICE-002`, external `PARTNER-EXT-001`) and **bob** (`ACC-BOB-001`).

Run the end-to-end smoke test (login → transfer → idempotent replay):

```bash
./scripts/smoke-test.sh           # against http://localhost:8080
```

---

## Running locally for development

**Backend** (needs a MySQL on :3306, or use the compose `mysql` service):

```bash
cd backend/transfer-service && mvn spring-boot:run
cd backend/partner-bank-service && mvn spring-boot:run
```

**Frontend**:

```bash
cd frontend && npm install && npm run dev   # http://localhost:3000
```

---

## API reference

All `/api/**` endpoints except `/api/auth/**` require `Authorization: Bearer <jwt>`.

### `POST /api/auth/login`
```json
{ "username": "alice", "password": "Password123!" }
→ { "accessToken": "...", "tokenType": "Bearer", "expiresInSeconds": 3600, "username": "alice" }
```

### `GET /api/accounts` · `GET /api/accounts/{accountNumber}`
Returns the caller's accounts. Accounts the caller does not own are reported as
`404` (not `403`) so the API does not leak which account numbers exist.

### `POST /api/transfers`  *(requires `Idempotency-Key` header)*
```json
{
  "sourceAccountNumber": "ACC-ALICE-001",
  "destinationAccountNumber": "ACC-BOB-001",
  "amount": "100.00",
  "currency": "USD",
  "narrative": "rent"
}
```
Responses:
- `201 Created` — transfer resource created (`status` is `COMPLETED` or, for a
  partner-rejected external transfer, `FAILED`).
- `202 Accepted` — external transfer accepted but the partner outcome is unknown;
  `status` is `PENDING`. Poll `GET /api/transfers/{reference}`.
- `409 Conflict` — the `Idempotency-Key` was reused with a different payload, or a
  prior attempt with that key is still in flight.
- `422 Unprocessable` — insufficient funds, currency mismatch, same source/dest.

Replaying the **same** key with the **same** payload returns the **original**
response byte-for-byte and does **not** move money again.

### `GET /api/transfers?accountNumber=...&page=0&size=20`
Paginated transaction history for an owned account.

---

## Design decisions

This section is the point of the project. The brief asked specifically about
**consistency** and **failure handling**, so those get the most space.

### Money & data modelling

- **Balances and amounts are `DECIMAL(19,4)`**, never floating point. Binary
  floats can't represent `0.10` exactly; using them for money guarantees
  rounding drift. `BigDecimal` end-to-end (and serialized to JSON as a string on
  the frontend) keeps every cent exact.
- **The transaction table is an append-style ledger.** A transfer row's monetary
  fields are immutable once written; only `status` (`PENDING → COMPLETED/FAILED`)
  and `failureReason` transition. This gives an auditable history rather than
  mutable "current state".
- **Schema is owned by Flyway**, and Hibernate runs with `ddl-auto: validate`.
  The app refuses to start if the entities and the migrated schema disagree —
  this actually caught two type mismatches during development.

### Consistency: ACID + concurrency control

The core risk in a money system is the **lost update**: two concurrent transfers
read the same balance of 100, both subtract 80, and both write 20 — 160 leaves an
account that only had 100.

Defenses, in layers:

1. **Single ACID transaction per internal transfer.** Debit, credit, and the
   ledger write either all commit or all roll back (`@Transactional` on
   `LedgerService.executeInternalTransfer`). There is never a window where money
   has left one account but not arrived at the other.

2. **Pessimistic row locks (`SELECT … FOR UPDATE`).** The transfer path loads
   accounts via `AccountRepository.findByAccountNumberForUpdate`, which takes a
   write lock for the duration of the transaction. Concurrent transfers touching
   the same account **serialize** at the database instead of racing on the
   balance. This is enforced by an integration test that fires 8 concurrent
   transfers from one account and asserts the final balance is exactly correct.

3. **Deterministic lock ordering.** When a transfer locks two accounts it always
   locks the lower account number first (`LedgerService.lockPair`). Without this,
   transfer A→B and B→A could each grab one lock and deadlock; consistent
   ordering makes that impossible.

4. **Optimistic `@Version` as a backstop.** Any code path that mutates an account
   outside the pessimistic-locked flow is still protected against lost updates.

5. **A DB `CHECK (balance >= 0)` constraint** is the last line of defense if
   application logic is ever bypassed.

Network I/O is **never** done while holding a row lock — the external saga commits
the local debit *before* calling the partner (see below), so a slow partner can't
hold a lock and stall every other transfer on that account.

### Idempotency (the claim-first protocol)

Money endpoints must be safe to retry: a client that times out and resubmits, or
double-clicks "Send", must not transfer twice. We require an `Idempotency-Key`
header on `POST /api/transfers`.

A naïve "check if key exists, else do the transfer, then save the key" has a race:
two concurrent requests with the same key both find nothing and both transfer. A
`UNIQUE` constraint on the key would only stop the second *insert* — after the
money already moved twice.

So we **claim first** (`IdempotencyService` + `IdempotencyStore`):

1. **Insert an `IN_PROGRESS` row keyed by the idempotency key, before doing any
   work.** The key column is `UNIQUE`, so a concurrent duplicate collides on the
   index here — guaranteeing the transfer body runs **at most once**.
2. Perform the transfer.
3. **Update the row to `COMPLETED`** with the final HTTP status and the exact
   response body.

Replays and races resolve deterministically:

- **Genuine replay** (key exists, `COMPLETED`, same request hash) → return the
  stored response verbatim.
- **Different payload, same key** (request hash differs) → `409 Conflict`. We
  store a SHA-256 of the canonical request so a key can't be accidentally reused
  for a different transfer.
- **Still in progress** → `409` ("retry shortly").

A subtle but important implementation detail: each idempotency DB operation runs
in **its own transaction** (`REQUIRES_NEW`, in `IdempotencyStore`). When the
claiming `INSERT` loses the unique-constraint race, that transaction rolls back in
isolation, so the follow-up "read the winner" query runs in a **clean**
transaction rather than a poisoned, rollback-only one. (Getting this wrong
produced a `500` instead of a `409` — caught by an integration test.)

Business failures (insufficient funds) are recorded as the terminal response too,
so replaying a failed transfer returns the same `422` instead of re-attempting.

### Failure handling & the external-settlement saga

An internal transfer is one ACID transaction. An **external** transfer can't be —
it spans our database and a remote partner bank, and you cannot hold a DB
transaction open across an unreliable network call. We use a **saga with explicit
compensation**:

```
1. Txn A (committed):  lock + debit source, write ledger row = PENDING
2. Call partner.settle(reference, …)   ← reference doubles as the partner's idempotency key
3. Resolve:
   a. ACCEPTED            → Txn B: status = COMPLETED
   b. Hard rejection (4xx)→ Txn C: refund source, status = FAILED   (compensation)
   c. Unknown (timeout/5xx after retries) → leave PENDING, return 202
```

The key judgement call is **3b vs 3c**:

- A partner **4xx is deterministic**: the partner definitively did *not* take the
  money, so compensating (refunding the source) is safe. `PartnerBankException`
  carries an `isDeterministic` flag exactly so the orchestrator can tell these
  apart.
- A **timeout / unreachable partner is an unknown outcome**. The partner *might*
  have settled and we simply lost the response. Refunding here risks **paying
  twice** (refund + the partner also debited their side). So we **fail closed**:
  leave the transfer `PENDING`, return `202 Accepted` with the reference, and let
  reconciliation resolve it. **Never refund on an unknown outcome** is the rule.

Supporting pieces:

- **The partner endpoint is idempotent on `reference`.** That makes retries safe:
  the `PartnerBankClient` retries transient failures with backoff, and a replayed
  settlement returns the first outcome rather than settling again. (Verified — a
  replay with a different amount still returns the original result.)
- **Reconciliation** of stuck `PENDING` transfers (query the partner by reference,
  then complete or compensate) is described but not implemented; see
  [next steps](#known-limitations--next-steps).
- **Unexpected exceptions after the debit** also fail closed (`PENDING`, `202`) —
  we never leave money in a state that could double-spend, even if it means a
  human/automated reconciliation step is needed.

The partner-bank-service deliberately injects latency, a configurable
`transient-fault-probability`, and deterministic rejections above a limit — so all
three branches above are exercisable from the running stack (set
`PARTNER_TRANSIENT_FAULT_PROBABILITY` in `docker-compose.yml`).

### Authentication & authorization

- **RS256 JWTs.** The transfer service signs tokens with an RSA private key and
  validates them as an OAuth2 **resource server** with the public key. Asymmetric
  keys mean verification needs only the public key, and the signing key can be
  rotated/held separately. The bundled dev keypair (`resources/keys/`) is for
  local use only — point `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY` at a mounted secret in
  production.
- **Stateless**, so the service scales horizontally with no shared session store.
- **Passwords are BCrypt-hashed.** Login returns the same generic error for "no
  such user" and "wrong password" so it can't be used to enumerate usernames.
- **Ownership is enforced and masked.** You can only transfer from / read accounts
  you own; others return `404`, not `403`, so the API doesn't confirm which
  account numbers exist.

---

## Testing strategy

Two tiers, separated by Maven plugin so they can run independently:

- **Unit tests** (`*Test`, Surefire) — JUnit 5 + Mockito. Fast, no I/O.
  - `TransferServiceTest` — idempotent replay, internal success, business-failure
    recording, ownership denial, and all three external-saga branches
    (settle / compensate / leave-pending).
  - `LedgerServiceTest` — exact balance math, insufficient funds, same-account
    rejection, refund-on-compensation, lock acquisition.
  - `AuthServiceTest` — token issuance, wrong password, user enumeration safety.
  - `SettlementServiceTest` (partner) — accept, deterministic reject, idempotency.
- **Integration tests** (`*IT`, Failsafe) — **Testcontainers** spins up a real
  MySQL 8. `TransferFlowIT` drives the full HTTP stack: JWT auth, an internal
  transfer's ACID balance change, idempotent replay returning the same reference,
  key-reuse conflict (`409`), and a **concurrency test** (8 simultaneous transfers)
  proving no lost updates.

```bash
# Unit tests only
mvn -pl backend/transfer-service test
# Unit + integration (needs Docker)
mvn -pl backend/transfer-service verify
```

> **Local Docker note:** on very new Docker Engines (≥ 28, API ≥ 1.40) the
> Testcontainers client may report *"client version 1.32 is too old"*. If so, run
> with `-Dapi.version=1.43` and ensure `DOCKER_HOST=unix:///var/run/docker.sock`.
> GitHub Actions runners are unaffected.

---

## Observability

Both services expose Micrometer metrics at `/actuator/prometheus` and
liveness/readiness probes at `/actuator/health/{liveness,readiness}`.

Custom business metrics (transfer service):

| Metric                                | Meaning                                  |
|---------------------------------------|------------------------------------------|
| `mobmoney_transfers_initiated_total`  | transfers started                        |
| `mobmoney_transfers_completed_total`  | transfers that completed                 |
| `mobmoney_transfers_failed_total`     | transfers that ended FAILED              |
| `mobmoney_idempotent_replays_total`   | requests served from a stored response   |
| `mobmoney_compensations_total`        | source refunds after partner rejection   |
| `mobmoney_transfer_duration_seconds`  | end-to-end processing time (histogram)   |

Prometheus scrapes both services; Grafana auto-provisions the Prometheus
datasource and the **MobMoney Overview** dashboard (transfer outcomes, replay /
compensation rates, p95 latency, HTTP status mix, JVM heap, partner outcomes).

---

## Kubernetes / Minikube

Manifests in `k8s/` deploy the whole stack into a `mobmoney` namespace, with
`transfer-service` running **2 replicas** (safe because it's stateless and
correctness comes from DB locks + idempotency, not in-memory state), resource
requests/limits, and health probes wired to the actuator endpoints.

```bash
./scripts/minikube-deploy.sh    # builds images into Minikube and applies k8s/
```

Then grab URLs with `minikube service -n mobmoney <name> --url`.

---

## CI/CD

`.github/workflows/ci.yml` runs on every push/PR:

1. **transfer-service** — `mvn verify` (unit + Testcontainers integration tests;
   the runner's Docker daemon backs MySQL). Test reports uploaded as artifacts.
2. **partner-bank-service** — `mvn verify`.
3. **frontend** — `npm install`, `tsc --noEmit`, `next build`.
4. **docker-images** — builds all three images (only after tests pass). Pushing to
   a registry is left as a one-line change (add `push: true` + registry login).

---

## Known limitations & next steps

Honest about what's demo-grade vs production-grade:

- **Reconciliation job.** Stuck `PENDING` external transfers need a scheduled
  worker that re-queries the partner by `reference` and completes/compensates.
  The data model and partner idempotency support it; the worker isn't built.
- **Idempotency key TTL/cleanup.** Keys live forever; production needs a retention
  window and a sweep, plus a defined policy for an attempt that crashes while
  `IN_PROGRESS` (currently fails closed — blocks retries of that exact key until
  cleaned up, which is the safe choice for money).
- **Token storage on the client.** The demo keeps the JWT in `localStorage`; a
  production app should use an httpOnly cookie set by a backend-for-frontend to
  reduce XSS token theft, plus refresh tokens and shorter access-token TTLs.
- **Secrets.** Dev JWT keys and DB passwords are in-repo/plaintext for
  convenience. Use a real secret manager and rotate the JWT keypair.
- **Multi-currency / FX.** Transfers require matching currencies; there is no FX
  conversion.
- **Outbox pattern.** For stronger guarantees on the external leg, the PENDING
  write + partner call could use a transactional outbox so settlement is driven
  by a durable event rather than an in-request call.
