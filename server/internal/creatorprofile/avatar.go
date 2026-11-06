package creatorprofile

import "encoding/base64"

// DecodeAvatar校验备份中的标准Base64与解码后图片预算。
func DecodeAvatar(encoded string) ([]byte, error) {
	if len(encoded) > base64.StdEncoding.EncodedLen(maxAvatarBytes) {
		return nil, ErrInvalid
	}
	data, err := base64.StdEncoding.Strict().DecodeString(encoded)
	if err != nil {
		return nil, ErrInvalid
	}
	if _, err = AvatarType(data); err != nil {
		return nil, err
	}
	return data, nil
}
