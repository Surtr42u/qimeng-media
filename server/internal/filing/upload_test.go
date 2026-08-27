package filing

import (
	"errors"
	"testing"
)

var (
	jpgHead = []byte{0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00}
	pngHead = []byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D}
	exeHead = []byte{'M', 'Z', 0x90, 0x00, 0x03, 0x00, 0x00, 0x00}
	movHead = ftypHead("qt  ")
	mp4Head = ftypHead("isom")
	aviHead = append([]byte("RIFF\x24\x00\x00\x00AVI LIST"), 0x00)
)

const mb = 1 << 20

func TestValidateUpload(t *testing.T) {
	tests := []struct {
		name     string
		filename string
		size     int64
		maxBytes int64
		head     []byte
		want     error
	}{
		{"全部通过", "photo.jpg", 1000, 10 * mb, jpgHead, nil},
		{"全部通过-大写扩展名", "PHOTO.JPG", 1000, 10 * mb, jpgHead, nil},
		{"全部通过-mp4", "clip.mp4", 5 * mb, 100 * mb, mp4Head, nil},
		{"全部通过-m4v", "IMG_0001.m4v", 5 * mb, 100 * mb, mp4Head, nil},
		{"全部通过-avi", "old.avi", 5 * mb, 100 * mb, aviHead, nil},
		{"全部通过-mkv", "video.mkv", 5 * mb, 100 * mb, mkvHead("matroska"), nil},
		{"全部通过-webm", "video.webm", 5 * mb, 100 * mb, mkvHead("webm"), nil},
		{"①扩展名 exe 拒绝", "payload.exe", 100, 10 * mb, jpgHead, ErrUploadExtension},
		{"①无扩展名拒绝", "file", 100, 10 * mb, jpgHead, ErrUploadExtension},
		{"①txt 拒绝", "note.txt", 100, 10 * mb, jpgHead, ErrUploadExtension},
		{"②jpg 实为 exe", "x.jpg", 100, 10 * mb, exeHead, ErrUploadMimeMismatch},
		{"②png 扩展名配 jpg 内容", "x.png", 100, 10 * mb, jpgHead, ErrUploadMimeMismatch},
		{"②mov 改名 mp4（严格策略）", "x.mp4", 100, 10 * mb, movHead, ErrUploadMimeMismatch},
		{"②head 截断嗅探不出", "x.jpg", 100, 10 * mb, jpgHead[:2], ErrUploadMimeMismatch},
		{"②空文件（空 head）", "x.jpg", 0, 10 * mb, nil, ErrUploadMimeMismatch},
		{"③超过上限", "photo.jpg", 10*mb + 1, 10 * mb, jpgHead, ErrUploadTooLarge},
		{"③等于上限放行", "photo.jpg", 10 * mb, 10 * mb, jpgHead, nil},
		{"④保留设备名", "CON.jpg", 100, 10 * mb, jpgHead, ErrUploadFilename},
		{"④保留名小写", "con.mp4", 100, 10 * mb, mp4Head, ErrUploadFilename},
		// 注意：走过第①道的文件名必然带白名单扩展名，而扩展名字符全部合法，
		// 因此"清洗后为空"在本组合下不可能触发（那是 SanitizeFilename 层的
		// 错误，见 TestSanitizeFilenameErrors）；"???.jpg" 清洗后剩 "jpg"。
		{"④可剥离的非法字符放行（剥离语义）", "a<b.jpg", 100, 10 * mb, jpgHead, nil},
		{"④全剥只剩扩展名字符（剥离语义边界）", "???.jpg", 100, 10 * mb, jpgHead, nil},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := ValidateUpload(tt.filename, tt.size, tt.maxBytes, tt.head)
			if tt.want == nil {
				if err != nil {
					t.Fatalf("ValidateUpload 意外报错: %v", err)
				}
				return
			}
			if !errors.Is(err, tt.want) {
				t.Fatalf("ValidateUpload 错误 = %v, want %v", err, tt.want)
			}
		})
	}
}

// TestValidateUploadErrorsDistinct 四道错误必须可用 errors.Is 互相区分
// （handler 依赖它映射 400/413 与 openapi Error.code）。
func TestValidateUploadErrorsDistinct(t *testing.T) {
	all := []error{ErrUploadExtension, ErrUploadMimeMismatch, ErrUploadTooLarge, ErrUploadFilename}
	for i, a := range all {
		for j, b := range all {
			if i != j && errors.Is(a, b) {
				t.Fatalf("%v 与 %v 不可区分", a, b)
			}
		}
	}
}

func TestAllowedExtension(t *testing.T) {
	allowed := []string{".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif",
		".mp4", ".mkv", ".webm", ".mov", ".m4v", ".avi",
		".JPG", ".Mp4", ".AVIF"}
	for _, e := range allowed {
		if !AllowedExtension(e) {
			t.Errorf("AllowedExtension(%q) = false, want true", e)
		}
	}
	denied := []string{"", ".", ".exe", ".txt", ".zip", ".mp3", ".flac", ".iso", ".jpg.exe", "jpg"}
	for _, e := range denied {
		if AllowedExtension(e) {
			t.Errorf("AllowedExtension(%q) = true, want false", e)
		}
	}
}
