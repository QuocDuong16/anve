# Makefile — AnVề
#
# Every target is documented with a `##` comment and grouped under a `##@`
# heading, so `make help` lists the whole workflow.

.DEFAULT_GOAL := help

.PHONY: help clean \
	toolchain-install toolchain-check \
	docker-up docker-down docker-logs docker-clean docker-config compose-lint \
	up down logs \
	migrate-up migrate-down \
	server-run server-build server-test server-test-race server-test-integration server-history-plan server-fix server-lint server-check \
	android-sdk-check android-build android-test android-lint android-check android-release android-test-build android-instrumentation android-clean \
	contracts-lint deps-lint \
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

docker-up: ## Build and start the local stack (PostgreSQL/PostGIS + API)
	$(DOCKER_COMPOSE) build && $(DOCKER_COMPOSE) up -d --force-recreate
	@echo ""
	@echo "✅ Stack ready:"
	@echo "   API:          http://localhost:$${API_PORT:-8080}/healthz"
	@echo "   PostgreSQL:   localhost:$${POSTGRES_PORT:-5432}"

docker-down: ## Stop the local stack
	$(DOCKER_COMPOSE) down

docker-logs: ## Follow the local stack logs
	$(DOCKER_COMPOSE) logs -f

docker-clean: ## Stop the local stack and delete its volumes
	$(DOCKER_COMPOSE) down -v --remove-orphans

docker-config: ## Validate the effective Docker Compose configuration
	$(DOCKER_COMPOSE) config

compose-lint: ## Verify that no Compose port is published beyond loopback
	@command -v python3 >/dev/null 2>&1 || { echo "python3 is required to validate the Compose exposure." >&2; exit 1; }
	python3 scripts/check_compose_ports.py

# Short aliases for the same targets.
up: docker-up
down: docker-down
logs: docker-logs

##@ Database migrations
migrate-up: ## Apply the pending SQL migrations
	$(DOCKER_COMPOSE) run --rm migrate up

migrate-down: ## Roll back the most recent applied migration
	$(DOCKER_COMPOSE) run --rm migrate down

##@ Go server
server-run: ## Run the API server locally (no hot reload; restart after changes)
	cd apps/server && go run ./cmd/api

server-build: ## Build the API and migrate binaries
	cd apps/server && go build -o bin/api ./cmd/api
	cd apps/server && go build -o bin/migrate ./cmd/migrate

server-test: ## Run the Go server tests
	cd apps/server && go test ./...

server-test-race: ## Run the Go server tests with the race detector
	cd apps/server && go test -race -count=1 ./...

server-test-integration: ## Run the DB integration tests against the running local stack (make docker-up first)
	cd apps/server && TEST_DATABASE_URL="$${TEST_DATABASE_URL:-postgres://$${POSTGRES_USER:-anve}:$${POSTGRES_PASSWORD:-anve}@localhost:$${POSTGRES_PORT:-5432}/$${POSTGRES_DB:-anve}?sslmode=disable}" go test -race -count=1 ./cmd/migrate ./internal/auth ./internal/circles ./internal/locations ./internal/locationaccess ./internal/places ./internal/httpapi

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
	cd $(ANDROID_DIR) && ./gradlew :app:connectedDebugAndroidTest

android-lint: android-sdk-check ## Run the Android lint checks
	cd $(ANDROID_DIR) && ./gradlew :app:lintDebug

android-check: android-sdk-check ## Run the Android lint, unit test and debug build checks
	cd $(ANDROID_DIR) && ./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug

android-release: android-sdk-check ## Compile the minified release build (unsigned without a signing config)
	cd $(ANDROID_DIR) && ./gradlew :app:assembleRelease

android-clean: ## Remove the Android build outputs
	rm -rf $(ANDROID_DIR)/app/build $(ANDROID_DIR)/build $(ANDROID_DIR)/.gradle

##@ API contracts
contracts-lint: ## Validate the OpenAPI contract
	@command -v python3 >/dev/null 2>&1 || { echo "python3 is required to validate the OpenAPI contract." >&2; exit 1; }
	python3 -c "import sys, yaml; yaml.safe_load(open('packages/api-contracts/openapi.yaml')); print('OpenAPI contract parses as YAML.')"

##@ Dependency automation
deps-lint: ## Validate the Dependabot configuration against its schema
	@command -v python3 >/dev/null 2>&1 || { echo "python3 is required to validate the Dependabot configuration." >&2; exit 1; }
	python3 scripts/check_dependabot.py

##@ Quality gate
quality-check: toolchain-check server-check android-check contracts-lint deps-lint compose-lint ## Run the full CI quality gate

##@ Cleanup
clean: android-clean ## Remove all generated build outputs
	rm -rf apps/server/bin

# ─── Load .env ────────────────────────────────────────────────────────────────
ifneq (,$(wildcard .env))
    include .env
    export
endif
