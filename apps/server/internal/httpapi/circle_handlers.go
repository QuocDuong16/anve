package httpapi

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"github.com/QuocDuong16/anve/server/internal/circles"
)

// ── circles payloads ─────────────────────────────────────────────────────────

type circleBody struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	CreatedBy string `json:"createdBy"`
	CreatedAt string `json:"createdAt"`
}

type membershipBody struct {
	CircleID string `json:"circleId"`
	UserID   string `json:"userId"`
	Role     string `json:"role"`
	JoinedAt string `json:"joinedAt"`
}

type circleMembershipBody struct {
	Circle     *circleBody     `json:"circle"`
	Membership *membershipBody `json:"membership"`
}

type memberBody struct {
	UserID      string `json:"userId"`
	DisplayName string `json:"displayName"`
	Role        string `json:"role"`
	JoinedAt    string `json:"joinedAt"`
}

type memberListBody struct {
	Members []memberBody `json:"members"`
}

type inviteBody struct {
	ID        string `json:"id"`
	CircleID  string `json:"circleId"`
	Code      string `json:"code"`
	CreatedBy string `json:"createdBy"`
	ExpiresAt string `json:"expiresAt"`
	CreatedAt string `json:"createdAt"`
}

type inviteListBody struct {
	Invites []inviteBody `json:"invites"`
}

type createCircleRequest struct {
	Name string `json:"name"`
}

type createInviteRequest struct {
	ExpiresInHours *int `json:"expiresInHours"`
}

type redeemInviteRequest struct {
	Code string `json:"code"`
}

type leaveCircleRequest struct {
	Dissolve *bool `json:"dissolve"`
}

func toCircleBody(c circles.Circle) circleBody {
	return circleBody{
		ID: c.ID.String(), Name: c.Name,
		CreatedBy: c.CreatedBy.String(), CreatedAt: c.CreatedAt.UTC().Format(timeFormat),
	}
}

func toMembershipBody(m circles.Membership) membershipBody {
	return membershipBody{
		CircleID: m.CircleID.String(), UserID: m.UserID.String(),
		Role: m.Role, JoinedAt: m.JoinedAt.UTC().Format(timeFormat),
	}
}

func toCircleMembership(c circles.Circle, m *circles.Membership) circleMembershipBody {
	out := circleMembershipBody{}
	if m != nil {
		circle := toCircleBody(c)
		membership := toMembershipBody(*m)
		out.Circle = &circle
		out.Membership = &membership
	}
	return out
}

// ── handlers ─────────────────────────────────────────────────────────────────

func circleIDParam(r *http.Request) (uuid.UUID, bool) {
	id, err := uuid.Parse(chi.URLParam(r, "circleId"))
	return id, err == nil
}

func createCircleHandler(svc *circles.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, _ := AuthFromContext(r.Context())
		var body createCircleRequest
		if !decodeAuthBody(w, r, &body) {
			return
		}
		circle, membership, err := svc.Create(r.Context(), ac.User.ID, body.Name)
		if err != nil {
			writeError(w, r, err)
			return
		}
		writeJSON(w, http.StatusCreated, toCircleMembership(circle, &membership))
	}
}

func myCircleHandler(svc *circles.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, _ := AuthFromContext(r.Context())
		circle, membership, err := svc.MyCircle(r.Context(), ac.User.ID)
		if err != nil {
			writeError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, toCircleMembership(circle, membership))
	}
}

func updateCircleHandler(svc *circles.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, _ := AuthFromContext(r.Context())
		id, ok := circleIDParam(r)
		if !ok {
			writeValidationError(w, r, "circleId must be a UUID.")
			return
		}
		var body createCircleRequest
		if !decodeAuthBody(w, r, &body) {
			return
		}
		circle, err := svc.Rename(r.Context(), ac.User.ID, id, body.Name)
		if err != nil {
			writeError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, toCircleBody(circle))
	}
}

func listMembersHandler(svc *circles.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, _ := AuthFromContext(r.Context())
		id, ok := circleIDParam(r)
		if !ok {
			writeValidationError(w, r, "circleId must be a UUID.")
			return
		}
		members, err := svc.Members(r.Context(), ac.User.ID, id)
		if err != nil {
			writeError(w, r, err)
			return
		}
		out := make([]memberBody, 0, len(members))
		for _, m := range members {
			out = append(out, memberBody{
				UserID: m.UserID.String(), DisplayName: m.DisplayName,
				Role: m.Role, JoinedAt: m.JoinedAt.UTC().Format(timeFormat),
			})
		}
		writeJSON(w, http.StatusOK, memberListBody{Members: out})
	}
}

