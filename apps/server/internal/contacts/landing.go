package contacts

import _ "embed"

// LandingPageHTML contains the embedded HTML5 landing page.
//
//go:embed landing.html
var LandingPageHTML []byte

// PrivacyPolicyHTML contains the embedded HTML5 privacy policy page.
//
//go:embed privacy.html
var PrivacyPolicyHTML []byte

// AccountDeletionHTML contains the embedded HTML5 account deletion page.
//
//go:embed delete_account.html
var AccountDeletionHTML []byte

// HeroFamilyWelcomeWebP contains the embedded landing hero illustration.
//
//go:embed hero_family_welcome.webp
var HeroFamilyWelcomeWebP []byte

// BrandLogoSVG contains the embedded AnVề circular cottage emblem in SVG form.
//
//go:embed anve_logo.svg
var BrandLogoSVG []byte
