package locations

import (
	"context"
	"log/slog"
	"time"
)

// RetentionBatchSize bounds one cleanup transaction and its ingestion lock.
const RetentionBatchSize = 5000

const maxRetentionBatchesPerPass = 20

// RunRetention purges points older than retention immediately and then on
// every interval until ctx is cancelled. It is started as a goroutine by the
// API process; the caller owns shutdown.
//
// Retention is a product obligation, not an optimisation: location data is
// sensitive and must not accumulate indefinitely. The purge is deliberately based on capture time and logs
// only row counts — never coordinates.
func RunRetention(
	ctx context.Context,
	store Store,
	retention time.Duration,
	interval time.Duration,
	logger *slog.Logger,
) {
	if interval <= 0 {
		interval = time.Hour
	}
	purge := func() {
		cutoff := time.Now().UTC().Add(-retention)
		var total int64
		for batch := 0; batch < maxRetentionBatchesPerPass; batch++ {
			deleted, err := store.PurgeOlderThan(ctx, cutoff)
			if err != nil {
				if ctx.Err() == nil {
					logger.Error("location retention pass failed", "error", err)
				}
				return
			}
			total += deleted
			if deleted < RetentionBatchSize {
				break
			}
		}
		if total > 0 {
			logger.Info("location retention pass removed expired points", "deleted", total, "retention_days", int(retention.Hours()/24))
		}
	}

	purge()
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			purge()
		}
	}
}
