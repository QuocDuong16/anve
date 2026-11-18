package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"

	"github.com/go-chi/chi/v5/middleware"

	"github.com/QuocDuong16/anve/server/internal/contacts"
)

const maxContactBodyBytes = 16 << 10

func staticHTMLHandler(html []byte) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(html)
	}
}

func landingPageHandler() http.HandlerFunc {
	return staticHTMLHandler(contacts.LandingPageHTML)
}

func privacyPolicyHandler() http.HandlerFunc {
	return staticHTMLHandler(contacts.PrivacyPolicyHTML)
}

func accountDeletionHandler() http.HandlerFunc {
	return staticHTMLHandler(contacts.AccountDeletionHTML)
}

func invitePageHandler() http.HandlerFunc {
	const csp = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", csp)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(contacts.InvitePageHTML)
	}
}

func inviteScriptHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/javascript; charset=utf-8")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "public, max-age=86400")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(contacts.InviteScriptJS)
	}
}

func assetLinksHandler(pkg string, fingerprints []string) http.HandlerFunc {
	if pkg == "" {
		pkg = "com.anve.app"
	}
	type target struct {
		Namespace              string   `json:"namespace"`
		PackageName            string   `json:"package_name"`
		SHA256CertFingerprints []string `json:"sha256_cert_fingerprints"`
	}
	type statement struct {
		Relation []string `json:"relation"`
		Target   target   `json:"target"`
	}
	var payload []byte
	if len(fingerprints) > 0 {
		encoded, err := json.Marshal([]statement{{
			Relation: []string{"delegate_permission/common.handle_all_urls"},
			Target: target{
				Namespace:              "android_app",
				PackageName:            pkg,
				SHA256CertFingerprints: fingerprints,
			},
		}})
		if err == nil {
			payload = encoded
		}
	}
	return func(w http.ResponseWriter, _ *http.Request) {
		if len(payload) == 0 {
			http.NotFound(w, nil)
			return
		}
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "public, max-age=3600")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(payload)
	}
}

func heroAssetHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "image/webp")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "public, max-age=86400")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(contacts.HeroFamilyWelcomeWebP)
	}
}

func brandLogoAssetHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "image/svg+xml; charset=utf-8")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "public, max-age=86400")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(contacts.BrandLogoSVG)
	}
}

func contactCreateHandler(svc *contacts.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if svc == nil {
			writeJSON(w, http.StatusServiceUnavailable, errorBody{
				Code:      errCodeUnavailable,
				Message:   "Contacts storage needs a configured database.",
				RequestID: middleware.GetReqID(r.Context()),
			})
			return
		}

		r.Body = http.MaxBytesReader(w, r.Body, maxContactBodyBytes)
		var input contacts.CreateInput
		dec := json.NewDecoder(r.Body)
		dec.DisallowUnknownFields()
		if err := dec.Decode(&input); err != nil {
			var maxErr *http.MaxBytesError
			if errors.As(err, &maxErr) {
				writeValidationError(w, r, "Request body too large.")
				return
			}
			writeValidationError(w, r, "Request body must be valid JSON matching the contact schema.")
			return
		}
		if err := dec.Decode(&struct{}{}); err != io.EOF {
			writeValidationError(w, r, "Request body must contain a single JSON object.")
			return
		}

		created, err := svc.Submit(r.Context(), input)
		if err != nil {
			if errors.Is(err, contacts.ErrValidation) {
				writeValidationError(w, r, "Contact submission validation failed.")
				return
			}
			writeJSON(w, http.StatusInternalServerError, errorBody{
				Code:      errCodeInternal,
				Message:   "Failed to store contact inquiry.",
				RequestID: middleware.GetReqID(r.Context()),
			})
			return
		}
		writeJSON(w, http.StatusCreated, created)
	}
}
