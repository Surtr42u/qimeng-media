package gen

// 生成文件不入库；保留其运行时包的依赖边，使未生成代码时的 go mod tidy
// （包括 Dependabot）仍保留 runtime 根包所需的间接依赖。这里不实现协议或 SDK。
import _ "github.com/oapi-codegen/runtime"
