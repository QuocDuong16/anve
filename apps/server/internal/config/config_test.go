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
	assert.Equal(t, 15*time.Second, cfg.ShutdownTimeout)
}

func TestLoadOverrides(t *testing.T) {
	t.Setenv("PORT", "9090")
	t.Setenv("ENVIRONMENT", "staging")
	t.Setenv("SHUTDOWN_TIMEOUT", "30s")

	cfg, err := config.Load()
	require.NoError(t, err)

	assert.Equal(t, "9090", cfg.Port)
	assert.Equal(t, "staging", cfg.Environment)
	assert.Equal(t, 30*time.Second, cfg.ShutdownTimeout)
}

func TestDevLoginRefusedOutsideDevelopment(t *testing.T) {
	// Fail closed: production, staging, unknown and mis-cased names all
	// refuse to start with the fixture enabled. Only the exact documented
	// value "development" may mount it.
	for _, env := range []string{"production", "staging", "prod", "Development", "local"} {
		t.Run("env="+env, func(t *testing.T) {
			t.Setenv("ENVIRONMENT", env)
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

func TestDevLoginAllowedInDevelopment(t *testing.T) {
	t.Setenv("ENVIRONMENT", "development")
	t.Setenv("AUTH_DEV_LOGIN_ENABLED", "true")

	cfg, err := config.Load()
	require.NoError(t, err)
	assert.True(t, cfg.AuthDevLoginEnabled)
}
