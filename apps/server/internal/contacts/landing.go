package contacts

import _ "embed"

// LandingPageHTML contains the embedded HTML5 landing page.
//
//go:embed landing.html
var LandingPageHTML []byte

// HeroFamilyWelcomeWebP contains the embedded landing hero illustration.
//
//go:embed hero_family_welcome.webp
var HeroFamilyWelcomeWebP []byte
