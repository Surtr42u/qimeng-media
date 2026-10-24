package buildinfo

import (
	"runtime/debug"
	"strings"
	"testing"
)

func TestBuildIdentity(t *testing.T) {
	i := fromBuildInfo(&debug.BuildInfo{Settings: []debug.BuildSetting{
		{Key: "vcs.revision", Value: "1234567890abcdef"},
		{Key: "vcs.time", Value: "2026-10-21T00:00:00Z"},
		{Key: "vcs.modified", Value: "true"},
	}})
	if i.SourceTime != "2026-10-21T00:00:00Z" || !strings.HasSuffix(i.Display(), "+1234567890ab.dirty") {
		t.Fatalf("构建身份丢失: %+v", i)
	}
	unknown := fromBuildInfo(nil)
	if unknown.Revision != "unknown" || unknown.Modified || unknown.SourceTime != "" || unknown.Version == "" {
		t.Fatalf("缺失元数据不应伪造身份: %+v", unknown)
	}
}
