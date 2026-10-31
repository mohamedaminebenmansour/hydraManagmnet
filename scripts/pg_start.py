#!/usr/bin/env python3
"""Start (or attach to) a persistent PostgreSQL on 127.0.0.1:5432.

Uses the pgserver package (bundles PostgreSQL 16 binaries). cleanup_mode=None
leaves the postmaster running detached after this process exits. Idempotent:
if the cluster is already up, get_server just reconnects.
"""
import os
import sys
import time

os.environ.setdefault("XDG_RUNTIME_DIR", "/tmp/runtime-%d" % os.getuid())
os.makedirs(os.environ["XDG_RUNTIME_DIR"], exist_ok=True)

import pgserver  # noqa: E402

PGDATA = "/home/z/my-project/.pgdata"


def main() -> None:
    srv = pgserver.get_server(PGDATA, cleanup_mode=None)
    # Make sure TCP on 5432 is on (pgserver defaults to unix-socket only).
    conf = srv.pgdata_dir / "postgresql.auto.conf" if hasattr(srv, "pgdata_dir") else None
    uri = srv.get_uri()
    print("pgserver URI:", uri)
    # Ensure a known password for JDBC + app databases.
    srv.psql("alter user postgres password 'hydra';")
    srv.psql("select 1;")
    # Wait until TCP answers on 127.0.0.1:5432
    import socket
    for _ in range(30):
        try:
            with socket.create_connection(("127.0.0.1", 5432), timeout=1):
                print("TCP 127.0.0.1:5432 OK")
                break
        except OSError:
            time.sleep(1)
    else:
        print("WARN: TCP 5432 not reachable yet", file=sys.stderr)
    # Create app DBs if missing (idempotent).
    for db in ("hydra", "hydra_test"):
        try:
            srv.psql("create database %s;" % db)
            print("created db", db)
        except Exception:
            print("db exists:", db)


if __name__ == "__main__":
    main()
