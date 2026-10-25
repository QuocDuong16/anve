package httpapi

import (
	"context"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/go-chi/chi/v5/middleware"

	"github.com/QuocDuong16/anve/server/internal/auth"
)

// authContextKey namespaces the authenticated request data in the context.
type authContextKey struct{}

// AuthContext is what RequireAuth stores after a successful token check.
type AuthContext struct {
	Session auth.Session
	User    auth.User
}

// WithAuthContext returns a context carrying the authenticated session.
func WithAuthContext(ctx context.Context, ac AuthContext) context.Context {
	return context.WithValue(ctx, authContextKey{}, ac)
}

// AuthFromContext extracts the authenticated session put in place by
// RequireAuth.
func AuthFromContext(ctx context.Context) (AuthContext, bool) {
	ac, ok := ctx.Value(authContextKey{}).(AuthContext)
	return ac, ok
}

// RequireAuth rejects requests without a valid session token. It runs on
// every authenticated request, so revocation and expiry apply immediately
// (ADR 0002).
func RequireAuth(svc *auth.Service) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token := bearerToken(r)
			if token == "" {
				writeError(w, r, auth.ErrUnauthenticated)
				return
			}
			session, user, err := svc.Authenticate(r.Context(), token)
			if err != nil {
				writeError(w, r, err)
				return
			}
			next.ServeHTTP(w, r.WithContext(WithAuthContext(r.Context(), AuthContext{Session: session, User: user})))
		})
	}
}

// bearerToken extracts the Bearer credentials from the Authorization
// header (RFC 6750, case-insensitive scheme).
func bearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	scheme, token, found := strings.Cut(h, " ")
	if !found || !strings.EqualFold(scheme, "Bearer") {
		return ""
	}
	return strings.TrimSpace(token)
}

// clientIP extracts the host of RemoteAddr. The router deliberately does
// not honor X-Forwarded-For (spoofable); a trusted-proxy allowlist is a
// deployment concern.
func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// rateLimiter is an in-memory token bucket keyed by client IP. It exists to
// blunt credential stuffing and sign-in abuse, not as a precise quota; a
// restart resets it, which is acceptable at this scale.
type rateLimiter struct {
	mu      sync.Mutex
	buckets map[string]*bucket
	rate    float64 // tokens per second
	burst   float64
	now     func() time.Time
}

type bucket struct {
	tokens  float64
	lastRef time.Time
}

func newRateLimiter(perMinute float64, burst float64) *rateLimiter {
	return &rateLimiter{
		buckets: make(map[string]*bucket),
		rate:    perMinute / 60.0,
		burst:   burst,
		now:     time.Now,
	}
}

// Allow consumes one token for key, refilling lazily. Stale buckets are
// evicted on the same pass so the map stays bounded.
func (l *rateLimiter) Allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()

	now := l.now()
	const staleAfter = 15 * time.Minute
	for k, b := range l.buckets {
		if now.Sub(b.lastRef) > staleAfter {
			delete(l.buckets, k)
		}
	}

	b, ok := l.buckets[key]
	if !ok {
		b = &bucket{tokens: l.burst, lastRef: now}
		l.buckets[key] = b
	} else {
		b.tokens = min(l.burst, b.tokens+now.Sub(b.lastRef).Seconds()*l.rate)
		b.lastRef = now
	}
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

// RateLimit rejects requests from one IP once the bucket is empty.
func RateLimit(l *rateLimiter) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if !l.Allow(clientIP(r)) {
				writeJSON(w, http.StatusTooManyRequests, errorBody{
					Code:      errCodeRateLimited,
					Message:   "Too many requests. Try again later.",
					RequestID: middleware.GetReqID(r.Context()),
				})
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
