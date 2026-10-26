package config_test

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/config"
)

func TestLoadDefaults(t *testing.T) {
	cfg, err := config.Load()
	require.NoError(t, err)

	assert.Equal(t, "8080", cfg.Port)
	assert.Equal(t, "development", cfg.Environment)
	assert.Equal(t, "127.0.0.1:8080", cfg.ListenAddr,
		"a development process must not listen on every interface by default")
	assert.Equal(t, 15*time.Second, cfg.ShutdownTimeout)
	assert.Empty(t, cfg.AuthDevLoginNetworks, "the fixture is off by default")
	assert.Equal(t, 30, cfg.LocationRetentionDays, "location retention is short by default")
}

func TestLocationRetentionDaysIsBounded(t *testing.T) {
	for _, value := range []string{"0", "-1", "366"} {
		t.Run(value, func(t *testing.T) {
			t.Setenv("LOCATION_RETENTION_DAYS", value)
			_, err := config.Load()
			require.Error(t, err)
			assert.Contains(t, err.Error(), "LOCATION_RETENTION_DAYS")
		})
	}
	t.Run("not-a-number", func(t *testing.T) {
		t.Setenv("LOCATION_RETENTION_DAYS", "not-a-number")
		_, err := config.Load()
		require.Error(t, err)
	})
}

func TestLoadOverrides(t *testing.T) {
	t.Setenv("PORT", "9090")
	t.Setenv("ENVIRONMENT", "staging")
	t.Setenv("LISTEN_ADDR", "0.0.0.0:9090")
	t.Setenv("SHUTDOWN_TIMEOUT", "30s")

	cfg, err := config.Load()
	require.NoError(t, err)

	assert.Equal(t, "9090", cfg.Port)
	assert.Equal(t, "staging", cfg.Environment)
	assert.Equal(t, "0.0.0.0:9090", cfg.ListenAddr)
	assert.Equal(t, 30*time.Second, cfg.ShutdownTimeout)
}

func TestNonDevelopmentRequiresExplicitListener(t *testing.T) {
	for _, env := range []string{"staging", "production", "prod"} {
		t.Run(env, func(t *testing.T) {
			t.Setenv("ENVIRONMENT", env)
			t.Setenv("LISTEN_ADDR", "")

			_, err := config.Load()
			require.Error(t, err)
			assert.Contains(t, err.Error(), "LISTEN_ADDR")
		})
	}
}

func TestInvalidListenerIsRejected(t *testing.T) {
	cases := map[string]string{
		"missing port":  "127.0.0.1",
		"service name":  "127.0.0.1:http",
		"port too high": "127.0.0.1:70000",
		"negative port": "127.0.0.1:-1",
	}
	for name, addr := range cases {
		t.Run(name, func(t *testing.T) {
			t.Setenv("LISTEN_ADDR", addr)

			_, err := config.Load()
			require.Error(t, err)
			assert.Contains(t, err.Error(), "LISTEN_ADDR")
		})
	}
}

func TestDevLoginRefusedOutsideDevelopment(t *testing.T) {
	// Fail closed: production, staging, unknown and mis-cased names all
	// refuse to start with the fixture enabled. Only the exact documented
	// value "development" may mount it.
	for _, env := range []string{"production", "staging", "prod", "Development", "local"} {
		t.Run("env="+env, func(t *testing.T) {
			t.Setenv("ENVIRONMENT", env)
			t.Setenv("LISTEN_ADDR", "127.0.0.1:8080")
			t.Setenv("AUTH_DEV_LOGIN_ENABLED", "true")

			_, err := config.Load()
			require.Error(t, err, "the dev login must not start outside development")
			assert.Contains(t, err.Error(), "AUTH_DEV_LOGIN_ENABLED")
		})
	}
}

// An unset ENVIRONMENT keeps the documented development default (the binary
// starts with no configuration at all for local work). Production and
// staging must set the variable explicitly, at which point the allowlist
// above applies.
func TestUnsetEnvironmentDefaultsToDevelopment(t *testing.T) {
	cfg, err := config.Load()
	require.NoError(t, err)
	assert.Equal(t, config.EnvironmentDevelopment, cfg.Environment)
}

func TestDevLoginAllowedOnLoopbackListener(t *testing.T) {
	for _, addr := range []string{"127.0.0.1:8080", "localhost:8080", "[::1]:8080"} {
		t.Run(addr, func(t *testing.T) {
			t.Setenv("ENVIRONMENT", "development")
			t.Setenv("LISTEN_ADDR", addr)
			t.Setenv("AUTH_DEV_LOGIN_ENABLED", "true")

			cfg, err := config.Load()
			require.NoError(t, err)
			assert.True(t, cfg.AuthDevLoginEnabled)
			require.NotEmpty(t, cfg.AuthDevLoginNetworks)
			for _, prefix := range cfg.AuthDevLoginNetworks {
				assert.True(t, prefix.Addr().IsLoopback(),
					"loopback listeners only accept loopback sources: %s", prefix)
			}
		})
	}
}

