package authorattach

import (
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store/db"
	"testing"
)

func TestMasterTXTCombinesSourcesAndCurrentLinks(t *testing.T) {
	authors := []db.ListAuthorsRow{
		{ID: "beta", DisplayName: "Beta", Type: "regular", CreatedAt: "1"},
		{ID: "alpha", DisplayName: "Alpha", Type: "regular", CreatedAt: "2"},
		{ID: "new", DisplayName: "New", Type: "regular", CreatedAt: "3"},
		{ID: "opaque", DisplayName: "Different", Type: "regular"},
		{ID: "cos_person", DisplayName: "Person", Type: "cos"},
	}
	sources := []Source{
		{Filename: "first.txt", Content: "Alpha\n"},
		{Filename: "second.txt", Content: "1  Alpha  Alias\n来源  site-a\n作品\nold.png\n\n2  Beta\n作品\nb.png\n"},
	}
	works := map[string][]string{"alpha": {"current.png", "current.png"}, "new": {"new.mp4"}}
	content := buildMasterTXT(sources, authors, works)
	blocks := authoring.ParseAuthorBlocks(content)
	if len(blocks) != 3 || blocks[0].AuthorNames[0] != "Alpha" || blocks[1].AuthorNames[0] != "Beta" || blocks[2].AuthorNames[0] != "New" {
		t.Fatalf("unexpected master blocks: %+v", blocks)
	}
	if len(blocks[0].Works) != 2 || len(blocks[0].AuthorNames) != 2 || len(blocks[0].Sources) != 1 {
		t.Fatalf("lost aliases, sources or works: %+v", blocks[0])
	}
	if again := buildMasterTXT([]Source{{Content: content}}, authors, works); again != content {
		t.Fatal("master export must be stable after reimport")
	}
}
