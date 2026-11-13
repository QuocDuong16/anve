#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

env_file=.env.prod
compose_file=infra/docker/docker-compose.prod.yml

if [[ ! -f "$env_file" ]]; then
	cat >&2 <<'EOF'
Production configuration is missing. Copy .env.prod.example to .env.prod,
set the production values, and restrict it with: chmod 600 .env.prod
EOF
	exit 1
fi

if [[ ! -r "$env_file" ]]; then
	echo ".env.prod must be readable by the deployment user." >&2
	exit 1
fi

python3 - "$env_file" <<'PY'
import re
import stat
import sys
import os
from pathlib import Path

path = Path(sys.argv[1])
if stat.S_IMODE(path.stat().st_mode) & 0o077:
    print(".env.prod permissions are too open; run chmod 600 .env.prod.", file=sys.stderr)
    raise SystemExit(1)

values = {}
for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
    line = raw.strip()
    if not line or line.startswith("#"):
        continue
    key, separator, value = line.partition("=")
    if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key.strip()):
        print(f"Invalid .env.prod syntax on line {line_number}.", file=sys.stderr)
        raise SystemExit(1)
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        value = value[1:-1]
    values[key.strip()] = value

required = (
    "API_DOMAIN", "POSTGRES_USER", "POSTGRES_PASSWORD", "POSTGRES_DB",
    "AUTH_GOOGLE_CLIENT_ID", "ENVIRONMENT", "LISTEN_ADDR",
    "AUTH_DEV_LOGIN_ENABLED", "LOCATION_RETENTION_DAYS",
)
missing = [name for name in required if not values.get(name)]
if missing:
    print("Set required values in .env.prod: " + ", ".join(missing), file=sys.stderr)
    raise SystemExit(1)

domain = values["API_DOMAIN"]
if not re.fullmatch(r"(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}", domain):
    print("API_DOMAIN must be a hostname such as api.example.com.", file=sys.stderr)
    raise SystemExit(1)
if values["ENVIRONMENT"] != "production":
    print("ENVIRONMENT must be exactly production.", file=sys.stderr)
    raise SystemExit(1)
if values["LISTEN_ADDR"] != "0.0.0.0:8080":
    print("LISTEN_ADDR must be 0.0.0.0:8080 inside the production container.", file=sys.stderr)
    raise SystemExit(1)
if values["AUTH_DEV_LOGIN_ENABLED"].lower() != "false":
    print("AUTH_DEV_LOGIN_ENABLED must be false in production.", file=sys.stderr)
    raise SystemExit(1)
for name, default in (("HTTP_PORT", "24080"),):
    port = values.get(name, default)
    if not re.fullmatch(r"24[0-9]{3}", port):
        print(f"{name} must use the 24xxx port range.", file=sys.stderr)
        raise SystemExit(1)
if values["AUTH_GOOGLE_CLIENT_ID"].startswith("replace") or not values["AUTH_GOOGLE_CLIENT_ID"].endswith(".apps.googleusercontent.com"):
    print("Set AUTH_GOOGLE_CLIENT_ID to the Google OAuth Web client ID before deployment.", file=sys.stderr)
    raise SystemExit(1)
if not re.fullmatch(r"[A-Za-z0-9_]+", values["POSTGRES_USER"]) or not re.fullmatch(r"[A-Za-z0-9_]+", values["POSTGRES_DB"]):
    print("POSTGRES_USER and POSTGRES_DB may contain only letters, digits and underscores.", file=sys.stderr)
    raise SystemExit(1)
if not re.fullmatch(r"[A-Fa-f0-9]{64}", values["POSTGRES_PASSWORD"]):
    print("POSTGRES_PASSWORD must be 64 hexadecimal characters (generate with openssl rand -hex 32).", file=sys.stderr)
    raise SystemExit(1)
try:
    retention = int(values["LOCATION_RETENTION_DAYS"])
except ValueError:
    retention = 0
if not 1 <= retention <= 365:
    print("LOCATION_RETENTION_DAYS must be between 1 and 365.", file=sys.stderr)
    raise SystemExit(1)

fcm_project = values.get("FCM_PROJECT_ID", "")
fcm_file = values.get("FCM_CREDENTIALS_FILE", "/dev/null")
if fcm_project and (not Path(fcm_file).is_absolute() or fcm_file == "/dev/null" or not Path(fcm_file).is_file() or not os.access(fcm_file, os.R_OK)):
    print("FCM is enabled but FCM_CREDENTIALS_FILE is missing or unreadable.", file=sys.stderr)
    raise SystemExit(1)
if fcm_project:
    credential_mode = stat.S_IMODE(Path(fcm_file).stat().st_mode)
    parent_mode = stat.S_IMODE(Path(fcm_file).parent.stat().st_mode)
    if credential_mode & 0o222 or not credential_mode & 0o444 or parent_mode & 0o077:
        print("Store FCM credentials in a private directory (chmod 700) as a read-only file (chmod 444).", file=sys.stderr)
        raise SystemExit(1)
PY

command=${1:-}
case "$command" in
	up|down|logs|config) ;;
	*) echo "Usage: $0 {up|down|logs|config}" >&2; exit 2 ;;
esac

# Make imports .env for development and exports those variables. Remove app
# settings from that environment so .env.prod is always authoritative here.
unset_vars=(
	API_PORT API_DOMAIN AUTH_DEV_LOGIN_ALLOW_NETWORK AUTH_DEV_LOGIN_ALLOWED_CIDRS
	AUTH_DEV_LOGIN_ENABLED AUTH_GOOGLE_CLIENT_ID COMPOSE_PROJECT_NAME DATABASE_URL
	ENVIRONMENT FCM_CREDENTIALS_FILE FCM_PROJECT_ID LISTEN_ADDR LOCATION_RETENTION_DAYS
	HTTP_PORT POSTGRES_DB POSTGRES_PASSWORD POSTGRES_PORT POSTGRES_USER TZ
)
unset_args=()
for name in "${unset_vars[@]}"; do
	unset_args+=(--unset="$name")
done

compose() {
	env "${unset_args[@]}" docker compose \
		--project-directory . \
		--env-file "$env_file" \
		--project-name anve-prod \
		-f "$compose_file" "${@}"
}

case "$command" in
	up)
		compose build --pull
		compose up -d --force-recreate --remove-orphans
		compose ps
		domain="$(python3 -c 'import sys; from pathlib import Path; print(next(line.partition("=")[2].strip().strip("\"\047") for line in Path(sys.argv[1]).read_text().splitlines() if line.strip().startswith("API_DOMAIN=")))' "$env_file")"
		printf '\nProduction API: https://%s/healthz\n' "$domain"
		;;
	down)
		compose down
		;;
	logs)
		compose logs -f
		;;
	config)
		compose config --quiet
		echo "Production Compose configuration is valid."
		;;
esac
