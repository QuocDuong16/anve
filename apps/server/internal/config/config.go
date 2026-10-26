// Package config loads the server configuration from the environment.
//
// Every value has a development-friendly default so the binary starts with no
// configuration at all; production deployments override them through
// environment variables (see .env.example).
package config

import (
	"fmt"
	"time"

	"github.com/caarlos0/env/v11"
)

// Config holds every runtime knob of the AnVề API server.
type Config struct {
	// Port is the TCP port the HTTP server listens on.
	Port string `env:"PORT" envDefault:"8080"`
	// Environment names the deployment stage (development, staging, production).
	Environment string `env:"ENVIRONMENT" envDefault:"development"`
	// DatabaseURL points at the PostgreSQL/PostGIS instance. The business
	// endpoints (everything under /api/v1 except version) need it; when it is
	// empty those routes answer 503 until the operator configures a database.
	DatabaseURL string `env:"DATABASE_URL"`
	// ShutdownTimeout bounds how long in-flight requests may take to finish
	// when the server receives SIGINT or SIGTERM.
	ShutdownTimeout time.Duration `env:"SHUTDOWN_TIMEOUT" envDefault:"15s"`
	// ReadTimeout and WriteTimeout protect the server from slow clients.
	ReadTimeout  time.Duration `env:"READ_TIMEOUT" envDefault:"10s"`
	WriteTimeout time.Duration `env:"WRITE_TIMEOUT" envDefault:"20s"`
	// Version is the build version reported by /api/v1/version. It is
	// injected at build time with -ldflags.
	Version string `env:"VERSION" envDefault:"dev"`
	// AuthGoogleClientID is the OAuth client ID that Google ID tokens must be
	// issued for. Empty disables Google sign-in (the endpoint fails closed).
	AuthGoogleClientID string `env:"AUTH_GOOGLE_CLIENT_ID"`
	// AuthDevLoginEnabled mounts the development-only sign-in endpoint. It is
	// refused in production by Load — the check exists so a leaked
	// configuration cannot smuggle the endpoint into a production process.
	AuthDevLoginEnabled bool `env:"AUTH_DEV_LOGIN_ENABLED" envDefault:"false"`
}

// Load reads the configuration from the process environment.
func Load() (Config, error) {
	var cfg Config
	if err := env.Parse(&cfg); err != nil {
		return Config{}, fmt.Errorf("parse config: %w", err)
	}
	if err := cfg.validate(); err != nil {
		return Config{}, fmt.Errorf("validate config: %w", err)
	}
	return cfg, nil
}

// EnvironmentDevelopment is the only environment in which the development
// login may be mounted. It is an allowlist of one on purpose: "not
// production" would also admit staging, a typo or an unknown value, and a
// fixture that mints sessions for arbitrary emails must fail closed.
const EnvironmentDevelopment = "development"

// validate refuses configurations that are unsafe by construction.
func (c Config) validate() error {
	if c.AuthDevLoginEnabled && c.Environment != EnvironmentDevelopment {
		return fmt.Errorf(
			"AUTH_DEV_LOGIN_ENABLED must be false unless ENVIRONMENT is %q (got %q)",
			EnvironmentDevelopment, c.Environment)
	}
	return nil
}
