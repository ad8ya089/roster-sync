"""Local-only diagnostic matrix; writes raw results under ignored loadtest/results.

Requires Python3, Git Bash on Windows, curl/jq, and the running local Compose stack.
It never uses AWS and restores synchronous_commit even if a case fails.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading
import time
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "loadtest/results"
COMPOSE = ["docker", "compose", "-f", str(ROOT / "docker-compose.yml"), "-p", "roster-sync"]


def command(arguments):
    return subprocess.run(arguments, cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()


def sql(text):
    return command(COMPOSE + ["exec", "-T", "db", "sh", "-c", 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "$1"', "sh", text])


def reload_sync(value):
    if value not in {"on", "off", "local", "remote_write", "remote_apply"}:
        raise ValueError("Unexpected synchronous_commit value")
    sql("ALTER SYSTEM SET synchronous_commit='" + value + "'")
    sql("SELECT pg_reload_conf()")
    if sql("SHOW synchronous_commit") != value:
        raise RuntimeError("Database setting did not reload")


def drain():
    # The single publisher deliberately drains more slowly than peak ingestion.
    # Start each case with an empty queue instead of measuring the previous case's load.
    deadline = time.monotonic() + 1800
    while time.monotonic() < deadline:
        pending = int(sql("SELECT count(*) FROM outbox_event WHERE status='PENDING'"))
        if pending == 0:
            return
        print("waiting for local outbox pending=" + str(pending), flush=True)
        time.sleep(2)
    raise RuntimeError("Local outbox did not drain")


def gauge(name):
    with urlopen("http://127.0.0.1:8081/actuator/metrics/hikaricp.connections." + name, timeout=2) as response:
        return sum(item["value"] for item in json.load(response)["measurements"])


def run_case(batch, vus, index, diagnostic=False):
    label = f"b{batch}-v{vus}" + ("-sync-off" if diagnostic else "-sync-on")
    saved = RESULTS / (label + ".json")
    if os.environ.get("RESUME_MATRIX") == "1" and saved.exists():
        summary = json.loads(saved.read_text(encoding="utf-8"))
        if summary["exitCode"] == 0:
            print("RESUME completed " + label, flush=True)
            return summary
    drain()
    print("CASE " + label, flush=True)
    stop = threading.Event()
    samples = []

    def sample():
        while not stop.is_set():
            try:
                samples.append({"at": time.monotonic(), "pending": gauge("pending"), "active": gauge("active")})
            except (OSError, ValueError):
                samples.append({"at": time.monotonic(), "error": True})
            stop.wait(.2)

    before = int(sql("SELECT xact_commit FROM pg_stat_database WHERE datname=current_database()"))
    started = time.monotonic()
    monitor = threading.Thread(target=sample, daemon=True)
    monitor.start()
    environment = os.environ.copy()
    environment.update(BATCH_SIZE=str(batch), LOAD_VUS=str(vus), LOAD_DURATION="60s", RUN_ID=f"M{int(time.time())}_{index}")
    bash = r"C:\Program Files\Git\bin\bash.exe" if os.name == "nt" else shutil.which("bash")
    try:
        with (RESULTS / (label + ".log")).open("w", encoding="utf-8") as log:
            process = subprocess.run([bash, "scripts/loadtest.sh"], cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT)
    finally:
        stop.set()
        monitor.join(5)
    after = int(sql("SELECT xact_commit FROM pg_stat_database WHERE datname=current_database()"))
    elapsed = time.monotonic() - started
    summary = json.loads((RESULTS / "summary.json").read_text(encoding="utf-8"))
    successful = [item for item in samples if "error" not in item]
    summary.update(label=label, synchronousCommit=sql("SHOW synchronous_commit"), observedWindowSeconds=elapsed,
                   commitDelta=after-before, commitsPerSecond=(after-before)/elapsed,
                   maxConnectionsPending=max(item["pending"] for item in successful),
                   maxConnectionsActive=max(item["active"] for item in successful),
                   pendingSampleFraction=sum(item["pending"]>0 for item in successful)/len(successful),
                   gaugeSamples=len(successful), gaugeErrors=len(samples)-len(successful), sampleIntervalMs=200,
                   outboxPendingAtEnd=int(sql("SELECT count(*) FROM outbox_event WHERE status='PENDING'")),
                   exitCode=process.returncode)
    (RESULTS / (label + ".json")).write_text(json.dumps(summary, indent=2), encoding="utf-8")
    (RESULTS / (label + "-gauges.json")).write_text(json.dumps(samples), encoding="utf-8")
    print(json.dumps({key:summary[key] for key in ["label","events","p50Ms","p99Ms","perEventP50Ms","perEventP99Ms","maxConnectionsPending","commitsPerSecond","outboxPendingAtEnd","exitCode"]}), flush=True)
    if process.returncode:
        raise RuntimeError("Load scenario failed: " + label)
    return summary


def main():
    RESULTS.mkdir(exist_ok=True)
    context = json.loads(command(["docker", "context", "inspect"]))[0]
    endpoint = context["Endpoints"]["docker"]["Host"]
    if not endpoint.startswith(("npipe:", "unix:")):
        raise RuntimeError("Only a local Docker engine is allowed")
    original = sql("SHOW synchronous_commit")
    auto = sql("SELECT count(*) FROM pg_file_settings WHERE name='synchronous_commit' AND sourcefile LIKE '%postgresql.auto.conf'")
    results = []
    try:
        if original != "on":
            raise RuntimeError("Baseline requires synchronous_commit=on")
        for index, (batch, vus) in enumerate([(1,5),(50,1),(50,5),(50,10),(50,20)]):
            results.append(run_case(batch, vus, index))
        drain()
        reload_sync("off")
        results.append(run_case(50,20,5,diagnostic=True))
    finally:
        if auto == "0":
            sql("ALTER SYSTEM RESET synchronous_commit")
            sql("SELECT pg_reload_conf()")
        else:
            reload_sync(original)
        if sql("SHOW synchronous_commit") != original:
            raise RuntimeError("Could not restore synchronous_commit")
        print("RESTORED synchronous_commit=" + original, flush=True)
    (RESULTS / "matrix.json").write_text(json.dumps(results, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
