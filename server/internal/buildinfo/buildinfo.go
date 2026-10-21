// Package buildinfo 提供服务端和 Android 共用的发布版本及 Go 内建构建身份。
// 不读取工作目录或配置；无 VCS 信息时显式标记 unknown，禁止冒充已发布提交。
package buildinfo

import (
	_ "embed"
	"runtime"
	"runtime/debug"
	"strings"
)

// versionText 是发布版本唯一来源；Android app/build.gradle.kts 同时读取此文件。
// API info.version 是协议版本，独立于应用发布版本，不随内部维护强制重生成 SDK。
//
//go:embed version.txt
var versionText string

// Info 只含可公开的构建身份，不包含本机路径、用户名或运行配置。
type Info struct {
	Version    string `json:"version"`
	Revision   string `json:"revision"`
	SourceTime string `json:"sourceTime,omitempty"`
	Modified   bool   `json:"modified"`
	GoVersion  string `json:"goVersion"`
	Platform   string `json:"platform"`
}

// Current 从编译器嵌入的信息取值；SourceTime 是提交时间，不伪称构建时间。
func Current() Info {
	info, _ := debug.ReadBuildInfo()
	return fromBuildInfo(info)
}

func fromBuildInfo(info *debug.BuildInfo) Info {
	out := Info{Version: strings.TrimSpace(versionText), Revision: "unknown",
		GoVersion: runtime.Version(), Platform: runtime.GOOS + "/" + runtime.GOARCH}
	if info != nil {
		for _, setting := range info.Settings {
			switch setting.Key {
			case "vcs.revision":
				if setting.Value != "" {
					out.Revision = setting.Value
				}
			case "vcs.time":
				out.SourceTime = setting.Value
			case "vcs.modified":
				out.Modified = setting.Value == "true"
			}
		}
	}
	return out
}

// Display 复用现有 system/status 和 SSE hello 的字符串字段，不新增协议字段。
func (i Info) Display() string {
	revision := i.Revision
	const shortRevisionLength = 12
	if len(revision) > shortRevisionLength {
		revision = revision[:shortRevisionLength]
	}
	value := i.Version + "+" + revision
	if i.Modified {
		value += ".dirty"
	}
	return value
}
