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
	// DatabaseURL points at the PostgreSQL/PostGIS instance. It is optional
	// in M0: the server runs without a database until the first feature that
	// needs one lands.
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
}

// Load reads the configuration from the process environment.
func Load() (Config, error) {
	var cfg Config
	if err := env.Parse(&cfg); err != nil {
		return Config{}, fmt.Errorf("parse config: %w", err)
	}
	return cfg, nil
}
