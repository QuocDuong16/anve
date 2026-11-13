## verdict

1. Resolved — avatar-image-error.png now shows the complete retry arrow and its circular control outside the avatar's image clip; neither icon nor control is cut at the circle edge. The initials/photo region stays circular and the surrounding actions/footer retain their previous placement. The reported retained 48dp interaction target is supported by the correction's named test, not independently measurable from this screenshot.
2. Resolved — received-invitation-context.png now shows dark clock/network/battery icons against the sand system surface; the previously white-on-sand rendering is gone. Group, inviter, expiry and review/private-hide actions remain visible. This resolves the fixture finding without asserting a production-host defect.

## remaining

Clear for the two scored fixes; no visible regression introduced by this correction batch. The two recaptures are valid and manifest-bound to source 17b0fcdb45fdaf66017cf602db59ac724f473cbe, run 43289266521, attempt 1; both named tests passed. The other nine captures remain bound to ca13b4f. Overall run success is not established by this verdict: attempt 1 had an unrelated pre-UI font-scale setup failure and its rerun is pending. Formal PLATES and later gates remain pending; raw comp-diff failures are unchanged. This ship disposition covers the scored fixes, not the whole surface.

disposition: ship
