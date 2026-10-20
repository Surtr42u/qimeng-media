package httpapi

import (
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// 当前上传协议只有字节和目标参数，不携带源文件系统时间。此测试记录
// 跨端迁移的现有边界；保留原时间需要显式扩展协议，不能从字节猜测。
func TestUploadWithoutSourceMetadataUsesLandingTime(t *testing.T) {
	env := newTestEnv(t)
	source := filepath.Join(t.TempDir(), "source.jpg")
	if err := os.WriteFile(source, makeJPG(t, "", 32, 32), 0600); err != nil {
		t.Fatal(err)
	}
	originalTime := time.Date(2019, 1, 2, 3, 4, 5, 0, time.UTC)
	if err := os.Chtimes(source, originalTime, originalTime); err != nil {
		t.Fatal(err)
	}
	body, err := os.ReadFile(source)
	if err != nil {
		t.Fatal(err)
	}
	resp := env.uploadBytes(t, env.libID, "", "migrated.jpg", body)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("上传状态: %d", resp.StatusCode)
	}
	landed, err := os.Stat(filepath.Join(env.media, "migrated.jpg"))
	if err != nil {
		t.Fatal(err)
	}
	if landed.ModTime().Equal(originalTime) || time.Since(landed.ModTime()).Abs() > time.Minute {
		t.Fatalf("未传源 mtime 时应使用落盘时间: %v", landed.ModTime())
	}
}
