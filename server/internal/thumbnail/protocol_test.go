// protocol_test.go：ffmpeg/ffprobe 输入侧协议白名单参数的单测（2026-10-17
// 安全加固）。锁死"白名单只允许 file"与"参数以 -protocol_whitelist 开头"，
// 防止后续改动把唯一防线改回默认全协议集合。
package thumbnail

import "testing"

// TestInputProtocolArgs 锁定输入侧协议白名单参数形态：ffmpeg/ffprobe 的
// -protocol_whitelist 必须为 file（库内媒体是不可信输入，禁止 http/https/
// concat 等外部引用面）。
func TestInputProtocolArgs(t *testing.T) {
	args := inputProtocolArgs()
	if len(args) != 2 {
		t.Fatalf("inputProtocolArgs 应为 2 个参数，得到 %v", args)
	}
	if args[0] != "-protocol_whitelist" {
		t.Errorf("参数名 = %q，期望 -protocol_whitelist", args[0])
	}
	if args[1] != "file" {
		t.Errorf("协议白名单 = %q，期望 file（库内媒体只允许本地文件输入）", args[1])
	}
}
