# Original design diagrams

These figures were drawn specifically for Roster Sync from its implementation. No third-party diagram images or layouts are imported.

```bash
python -m pip install -r docs/diagrams/requirements.txt
python docs/diagrams/render.py
python docs/diagrams/render_design_note.py
```

Matplotlib renders the architecture and outbox recovery as PNG and SVG. ReportLab renders the separate print-sized, exactly two-page [design note](../DESIGN.pdf); text stays vector and readable. The scripts use fixed geometry, a stable SVG hash salt and deterministic PDF metadata.
