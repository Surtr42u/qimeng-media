// Package architecture_test 检查纯算法包的直接依赖；不把该检查当成形式化纯度证明。
package architecture_test

import (
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

// 白名单仅允许内存算法/文本/时间等标准包；新增 IO 或第三方包装必须失败，
// 防止只禁 os/net 后被另一个业务包间接引入。变更白名单须同步架构专题。
var algorithmImports = strings.Fields(`bytes cmp container/heap crypto/sha256 encoding/hex
encoding/json errors fmt hash/fnv math math/rand math/rand/v2 path path/filepath regexp
slices sort strconv strings sync sync/atomic time unicode unicode/utf8`)

func TestAlgorithmDependencies(t *testing.T) {
	allowed := make(map[string]bool)
	for _, name := range algorithmImports {
		allowed[name] = true
	}
	for _, pkg := range []string{"recommend", "stats", "authoring", "sourcematcher"} {
		dir := filepath.Join("..", pkg)
		entries, err := os.ReadDir(dir)
		if err != nil {
			t.Fatal(err)
		}
		for _, entry := range entries {
			if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".go") || strings.HasSuffix(entry.Name(), "_test.go") {
				continue
			}
			name := filepath.Join(dir, entry.Name())
			file, err := parser.ParseFile(token.NewFileSet(), name, nil, parser.ImportsOnly)
			if err != nil {
				t.Fatal(err)
			}
			for _, spec := range file.Imports {
				path, err := strconv.Unquote(spec.Path.Value)
				if err != nil {
					t.Fatal(err)
				}
				if !allowed[path] {
					t.Errorf("%s: 纯算法禁止依赖 %s；IO/编排请下沉其他业务包", name, path)
				}
			}
		}
	}
}