func removeMemberHandler(svc *circles.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, _ := AuthFromContext(r.Context())
		circleID, ok := circleIDParam(r)
		if !ok {
			writeValidationError(w, r, "circleId must be a UUID.")
			return
		}
		targetID, err := uuid.Parse(chi.URLParam(r, "userId"))
		if err != nil {
			writeValidationError(w, r, "userId must be a UUID.")
			return
		}
		if err := svc.RemoveMember(r.Context(), ac.User.ID, circleID, targetID); err != nil {
			writeError(w, r, err)
			return
		}
		writeNoContent(w)
	}
}

func leaveCircleHandler(svc *circles.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, _ := AuthFromContext(r.Context())
		id, ok := circleIDParam(r)
		if !ok {
			writeValidationError(w, r, "circleId must be a UUID.")
			return
		}
		var body leaveCircleRequest
		if !decodeAuthBody(w, r, &body) {
			return
		}
		if body.Dissolve == nil {
			writeValidationError(w, r, "dissolve is required.")
			return
		}
		if err := svc.Leave(r.Context(), ac.User.ID, id, *body.Dissolve); err != nil {
			writeError(w, r, err)
			return
		}
		writeNoContent(w)
	}
}

func createInviteHandler(svc *circles.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, _ := AuthFromContext(r.Context())
		id, ok := circleIDParam(r)
		if !ok {
			writeValidationError(w, r, "circleId must be a UUID.")
			return
		}
		var body createInviteRequest
		// The body is optional; an empty one keeps the default TTL.
		if r.ContentLength != 0 && !decodeAuthBody(w, r, &body) {
			return
		}
		invite, err := svc.CreateInvite(r.Context(), ac.User.ID, id, body.ExpiresInHours)
		if err != nil {
			writeError(w, r, err)
			return
		}
		writeJSON(w, http.StatusCreated, inviteBody{
			ID: invite.ID.String(), CircleID: invite.CircleID.String(),
			Code: invite.Code, CreatedBy: invite.CreatedBy.String(),
			ExpiresAt: invite.ExpiresAt.UTC().Format(timeFormat),
			CreatedAt: invite.CreatedAt.UTC().Format(timeFormat),
		})
	}
}

func listInvitesHandler(svc *circles.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, _ := AuthFromContext(r.Context())
		id, ok := circleIDParam(r)
		if !ok {
			writeValidationError(w, r, "circleId must be a UUID.")
			return
		}
		invites, err := svc.Invites(r.Context(), ac.User.ID, id)
		if err != nil {
			writeError(w, r, err)
			return
		}
		out := make([]inviteBody, 0, len(invites))
		for _, i := range invites {
			out = append(out, inviteBody{
				ID: i.ID.String(), CircleID: i.CircleID.String(), Code: i.Code,
				CreatedBy: i.CreatedBy.String(),
				ExpiresAt: i.ExpiresAt.UTC().Format(timeFormat),
				CreatedAt: i.CreatedAt.UTC().Format(timeFormat),
			})
		}
		writeJSON(w, http.StatusOK, inviteListBody{Invites: out})
	}
}

func revokeInviteHandler(svc *circles.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, _ := AuthFromContext(r.Context())
		inviteID, err := uuid.Parse(chi.URLParam(r, "inviteId"))
		if err != nil {
			writeValidationError(w, r, "inviteId must be a UUID.")
			return
		}
		if err := svc.RevokeInvite(r.Context(), ac.User.ID, inviteID); err != nil {
			writeError(w, r, err)
			return
		}
		writeNoContent(w)
	}
}

func redeemInviteHandler(svc *circles.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, _ := AuthFromContext(r.Context())
		var body redeemInviteRequest
		if !decodeAuthBody(w, r, &body) {
			return
		}
		circle, membership, err := svc.Redeem(r.Context(), ac.User.ID, body.Code)
		if err != nil {
			writeError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, toCircleMembership(circle, &membership))
	}
}
