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

func landingPageHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(contacts.LandingPageHTML)
	}
}

func heroAssetHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "image/webp")
		w.Header().Set("Cache-Control", "public, max-age=86400")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(contacts.HeroFamilyWelcomeWebP)
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
