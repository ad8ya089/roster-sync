# References (only the ones the design actually uses)

| Topic | Source | Used for |
|---|---|---|
| Transactional outbox | Chris Richardson, *Pattern: Transactional outbox* — https://microservices.io/patterns/data/transactional-outbox.html | ADR-003; dual-write problem framing |
| Idempotent consumer | Chris Richardson, *Pattern: Idempotent Consumer* — https://microservices.io/patterns/communication-style/idempotent-consumer.html | ADR-004; `eventId` dedupe |
| Polling publisher with `SKIP LOCKED` | OneUptime, *How to Build Outbox Pattern Implementation* (2026) — https://oneuptime.com/blog/post/2026-01-30-outbox-pattern-implementation/view ; faststream-outbox README (notes SKIP LOCKED gives best-effort order only) — https://github.com/modern-python/faststream-outbox | publisher query; ordering caveat |
| CDC alternative | Debezium outbox event router — https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html | "what I'd do at scale" |
| Mass-delete safety brake precedent | Microsoft Learn, *Enable accidental deletions prevention in the Microsoft Entra provisioning service* — https://learn.microsoft.com/en-us/entra/identity/app-provisioning/accidental-deletions ; *Entra Connect Sync: Prevent accidental deletes* (default threshold 500 objects) — https://learn.microsoft.com/azure/active-directory/hybrid/how-to-connect-sync-feature-prevent-accidental-deletes | brake semantics (threshold, quarantine, allow/reject, notification) |
| HR → app provisioning standard | RFC 7643 / 7644 (SCIM 2.0) — https://www.rfc-editor.org/rfc/rfc7644 | full-resource PUT semantics, `active=false` deactivation, ETag versioning analogy |
| Hash-partitioned diff | Grace hash join (Kitsuregawa, Tanaka, Moto-oka, 1983); any DB textbook chapter on hash joins | bucketed reconciliation |
| Anti-entropy with hash trees | DeCandia et al., *Dynamo: Amazon's Highly Available Key-value Store*, SOSP 2007 — https://www.allthingsdistributed.com/files/amazon-dynamo-sosp2007.pdf | "next step: Merkle tree over buckets" |
| Tombstones / last-writer-wins by version | Dynamo paper; Cassandra docs on tombstones | DEACTIVATE before CREATE |
| Spring Boot 4.1 | Spring blog, *Spring Boot 4.1.0 available now* (June 10, 2026) — https://spring.io/blog/2026/06/10/spring-boot-4/ ; 4.1.1 (Aug 2026) | version choice |
| Boot 3.5 end of OSS support (June 2026) | Dan Vega, *Spring Boot end of life* — https://www.danvega.dev/blog/spring-boot-end-of-life | ADR-001 |
| Boot 4 migration gotchas | BellSoft, *How to migrate a Spring Boot 3 project to Spring Boot 4* — https://bell-sw.com/blog/how-to-migrate-a-spring-boot-3-project-to-spring-boot-4/ | Boot 4 dependency coordinates (starter renames, Flyway starter, Jackson 3) |
| Structured logging | Spring blog, *Structured logging in Spring Boot 3.4* — https://spring.io/blog/2024/08/23/structured-logging-in-spring-boot-3-4 | `logging.structured.format.console=ecs` |
| Testcontainers + `@ServiceConnection` | Vaadin docs, *Replace H2* (Testcontainers 2 artifact names) — https://vaadin.com/docs/latest/building-apps/forms-data/replace-h2 | test setup |
| k6 | Grafana k6 docs, *Custom summary* — https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/ ; *Run locally (Docker)* — https://grafana.com/docs/learning-paths/run-first-k6-test/run-locally/ | load test |
| RFC 9457 Problem Details | https://www.rfc-editor.org/rfc/rfc9457 | error format |
| AWS App Runner availability change | AWS docs — https://docs.aws.amazon.com/apprunner/latest/dg/apprunner-availability-change.html | DEPLOYMENT option F |
| ECS Express Mode | AWS What's New (Nov 2025) — https://aws.amazon.com/about-aws/whats-new/2025/11/announcing-amazon-ecs-express-mode/ | DEPLOYMENT option C |
| Fargate / ALB / IPv4 costs | Cloudburn, *AWS Fargate Pricing* (2026) — https://cloudburn.io/blog/aws-fargate-pricing ; AWS blog, *public IPv4 address charge* — https://aws.amazon.com/blogs/aws/new-aws-public-ipv4-address-charge-public-ip-insights | cost table |
| Sydney EC2/EBS prices | [AWS regional price list](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/ap-southeast-2/index.csv) | selected-region on-demand estimate; the pricing distribution hostname is global |
| Lightsail bundles | Cloudburn, *Amazon Lightsail Pricing 2026* — https://cloudburn.io/blog/amazon-lightsail-pricing | option E |
| AWS Free Tier (credit-based, since July 2025) | https://aws.amazon.com/free | budget note |
| Render free tier limits | Render free-tier summaries (15-min sleep, 30-day Postgres) — https://livemy.app/blog/render-pricing | fallback |
| Prometheus pinned release | [Prometheus v3.15.1](https://github.com/prometheus/prometheus/releases/tag/v3.15.1) | optional monitoring image |
| Grafana pinned release | [Grafana v13.3.0](https://github.com/grafana/grafana/releases/tag/v13.3.0) | optional dashboard image |
| k6 pinned release | [k6 v2.3.0](https://github.com/grafana/k6/releases/tag/v2.3.0) | portable Docker load runner |
| Failsafe report handling | [Failsafe integration-test parameters](https://maven.apache.org/surefire/maven-failsafe-plugin/integration-test-mojo.html) | separate benchmark execution and summary file |
