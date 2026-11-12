// Package avatars owns bounded, authenticated profile photos and their revisions.
package avatars

import (
	"bytes"
	"errors"
	"image"
	"image/color"
	"image/jpeg"
	_ "image/png" // Register PNG decoding; all outputs are metadata-free JPEG.

	"golang.org/x/image/draw"
)

// Resource budgets bound decoding even for malformed authenticated uploads.
const (
	MaxInputBytes = 1 << 20
	MaxImageBytes = 128 << 10
	ImageSize     = 256
)

// Domain failures separate rejected input from writes requiring fresh readback.
var (
	ErrInvalid  = errors.New("invalid avatar")
	ErrConflict = errors.New("avatar revision conflict")
	ErrNotFound = errors.New("avatar not found")
	ErrBusy     = errors.New("avatar normalization busy")
)

// Normalize bounds allocation before decoding and drops every source metadata
// segment by encoding fresh pixels rather than retaining the uploaded container.
func Normalize(input []byte) ([]byte, error) {
	if len(input) == 0 || len(input) > MaxInputBytes {
		return nil, ErrInvalid
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(input))
	if err != nil || (format != "jpeg" && format != "png") || cfg.Width < 1 || cfg.Height < 1 || cfg.Width > 1024 || cfg.Height > 1024 {
		return nil, ErrInvalid
	}
	source, decodedFormat, err := image.Decode(bytes.NewReader(input))
	if err != nil || decodedFormat != format || source.Bounds().Dx() != cfg.Width || source.Bounds().Dy() != cfg.Height {
		return nil, ErrInvalid
	}
	bounds := source.Bounds()
	side := min(bounds.Dx(), bounds.Dy())
	x, y := bounds.Min.X+(bounds.Dx()-side)/2, bounds.Min.Y+(bounds.Dy()-side)/2
	crop := image.Rect(x, y, x+side, y+side)
	destination := image.NewRGBA(image.Rect(0, 0, ImageSize, ImageSize))
	draw.Draw(destination, destination.Bounds(), image.NewUniform(color.White), image.Point{}, draw.Src)
	draw.CatmullRom.Scale(destination, destination.Bounds(), source, crop, draw.Over, nil)
	var output bytes.Buffer
	if err := jpeg.Encode(&output, destination, &jpeg.Options{Quality: 85}); err != nil {
		return nil, ErrInvalid
	}
	if output.Len() > MaxImageBytes {
		return nil, ErrInvalid
	}
	return output.Bytes(), nil
}
