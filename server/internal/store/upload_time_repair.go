package store

import (
	"context"
	"database/sql"
	"errors"
)

// UploadTimeRepairRow 是本地维护所需的原始快照，不改变资产身份或关联数据。
type UploadTimeRepairRow struct {
	ID, Root, Library, Rel, Mtime, AddedAt string
	Size                                   int64
	ContentSHA256                          string
}

func UploadTimeRepairRows(ctx context.Context, conn *sql.DB, from, to, authorName string) (result []UploadTimeRepairRow, err error) {
	rows, err := conn.QueryContext(ctx, `SELECT a.asset_id,l.root_path,l.name,a.rel_path,a.mtime,a.created_at,a.size_bytes
 FROM assets a JOIN libraries l ON l.id=a.library_id
 WHERE a.created_at>=? AND a.created_at<? AND
 EXISTS (SELECT 1 FROM asset_authors aa JOIN authors au ON au.id=aa.author_id WHERE aa.asset_id=a.asset_id AND lower(au.display_name)=lower(?))
 ORDER BY a.created_at,a.asset_id LIMIT 33`, from, to, authorName)
	if err != nil {
		return nil, err
	}
	defer func() { err = errors.Join(err, rows.Close()) }()
	for rows.Next() {
		var r UploadTimeRepairRow
		if err := rows.Scan(&r.ID, &r.Root, &r.Library, &r.Rel, &r.Mtime, &r.AddedAt, &r.Size); err != nil {
			return nil, err
		}
		result = append(result, r)
	}
	return result, rows.Err()
}
