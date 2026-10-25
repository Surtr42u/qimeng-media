package httpapi

import (
	"time"

	"qimeng-media/server/internal/portablebackup"
)

const portableSchemaVersion = portablebackup.SchemaVersion

func millisOf(ts string) (int64, error) {
	at, err := time.Parse(time.RFC3339Nano, ts)
	return at.UnixMilli(), err
}
