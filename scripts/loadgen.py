#!/usr/bin/env python3
"""Traffic generator for the MobMoney transfer-service.

Produces a steady, mixed workload so the Prometheus/Grafana dashboards have
something to show: successful internal transfers, external transfers that
exercise the partner-bank settlement + retry path, deliberate failures, and
read traffic. Every transfer gets a fresh Idempotency-Key.

Usage:
    python3 scripts/loadgen.py                 # run ~10 min at ~3 req/s
    DURATION=120 RATE=8 python3 scripts/loadgen.py
Env:
    BASE_URL   default http://localhost:8080
    DURATION   seconds to run            (default 600)
    RATE       requests per second target(default 3)
"""
import json
import os
import random
import time
import urllib.request
import urllib.error
import uuid

BASE = os.environ.get("BASE_URL", "http://localhost:8080")
DURATION = float(os.environ.get("DURATION", "600"))
RATE = float(os.environ.get("RATE", "3"))
PW = "Password123!"


def call(method, path, token=None, body=None, idem=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if idem:
        req.add_header("Idempotency-Key", idem)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:  # connection refused, timeout, etc.
        return 0, str(e)


def login(user):
    status, body = call("POST", "/api/auth/login", body={"username": user, "password": PW})
    if status != 200:
        raise SystemExit(f"login failed for {user}: HTTP {status} {body}")
    return json.loads(body)["accessToken"]


def transfer(token, src, dst, amount, note):
    return call(
        "POST", "/api/transfers", token=token, idem=str(uuid.uuid4()),
        body={
            "sourceAccountNumber": src,
            "destinationAccountNumber": dst,
            "amount": str(amount),
            "currency": "USD",
            "narrative": note,
        },
    )


def main():
    print(f"Logging in… target {RATE} req/s for {DURATION:.0f}s against {BASE}")
    alice = login("alice")
    bob = login("bob")

    stats = {"2xx": 0, "4xx": 0, "5xx": 0, "err": 0, "total": 0}
    interval = 1.0 / RATE if RATE > 0 else 0
    end = time.time() + DURATION
    i = 0
    last_report = time.time()

    while time.time() < end:
        i += 1
        roll = random.random()

        if roll < 0.55:
            # Successful internal transfer between alice's two accounts;
            # alternate direction so neither balance drains to zero.
            if i % 2 == 0:
                src, dst = "ACC-ALICE-001", "ACC-ALICE-002"
            else:
                src, dst = "ACC-ALICE-002", "ACC-ALICE-001"
            status, _ = transfer(alice, src, dst, round(random.uniform(1, 15), 2), "internal")
        elif roll < 0.75:
            # External transfer -> partner bank (exercises retry/saga, ~10% transient fault).
            status, _ = transfer(alice, "ACC-ALICE-001", "PARTNER-EXT-001",
                                 round(random.uniform(1, 25), 2), "payout")
        elif roll < 0.85:
            # Bob sends to alice.
            status, _ = transfer(bob, "ACC-BOB-001", "ACC-ALICE-001",
                                 round(random.uniform(1, 10), 2), "from-bob")
        elif roll < 0.93:
            # Deliberate failure: amount above the partner reject threshold.
            status, _ = transfer(alice, "ACC-ALICE-001", "PARTNER-EXT-001",
                                 2_000_000, "too-big")
        else:
            # Read traffic.
            status, _ = call("GET", "/api/transfers?accountNumber=ACC-ALICE-001&size=5", token=alice)

        stats["total"] += 1
        if status == 0:
            stats["err"] += 1
        elif status < 400:
            stats["2xx"] += 1
        elif status < 500:
            stats["4xx"] += 1
        else:
            stats["5xx"] += 1

        if time.time() - last_report >= 5:
            print(f"  sent={stats['total']:5d}  2xx={stats['2xx']}  "
                  f"4xx={stats['4xx']}  5xx={stats['5xx']}  conn-err={stats['err']}")
            last_report = time.time()

        if interval:
            time.sleep(interval)

    print("DONE", stats)


if __name__ == "__main__":
    main()
