package auth

// SetTestAfterUserLock installs a barrier that runs after the deletion
// transaction acquired the user row lock. Test-only: this file is compiled
// exclusively into test binaries.
func (s *PGStore) SetTestAfterUserLock(fn func()) {
	s.testAfterUserLock = fn
}
