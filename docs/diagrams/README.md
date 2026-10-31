# Original design diagrams

These figures were drawn specifically for Roster Sync from its implementation. No third-party diagram images or layouts are imported.

## Editable Mermaid figures

The `.mmd` sources describe this repository's Flyway schema, transactions and workers. PNG exports are embedded in the README for reliable display; SVG exports remain readable when zoomed. Generation runs locally and sends no source, recordings or credentials to a diagram service.

With Node 24 installed, set up the optional documentation tools outside the application dependencies:

```bash
npm install --prefix target/diagram-tools --no-audit --no-fund mermaid@12.2.0 playwright@1.64.1
node target/diagram-tools/node_modules/playwright/cli.js install chromium
node docs/diagrams/render-mermaid.mjs
```

Pass one or more stems to render only those files, for example `node docs/diagrams/render-mermaid.mjs system-context`. `DIAGRAM_TOOLS` can point to a different tools directory. The renderer reuses one browser and uses the committed theme. These packages are not needed to build or run the app.

## Earlier print-design figures

| Figure | Purpose |
|---|---|
| `system-context` | Actual single-app/PostgreSQL runtime and publisher boundary |
| `roster-key-erd` | Readable core employee/receipt/outbox relationship view |
| `workforce-erd` | Tenant, office, staff account, booking and seat relationships |
| `sync-erd` | Reconciliation plans, rejection audit, alerts and consumer deduplication |
| `schema-full` | All 14 application tables, with physical versus logical links distinguished |
| `event-sequence` | Per-record writes and independent outbox delivery |
| `nightly-sequence` | Streaming buckets, brake, operator decision and locked application |

ER figures show selected columns, not a replacement for the migrations. `char64` means SQL `CHAR(64)`. Solid lines denote the declared SQL FKs; dotted lines denote logical application associations. Composite unique keys and nullable/tombstone semantics are explained in [the schema guide](../SCHEMA_GUIDE.md). Not every tenant-scoping relation is drawn as a physical FK. The notation is rendered with [Mermaid's ER syntax](https://mermaid.js.org/syntax/entityRelationshipDiagram.html).

```bash
python -m pip install -r docs/diagrams/requirements.txt
python docs/diagrams/render.py
python docs/diagrams/render_design_note.py
```

Matplotlib renders the architecture and outbox recovery as PNG and SVG. ReportLab renders the separate print-sized, exactly two-page [design note](../DESIGN.pdf); text stays vector and readable. The scripts use fixed geometry, a stable SVG hash salt and deterministic PDF metadata.
