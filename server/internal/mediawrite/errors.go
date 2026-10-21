package mediawrite

import (
	"errors"
	"fmt"
)

// FailureKind 是业务失败分类，不包含 HTTP 状态、SDK 类型或协议错误码。
type FailureKind uint8

const (
	InternalFailure FailureKind = iota
	InvalidDirectory
	InvalidLibrary
	ActiveLibraryRequired
	InvalidSessionInput
	LibraryGone
	UploadDisabled
	ReadStreamFailure
	AssetMissing
	MediaFileMissing
	InvalidAssetPath
	InvalidTrashPath
	TrashMissing
	OriginalLibraryGone
	InvalidTrashMeta
	InvalidFilename
	MoveFileConflict
	MoveRecordConflict
	MovePathEscape
)

// Failure 的 Op 用于服务端诊断，Kind 由接口层映射；底层错误不直接外发。
type Failure struct {
	Kind FailureKind
	Op   string
	Err  error
}

func (e *Failure) Error() string {
	if e.Err == nil {
		return e.Op
	}
	return fmt.Sprintf("%s: %v", e.Op, e.Err)
}
func (e *Failure) Unwrap() error { return e.Err }
func fail(kind FailureKind, op string, err error) error {
	return &Failure{Kind: kind, Op: op, Err: err}
}

var (
	ErrTargetEscape       = errors.New("upload target escapes library root")
	ErrSealedSizeMismatch = errors.New("uploads: 分片成品与会话声明大小不符")
)
