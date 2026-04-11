# mini-chronos

A simplified ERP backend module inspired by [Alpha Loop's Chronos system](https://www.alpha-loop.de/chronos-erp/),
built as practical preparation for an internship at Alpha Loop GmbH in Lindlar, NRW.

## What this is

mini-chronos implements the **Auftragsabwicklung** (order processing) core of a rental ERP system —
the same domain that Chronos uses for clients like Lang GmbH managing event equipment.

The project focuses on the Spring Boot + Hibernate fundamentals that Sebastian asked about:
Hibernate relationships, Liquibase migrations, optimistic locking, and the N+1 problem.

---

## Tech stack

| Layer | Technology |
|---|---|
| Runtime | Java 21, Spring Boot 3.x |
| Persistence | Spring Data JPA, Hibernate 6, PostgreSQL 16 |
| Migrations | Liquibase 4.x |
| Mapping | MapStruct 1.5 |
| Validation | Jakarta Validation 3 |
| API Docs | springdoc-openapi (Swagger UI) |
| Build | Maven 3.9 |
| Infrastructure | Docker, Docker Compose |

---

## Domain model

```
Customer (Kunde)
  └── Project (Vorgang)
        └── Order (Auftrag)  ←── @Version (optimistic locking)
              └── OrderItem (Position) ←── price snapshot
                    └── Availability (Verfügbarkeit)

EquipmentUnit (Gerät)
  └── Availability  ←── composite index (equipment_id, start_date, end_date)
```

Key design decisions documented in the entity Javadocs:
- `@Version` on `Order` prevents double-booking under concurrent load
- `Availability.orderItem` is nullable — maintenance blocks have no order
- `OrderItem.unitPrice` is a price snapshot — changing daily rates never affects past orders
- All collections use `FetchType.LAZY` — N+1 solved via `@EntityGraph` in repositories

---

## Quick start

### Prerequisites

- Docker + Docker Compose
- Java 21 (only needed if running without Docker)

### Run with Docker Compose

```bash
# 1. Clone and enter the project
git clone <repo-url>
cd mini-chronos

# 2. Set up environment
cp .env.example .env
# Edit .env if needed (defaults work out of the box)

# 3. Start everything
docker-compose up -d

# 4. Watch the logs
docker-compose logs -f app
```

Wait for: `Started MiniChronosApplication in X seconds`

### Verify it works

```bash
# Health check
curl http://localhost:8080/api/customers

# Or open Swagger UI in browser:
# http://localhost:8080/swagger-ui.html
```

### Stop

```bash
docker-compose down        # stop, keep data
docker-compose down -v     # stop + delete database (fresh start)
```

---

## Run locally (without Docker)

```bash
# Start only the database
docker-compose up -d postgres

# Run the app with dev profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

---

## API overview

Full interactive documentation: **http://localhost:8080/swagger-ui.html**

| Resource | Endpoint | Description |
|---|---|---|
| Customers | `GET /api/customers` | Paginated list with search |
| | `GET /api/customers/{id}` | Detail with projects |
| | `POST /api/customers` | Create |
| | `PUT /api/customers/{id}` | Update |
| | `DELETE /api/customers/{id}` | Deactivate (soft delete) |
| Projects | `GET /api/projects/{id}` | Detail with orders |
| | `POST /api/projects` | Create for a customer |
| | `PATCH /api/projects/{id}/status` | Status transition |
| Equipment | `GET /api/equipment/available` | Availability search |
| | `GET /api/equipment/calendar` | Monthly calendar view |
| | `POST /api/equipment/{id}/maintenance` | Schedule maintenance |
| Orders | `POST /api/orders` | Create draft |
| | `POST /api/orders/{id}/confirm` | Confirm + reserve equipment |
| | `POST /api/orders/{id}/cancel` | Cancel + release equipment |

---

## Seed data (dev profile)

The dev profile loads test data automatically via Liquibase (`010-seed-data.xml`):

| What | Details |
|---|---|
| Users | `admin` / `m.mueller` / `j.schmidt` — all with password `password123` |
| Customers | Lang GmbH, Messe Köln GmbH |
| Equipment | 3× Epson EB-L1755U projectors, 1 screen, 1 microphone (in maintenance) |
| Sample order | `ORD-2026-00000400` — 3 projectors booked April 13–14 for Jahreskonferenz 2026 |

---

## Project structure

```
src/main/java/de/alphaloop/chronos/backend/
├── config/
│   ├── JpaConfig.java          # @EnableJpaAuditing
│   └── OpenApiConfig.java      # Swagger / OpenAPI 3
├── controller/
│   ├── GlobalExceptionHandler.java
│   ├── CustomerController.java
│   ├── ProjectController.java
│   ├── EquipmentController.java
│   ├── OrderController.java
│   └── UserController.java
├── domain/                     # JPA entities
├── dto/
│   ├── request/                # incoming request bodies
│   └── response/               # outgoing JSON responses
├── enums/
├── exception/                  # ResourceNotFoundException, ConflictException, ...
├── mapper/                     # MapStruct interfaces
├── repository/                 # Spring Data JPA repositories
└── service/                    # business logic

src/main/resources/
├── application.yml
├── application-dev.yml
└── db/changelog/
    ├── db.changelog-master.xml
    ├── 001-create-sequences.xml
    ├── 002-create-users.xml
    ├── ...
    └── 010-seed-data.xml
```

---

## Key Hibernate concepts demonstrated

**N+1 problem and solutions** — `CustomerRepository.findByIdWithProjects()` uses
`@EntityGraph` to load customer + projects in one JOIN query instead of 1 + N queries.

**Optimistic locking** — `Order` has `@Version Long version`. When two users confirm
the same order simultaneously, the second gets a 409 Conflict response.
The first-check / second-check pattern in `OrderService.confirmOrder()` also prevents
double-booking at the availability level.

**Lazy loading strategy** — all `@OneToMany` and `@ManyToOne` associations use
`FetchType.LAZY`. The service layer explicitly chooses what to load using
`findByIdWithXxx()` variants backed by `@EntityGraph`.

**Price snapshot** — `OrderItem.unitPrice` is copied from `EquipmentUnit.dailyRate`
at order creation time. Changing the daily rate later never affects existing orders.

**Soft delete** — customers and users are deactivated (`active = false`), never deleted.
German law (GoBD) requires financial records to be preserved for 10 years.

---

## Liquibase migration notes

Migrations run automatically on application startup.
**Never edit an existing changeset** — Liquibase stores md5 checksums and will fail to start.
To change schema: always create a new numbered changeset.

The seed data changeset (`010`) only runs with `contexts: dev`.
Production will never execute it.

---

## What's next (not in this MVP)

- Spring Security + JWT authentication
- `@PreAuthorize` role-based access control
- Angular frontend (dashboard, order editor, equipment calendar)
- Integration tests with Testcontainers
- GitLab CI/CD pipeline
