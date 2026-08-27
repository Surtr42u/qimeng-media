package filing

import (
	"errors"
	"path/filepath"
	"strings"
)

// 上传四道校验哨兵错误（docs/SECURITY.md 红线 #4 / docs/adr/0007）。
// handler 映射建议（对应 openapi Error.code）：
//   - ErrUploadExtension / ErrUploadMimeMismatch / ErrUploadFilename → 400
//   - ErrUploadTooLarge → 413
var (
	// ErrUploadExtension 第①道：扩展名不在白名单。
	ErrUploadExtension = errors.New("filing: 扩展名不在白名单")
	// ErrUploadMimeMismatch 第②道：文件头魔数与扩展名不符（含嗅探不出）。
	ErrUploadMimeMismatch = errors.New("filing: 文件内容与扩展名不符")
	// ErrUploadTooLarge 第③道：超过单文件大小上限 → 413。
	ErrUploadTooLarge = errors.New("filing: 文件超过大小上限")
	// ErrUploadFilename 第④道：文件名清洗不通过（清洗后为空/保留设备名）。
	ErrUploadFilename = errors.New("filing: 文件名不合法")
)

// allowedExtensions 扩展名白名单及其期望 MIME。
//
// 清单依据：docs/SECURITY.md「上传安全」与 docs/DOMAIN_RULES.md §9。两处视频
// 清单有出入（DOMAIN_RULES §9 无 m4v，SECURITY 有），本表取 SECURITY 超集：
// m4v 与 mp4 同为 ISO BMFF 容器（major_brand 可能相同），拒绝 m4v 会让 iPhone
// 拍摄的视频无法直传。m4v 与 mp4 因此映射到同一 MediaType。
var allowedExtensions = map[string]MediaType{
	".jpg":  ImageJPEG,
	".jpeg": ImageJPEG,
	".png":  ImagePNG,
	".gif":  ImageGIF,
	".webp": ImageWEBP,
	".avif": ImageAVIF,
	".mp4":  VideoMP4,
	".m4v":  VideoMP4,
	".mkv":  VideoMatroska,
	".webm": VideoWEBM,
	".mov":  VideoQuickTime,
	".avi":  VideoAVI,
}

// AllowedExtension 判断点扩展名（如 ".jpg"，大小写不敏感）是否在白名单内。
func AllowedExtension(ext string) bool {
	_, ok := allowedExtensions[strings.ToLower(ext)]
	return ok
}

// ValidateUpload 在写盘前执行四道校验，按①→④顺序短路返回，错误可区分
// （errors.Is），供 handler 映射 400/413 与 openapi Error.code。
//
//	① 扩展名白名单（大小写不敏感）；
//	② 魔数嗅探与扩展名交叉验证：SniffMagic(head) 必须成功且等于白名单映射的
//	   MIME。具体规则：.jpg 实为 exe（MZ 头）→ 嗅探不出或类型不符 → 拒；
//	   .mov 改名 .mp4（major_brand=qt）→ 拒——mp4/mov 虽同族容器，但放宽交叉
//	   规则会扩大伪造面，当前从严格；若真实使用中确有合法文件被误拒，再评估
//	   引入兼容组（届时改这里与测试）。head 长度不足导致嗅探不出 → 一律拒
//	   （调用方应至少读 RecommendedHeadBytes 字节；空文件同样在此被拒）；
//	③ size ≤ maxBytes（等于上限放行；maxBytes≤0 时任何非空文件都会被②③拒绝）；
//	④ 文件名清洗通过——注意本道只校验"原名可清洗"，落盘名由调用方使用
//	   SanitizeFilename 的返回值；路径部分的目标目录穿越检查由调用方在 join
//	   目录时走 NormalizeRelPath（四道校验中的"目标路径穿越检查"不在本函数，
//	   因为目录与文件名在调用方是两个输入）。
func ValidateUpload(filename string, size int64, maxBytes int64, head []byte) error {
	want, ok := allowedExtensions[strings.ToLower(filepath.Ext(filename))]
	if !ok {
		return ErrUploadExtension
	}
	got, ok := SniffMagic(head)
	if !ok || got != want {
		return ErrUploadMimeMismatch
	}
	if size > maxBytes {
		return ErrUploadTooLarge
	}
	if _, err := SanitizeFilename(filename); err != nil {
		return ErrUploadFilename
	}
	return nil
}
