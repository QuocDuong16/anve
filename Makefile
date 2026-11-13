# Makefile — AnVề
#
# Every target is documented with a `##` comment and grouped under a `##@`
# heading, so `make help` lists the whole workflow.

.DEFAULT_GOAL := help

.PHONY: help clean \
	toolchain-install toolchain-check \
	docker-up docker-down docker-logs docker-config \
	docker-up-dev docker-down-dev docker-logs-dev docker-clean-dev docker-config-dev \
	compose-lint up down logs up-dev down-dev logs-dev \
	migrate-up-dev migrate-down-dev \
	server-run server-build server-observability-smoke server-test server-test-race server-test-integration server-history-plan server-fix server-lint server-check \
	android-sdk-check android-build android-test android-lint android-check android-release android-release-config-check android-release-check android-signed-smoke android-privacy-candidate android-distribution android-test-build android-instrumentation android-validation-avd android-emulator-validation android-ui-captures tracking-validation android-clean \
	contracts-lint deps-lint release-tooling-check \
	quality-check

##@ Getting started
help: ## Show the documented Make targets
	@awk 'BEGIN { FS = ":.*##"; printf "Usage: make <target> [VAR=value...]\n" } /^##@/ { if (shown++) printf "\n"; printf "%s\n", substr($$0, 5); next } /^[a-zA-Z0-9_.-]+:.*##/ { printf "  \033[36m%-32s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

##@ Toolchain
# Go, the JDK and the Gradle distribution are pinned in mise.toml and resolved
# by mise.lock, so local development and CI share one toolchain definition.
# Install mise first (https://mise.jdx.dev), then run `make toolchain-install`.
TOOLCHAIN_TOOLS = go java gradle golangci-lint actionlint \
	go:golang.org/x/tools/gopls \
	go:golang.org/x/vuln/cmd/govulncheck \
	go:github.com/securego/gosec/v2/cmd/gosec

MISE_MISSING = { echo "Missing mise — install it from https://mise.jdx.dev, then run 'make toolchain-install'." >&2; exit 1; }

toolchain-install: ## Install the repository-pinned runtimes and Go tools
	@command -v mise >/dev/null 2>&1 || $(MISE_MISSING)
	mise install --locked $(TOOLCHAIN_TOOLS)

toolchain-check: ## Verify the repository toolchain pins are consistent
	@command -v mise >/dev/null 2>&1 || $(MISE_MISSING)
	bash scripts/check_toolchain_versions.sh

##@ Docker Compose
DOCKER_COMPOSE = docker compose --project-directory . -f infra/docker/docker-compose.yml
DEV_COMPOSE = $(DOCKER_COMPOSE)

docker-up: ## Deploy production API, PostgreSQL/PostGIS and HTTPS ingress from .env.prod
	bash scripts/docker_prod.sh up

docker-down: ## Stop the production stack without deleting persistent data
	bash scripts/docker_prod.sh down

docker-logs: ## Follow production service logs
	bash scripts/docker_prod.sh logs

docker-config: ## Validate production Compose without printing secrets
	bash scripts/docker_prod.sh config

docker-up-dev: ## Build and start the local development stack (PostgreSQL/PostGIS + API)
	$(DEV_COMPOSE) build && $(DEV_COMPOSE) up -d --force-recreate
	@echo ""
	@echo "✅ Development stack ready:"
	@echo "   API:          http://localhost:$${API_PORT:-8080}/healthz"
	@echo "   PostgreSQL:   localhost:$${POSTGRES_PORT:-5432}"

docker-down-dev: ## Stop the development stack
	$(DEV_COMPOSE) down

docker-logs-dev: ## Follow the development stack logs
	$(DEV_COMPOSE) logs -f

docker-clean-dev: ## Stop the development stack and delete its volumes
	$(DEV_COMPOSE) down -v --remove-orphans

docker-config-dev: ## Print the effective development Compose configuration
	$(DEV_COMPOSE) config

compose-lint: ## Verify that no Compose port is published beyond loopback
	@command -v python3 >/dev/null 2>&1 || { echo "python3 is required to validate the Compose exposure." >&2; exit 1; }
	python3 scripts/check_compose_ports.py

# Short aliases; the unqualified form operates on production.
up: docker-up
down: docker-down
logs: docker-logs
up-dev: docker-up-dev
down-dev: docker-down-dev
logs-dev: docker-logs-dev

