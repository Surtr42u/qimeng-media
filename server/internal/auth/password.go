package auth

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"strconv"
	"strings"

	"golang.org/x/crypto/argon2"
)

// argon2id 参数：docs/SECURITY.md「鉴权设计」要求密码哈希用 argon2id，
// t=1 / m=64MB / p=4 / keyLen=32 为任务基线值（内存档位按 NAS 实际内存折中，
// 威胁模型是内网单用户，非公网爆破防护）。盐 16 字节、输出 32 字节取 PHC
// 规范（C2SP/phc-strings）默认值。
const (
	argonTime    uint32 = 1         // t：迭代次数
	argonMemory  uint32 = 64 * 1024 // m：内存，KiB（64MB）
	argonThreads uint8  = 4         // p：并行度
	argonKeyLen  uint32 = 32        // 输出哈希长度，字节
	argonSaltLen        = 16        // 盐长度，字节（PHC 规范默认）
	argonVersion        = 19        // argon2 当前版本 0x13

	// 解析外部哈希串时的防御性上限：m 太大会让攻击者用一条畸形 PHC 触发
	// 数 GB 内存分配（DoS），keyLen 同理。我们自己生成的值远小于这两个上限。
	argonMaxMemoryKB uint32 = 1 << 20 // 1GB
	argonMaxKeyLen   uint32 = 64      // PHC 规范输出上限 64 字节
)

// HashPassword 用 argon2id 哈希密码，返回 PHC 格式字符串：
//
//	$argon2id$v=19$m=65536,t=1,p=4$<盐>$<哈希>
//
// 盐与哈希按 PHC 规范用无 padding 的标准 Base64（RawStdEncoding）编码，
// 参数顺序固定为 m,t,p。该格式可被任何标准 argon2 实现（如未来换语言重写
// 的端侧工具）直接解析，不与 Go 库绑定。
func HashPassword(password string) (string, error) {
	salt := make([]byte, argonSaltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", fmt.Errorf("生成密码盐失败: %w", err)
	}
	hash := argon2.IDKey([]byte(password), salt, argonTime, argonMemory, argonThreads, argonKeyLen)
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argonVersion, argonMemory, argonTime, argonThreads,
		encodeB64(salt), encodeB64(hash)), nil
}

// VerifyPassword 校验密码与库中 PHC 哈希是否匹配。
//
// 语义约定（调用方据此区分"用户输错密码"与"库里的哈希坏了"）：
//   - 密码不匹配 → (false, nil)
//   - 哈希串无法解析（格式/算法/版本/参数非法）→ (false, 非 nil error)
//
// 哈希比较用 crypto/subtle 恒时比较，防时序侧信道。
func VerifyPassword(password, phc string) (bool, error) {
	params, err := parsePHC(phc)
	if err != nil {
		return false, err
	}
	computed := argon2.IDKey([]byte(password), params.salt, params.time, params.memory, params.threads, uint32(len(params.hash)))
	return subtle.ConstantTimeCompare(computed, params.hash) == 1, nil
}

// argonParams 是从 PHC 串解析出的重算哈希所需的全部输入。
type argonParams struct {
	memory  uint32
	time    uint32
	threads uint8
	salt    []byte
	hash    []byte
}

