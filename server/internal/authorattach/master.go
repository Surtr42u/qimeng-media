package authorattach

import (
	"context"
	"sort"
	"strings"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store/db"
)

// MasterTXT projects the current regular authors and links into one importable
// document. Callers exporting a backup supply transaction-bound queries.
func MasterTXT(ctx context.Context, q *db.Queries) (string, error) {
	sources, err := LoadSources(ctx, q)
	if err != nil {
		return "", err
	}
	authors, err := q.ListAuthors(ctx)
	if err != nil {
		return "", err
	}
	assets, err := q.ExportListAssets(ctx)
	if err != nil {
		return "", err
	}
	refs, err := q.ExportAssetAuthors(ctx)
	if err != nil {
		return "", err
	}
	files := make(map[string]string, len(assets))
	for _, asset := range assets {
		files[asset.AssetID] = asset.FileName
	}
	works := make(map[string][]string)
	for _, ref := range refs {
		works[ref.AuthorID] = append(works[ref.AuthorID], files[ref.AssetID])
	}
	return buildMasterTXT(sources, authors, works), nil
}

func buildMasterTXT(sources []Source, authors []db.ListAuthorsRow, works map[string][]string) string {
	byID := make(map[string]db.ListAuthorsRow)
	for _, author := range authors {
		if author.Type == authoring.AuthorTypeRegular {
			byID[author.ID] = author
		}
	}
	blocks := make(map[string]authoring.AuthorBlock)
	var order []string
	add := func(block authoring.AuthorBlock) {
		if len(block.AuthorNames) == 0 {
			return
		}
		id := authoring.GenerateAuthorID(block.AuthorNames[0])
		if _, exists := byID[id]; !exists {
			return
		}
		old, exists := blocks[id]
		if !exists {
			order = append(order, id)
		}
		old.AuthorNames = append(old.AuthorNames, block.AuthorNames...)
		old.Sources = append(old.Sources, block.Sources...)
		old.Works = append(old.Works, block.Works...)
		blocks[id] = old
	}
	for _, source := range sources {
		parsed := authoring.ParseAuthorBlocks(source.Content)
		if len(parsed) == 0 {
			for _, name := range authoring.ParsePlainAuthorNames(source.Content) {
				add(authoring.AuthorBlock{AuthorNames: []string{name}})
			}
		}
		for _, block := range parsed {
			add(block)
		}
	}
	// Authors without a TXT position follow in their recorded creation order.
	sort.SliceStable(authors, func(i, j int) bool {
		if authors[i].CreatedAt == authors[j].CreatedAt {
			return authors[i].ID < authors[j].ID
		}
		return authors[i].CreatedAt < authors[j].CreatedAt
	})
	for _, author := range authors {
		if _, exists := blocks[author.ID]; exists {
			continue
		}
		names := authoring.CanonicalAuthorNames(author.DisplayName)
		// Opaque historical identities remain fully represented in the v2 file.
		if len(names) > 0 && authoring.GenerateAuthorID(names[0]) == author.ID {
			add(authoring.AuthorBlock{AuthorNames: names})
		}
	}
	if len(order) == 0 {
		return ""
	}
	content := "绮梦影库 作者总表\n完整档案、头像、COS及标签请使用同目录v2备份迁移。\n"
	for _, id := range order {
		block := blocks[id]
		block.Works = append(block.Works, works[id]...)
		content = authoring.AppendAuthorBlock(content, uniqueLines(block.AuthorNames), uniqueLines(block.Sources), uniqueLines(block.Works))
	}
	return content
}

func uniqueLines(lines []string) []string {
	seen := make(map[string]bool)
	var result []string
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line != "" && !seen[line] {
			seen[line] = true
			result = append(result, line)
		}
	}
	return result
}
