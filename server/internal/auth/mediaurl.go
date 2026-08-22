package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"strconv"
	"time"
)

// 签名直链协议（/media/** 专用，稳定协议，改动即三端全炸）：
//
//	message = path + "\n" + 十进制 Unix 秒(exp)
//	sig     = hex(HMAC-SHA256(secret, message))
//
// 约定：
//   - path 必须与 URL 里出现的路径形态逐字节一致（含前导 "/"），
//     编码/归一化差异会导致签名不匹配——由 handler 保证两侧同一形态；
//   - exp 为 Unix 秒；exp 时刻本身有效，now > exp 才算过期；
//   - secret 为服务端直链密钥；换 secret 等于吊销全部存量直链
//     （SECURITY §"token 泄露应急"的落地手段之一）。
//
// 用 "\n" 分隔而不是直接拼接：路径里可含任意字节，定界符保证
// ("a\n1"+"2") 与 ("a\n"+"12") 之类的拼接歧义不可能出现。
func SignMediaURL(path string, expiresAt time.Time, secret []byte) (exp int64, sig string) {
	exp = expiresAt.Unix()
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(path))
	mac.Write([]byte("\n"))
	mac.Write([]byte(strconv.FormatInt(exp, 10)))
	return exp, hex.EncodeToString(mac.Sum(nil))
}

// 签名校验错误。分两类而不是笼统一个 error：handler 需要区分语义
// （都已映射为 403，但日志/响应文案不同；SECURITY 安全测试用例 2）。
var (
	// ErrSignatureInvalid 签名与内容不匹配（篡改 path、伪造 sig、密钥不符）。
	ErrSignatureInvalid = errors.New("媒体直链签名不匹配")
	// ErrSignatureExpired 直链已过有效期。
	ErrSignatureExpired = errors.New("媒体直链已过期")
)

// VerifyMediaSignature 校验 /media/** 直链签名。
//
// now 为注入时钟（测试传固定时钟，不依赖真实时间/sleep）。
// 校验顺序刻意为先签名后过期：签名不合法的数据不进入后续判断，
// 避免攻击者用无效签名探测服务端的时间处理逻辑。
func VerifyMediaSignature(path string, exp int64, sig string, secret []byte, now func() time.Time) error {
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(path))
	mac.Write([]byte("\n"))
	mac.Write([]byte(strconv.FormatInt(exp, 10)))
	expected := hex.EncodeToString(mac.Sum(nil))
	if subtle.ConstantTimeCompare([]byte(sig), []byte(expected)) != 1 {
		return ErrSignatureInvalid
	}
	if now().Unix() > exp {
		return ErrSignatureExpired
	}
	return nil
}
