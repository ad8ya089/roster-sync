"""Draw the repository overview with readable labels, using Matplotlib.

Original geometry and copy for HR Roster Sync. No reference-repository assets.
Run: python docs/diagrams/review-overview.py
"""
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle, FancyArrowPatch

OUT = Path(__file__).resolve().parent
plt.rcParams.update({"font.family": "DejaVu Sans", "svg.fonttype": "path", "svg.hashsalt": "roster-review-overview"})
INK, MUTED, TEAL, BLUE, RULE = "#1B2430", "#5B6673", "#00756F", "#0057B8", "#D5DBD6"
fig, ax = plt.subplots(figsize=(12, 7.2), dpi=160)
fig.patch.set_facecolor("white")
ax.set(xlim=(0, 1200), ylim=(0, 720))
ax.axis("off")
fig.subplots_adjust(left=0, right=1, bottom=0, top=1)
ax.text(40, 678, "Two ways to keep the roster correct", fontsize=23, weight="bold", color=INK)
ax.text(40, 644, "A single Java service, backed by PostgreSQL", fontsize=16, color=MUTED)

def box(x, y, w, h, title, body, color=TEAL):
    ax.add_patch(Rectangle((x, y), w, h, facecolor="white", edgecolor=RULE, linewidth=1.4))
    ax.plot([x, x+w], [y+h, y+h], color=color, linewidth=3)
    ax.text(x+18, y+h-31, title, fontsize=16, weight="bold", color=INK, va="center")
    ax.text(x+18, y+h-59, body, fontsize=13, color=MUTED, va="top", linespacing=1.55)

def arrow(a, b, color=TEAL):
    ax.add_patch(FancyArrowPatch(a, b, arrowstyle="-|>", mutation_scale=15, linewidth=1.7, color=color))

box(40, 500, 530, 106, "HR events", "Create, update or deactivate an employee.\nEach event carries a source version.", BLUE)
box(630, 500, 530, 106, "Nightly CSV export", "Compare the full HR roster with stored state.\nRepair changes missed during the day.")
box(40, 309, 530, 136, "Check each event independently", "Check field ownership and required values.\nDetect replays and compare source versions.\nReject a bad record without blocking its neighbours.", BLUE)
box(630, 309, 530, 136, "Compare in 256 small buckets", "Build a durable change plan.\nIf too many employees are missing, wait for approval.\nApply safe plans in bounded transactions.")
arrow((305, 497), (305, 451), BLUE)
arrow((895, 497), (895, 451))
box(40, 103, 730, 143, "PostgreSQL: the durable record", "Employee state and source versions\nReceipts for accepted keys, pending change events\nReconciliation plans, decisions and progress")
box(830, 103, 330, 143, "Background delivery", "Retry pending events.\nDeduplicate delivery.\nCancel bookings / free seats.")
arrow((305, 306), (305, 252), BLUE)
ax.plot([895, 895, 650], [305, 275, 275], color=TEAL, linewidth=1.7)
arrow((650, 275), (650, 252))
arrow((774, 175), (825, 175))
ax.text(40, 48, "React console and REST API use the same rules. Role and company checks protect every API call.", fontsize=13, color=INK)
fig.savefig(OUT/"review-overview.png", dpi=160)
fig.savefig(OUT/"review-overview.svg", metadata={"Date": None})
plt.close(fig)
