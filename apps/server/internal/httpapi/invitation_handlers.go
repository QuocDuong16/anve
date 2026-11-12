package httpapi

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"github.com/QuocDuong16/anve/server/internal/circles"
)

func invitationPrincipal(w http.ResponseWriter, r *http.Request) (circles.InvitationPrincipal, bool) {
	w.Header().Set("Cache-Control", "no-store, private")
	owner, ok := placeActor(w, r)
	if !ok {
		return circles.InvitationPrincipal{}, false
	}
	ac, _ := AuthFromContext(r.Context())
	return circles.InvitationPrincipal{UserID: owner, SessionID: ac.Session.ID}, true
}
func privateInvitationCreate(store *circles.InvitationStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		p, ok := invitationPrincipal(w, r)
		if !ok {
			return
		}
		circle, valid := circleIDParam(r)
		if !valid {
			writeError(w, r, circles.ErrInviteUnavailable)
			return
		}
		var body circles.InvitationRequest
		if !decodeAuthBody(w, r, &body) {
			return
		}
		invite, created, err := store.Create(r.Context(), p, circle, body)
		if err != nil {
			writeError(w, r, err)
			return
		}
		status := http.StatusOK
		if created {
			status = http.StatusCreated
		}
		writeJSON(w, status, toInviteBody(invite))
	}
}
func privateInvitationReadback(store *circles.InvitationStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		p, ok := invitationPrincipal(w, r)
		if !ok {
			return
		}
		circle, valid := circleIDParam(r)
		id, err := uuid.Parse(chi.URLParam(r, "requestId"))
		if !valid || err != nil {
			writeError(w, r, circles.ErrInvitationConflict)
			return
		}
		invite, err := store.Created(r.Context(), p, circle, id)
		if err != nil {
			writeError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, toInviteBody(invite))
	}
}
func receivedInvitations(store *circles.InvitationStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		p, ok := invitationPrincipal(w, r)
		if !ok {
			return
		}
		invitations, err := store.Received(r.Context(), p)
		if err != nil {
			writeError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, struct {
			Invitations []circles.ReceivedInvitation `json:"invitations"`
		}{invitations})
	}
}
func receivedInvitationDetail(store *circles.InvitationStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		p, ok := invitationPrincipal(w, r)
		if !ok {
			return
		}
		id, err := uuid.Parse(chi.URLParam(r, "inviteId"))
		if err != nil {
			writeError(w, r, circles.ErrInviteUnavailable)
			return
		}
		invite, err := store.ReceivedOne(r.Context(), p, id, r.Method == http.MethodDelete)
		if err != nil {
			writeError(w, r, err)
			return
		}
		if r.Method == http.MethodDelete {
			writeNoContent(w)
			return
		}
		writeJSON(w, http.StatusOK, invite)
	}
}

func toInviteBody(i circles.Invite) inviteBody {
	return inviteBody{ID: i.ID.String(), CircleID: i.CircleID.String(), Code: i.Code, CreatedBy: i.CreatedBy.String(),
		ExpiresAt: i.ExpiresAt.UTC().Format(timeFormat), CreatedAt: i.CreatedAt.UTC().Format(timeFormat), RecipientRestricted: i.RecipientRestricted}
}
