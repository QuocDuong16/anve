package locations

// SetTestAfterConsentLock installs a test-only barrier that runs after the
// ingestion transaction has share-locked the account row and read the
// consent flag. Concurrency tests use it to hold the batch at a precise
// boundary; it is nil in production.
func (s *PGStore) SetTestAfterConsentLock(fn func()) { s.testAfterConsentLock = fn }

// SetTestAfterDeviceLock installs a test-only barrier after the ingestion
// transaction has share-locked the session and device rows.
func (s *PGStore) SetTestAfterDeviceLock(fn func()) { s.testAfterDeviceLock = fn }
