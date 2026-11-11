// Package httpserver coordinates HTTP listeners and their bounded lifetime.
package httpserver

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"time"
)

// Run binds every listener before serving any of them. An optional listener is
// never silently dropped, and a failed server cannot leave its peers serving.
func Run(ctx context.Context, drain time.Duration, servers ...*http.Server) error {
	if len(servers) == 0 {
		return errors.New("no http servers configured")
	}
	listeners := make([]net.Listener, 0, len(servers))
	defer func() {
		for _, listener := range listeners {
			_ = listener.Close()
		}
	}()
	for _, server := range servers {
		listener, err := net.Listen("tcp", server.Addr)
		if err != nil {
			return fmt.Errorf("bind http listener: %w", err)
		}
		listeners = append(listeners, listener)
	}
	stopped := make(chan error, len(servers))
	for i, server := range servers {
		go func() { stopped <- server.Serve(listeners[i]) }()
	}
	var cause error
	select {
	case <-ctx.Done():
	case err := <-stopped:
		if errors.Is(err, http.ErrServerClosed) {
			cause = errors.New("http server stopped unexpectedly")
		} else {
			cause = fmt.Errorf("serve http: %w", err)
		}
	}
	shutdownCtx, cancel := context.WithTimeout(context.Background(), drain)
	defer cancel()
	// Shutdown all listeners together; one slow peer cannot keep another
	// listener accepting requests until the common deadline.
	drained := make(chan error, len(servers))
	for _, server := range servers {
		go func() { drained <- server.Shutdown(shutdownCtx) }()
	}
	for range servers {
		if err := <-drained; err != nil {
			cause = errors.Join(cause, fmt.Errorf("drain http: %w", err))
		}
	}
	for _, server := range servers {
		if err := server.Close(); err != nil {
			cause = errors.Join(cause, fmt.Errorf("close http: %w", err))
		}
	}
	return cause
}
