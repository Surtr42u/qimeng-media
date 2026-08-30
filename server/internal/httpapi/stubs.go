package httpapi

// M1 未接线的端点统一 501。
//
// 为什么显式列出而不是 404：openapi 已定义全部路径（协议宪法），三端
// SDK 会先生成调用代码；501 + NOT_IMPLEMENTED 让客户端把"功能未到
// 里程碑"与"路径拼错"区分开，调试体验完全不同。每个里程碑接线时把
// 对应方法从这里移走、实现到各自文件。

// 作者体系三端点（GetApiV1Authors / PostApiV1AuthorsImportTxt /
// PutApiV1AuthorsAuthorIdFollow）已接线到 authors.go（M3 作者体系）。
