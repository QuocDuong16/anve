// Command api starts the AnVề backend HTTP server.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/config"
	"github.com/QuocDuong16/anve/server/internal/httpapi"
	"github.com/QuocDuong16/anve/server/internal/locationaccess"
	"github.com/QuocDuong16/anve/server/internal/locations"
	"github.com/QuocDuong16/anve/server/internal/places"
)

// version is the build version, injected at link time with
// -ldflags "-X main.version=<version>". It falls back to the VERSION
// environment variable and finally to "dev".
var version = "dev"

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	if err := run(); err != nil {
		logger.Error("server exited with an error", "error", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}

	if version != "dev" {
		cfg.Version = version
	}

	logger := slog.Default().With(
		"service", "api",
		"environment", cfg.Environment,
		"version", cfg.Version,
	)

	var authService *auth.Service
	var circlesService *circles.Service
	var locationsService *locations.Service
	var locationAccessService *locationaccess.Service
	var locationStore *locations.PGStore
	var placeStore *places.Store
	var pool *pgxpool.Pool
	if cfg.DatabaseURL != "" {
		// The pool outlives the HTTP server; the context only bounds the
		// connect attempt.
		connectCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		pool, err = pgxpool.New(connectCtx, cfg.DatabaseURL)
		cancel()
		if err != nil {
			return fmt.Errorf("connect to database: %w", err)
		}
		defer pool.Close()

		pingCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		err = pool.Ping(pingCtx)
		cancel()
		if err != nil {
			return fmt.Errorf("ping database: %w", err)
		}

		var verifier auth.IdentityVerifier
		if cfg.AuthGoogleClientID != "" {
			verifier, err = auth.NewGoogleVerifier(cfg.AuthGoogleClientID)
			if err != nil {
				return fmt.Errorf("set up google verifier: %w", err)
			}
			logger.Info("google sign-in enabled")
		} else {
			logger.Warn("google sign-in disabled: AUTH_GOOGLE_CLIENT_ID is empty")
		}
		if cfg.AuthDevLoginEnabled {
			logger.Warn("development login is mounted",
				"listen_addr", cfg.ListenAddr,
				"allowed_source_networks", len(cfg.AuthDevLoginNetworks),
				"note", "this fixture mints sessions for arbitrary emails; never point it at production data")
		}

		store := auth.NewPGStore(pool)
		authService = auth.NewService(store, verifier, cfg.AuthDevLoginEnabled)
		circlesService = circles.NewService(circles.NewPGStore(pool))
		locationStore = locations.NewPGStore(pool)
		placeStore = places.NewStore(pool, time.Duration(cfg.LocationRetentionDays)*24*time.Hour)
		locationsService = locations.NewServiceWithRetention(locationStore, time.Duration(cfg.LocationRetentionDays)*24*time.Hour)
		locationAccessService = locationaccess.NewService(
			locationaccess.NewPGStore(pool),
			time.Duration(cfg.LocationRetentionDays)*24*time.Hour,
		)
	} else {
		logger.Warn("DATABASE_URL is empty: business endpoints answer 503")
	}

	server := &http.Server{
		// ListenAddr is resolved by config: loopback in development unless
		// explicitly overridden, mandatory outside development.
		Addr: cfg.ListenAddr,
		Handler: httpapi.NewRouter(httpapi.Deps{
			Version:                 cfg.Version,
			Auth:                    authService,
			Circles:                 circlesService,
			Locations:               locationsService,
			LocationAccess:          locationAccessService,
			Places:                  placeStore,
			DB:                      pool,
			DevLoginAllowedNetworks: cfg.AuthDevLoginNetworks,
		}),
		ReadTimeout:  cfg.ReadTimeout,
		WriteTimeout: cfg.WriteTimeout,
	}

	// A signal cancels the root context, which unwinds run() and triggers the
	// graceful shutdown below.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Retention is a product obligation, not best-effort cleanup: it runs
	// on the same lifetime as the server and stops with the shutdown
	// context. The first pass happens immediately, so a restart also
	// catches up on rows that aged out while the process was down.
	if locationStore != nil {
		go locations.RunRetention(
			ctx,
			locationStore,
			time.Duration(cfg.LocationRetentionDays)*24*time.Hour,
			time.Hour,
			logger.With("component", "location-retention"),
		)
	}
	if placeStore != nil {
		go places.RunWorker(ctx, placeStore, logger.With("component", "place-evaluation"))
	}

	serverErr := make(chan error, 1)
	go func() {
		logger.Info("http server listening", "addr", server.Addr)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serverErr <- err
			return
		}
		serverErr <- nil
	}()

	select {
	case err := <-serverErr:
		return err
	case <-ctx.Done():
		logger.Info("shutdown signal received, draining connections")
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer cancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		return err
	}
	logger.Info("http server stopped")
	return nil
}
