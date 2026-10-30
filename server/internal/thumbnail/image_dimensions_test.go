package thumbnail

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"hash/crc32"
	"image"
	"image/gif"
	"image/jpeg"
	"image/png"
	"os"
	"os/exec"
	"path/filepath"
	"testing"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/store"
)

func TestImageDimensionsReadsHeaderWithoutFullPixelDecode(t *testing.T) {
	root := t.TempDir()
	var data bytes.Buffer
	data.WriteString("\x89PNG\r\n\x1a\n")
	payload := make([]byte, 13)
	binary.BigEndian.PutUint32(payload, 12000)
	binary.BigEndian.PutUint32(payload[4:], 9000)
	payload[8], payload[9] = 8, 2
	_ = binary.Write(&data, binary.BigEndian, uint32(len(payload)))
	chunk := append([]byte("IHDR"), payload...)
	data.Write(chunk)
	_ = binary.Write(&data, binary.BigEndian, crc32.ChecksumIEEE(chunk))
	// 无IDAT像素区；DecodeConfig可读出尺寸，完整Decode必然失败。
	if _, err := png.Decode(bytes.NewReader(data.Bytes())); err == nil {
		t.Fatal("fixture must not be a complete image")
	}
	path := filepath.Join(root, "huge.png")
	if err := os.WriteFile(path, data.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	g := &Generator{ffprobeBin: "must-not-execute"}
	w, h, err := g.ReadImageDimensions(t.Context(), root, "huge.png", info.Size(), store.FormatTimestamp(info.ModTime()))
	if err != nil || w != 12000 || h != 9000 {
		t.Fatalf("header-only dimensions: %dx%d %v", w, h, err)
	}
}

func TestImageDimensionsFormatsAndSourceGuards(t *testing.T) {
	root := t.TempDir()
	img := image.NewRGBA(image.Rect(0, 0, 17, 29))
	for name, encode := range map[string]func(*bytes.Buffer) error{
		"still.png":  func(b *bytes.Buffer) error { return png.Encode(b, img) },
		"still.jpg":  func(b *bytes.Buffer) error { return jpeg.Encode(b, img, nil) },
		"motion.gif": func(b *bytes.Buffer) error { return gif.Encode(b, img, nil) },
	} {
		t.Run(name, func(t *testing.T) {
			var b bytes.Buffer
			if err := encode(&b); err != nil {
				t.Fatal(err)
			}
			path := filepath.Join(root, name)
			if err := os.WriteFile(path, b.Bytes(), 0o600); err != nil {
				t.Fatal(err)
			}
			info, err := os.Stat(path)
			if err != nil {
				t.Fatal(err)
			}
			g := &Generator{ffprobeBin: "must-not-execute"}
			stamp := store.FormatTimestamp(info.ModTime())
			w, h, err := g.ReadImageDimensions(t.Context(), root, name, info.Size(), stamp)
			if err != nil || w != 17 || h != 29 {
				t.Fatalf("dimensions %dx%d %v", w, h, err)
			}
			if _, _, err := g.ReadImageDimensions(t.Context(), root, name, info.Size()+1, stamp); !errors.Is(err, ErrSourceUnavailable) {
				t.Fatalf("stale source accepted: %v", err)
			}
			if _, _, err := g.ReadImageDimensions(t.Context(), root, name, info.Size(), "old"); !errors.Is(err, ErrSourceUnavailable) {
				t.Fatalf("stale mtime accepted: %v", err)
			}
		})
	}
	g := &Generator{}
	if _, _, err := g.ReadImageDimensions(t.Context(), root, "../escape.png", 0, ""); !errors.Is(err, filing.ErrPathEscape) {
		t.Fatalf("escape accepted: %v", err)
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if _, _, err := g.ReadImageDimensions(ctx, root, "absent.png", 0, ""); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancel ignored: %v", err)
	}
}

func TestImageDimensionsJPEGDisplayOrientation(t *testing.T) {
	root := t.TempDir()
	path := makeOrientedJPEG(t, root, "rotated.jpg", binary.LittleEndian, orientationRot90CW)
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	g := &Generator{ffprobeBin: "must-not-execute"}
	w, h, err := g.ReadImageDimensions(t.Context(), root, "rotated.jpg", info.Size(), store.FormatTimestamp(info.ModTime()))
	if err != nil || w != quadH || h != quadW {
		t.Fatalf("EXIF display dimensions %dx%d %v", w, h, err)
	}
}

func TestImageDimensionsWebPFallbackIntegration(t *testing.T) {
	requireFFmpeg(t)
	root := t.TempDir()
	src := makeTallPNG(t, root)
	path := filepath.Join(root, "fallback.webp")
	if out, err := exec.CommandContext(t.Context(), DefaultFFmpegBin, "-v", "error", "-i", src, path).CombinedOutput(); err != nil {
		t.Fatalf("synthetic WebP: %v %s", err, out)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	g := &Generator{ffprobeBin: DefaultFFprobeBin}
	w, h, err := g.ReadImageDimensions(t.Context(), root, "fallback.webp", info.Size(), store.FormatTimestamp(info.ModTime()))
	if err != nil || w != 20 || h != 100 {
		t.Fatalf("fallback dimensions %dx%d %v", w, h, err)
	}
}