##@ Database migrations
migrate-up-dev: ## Apply pending migrations to the development database
	$(DEV_COMPOSE) run --rm migrate up

migrate-down-dev: ## Roll back the most recent development migration
	$(DEV_COMPOSE) run --rm migrate down

##@ Go server
server-run: ## Run the API server locally (no hot reload; restart after changes)
	cd apps/server && go run ./cmd/api

server-build: ## Build the API and migrate binaries
	cd apps/server && go build -o bin/api ./cmd/api
	cd apps/server && go build -o bin/migrate ./cmd/migrate

server-observability-smoke: server-build ## Smoke-test private metrics and clean HTTP shutdown without a database
	python3 scripts/check_observability_smoke.py

server-test: ## Run the Go server tests
	cd apps/server && go test ./...

SERVER_TEST_RACE_COUNT ?= 1
SERVER_TEST_RACE_PACKAGES ?= ./...

server-test-race: ## Run the Go server tests with the race detector
	cd apps/server && go test -race -count=$(SERVER_TEST_RACE_COUNT) $(SERVER_TEST_RACE_PACKAGES)

server-test-integration: ## Run DB integration tests against the running development stack (make docker-up-dev first)
	cd apps/server && TEST_DATABASE_URL="$${TEST_DATABASE_URL:-postgres://$${POSTGRES_USER:-anve}:$${POSTGRES_PASSWORD:-anve}@localhost:$${POSTGRES_PORT:-5432}/$${POSTGRES_DB:-anve}?sslmode=disable}" go test -race -count=1 ./cmd/migrate ./internal/auth ./internal/circles ./internal/locations ./internal/locationaccess ./internal/places ./internal/sos ./internal/journeys ./internal/httpapi

server-history-plan: ## Inspect an isolated synthetic PostgreSQL history query plan
	cd apps/server && TEST_DATABASE_URL="$${TEST_DATABASE_URL:-postgres://$${POSTGRES_USER:-anve}:$${POSTGRES_PASSWORD:-anve}@localhost:$${POSTGRES_PORT:-5432}/$${POSTGRES_DB:-anve}?sslmode=disable}" go test -count=1 -run '^TestPGHistoryIndexPlan$$' -v ./internal/locationaccess

server-fix: ## Tidy Go modules and format Go sources
	cd apps/server && go mod tidy && go fmt ./...

server-lint: ## Lint the Go server
	cd apps/server && mise exec -- golangci-lint run ./...

server-check: ## Run the Go server format, vet, lint, test and build checks
	@echo "Check formatting..."
	cd apps/server && test -z "$$(gofmt -l .)"
	@echo "Check tidy..."
	cd apps/server && go mod tidy -diff
	@echo "Check vet..."
	cd apps/server && go vet ./...
	@echo "Check lint..."
	cd apps/server && mise exec -- golangci-lint run ./...
	@echo "Check vulnerabilities..."
	cd apps/server && mise exec -- govulncheck ./...
	@echo "Test..."
	cd apps/server && go test ./...
	@echo "Build..."
	$(MAKE) server-build

##@ Android app
# The Android SDK is not managed by mise: point ANDROID_HOME (or
# ANDROID_SDK_ROOT) at a local SDK installation that has the compile SDK
# pinned in apps/android/gradle/libs.versions.toml. When neither variable is
# set, the default Android Studio location is used.
ANDROID_DIR = apps/android
ANDROID_TEST_GRADLE_ARGS ?=
ANDROID_HOME ?= $(if $(ANDROID_SDK_ROOT),$(ANDROID_SDK_ROOT),$(HOME)/Android/Sdk)
export ANDROID_HOME
export ANDROID_SDK_ROOT := $(ANDROID_HOME)

android-sdk-check: ## Verify that the Android SDK is available
	@test -d "$(ANDROID_HOME)" || { \
		echo "Android SDK not found at $(ANDROID_HOME)." >&2; \
		echo "Set ANDROID_HOME to a local SDK installation, or run 'mise install' and set ANDROID_SDK_ROOT." >&2; \
		exit 1; \
	}

android-build: android-sdk-check ## Build the Android debug APK
	cd $(ANDROID_DIR) && ./gradlew :app:assembleDebug
	@echo "✅ APK: $(ANDROID_DIR)/app/build/outputs/apk/debug/app-debug.apk"

