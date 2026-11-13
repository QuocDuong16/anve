package avatars

import (
	"bytes"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestNormalizeCropsResizesAndDropsSourceMetadata(t *testing.T) {
	source := image.NewRGBA(image.Rect(0, 0, 600, 300))
	for y := range 300 {
		for x := range 600 {
			pixel := color.RGBA{B: 255, A: 255}
			if x >= 150 && x < 450 {
				pixel = color.RGBA{R: 255, A: 255}
			}
			source.SetRGBA(x, y, pixel)
		}
	}
	var encoded bytes.Buffer
	require.NoError(t, jpeg.Encode(&encoded, source, nil))
	const secret = "private-location-metadata-should-not-survive"
	segment := append([]byte{0xff, 0xfe, 0, byte(len(secret) + 2)}, secret...)
	input := append(append(append([]byte{}, encoded.Bytes()[:2]...), segment...), encoded.Bytes()[2:]...)
	output, err := Normalize(input)
	require.NoError(t, err)
	require.NotContains(t, string(output), string(secret))
	require.LessOrEqual(t, len(output), MaxImageBytes)
	decoded, format, err := image.Decode(bytes.NewReader(output))
	require.NoError(t, err)
	require.Equal(t, "jpeg", format)
	require.Equal(t, image.Rect(0, 0, ImageSize, ImageSize), decoded.Bounds())
	r, _, b, _ := decoded.At(128, 128).RGBA()
	require.Greater(t, r, uint32(60000))
	require.Less(t, b, uint32(1000))
}

func TestNormalizeRejectsInvalidOversizedAndUnsupportedImages(t *testing.T) {
	var oversized bytes.Buffer
	require.NoError(t, png.Encode(&oversized, image.NewRGBA(image.Rect(0, 0, 1025, 1))))
	for _, tc := range []struct {
		name string
		data []byte
	}{
		{"empty", nil},
		{"body limit", make([]byte, MaxInputBytes+1)},
		{"decoded width limit", oversized.Bytes()},
		{"truncated jpeg", []byte{0xff, 0xd8, 0xff}},
		{"svg", []byte(`<svg width="1" height="1"></svg>`)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Normalize(tc.data)
			require.ErrorIs(t, err, ErrInvalid)
		})
	}
}

func TestNormalizeFlattensTransparentPNG(t *testing.T) {
	var input bytes.Buffer
	require.NoError(t, png.Encode(&input, image.NewNRGBA(image.Rect(0, 0, 64, 64))))
	output, err := Normalize(input.Bytes())
	require.NoError(t, err)
	decoded, err := jpeg.Decode(bytes.NewReader(output))
	require.NoError(t, err)
	r, g, b, a := decoded.At(128, 128).RGBA()
	for _, component := range []uint32{r, g, b, a} {
		require.Greater(t, component, uint32(65000))
	}
}
