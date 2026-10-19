package thumbnail

import "testing"

func TestRepresentationVersionIncludesSourceAndPolicy(t *testing.T) {
	g := &Generator{longSide: int(SizeGrid)}
	base := g.RepresentationVersion(100, "2026-10-19T00:00:00.000Z")
	if base == g.RepresentationVersion(101, "2026-10-19T00:00:00.000Z") || base == g.RepresentationVersion(100, "2026-10-19T00:00:01.000Z") {
		t.Fatal("source fingerprint ignored")
	}
	before := g.PolicyVersion()
	g.longSide = int(SizePreview)
	if base == g.RepresentationVersion(100, "2026-10-19T00:00:00.000Z") || before == g.PolicyVersion() {
		t.Fatal("pixel configuration ignored")
	}
	before = g.PolicyVersion()
	g.stillFormat = StillFormatJPEG
	if before == g.PolicyVersion() {
		t.Fatal("output format ignored")
	}
}
