package httpapi

import (
	"net/http"
	"net/netip"

	"github.com/go-chi/chi/v5/middleware"
)

// errCodeDevLoginNetworkDenied is returned when the development login is
// reached from a source address outside its allowlist.
const errCodeDevLoginNetworkDenied = "DEV_LOGIN_NETWORK_DENIED"

// RequireDevNetwork limits the development login to an explicit source
// allowlist. It reads RemoteAddr only: the router deliberately does not
// honor X-Forwarded-For or X-Real-IP, because a client can forge both. A
// deployment behind a proxy must therefore list the proxy's own address.
//
// An empty allowlist denies every request. That is the fail-closed answer
// for a fixture that mints sessions for arbitrary emails: a missing
// configuration must not become an open endpoint.
func RequireDevNetwork(networks []netip.Prefix) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			addr, err := netip.ParseAddr(clientIP(r))
			if err != nil || !withinNetworks(addr, networks) {
				writeJSON(w, http.StatusForbidden, errorBody{
					Code:      errCodeDevLoginNetworkDenied,
					Message:   "Development login is not available from this network.",
					RequestID: middleware.GetReqID(r.Context()),
				})
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

func withinNetworks(addr netip.Addr, networks []netip.Prefix) bool {
	// A dual-stack socket reports IPv4 peers as ::ffff:a.b.c.d; compare the
	// unmapped address so the IPv4 prefixes in the allowlist still match.
	addr = addr.Unmap()
	for _, network := range networks {
		if network.Contains(addr) {
			return true
		}
	}
	return false
}
