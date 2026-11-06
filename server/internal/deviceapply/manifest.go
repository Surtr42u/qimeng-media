package deviceapply

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"strings"
)

var targetDeviceIDPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,100}$`)

// parseManifest decodes and validates the manifest, returning the normalized
// struct and the canonical JSON persisted in manifest_json. Unknown fields are
// rejected so a protocol change must bump protocolVersion instead of silently
// dropping data.
func parseManifest(raw []byte) (Manifest, []byte, error) {
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 {
		return Manifest{}, nil, fmt.Errorf("%w: manifest 不能为空", ErrInvalidInput)
	}
	if len(trimmed) > MaxManifestBytes {
		return Manifest{}, nil, fmt.Errorf("%w: manifest 超过 %d 字节", ErrInvalidInput, MaxManifestBytes)
	}
	decoder := json.NewDecoder(bytes.NewReader(trimmed))
	decoder.DisallowUnknownFields()
	var manifest Manifest
	if err := decoder.Decode(&manifest); err != nil {
		return Manifest{}, nil, fmt.Errorf("%w: manifest 不是合法 JSON 或含未知字段", ErrInvalidInput)
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return Manifest{}, nil, fmt.Errorf("%w: manifest 后有多余内容", ErrInvalidInput)
	}

	manifest.TargetDeviceID = strings.TrimSpace(manifest.TargetDeviceID)
	if !ValidTargetDeviceID(manifest.TargetDeviceID) {
		return Manifest{}, nil, fmt.Errorf("%w: targetDeviceId 须为 1–%d 个 ASCII 字符", ErrInvalidInput, MaxTargetDeviceIDRunes)
	}
	if manifest.ProtocolVersion != CurrentProtocolVersion {
		return Manifest{}, nil, fmt.Errorf("%w: 仅支持 protocolVersion=%d", ErrInvalidInput, CurrentProtocolVersion)
	}
	fingerprint, err := normalizeSHA256(manifest.RulesFingerprint)
	if err != nil {
		return Manifest{}, nil, fmt.Errorf("%w: rulesFingerprint 须为 64 位小写十六进制", ErrInvalidInput)
	}
	manifest.RulesFingerprint = fingerprint
	if manifest.TTLSeconds == 0 {
		manifest.TTLSeconds = DefaultTTLSeconds
	}
	if manifest.TTLSeconds < MinTTLSeconds || manifest.TTLSeconds > MaxTTLSeconds {
		return Manifest{}, nil, fmt.Errorf("%w: ttlSeconds 须在 %d–%d", ErrInvalidInput, MinTTLSeconds, MaxTTLSeconds)
	}
	if len(manifest.Segments) == 0 || len(manifest.Segments) > 2 {
		return Manifest{}, nil, fmt.Errorf("%w: segments 须为 1–2 项", ErrInvalidInput)
	}
	seen := make(map[SegmentKind]struct{}, len(manifest.Segments))
	normalized := make([]ManifestSegment, 0, len(manifest.Segments))
	for i := range manifest.Segments {
		segment := manifest.Segments[i]
		if !segment.Kind.Valid() {
			return Manifest{}, nil, fmt.Errorf("%w: 不支持的段类型", ErrInvalidInput)
		}
		if _, ok := seen[segment.Kind]; ok {
			return Manifest{}, nil, fmt.Errorf("%w: 段类型 %s 重复", ErrInvalidInput, segment.Kind)
		}
		seen[segment.Kind] = struct{}{}
		if segment.Bytes < 1 || segment.Bytes > segment.Kind.MaxBytes() {
			return Manifest{}, nil, fmt.Errorf("%w: 段 %s 字节数须为 1–%d", ErrInvalidInput, segment.Kind, segment.Kind.MaxBytes())
		}
		sha, err := normalizeSHA256(segment.SHA256)
		if err != nil {
			return Manifest{}, nil, fmt.Errorf("%w: 段 %s 的 sha256 须为 64 位小写十六进制", ErrInvalidInput, segment.Kind)
		}
		segment.SHA256 = sha
		if len(bytes.TrimSpace(segment.ExpectedReadback)) > 0 {
			readback, err := canonicalJSONObject(segment.ExpectedReadback, MaxExpectedReadbackBytes)
			if err != nil {
				return Manifest{}, nil, fmt.Errorf("%w: 段 %s 的 expectedReadback %s", ErrInvalidInput, segment.Kind, strings.TrimPrefix(err.Error(), ErrInvalidInput.Error()+": "))
			}
			segment.ExpectedReadback = readback
		} else {
			segment.ExpectedReadback = nil
		}
		normalized = append(normalized, segment)
	}
	manifest.Segments = normalized
	encoded, err := json.Marshal(manifest)
	if err != nil {
		return Manifest{}, nil, fmt.Errorf("%w: manifest 无法序列化", ErrInvalidInput)
	}
	return manifest, encoded, nil
}

// ValidTargetDeviceID reports whether the opaque target device label is
// syntactically acceptable. It is not a device identity check.
func ValidTargetDeviceID(value string) bool {
	return targetDeviceIDPattern.MatchString(value)
}

// normalizeSHA256 returns a lower-case 64-char hexadecimal digest or
// ErrInvalidInput.
func normalizeSHA256(value string) (string, error) {
	value = strings.ToLower(strings.TrimSpace(value))
	if len(value) != 64 {
		return "", fmt.Errorf("%w: sha256 须为 64 位十六进制", ErrInvalidInput)
	}
	if _, err := hex.DecodeString(value); err != nil {
		return "", fmt.Errorf("%w: sha256 须为 64 位十六进制", ErrInvalidInput)
	}
	return value, nil
}

// canonicalJSONObject validates a JSON object and re-encodes it with stable
// key order, preserving number literals.
func canonicalJSONObject(raw []byte, maxBytes int) ([]byte, error) {
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 {
		return []byte("{}"), nil
	}
	if len(trimmed) > maxBytes {
		return nil, fmt.Errorf("%w: JSON 对象超过 %d 字节", ErrInvalidInput, maxBytes)
	}
	decoder := json.NewDecoder(bytes.NewReader(trimmed))
	decoder.UseNumber()
	var value any
	if err := decoder.Decode(&value); err != nil {
		return nil, fmt.Errorf("%w: JSON 对象不合法", ErrInvalidInput)
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return nil, fmt.Errorf("%w: JSON 对象后有多余内容", ErrInvalidInput)
	}
	object, ok := value.(map[string]any)
	if !ok {
		return nil, fmt.Errorf("%w: 须为 JSON 对象", ErrInvalidInput)
	}
	out, err := json.Marshal(object)
	if err != nil {
		return nil, fmt.Errorf("%w: JSON 对象不合法", ErrInvalidInput)
	}
	if len(out) > maxBytes {
		return nil, fmt.Errorf("%w: JSON 对象超过 %d 字节", ErrInvalidInput, maxBytes)
	}
	return out, nil
}
