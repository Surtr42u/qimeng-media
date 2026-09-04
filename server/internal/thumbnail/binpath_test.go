package thumbnail

import (
	"context"
	"strings"
	"testing"
)

// 本文件锁定 ffmpeg/ffprobe 二进制路径解析的两分支行为契约（S-3，M6 前置）：
//   - 显式配置优先：配置的路径必须原样进 exec（不得被默认值顶替）——
//     M6 单机形态（ADR-0015）二进制在 App 的 nativeLibraryDir，PATH 不可达，
//     显式路径是唯一通道；
//   - 缺省回退自动发现：空配置回退裸命令名走 PATH，与路径配置化之前
//     行为完全一致（零变化契约，NAS/PC 形态不配置即维持现状）。

// TestResolveBinExplicitPriority 显式配置优先分支：非空配置原样返回。
func TestResolveBinExplicitPriority(t *testing.T) {
	const explicit = "/data/nativeDir/libffmpeg.so" // 任意路径都不得被改写（测试字面量豁免）
	if got := resolveBin(explicit, DefaultFFmpegBin); got != explicit {
		t.Errorf("显式配置应原样采用：resolveBin(%q, %q) = %q", explicit, DefaultFFmpegBin, got)
	}
}

// TestResolveBinFallbackAutoDiscover 缺省回退分支：空配置回退裸命令名。
func TestResolveBinFallbackAutoDiscover(t *testing.T) {
	if got := resolveBin("", DefaultFFmpegBin); got != DefaultFFmpegBin {
		t.Errorf("空配置应回退 %q，得到 %q", DefaultFFmpegBin, got)
	}
	if got := resolveBin("", DefaultFFprobeBin); got != DefaultFFprobeBin {
		t.Errorf("空配置应回退 %q，得到 %q", DefaultFFprobeBin, got)
	}
}

// TestNewGeneratorBinResolution 构造期单点解析：Options 空 → 双双回退裸命令名；
// 显式配置 → 原样保留（Generator 字段是管线内唯一取用点）。
func TestNewGeneratorBinResolution(t *testing.T) {
	t.Run("缺省回退自动发现", func(t *testing.T) {
		g := newTestGenerator(t)
		if g.ffmpegBin != DefaultFFmpegBin || g.ffprobeBin != DefaultFFprobeBin {
			t.Errorf("缺省构造应回退裸命令名，得到 ffmpeg=%q ffprobe=%q", g.ffmpegBin, g.ffprobeBin)
		}
	})
	t.Run("显式配置优先", func(t *testing.T) {
		g := NewGenerator(t.TempDir(), nil, Options{FFmpegPath: "/x/ffmpeg", FFprobePath: "/y/ffprobe"})
		defer g.Close()
		if g.ffmpegBin != "/x/ffmpeg" || g.ffprobeBin != "/y/ffprobe" {
			t.Errorf("显式配置应原样保留，得到 ffmpeg=%q ffprobe=%q", g.ffmpegBin, g.ffprobeBin)
		}
	})
}

// TestProbeVideoUsesConfiguredBin 显式配置确实进 exec 的实证：把 ffprobe 路径
// 指向不存在的文件，探测必然失败且错误信息携带该路径（run 把 bin 名拼进错误
// 首段）——证明配置值（而非裸命令名）被送进了 exec.CommandContext。
// 不依赖真实 ffmpeg，任何环境可跑。
func TestProbeVideoUsesConfiguredBin(t *testing.T) {
	g := NewGenerator(t.TempDir(), nil, Options{FFprobePath: "/no-such-dir/qimeng-missing-ffprobe"})
	defer g.Close()
	_, err := g.ProbeVideo(context.Background(), "whatever.mp4")
	if err == nil {
		t.Fatal("探测应失败（配置的 ffprobe 路径不存在）")
	}
	if !strings.Contains(err.Error(), "/no-such-dir/qimeng-missing-ffprobe") {
		t.Errorf("错误信息应携带显式配置的 ffprobe 路径，实际: %v", err)
	}
}

// TestProbeVideoFallbackUsesBareName 缺省回退确实进 exec 的实证：空配置时
// 错误信息携带裸命令名 ffprobe（而非其他路径）。
func TestProbeVideoFallbackUsesBareName(t *testing.T) {
	g := newTestGenerator(t)
	_, err := g.ProbeVideo(context.Background(), "/no-such-dir/whatever.mp4")
	if err == nil {
		t.Fatal("探测应失败（文件不存在且裸命令名在测试环境可能不可用）")
	}
	// 两种失败形态都合法：裸命令名不存在（错误含 "ffprobe"）或存在但探测
	// 不到流（错误含文件路径）——共同点是绝不能出现显式配置才有的路径段。
	if !strings.Contains(err.Error(), DefaultFFprobeBin) && !strings.Contains(err.Error(), "whatever.mp4") {
		t.Errorf("错误信息应指向裸命令名或被探测文件，实际: %v", err)
	}
}