// parsePHC 解析本系统接受的 PHC 严格子集：
// $argon2id$v=19$m=<KiB>,t=<迭代>,p=<并行度>$<盐>$<哈希>。
//
// 只接受 argon2id（argon2d/i 不引入，避免库中出现第二种算法分支）；
// 参数段必须恰好是 m,t,p 三个且按规范顺序（PHC 规范要求参数顺序唯一）；
// keyid/data 等可选参数不使用，出现即拒绝——收紧接受面比宽松解析更安全，
// 且本系统哈希只会由 HashPassword 自己生成。
func parsePHC(phc string) (*argonParams, error) {
	parts := strings.Split(phc, "$")
	// Split 后首元素为空前缀：["", "argon2id", "v=19", "m=..", "盐", "哈希"]
	if len(parts) != 6 {
		return nil, fmt.Errorf("畸形 PHC 哈希：期望 5 个 $ 分隔段，实际 %d 段: %w", len(parts)-1, errMalformedPHC)
	}
	if parts[1] != "argon2id" {
		return nil, fmt.Errorf("%w：不支持的算法 %q（仅接受 argon2id）", errMalformedPHC, parts[1])
	}
	if parts[2] != "v=19" {
		return nil, fmt.Errorf("%w：不支持的版本 %q（仅接受 v=19）", errMalformedPHC, parts[2])
	}
	params, err := parseArgonFields(parts[3])
	if err != nil {
		return nil, err
	}
	salt, err := decodeB64(parts[4], "盐")
	if err != nil {
		return nil, err
	}
	hash, err := decodeB64(parts[5], "哈希")
	if err != nil {
		return nil, err
	}
	// PHC 规范：盐 8..48 字节，哈希 12..64 字节。拒绝越界值，
	// 防止用畸形哈希串驱动超长输出计算。
	if len(salt) < 8 || len(salt) > 48 {
		return nil, fmt.Errorf("%w：盐长度 %d 越界（8..48）", errMalformedPHC, len(salt))
	}
	if len(hash) < 12 || uint32(len(hash)) > argonMaxKeyLen {
		return nil, fmt.Errorf("%w：哈希长度 %d 越界（12..%d）", errMalformedPHC, len(hash), argonMaxKeyLen)
	}
	return &argonParams{salt: salt, hash: hash, memory: params[0], time: params[1], threads: uint8(params[2])}, nil
}

// errMalformedPHC 标记"库中哈希串本身不合法"，供调用方与服务端故障场景区分。
var errMalformedPHC = errors.New("畸形 PHC 哈希")

// IsMalformedPHC 报告 err 是否为 PHC 格式错误（即库数据坏了，而非密码错误）。
func IsMalformedPHC(err error) bool { return errors.Is(err, errMalformedPHC) }

// parseArgonFields 解析参数段 "m=65536,t=1,p=4"，返回 [m, t, p]。
func parseArgonFields(field string) ([3]uint32, error) {
	var out [3]uint32
	kvs := strings.Split(field, ",")
	if len(kvs) != 3 {
		return out, fmt.Errorf("%w：参数段 %q 应恰好含 m,t,p 三项", errMalformedPHC, field)
	}
	for i, name := range []string{"m", "t", "p"} {
		kv := strings.SplitN(kvs[i], "=", 2)
		if len(kv) != 2 || kv[0] != name {
			return out, fmt.Errorf("%w：参数段 %q 第 %d 项应为 %s=<数值>", errMalformedPHC, field, i+1, name)
		}
		v, err := strconv.ParseUint(kv[1], 10, 32)
		if err != nil {
			return out, fmt.Errorf("%w：参数 %s=%q 不是合法数值", errMalformedPHC, name, kv[1])
		}
		if v < 1 {
			return out, fmt.Errorf("%w：参数 %s=%d 必须 ≥1", errMalformedPHC, name, v)
		}
		out[i] = uint32(v)
	}
	if out[0] > argonMaxMemoryKB {
		return out, fmt.Errorf("%w：内存参数 m=%d 超上限 %d", errMalformedPHC, out[0], argonMaxMemoryKB)
	}
	if out[2] > 255 {
		return out, fmt.Errorf("%w：并行度 p=%d 超 argon2 上限 255", errMalformedPHC, out[2])
	}
	return out, nil
}

// encodeB64 是 PHC 规范的 B64：标准字母表、无 padding、不允许空白。
func encodeB64(b []byte) string { return base64.RawStdEncoding.EncodeToString(b) }

func decodeB64(s, what string) ([]byte, error) {
	b, err := base64.RawStdEncoding.DecodeString(s)
	if err != nil {
		return nil, fmt.Errorf("%w：%s 不是合法 B64: %v", errMalformedPHC, what, err)
	}
	return b, nil
}
