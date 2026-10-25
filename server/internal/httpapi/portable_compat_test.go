package httpapi

import (
	"qimeng-media/server/internal/portablebackup"
	"time"
)

const portableSchemaVersion = portablebackup.SchemaVersion
const legacyImportSession = portablebackup.ImportSession
const settingKeyLegacyImportBatch = portablebackup.ImportBatchKey

// 旧代无事件键夹具必须保留当时正午/逐秒回放布局，不从新服务复制状态。
const replayNoonOffset = 12 * time.Hour
const replayStagger = time.Second

func millisOf(ts string) (int64, error) {
	at, err := time.Parse(time.RFC3339Nano, ts)
	return at.UnixMilli(), err
}
