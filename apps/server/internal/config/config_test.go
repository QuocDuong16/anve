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

func TestDevLoginRefusedInProduction(t *testing.T) {
	t.Setenv("ENVIRONMENT", "production")
	t.Setenv("AUTH_DEV_LOGIN_ENABLED", "true")

	_, err := config.Load()
	require.Error(t, err, "the dev login must never start inside a production process")
	assert.Contains(t, err.Error(), "AUTH_DEV_LOGIN_ENABLED")
}

func TestDevLoginAllowedOutsideProduction(t *testing.T) {
	t.Setenv("ENVIRONMENT", "development")
	t.Setenv("AUTH_DEV_LOGIN_ENABLED", "true")

	cfg, err := config.Load()
	require.NoError(t, err)
	assert.True(t, cfg.AuthDevLoginEnabled)
}
