#!/usr/bin/env python3
"""Exercise the compiled API with no DB/provider credentials or inherited env."""

from pathlib import Path
import json
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


def available_addresses():
    sockets = [socket.socket(), socket.socket()]
    try:
        for listener in sockets:
            listener.bind(("127.0.0.1", 0))
        return [f"127.0.0.1:{listener.getsockname()[1]}" for listener in sockets]
    finally:
        for listener in sockets:
            listener.close()


def main():
    root = Path(__file__).resolve().parents[1]
    api_address, metrics_address = available_addresses()
    api = f"http://{api_address}"
    metrics = f"http://{metrics_address}"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(url, *, headers=None):
        incoming = urllib.request.Request(url, headers=headers or {})
        try:
            response = opener.open(incoming, timeout=2)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.read()

    with tempfile.TemporaryDirectory(prefix="anve-observability-") as scratch:
        log_path = Path(scratch) / "api.log"
        with log_path.open("wb") as output:
            process = subprocess.Popen(
                [str(root / "apps/server/bin/api")],
                env={
                    "ENVIRONMENT": "development",
                    "LISTEN_ADDR": api_address,
                    "METRICS_LISTEN_ADDR": metrics_address,
                    "AUTH_DEV_LOGIN_ENABLED": "false",
                    "SHUTDOWN_TIMEOUT": "2s",
                    "VERSION": "observability-smoke",
                },
                stdout=output,
                stderr=subprocess.STDOUT,
            )
            try:
                deadline = time.monotonic() + 10
                while True:
                    if process.poll() is not None:
                        raise AssertionError("API exited before readiness smoke")
                    try:
                        status, body = request(api + "/healthz")
                        if status == 200:
                            assert json.loads(body)["version"] == "observability-smoke"
                            break
                    except (urllib.error.URLError, TimeoutError):
                        pass
                    if time.monotonic() >= deadline:
                        raise AssertionError("API did not start within smoke deadline")
                    time.sleep(0.05)

                readiness, _ = request(api + "/readyz")
                assert readiness == 503, f"no-database readiness must be 503, got {readiness}"
                assert request(api + "/metrics")[0] == 404, "public metrics route must be absent"
                assert request(metrics + "/api/v1/version")[0] == 404
                assert request(metrics + "/metrics", headers={"Host": "attacker.invalid"})[0] == 403
                assert request(metrics + "/metrics", headers={"Origin": "https://attacker.invalid"})[0] == 403
                private_values = ["synthetic-private-query", "synthetic-private-id", "synthetic-private-header"]
                assert request(api + "/healthz?private=" + private_values[0], headers={
                    "X-Request-ID": private_values[1],
                    "Authorization": "Bearer " + private_values[2],
                })[0] == 200
                status, body = request(metrics + "/metrics")
                assert status == 200
                text = body.decode()
                assert 'route="/healthz"' in text
                assert "anve_http_requests_total" in text
                assert "anve_http_requests_in_flight 0" in text
                assert all(value not in text for value in private_values)
                process.terminate()
                assert process.wait(timeout=4) == 0, "SIGTERM must drain both listeners cleanly"
                logs = log_path.read_text()
                assert all(value not in logs for value in private_values)
                assert '"msg":"http request completed"' in logs
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=4)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=2)
    print("Observability smoke passed: private listener, sanitized telemetry, no DB/provider, clean SIGTERM.")


if __name__ == "__main__":
    main()
