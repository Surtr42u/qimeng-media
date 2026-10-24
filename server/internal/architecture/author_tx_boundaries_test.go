package architecture_test

// author_tx_boundaries_test.go：Q1 事务下沉的 AST 回归门禁（任务书附录 E）。
// 只锁定本轮已迁移的 author_edit.go / authors_manage.go 两个 handler：它们
// 的多步事务必须由 authoredit 用例包持有，handler 内不得再出现
// Begin/BeginTx/WithTx 调用（防止改动回流）。这不是 HTTP 纯度或业务正确性
// 的完整证明——行为不变量由 authoredit 的事务行为测试与 httpapi 既有矩阵
// 测试承担；authoredit 不依赖 HTTP/SDK 由 .golangci.yml 的 depguard 两条
// 红线（gen-httpapi-only / no-httpapi-in-business，$all 否定式覆盖全部非
// httpapi 包）强制，本测试不重复实现。不把其他存量 handler 列为豁免：
// 本门禁只声明这两个文件的边界，其余文件的事务治理由各自任务另行推进。

import (
	"go/ast"
	"go/parser"
	"go/token"
	"path/filepath"
	"strings"
	"testing"
)

// txCallNames 是事务所有权标志：出现任一调用即视为在自行开/传事务。
// Begin/BeginTx 开启事务；WithTx 把连接包装成事务查询集（httpapi 侧持有
// 它同样意味着 handler 在拼事务）。
var txCallNames = map[string]bool{"Begin": true, "BeginTx": true, "WithTx": true}

// txBoundaryViolations 解析单个 Go 源文件（src 为 nil 时按路径读盘，否则
// 解析内嵌源码——正反例 fixture 用），返回其中事务调用 的位置与名称。
// 检查器本体抽成函数，使正反例与真实文件共用同一条判定路径。
func txBoundaryViolations(t *testing.T, fset *token.FileSet, filename string, src any) []string {
	t.Helper()
	file, err := parser.ParseFile(fset, filename, src, 0)
	if err != nil {
		t.Fatalf("解析 %s 失败: %v", filename, err)
	}
	var violations []string
	ast.Inspect(file, func(node ast.Node) bool {
		call, ok := node.(*ast.CallExpr)
		if !ok {
			return true
		}
		selector, ok := call.Fun.(*ast.SelectorExpr)
		if !ok {
			return true
		}
		if txCallNames[selector.Sel.Name] {
			// pos.String() 自带 "file:line:col" 形态，直接拼接调用名。
			violations = append(violations, fset.Position(call.Pos()).String()+" "+selector.Sel.Name)
		}
		return true
	})
	return violations
}

// TestMigratedAuthorHandlersDoNotOwnTransactions：两个已迁移 handler 的
// 真实源文件必须零事务调用。
func TestMigratedAuthorHandlersDoNotOwnTransactions(t *testing.T) {
	for _, name := range []string{"author_edit.go", "authors_manage.go"} {
		path := filepath.Join("..", "httpapi", name)
		fset := token.NewFileSet()
		if violations := txBoundaryViolations(t, fset, path, nil); len(violations) > 0 {
			t.Errorf("%s: 作者事务应由 authoredit 用例持有，handler 内发现事务调用：%s",
				name, strings.Join(violations, "；"))
		}
	}
}

// TestTxBoundaryCheckerFlagsTransactionCalls：检查器正例——三种事务调用
// 形态（裸 Begin、带参 BeginTx、WithTx）都必须命中；同一行不合并计数。
func TestTxBoundaryCheckerFlagsTransactionCalls(t *testing.T) {
	fixture := `package fixture

import "context"

func own(s *stub) {
	tx, err := s.conn.Begin()
	_ = err
	tx2, err2 := s.conn.BeginTx(context.Background(), nil)
	_, _ = tx2, err2
	q := s.q.WithTx(tx)
	_ = q
}
`
	fset := token.NewFileSet()
	violations := txBoundaryViolations(t, fset, "fixture.go", fixture)
	if len(violations) != 3 {
		t.Fatalf("正例期望命中 3 处事务调用，得到 %d 处：%v", len(violations), violations)
	}
	for _, want := range []string{"Begin", "BeginTx", "WithTx"} {
		found := false
		for _, v := range violations {
			if strings.HasSuffix(v, " "+want) {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("正例未命中 %s：%v", want, violations)
		}
	}
}

// TestTxBoundaryCheckerAllowsNonTransactionCode：检查器反例——用例调用、
// 普通方法调用、以及取事务方法的值但不调用（SelectorExpr 而非 CallExpr）
// 都不得误报。
func TestTxBoundaryCheckerAllowsNonTransactionCode(t *testing.T) {
	fixture := `package fixture

import "context"

func clean(s *stub, ctx context.Context) error {
	if err := s.authorEdit.ReplaceAssetAuthors(ctx, "a", nil, nil); err != nil {
		return err
	}
	fn := s.conn.Begin // 值引用不是调用：不构成本地事务边界
	_ = fn
	return s.doWork(ctx)
}
`
	fset := token.NewFileSet()
	if violations := txBoundaryViolations(t, fset, "fixture.go", fixture); len(violations) != 0 {
		t.Fatalf("反例不应命中任何事务调用：%v", violations)
	}
}
