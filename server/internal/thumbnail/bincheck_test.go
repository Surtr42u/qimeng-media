package thumbnail

import (
	"os"
	"testing"
)

// 本文件锁定启动期自检 CheckBinaries 的行为契约（任务T T1）：
//   - 与 resolveBin 同源：返回的最终命令必须与显式配置逐字相等（不重写）、
//     空配置回退裸命令名（解析语义单点，自检与实际 exec 不漂移）；
//   - 可用性判定即 exec.LookPath 语义：不存在/不可执行 → 非 nil 错误。
// 任何环境可跑：不依赖真实 ffmpeg（成功分支用测试进程自身当"存在的
// 可执行文件"）。

// TestCheckBinariesExplicitMissing 显式路径指向不存在的文件：两个 Err 都非 nil
// （M6 投放错误形态——路径配了但二进制没放对位置，启动期就要暴露）。
func TestCheckBinariesExplicitMissing(t *testing.T) {
	_, _, ffErr, fpErr := CheckBinaries("/no-such-dir/qimeng-missing-ffmpeg", "/no-such-dir/qimeng-missing-ffprobe")
	if ffErr == nil {
		t.Error("显式 ffmpeg 路径不存在时 ffmpegErr 应非 nil")
	}
	if fpErr == nil {
		t.Error("显式 ffprobe 路径不存在时 ffprobeErr 应非 nil")
	}
}

// TestCheckBinariesBareNameNotOnPath 空配置回退裸命令名 + PATH 指向空目录：
// 自动发现必然失败（Err 非 nil）——验证空配置分支确实走 LookPath 而非恒通过。
func TestCheckBinariesBareNameNotOnPath(t *testing.T) {
	t.Setenv("PATH", t.TempDir())
	_, _, ffErr, fpErr := CheckBinaries("", "")
	if ffErr == nil {
		t.Error("PATH 空目录下裸命令名 ffmpeg 应查找失败（ffmpegErr 非 nil）")
	}
	if fpErr == nil {
		t.Error("PATH 空目录下裸命令名 ffprobe 应查找失败（ffprobeErr 非 nil）")
	}
}

// TestCheckBinariesFfmpegFound 成功分支（跨平台稳定）：用测试进程自身
// （os.Executable() 必然存在且可执行）当 ffmpeg 侧的"显式配置路径"，
// ffprobe 侧仍指向不存在文件——ffmpegErr==nil 且 ffprobeErr!=nil。
// Windows 下 os.Executable() 返回 .exe 路径，LookPath 认可。
func TestCheckBinariesFfmpegFound(t *testing.T) {
	self, err := os.Executable()
	if err != nil {
		t.Fatalf("获取测试进程路径失败: %v", err)
	}
	_, _, ffErr, fpErr := CheckBinaries(self, "/no-such-dir/qimeng-missing-ffprobe")
	if ffErr != nil {
		t.Errorf("存在的可执行文件（测试进程自身）应通过 LookPath: %v", ffErr)
	}
	if fpErr == nil {
		t.Error("不存在的 ffprobe 路径应失败（fpErr 非 nil）")
	}
}

// TestCheckBinariesReturnsConfiguredVerbatim 显式路径语义：返回的最终命令
// 与配置值逐字相等（原样采用不重写）——与 resolveBin 的实证契约同源，
// 保证自检探测的对象就是 run() 实际 exec 的对象。
func TestCheckBinariesReturnsConfiguredVerbatim(t *testing.T) {
	const explicitFfmpeg = "/data/nativeDir/libffmpeg.so" // 测试字面量豁免
	const explicitFfprobe = "/data/nativeDir/libffprobe.so"
	ffBin, fpBin, _, _ := CheckBinaries(explicitFfmpeg, explicitFfprobe)
	if ffBin != explicitFfmpeg {
		t.Errorf("ffmpeg 命令应与配置逐字相等：得到 %q，期望 %q", ffBin, explicitFfmpeg)
	}
	if fpBin != explicitFfprobe {
		t.Errorf("ffprobe 命令应与配置逐字相等：得到 %q，期望 %q", fpBin, explicitFfprobe)
	}
}
