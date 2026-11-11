package httpserver_test

import (
	"context"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/httpapi"
	"github.com/QuocDuong16/anve/server/internal/httpserver"
	"github.com/QuocDuong16/anve/server/internal/observability"
)

func address(t *testing.T, started <-chan string) string {
	t.Helper()
	select {
	case value := <-started:
		return "http://" + value
	case <-time.After(5 * time.Second):
		t.Fatal("listener did not start")
		return ""
	}
}

func stopped(t *testing.T, done <-chan error) error {
	t.Helper()
	select {
	case err := <-done:
		return err
	case <-time.After(5 * time.Second):
		t.Fatal("listeners did not stop")
		return nil
	}
}

func server(handler http.Handler, started chan<- string) *http.Server {
	return &http.Server{Addr: "127.0.0.1:0", Handler: handler, ReadHeaderTimeout: time.Second,
		BaseContext: func(listener net.Listener) context.Context {
			started <- listener.Addr().String()
			return context.Background()
		},
	}
}

func client(t *testing.T) *http.Client {
	t.Helper()
	transport := &http.Transport{Proxy: nil}
	t.Cleanup(transport.CloseIdleConnections)
	return &http.Client{Transport: transport, Timeout: 3 * time.Second}
}

func TestDedicatedListenerIsIsolatedAndBothStop(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	registry := observability.New()
	apiStarted, metricsStarted := make(chan string, 1), make(chan string, 1)
	api := server(httpapi.NewRouter(httpapi.Deps{Metrics: registry}), apiStarted)
	metrics := server(registry.Handler(), metricsStarted)
	var stateMu sync.Mutex
	states := make(map[net.Conn]http.ConnState)
	for _, peer := range []*http.Server{api, metrics} {
		peer.ConnState = func(conn net.Conn, state http.ConnState) {
			stateMu.Lock()
			defer stateMu.Unlock()
			if state == http.StateClosed {
				delete(states, conn)
			} else {
				states[conn] = state
			}
		}
	}
	done := make(chan error, 1)
	go func() { done <- httpserver.Run(ctx, time.Second, api, metrics) }()
	apiURL, metricsURL := address(t, apiStarted), address(t, metricsStarted)
	httpClient := client(t)
	for _, tc := range []struct {
		url    string
		status int
	}{
		{apiURL + "/metrics", 404}, {apiURL + "/healthz", 200}, {metricsURL + "/metrics", 200}, {metricsURL + "/api/v1/version", 404},
	} {
		response, err := httpClient.Get(tc.url)
		require.NoError(t, err)
		assert.Equal(t, tc.status, response.StatusCode)
		// Reuse completed connections rather than cancelling unread bodies,
		// which can leave speculative transport dials in StateNew.
		_, err = io.Copy(io.Discard, response.Body)
		require.NoError(t, err)
		require.NoError(t, response.Body.Close())
	}
	response, err := httpClient.Head(metricsURL + "/metrics")
	require.NoError(t, err)
	assert.Equal(t, 200, response.StatusCode)
	require.NoError(t, response.Body.Close())
	stateMu.Lock()
	beforeCancel := make([]http.ConnState, 0, len(states))
	for _, state := range states {
		beforeCancel = append(beforeCancel, state)
	}
	stateMu.Unlock()
	cancel()
	require.NoError(t, stopped(t, done), "connection states before cancellation: %v", beforeCancel)
	for _, url := range []string{apiURL, metricsURL} {
		response, err := httpClient.Get(url)
		if response != nil {
			_ = response.Body.Close()
		}
		require.Error(t, err)
	}
}

func TestAllListenersBindBeforeAnyHandlerStarts(t *testing.T) {
	occupied, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	defer func() { _ = occupied.Close() }()
	started := make(chan string, 1)
	api := server(http.NotFoundHandler(), started)
	metrics := &http.Server{Addr: occupied.Addr().String(), Handler: http.NotFoundHandler(), ReadHeaderTimeout: time.Second}
	err = httpserver.Run(context.Background(), time.Second, api, metrics)
	require.ErrorContains(t, err, "bind http listener")
	assert.Empty(t, started, "an API must not start while a configured metrics listener cannot bind")
}

func TestUnexpectedStopDrainsPeer(t *testing.T) {
	apiStarted, metricsStarted := make(chan string, 1), make(chan string, 1)
	api := server(http.NotFoundHandler(), apiStarted)
	metrics := server(http.NotFoundHandler(), metricsStarted)
	done := make(chan error, 1)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { done <- httpserver.Run(ctx, time.Second, api, metrics) }()
	_ = address(t, apiStarted)
	metricsURL := address(t, metricsStarted)
	require.NoError(t, api.Close())
	require.ErrorContains(t, stopped(t, done), "stopped unexpectedly")
	response, err := client(t).Get(metricsURL)
	if response != nil {
		_ = response.Body.Close()
	}
	require.Error(t, err)
}

func TestDrainDeadlineClosesActiveConnection(t *testing.T) {
	started := make(chan string, 1)
	entered, release := make(chan struct{}), make(chan struct{})
	defer close(release)
	api := server(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { close(entered); <-release }), started)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- httpserver.Run(ctx, 20*time.Millisecond, api) }()
	apiURL := address(t, started)
	requestDone := make(chan error, 1)
	httpClient := client(t)
	go func() {
		response, err := httpClient.Get(apiURL)
		if response != nil {
			_ = response.Body.Close()
		}
		requestDone <- err
	}()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("request did not enter handler")
	}
	cancel()
	require.ErrorIs(t, stopped(t, done), context.DeadlineExceeded)
	require.Error(t, stopped(t, requestDone))
}

func TestDrainDeadlineClosesConnectionWithoutRequest(t *testing.T) {
	started := make(chan string, 1)
	accepted := make(chan struct{}, 1)
	api := server(http.NotFoundHandler(), started)
	api.ConnState = func(_ net.Conn, state http.ConnState) {
		if state == http.StateNew {
			accepted <- struct{}{}
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- httpserver.Run(ctx, 20*time.Millisecond, api) }()
	apiURL := address(t, started)
	conn, err := net.DialTimeout("tcp", strings.TrimPrefix(apiURL, "http://"), time.Second)
	require.NoError(t, err)
	defer func() { _ = conn.Close() }()
	select {
	case <-accepted:
	case <-time.After(5 * time.Second):
		t.Fatal("connection was not accepted")
	}
	cancel()
	require.ErrorIs(t, stopped(t, done), context.DeadlineExceeded)
	require.NoError(t, conn.SetReadDeadline(time.Now().Add(time.Second)))
	_, err = conn.Read(make([]byte, 1))
	require.ErrorIs(t, err, io.EOF)
}
