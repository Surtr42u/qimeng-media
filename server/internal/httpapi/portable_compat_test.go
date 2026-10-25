package httpapi

import (
	"qimeng-media/server/internal/portablebackup"
	"time"
)

const portableSchemaVersion = portablebackup.SchemaVersion
const legacyImportSession = portablebackup.ImportSession
const settingKeyLegacyImportBatch = portablebackup.ImportBatchKey

func millisOf(ts string) (int64, error) {
	at, err := time.Parse(time.RFC3339Nano, ts)
	return at.UnixMilli(), err
}