android-test: android-sdk-check ## Run the Android unit tests
	cd $(ANDROID_DIR) && ./gradlew :app:testDebugUnitTest

android-test-build: android-sdk-check ## Compile the Android instrumentation test APK
	cd $(ANDROID_DIR) && ./gradlew :app:assembleDebugAndroidTest

android-instrumentation: android-sdk-check ## Run instrumentation on a connected emulator or device
	cd $(ANDROID_DIR) && ./gradlew :app:connectedDebugAndroidTest $(ANDROID_TEST_GRADLE_ARGS)

android-emulator-validation: android-sdk-check ## Run emulator tests and retain captures even when tests fail
	bash scripts/run_android_validation.sh

android-validation-avd: ## Match the CI AVD to the approved Diagnostics comparison viewport
	python3 scripts/configure_android_validation_avd.py

android-ui-captures: android-sdk-check ## Collect synthetic UI captures from the connected validation target
	mkdir -p $(ANDROID_DIR)/app/build/outputs/ui-validation
	"$(ANDROID_HOME)/platform-tools/adb" pull /sdcard/Android/data/com.anve.app.debug/files/ui-validation/. $(ANDROID_DIR)/app/build/outputs/ui-validation/

tracking-validation: android-check android-test-build ## Check tracking diagnostics and compile emulator validation tests

android-lint: android-sdk-check ## Run the Android lint checks
	cd $(ANDROID_DIR) && ./gradlew :app:lintDebug

android-check: android-sdk-check ## Run the Android lint, unit test and debug build checks
	cd $(ANDROID_DIR) && ./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug

android-release: android-sdk-check ## Build the guarded signed production release APK
	cd $(ANDROID_DIR) && ./gradlew --no-daemon --no-configuration-cache --no-build-cache :app:assembleRelease

android-release-config-check: android-sdk-check ## Verify release inputs and resource wiring using an ephemeral key
	python3 scripts/android_release.py config-check

android-release-check: android-sdk-check ## Verify a signed validation AAB and reject tampered/unsigned fixtures
	python3 scripts/android_release.py check

android-signed-smoke: android-sdk-check ## Smoke-test the signed fixture APK on an explicitly disposable emulator
	python3 scripts/android_signed_smoke.py --artifact-dir "$(SIGNED_SMOKE_ARTIFACT_DIR)"

android-privacy-candidate: android-sdk-check ## Write a review candidate from merged manifest and resolved release SDKs
	python3 scripts/android_release.py review-candidate

android-distribution: android-sdk-check ## Build and verify a signed production AAB from explicit environment inputs
	python3 scripts/android_release.py distribution

android-clean: ## Remove the Android build outputs
	rm -rf $(ANDROID_DIR)/app/build $(ANDROID_DIR)/build $(ANDROID_DIR)/.gradle

##@ API contracts
contracts-lint: ## Validate the OpenAPI contract
	@command -v python3 >/dev/null 2>&1 || { echo "python3 is required to validate the OpenAPI contract." >&2; exit 1; }
	python3 -c "import sys, yaml; yaml.safe_load(open('packages/api-contracts/openapi.yaml')); print('OpenAPI contract parses as YAML.')"

release-tooling-check: ## Check the exact-commit distribution CI gate
	python3 -m unittest discover -s scripts -p 'test_release_ci.py'
	python3 -m unittest discover -s scripts -p 'test_android_privacy.py'
	python3 -m unittest discover -s scripts -p 'test_android_signed_smoke.py'

##@ Dependency automation
deps-lint: ## Validate the Dependabot configuration against its schema
	@command -v python3 >/dev/null 2>&1 || { echo "python3 is required to validate the Dependabot configuration." >&2; exit 1; }
	python3 scripts/check_dependabot.py

##@ Quality gate
quality-check: toolchain-check server-check server-observability-smoke android-check android-release-config-check release-tooling-check contracts-lint deps-lint compose-lint ## Run the full CI quality gate

##@ Cleanup
clean: android-clean ## Remove all generated build outputs
	rm -rf apps/server/bin

# ─── Load .env ────────────────────────────────────────────────────────────────
ifneq (,$(wildcard .env))
    include .env
    export
endif
