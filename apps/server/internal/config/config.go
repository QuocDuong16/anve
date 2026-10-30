// Package config loads the server configuration from the environment.
//
// Every value has a development-friendly default so the binary starts with no
// configuration at all; production deployments override them through
// environment variables (see .env.example).
package config

import (
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"time"

	"github.com/caarlos0/env/v11"
)

// Config holds every runtime knob of the AnVề API server.
type Config struct {
	// Port is the TCP port used when ListenAddr is not set. It is retained
	// for the local workflow (PORT=8081 ./bin/api).
	Port string `env:"PORT" envDefault:"8080"`
	// ListenAddr is the exact host:port the HTTP server binds. In
	// development it defaults to 127.0.0.1:<Port>, so a dev process is not
	// reachable from the network by accident. Every other environment must
	// state its exposure explicitly.
	ListenAddr string `env:"LISTEN_ADDR"`
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
	// AuthDevLoginAllowNetwork acknowledges that a development server with
	// the fixture enabled listens on a non-loopback address (a container,
	// typically). It is only honoured in development and only together with
	// an explicit AuthDevLoginAllowedCIDRs allowlist.
	AuthDevLoginAllowNetwork bool `env:"AUTH_DEV_LOGIN_ALLOW_NETWORK" envDefault:"false"`
	// AuthDevLoginAllowedCIDRs is the comma-separated network allowlist for
	// the development login endpoint. Requests from any other source address
	// are refused before the handler runs, so the fixture is never reachable
	// from an unexpected network even if the listener is exposed.
	AuthDevLoginAllowedCIDRs string `env:"AUTH_DEV_LOGIN_ALLOWED_CIDRS"`
	// AuthDevLoginNetworks is the parsed form of AuthDevLoginAllowedCIDRs,
	// filled by Load. An empty list means "deny every source" (fail closed).
	AuthDevLoginNetworks []netip.Prefix `env:"-"`
	// LocationRetentionDays is how long consented location points are kept.
	// It is also the backfill window for delayed uploads: a point older than
	// this is refused rather than stored and immediately purged. The
	// product default is deliberately short while M5 history is unimplemented.
	LocationRetentionDays int `env:"LOCATION_RETENTION_DAYS" envDefault:"30"`
	// FCMProjectID enables the optional Firebase HTTP v1 notification gateway.
	// Credentials are supplied separately through Application Default Credentials.
	FCMProjectID string `env:"FCM_PROJECT_ID"`
}

// Load reads the configuration from the process environment.
func Load() (Config, error) {
	var cfg Config
	if err := env.Parse(&cfg); err != nil {
		return Config{}, fmt.Errorf("parse config: %w", err)
	}
	if err := cfg.resolve(); err != nil {
		return Config{}, fmt.Errorf("validate config: %w", err)
	}
	return cfg, nil
}

// EnvironmentDevelopment is the only environment in which the development
// login may be mounted. It is an allowlist of one on purpose: "not
// production" would also admit staging, a typo or an unknown value, and a
// fixture that mints sessions for arbitrary emails must fail closed.
const EnvironmentDevelopment = "development"

// loopbackNetworks is the source allowlist used when the development login
// is mounted on a loopback listener.
var loopbackNetworks = []netip.Prefix{
	netip.MustParsePrefix("127.0.0.0/8"),
	netip.MustParsePrefix("::1/128"),
}

// resolve applies the environment-dependent listener default and parses the
// development-login allowlist.
func (c *Config) resolve() error {
	if c.ListenAddr == "" {
		if c.Environment == EnvironmentDevelopment {
			c.ListenAddr = net.JoinHostPort("127.0.0.1", c.Port)
		} else {
			return fmt.Errorf("LISTEN_ADDR must be set explicitly when ENVIRONMENT is %q", c.Environment)
		}
	}
	host, port, err := net.SplitHostPort(c.ListenAddr)
	if err != nil {
		return fmt.Errorf("LISTEN_ADDR %q: %w", c.ListenAddr, err)
	}
	if err := validatePort(port); err != nil {
		return fmt.Errorf("LISTEN_ADDR %q: %w", c.ListenAddr, err)
	}
	if c.LocationRetentionDays < 1 || c.LocationRetentionDays > 365 {
		return fmt.Errorf("LOCATION_RETENTION_DAYS must be between 1 and 365 (got %d)",
			c.LocationRetentionDays)
	}

	if !c.AuthDevLoginEnabled {
		return nil
	}
	if c.Environment != EnvironmentDevelopment {
		return fmt.Errorf(
			"AUTH_DEV_LOGIN_ENABLED must be false unless ENVIRONMENT is %q (got %q)",
			EnvironmentDevelopment, c.Environment)
	}
	if isLoopbackHost(host) {
		// A loopback listener is the safe default. The request-source guard
		// is still installed: if a reverse proxy forwards to this listener,
		// the proxy's own address is what the handler sees, and an operator
		// who needs that setup must state the proxy network explicitly.
		c.AuthDevLoginNetworks = loopbackNetworks
		return nil
	}
	if !c.AuthDevLoginAllowNetwork {
		return fmt.Errorf(
			"AUTH_DEV_LOGIN_ENABLED on non-loopback LISTEN_ADDR %q requires "+
				"AUTH_DEV_LOGIN_ALLOW_NETWORK=true and an explicit "+
				"AUTH_DEV_LOGIN_ALLOWED_CIDRS allowlist (use a tunnel or ADB reverse instead)",
			c.ListenAddr)
	}
	networks, err := ParseCIDRs(c.AuthDevLoginAllowedCIDRs)
	if err != nil {
		return fmt.Errorf("AUTH_DEV_LOGIN_ALLOWED_CIDRS: %w", err)
	}
	if len(networks) == 0 {
		return errors.New(
			"AUTH_DEV_LOGIN_ALLOW_NETWORK=true requires AUTH_DEV_LOGIN_ALLOWED_CIDRS " +
				"(comma-separated CIDRs; catch-all prefixes are refused)")
	}
	for _, prefix := range networks {
		if prefix.Bits() == 0 {
			return fmt.Errorf(
				"AUTH_DEV_LOGIN_ALLOWED_CIDRS must not contain the catch-all prefix %s: "+
					"a development login reachable from every address is a public credential fixture",
				prefix)
		}
	}
	c.AuthDevLoginNetworks = networks
	return nil
}

// ParseCIDRs parses a comma-separated CIDR list. Blank entries are ignored
// so a trailing comma is harmless.
func ParseCIDRs(raw string) ([]netip.Prefix, error) {
	var out []netip.Prefix
	for _, entry := range strings.Split(raw, ",") {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		prefix, err := netip.ParsePrefix(entry)
		if err != nil {
			return nil, fmt.Errorf("invalid CIDR %q: %w", entry, err)
		}
		out = append(out, prefix.Masked())
	}
	return out, nil
}

// validatePort rejects service names and out-of-range values: the listener
// is a network boundary and should fail at startup, not in the kernel.
func validatePort(port string) error {
	n, err := strconv.Atoi(port)
	if err != nil {
		return fmt.Errorf("port %q is not numeric", port)
	}
	if n < 0 || n > 65535 {
		return fmt.Errorf("port %d is outside 0-65535", n)
	}
	return nil
}

// isLoopbackHost reports whether the bind host is provably loopback. Only
// IP literals and "localhost" qualify; any other hostname may resolve
// anywhere, so it has to go through the explicit network configuration.
func isLoopbackHost(host string) bool {
	if strings.EqualFold(host, "localhost") {
		return true
	}
	ip, err := netip.ParseAddr(host)
	return err == nil && ip.IsLoopback()
}