func TestDevLoginOnWildcardListenerNeedsExplicitNetworkOptIn(t *testing.T) {
	t.Setenv("ENVIRONMENT", "development")
	t.Setenv("LISTEN_ADDR", "0.0.0.0:8080")
	t.Setenv("AUTH_DEV_LOGIN_ENABLED", "true")

	_, err := config.Load()
	require.Error(t, err)
	assert.Contains(t, err.Error(), "AUTH_DEV_LOGIN_ALLOW_NETWORK")
}

func TestDevLoginNetworkOptInRequiresAllowlist(t *testing.T) {
	t.Setenv("ENVIRONMENT", "development")
	t.Setenv("LISTEN_ADDR", "0.0.0.0:8080")
	t.Setenv("AUTH_DEV_LOGIN_ENABLED", "true")
	t.Setenv("AUTH_DEV_LOGIN_ALLOW_NETWORK", "true")
	t.Setenv("AUTH_DEV_LOGIN_ALLOWED_CIDRS", "")

	_, err := config.Load()
	require.Error(t, err)
	assert.Contains(t, err.Error(), "AUTH_DEV_LOGIN_ALLOWED_CIDRS")
}

func TestDevLoginRejectsCatchAllAllowlist(t *testing.T) {
	for _, cidrs := range []string{"0.0.0.0/0", "::/0", "127.0.0.0/8,0.0.0.0/0"} {
		t.Run(cidrs, func(t *testing.T) {
			t.Setenv("ENVIRONMENT", "development")
			t.Setenv("LISTEN_ADDR", "0.0.0.0:8080")
			t.Setenv("AUTH_DEV_LOGIN_ENABLED", "true")
			t.Setenv("AUTH_DEV_LOGIN_ALLOW_NETWORK", "true")
			t.Setenv("AUTH_DEV_LOGIN_ALLOWED_CIDRS", cidrs)

			_, err := config.Load()
			require.Error(t, err)
			assert.Contains(t, err.Error(), "catch-all")
		})
	}
}

func TestDevLoginRejectsInvalidAllowlist(t *testing.T) {
	t.Setenv("ENVIRONMENT", "development")
	t.Setenv("LISTEN_ADDR", "0.0.0.0:8080")
	t.Setenv("AUTH_DEV_LOGIN_ENABLED", "true")
	t.Setenv("AUTH_DEV_LOGIN_ALLOW_NETWORK", "true")
	t.Setenv("AUTH_DEV_LOGIN_ALLOWED_CIDRS", "not-a-cidr")

	_, err := config.Load()
	require.Error(t, err)
	assert.Contains(t, err.Error(), "invalid CIDR")
}

func TestDevLoginWithExplicitNetworkAllowlist(t *testing.T) {
	t.Setenv("ENVIRONMENT", "development")
	t.Setenv("LISTEN_ADDR", "0.0.0.0:8080")
	t.Setenv("AUTH_DEV_LOGIN_ENABLED", "true")
	t.Setenv("AUTH_DEV_LOGIN_ALLOW_NETWORK", "true")
	t.Setenv("AUTH_DEV_LOGIN_ALLOWED_CIDRS", "127.0.0.0/8, 172.28.0.0/24")

	cfg, err := config.Load()
	require.NoError(t, err)
	require.Len(t, cfg.AuthDevLoginNetworks, 2)
	assert.Equal(t, "127.0.0.0/8", cfg.AuthDevLoginNetworks[0].String())
	assert.Equal(t, "172.28.0.0/24", cfg.AuthDevLoginNetworks[1].String())
}

// A non-loopback listener without the fixture is a normal server: the
// network boundary protects the fixture, not the Google-authenticated API.
func TestWildcardListenerWithoutDevLoginIsAllowed(t *testing.T) {
	t.Setenv("ENVIRONMENT", "development")
	t.Setenv("LISTEN_ADDR", "0.0.0.0:8080")

	cfg, err := config.Load()
	require.NoError(t, err)
	assert.False(t, cfg.AuthDevLoginEnabled)
	assert.Empty(t, cfg.AuthDevLoginNetworks)
}

func TestPortIsTheDefaultListenerOnly(t *testing.T) {
	t.Setenv("PORT", "9091")
	cfg, err := config.Load()
	require.NoError(t, err)
	assert.Equal(t, "127.0.0.1:9091", cfg.ListenAddr)
}
