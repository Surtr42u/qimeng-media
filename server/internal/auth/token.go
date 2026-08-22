package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
)

// tokenEntropyBytes 是 token 原始熵：32 字节（256 位），hex 编码后 64 字符。
// 256 位随机量让暴力枚举在任何现实时间内都不可行，无需再加长。
const tokenEntropyBytes = 32

// GenerateToken 生成 API token（crypto/rand 32 字节，hex 小写返回）。
//
// 按 docs/SECURITY.md「鉴权设计」：token 只在签发时明文展示一次；
// 库里只存 TokenHash 的结果，任何持久层禁止出现明文 token——
// 这是"重置 token 即吊销一切"应急路径成立的前提。
func GenerateToken() (string, error) {
	buf := make([]byte, tokenEntropyBytes)
	if _, err := rand.Read(buf); err != nil {
		return "", fmt.Errorf("生成 token 失败: %w", err)
	}
	return hex.EncodeToString(buf), nil
}

// TokenHash 返回 token 的 SHA-256（hex 小写）。
// 明文 token 不落库（SECURITY §"token 泄露应急"），比对/存储一律用本哈希。
func TokenHash(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// VerifyTokenHash 恒时比较 token 哈希（crypto/subtle）。
// 时序攻击对哈希比对的意义弱于对 MAC/口令，但恒时比较零成本，
// 统一走 subtle 避免"这里忘了"类失误。
func VerifyTokenHash(provided, stored string) bool {
	return subtle.ConstantTimeCompare([]byte(provided), []byte(stored)) == 1
}
