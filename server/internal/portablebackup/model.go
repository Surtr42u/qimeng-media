// Package portablebackup 编排便携 v1/v2 文件格式、身份合并与分段导入/快照导出。
// 文件 DTO 与 OpenAPI 对应 schema 同步，由 HTTP 边界契约测试比较字段；不依赖 gen。
package portablebackup

type AppPrefs struct {
	RecommendationPrefs  *RecommendPrefs        `json:"recommendationPrefs,omitempty"`
	Version              *int                   `json:"version,omitempty"`
	AdditionalProperties map[string]interface{} `json:"-"`
}

type Author struct {
	AuthorId        string `json:"authorId"`
	CreatedAtMillis *int64 `json:"createdAtMillis,omitempty"`
	DisplayName     string `json:"displayName"`

	Origin          *string `json:"origin,omitempty"`
	UpdatedAtMillis *int64  `json:"updatedAtMillis,omitempty"`
}

type AuthorMediaRef struct {
	AuthorId string `json:"authorId"`

	CreatedAtMillis *int64 `json:"createdAtMillis,omitempty"`
	FileName        string `json:"fileName"`

	IsMatched *bool `json:"isMatched,omitempty"`

	Origin    *string `json:"origin,omitempty"`
	RecordKey string  `json:"recordKey"`
}

type BackupData struct {
	AlbumRules *[]map[string]interface{} `json:"albumRules,omitempty"`

	AppPrefs        *AppPrefs         `json:"appPrefs,omitempty"`
	AuthorMediaRefs *[]AuthorMediaRef `json:"authorMediaRefs,omitempty"`
	Authors         *[]Author         `json:"authors,omitempty"`
	CosWorks        *[]CosWork        `json:"cosWorks,omitempty"`
	DailyBrowse     *[]DailyBrowse    `json:"dailyBrowse,omitempty"`

	Favorites *[]string `json:"favorites,omitempty"`

	FollowedAuthorIds *[]string       `json:"followedAuthorIds,omitempty"`
	History           *[]HistoryEntry `json:"history,omitempty"`
	Likes             *[]Like         `json:"likes,omitempty"`
	MediaFiles        *[]MediaFile    `json:"mediaFiles,omitempty"`
	MediaStats        *[]MediaStats   `json:"mediaStats,omitempty"`
	MediaTagRefs      *[]MediaTagRef  `json:"mediaTagRefs,omitempty"`
	ScanSources       *[]ScanSource   `json:"scanSources,omitempty"`
	Settings          *[]Setting      `json:"settings,omitempty"`
	Tags              *[]Tag          `json:"tags,omitempty"`
	TimelineTags      *[]TimelineTag  `json:"timelineTags,omitempty"`

	TxtFragments *[]TxtFragment `json:"txtFragments,omitempty"`

	ViewEvents *[]ViewEvent `json:"viewEvents,omitempty"`
}

type BackupFile struct {
	AppIdentifier string     `json:"appIdentifier"`
	Data          BackupData `json:"data"`

	ExportedAtMillis *int64 `json:"exportedAtMillis,omitempty"`

	Format string `json:"format"`

	SchemaVersion int `json:"schemaVersion"`
}

type BackupImport struct {
	AppIdentifier string     `json:"appIdentifier"`
	Data          BackupData `json:"data"`

	ExportedAtMillis *int64 `json:"exportedAtMillis,omitempty"`

	Format        string `json:"format"`
	SchemaVersion int    `json:"schemaVersion"`
}

type CosWork struct {
	AuthorName      string  `json:"authorName"`
	FileCount       *int    `json:"fileCount,omitempty"`
	FolderUri       *string `json:"folderUri,omitempty"`
	IndexedAtMillis *int64  `json:"indexedAtMillis,omitempty"`
	SourceUri       *string `json:"sourceUri,omitempty"`
	WorkName        string  `json:"workName"`
}

type DailyBrowse struct {
	DayStartMillis     int64                `json:"dayStartMillis"`
	FileName           string               `json:"fileName"`
	MediaType          DailyBrowseMediaType `json:"mediaType"`
	PlayCount          *int                 `json:"playCount,omitempty"`
	RecordKey          string               `json:"recordKey"`
	TotalBrowseSeconds *int64               `json:"totalBrowseSeconds,omitempty"`
	UpdatedAtMillis    *int64               `json:"updatedAtMillis,omitempty"`
	ViewCount          *int                 `json:"viewCount,omitempty"`
}

type DailyBrowseMediaType string

type HistoryEntry struct {
	FileName       string                `json:"fileName"`
	MediaType      HistoryEntryMediaType `json:"mediaType"`
	OpenedAtMillis int64                 `json:"openedAtMillis"`
	RecordKey      string                `json:"recordKey"`
}

type HistoryEntryMediaType string

type ImportResult struct {
	AssetsMatched *int `json:"assetsMatched,omitempty"`

	AssetsTagsReplaced *int `json:"assetsTagsReplaced,omitempty"`

	AuthorRefsImported *int `json:"authorRefsImported,omitempty"`

	AuthorRefsSkipped *int `json:"authorRefsSkipped,omitempty"`
	AuthorsImported   *int `json:"authorsImported,omitempty"`

	EventsReplayed        *int `json:"eventsReplayed,omitempty"`
	FavoritesImported     *int `json:"favoritesImported,omitempty"`
	FollowedAuthorsMarked *int `json:"followedAuthorsMarked,omitempty"`
	LikesImported         *int `json:"likesImported,omitempty"`

	MediaFilesTotal *int `json:"mediaFilesTotal,omitempty"`

	PrefsImported        *bool `json:"prefsImported,omitempty"`
	TagRefsImported      *int  `json:"tagRefsImported,omitempty"`
	TagRefsSkipped       *int  `json:"tagRefsSkipped,omitempty"`
	TagsImported         *int  `json:"tagsImported,omitempty"`
	TimelineTagsImported *int  `json:"timelineTagsImported,omitempty"`

	TxtFragmentsImported *int `json:"txtFragmentsImported,omitempty"`

	TxtFragmentsSkipped *int `json:"txtFragmentsSkipped,omitempty"`

	Warnings *[]string `json:"warnings,omitempty"`
}

type Like struct {
	LastLikeDate *string `json:"lastLikeDate,omitempty"`

	LikeCount *int   `json:"likeCount,omitempty"`
	RecordKey string `json:"recordKey"`
}

type MediaFile struct {
	AssetId         *string `json:"assetId,omitempty"`
	DisplayName     *string `json:"displayName,omitempty"`
	DurationMillis  *int64  `json:"durationMillis,omitempty"`
	Extension       *string `json:"extension,omitempty"`
	FileName        string  `json:"fileName"`
	FolderName      *string `json:"folderName,omitempty"`
	Height          *int    `json:"height,omitempty"`
	IndexedAtMillis *int64  `json:"indexedAtMillis,omitempty"`
	IsCosFile       *bool   `json:"isCosFile,omitempty"`
	IsDuplicateName *bool   `json:"isDuplicateName,omitempty"`

	LibraryName      *string            `json:"libraryName,omitempty"`
	MediaType        MediaFileMediaType `json:"mediaType"`
	ModifiedAtMillis int64              `json:"modifiedAtMillis"`
	PathHash         *string            `json:"pathHash,omitempty"`

	RecordKey string `json:"recordKey"`

	RelPath   *string `json:"relPath,omitempty"`
	SizeBytes int64   `json:"sizeBytes"`
	SourceUri *string `json:"sourceUri,omitempty"`

	TagsUpdatedAtMillis *int64  `json:"tagsUpdatedAtMillis,omitempty"`
	UriString           *string `json:"uriString,omitempty"`
	Width               *int    `json:"width,omitempty"`
}

type MediaFileMediaType string

type MediaStats struct {
	FileName           string `json:"fileName"`
	LastOpenedAtMillis *int64 `json:"lastOpenedAtMillis,omitempty"`
	PlayCount          *int   `json:"playCount,omitempty"`
	RecordKey          string `json:"recordKey"`
	TotalBrowseSeconds *int64 `json:"totalBrowseSeconds,omitempty"`
	UpdatedAtMillis    *int64 `json:"updatedAtMillis,omitempty"`
	ViewCount          *int   `json:"viewCount,omitempty"`
}

type MediaTagRef struct {
	CreatedAtMillis *int64 `json:"createdAtMillis,omitempty"`

	Origin    *string `json:"origin,omitempty"`
	RecordKey string  `json:"recordKey"`

	TagName string `json:"tagName"`
}

type ScanSource struct {
	AddedAtMillis     *int64 `json:"addedAtMillis,omitempty"`
	DisplayName       string `json:"displayName"`
	IsBackupDirectory *bool  `json:"isBackupDirectory,omitempty"`

	IsCosDirectory      *bool  `json:"isCosDirectory,omitempty"`
	LastScannedAtMillis *int64 `json:"lastScannedAtMillis,omitempty"`

	UriString string `json:"uriString"`
}

type Setting struct {
	Key             string `json:"key"`
	UpdatedAtMillis *int64 `json:"updatedAtMillis,omitempty"`
	Value           string `json:"value"`
}

type Tag struct {
	CreatedAtMillis *int64 `json:"createdAtMillis,omitempty"`
	Name            string `json:"name"`

	TagId *int64 `json:"tagId,omitempty"`
}

type TimelineTag struct {
	CreatedAtMillis *int64 `json:"createdAtMillis,omitempty"`
	FileName        string `json:"fileName"`
	Name            string `json:"name"`
	RecordKey       string `json:"recordKey"`

	TimeMillis int64 `json:"timeMillis"`
}

type TxtFragment struct {
	Content string `json:"content"`

	Filename string `json:"filename"`

	ImportedAtMillis int64 `json:"importedAtMillis"`
}

type ViewEvent struct {
	Day *string `json:"day,omitempty"`

	EventId string `json:"eventId"`

	Kind      string `json:"kind"`
	RecordKey string `json:"recordKey"`
	Seconds   *int64 `json:"seconds"`

	SessionId string `json:"sessionId"`

	StartedAt string `json:"startedAt"`
}

type RecommendPrefs struct {
	BrowseDepth   *float64 `json:"browseDepth,omitempty"`
	Discovery     *float64 `json:"discovery,omitempty"`
	Engagement    *float64 `json:"engagement,omitempty"`
	Freshness     *float64 `json:"freshness,omitempty"`
	LikeScore     *float64 `json:"likeScore,omitempty"`
	MaxRandom     *float64 `json:"maxRandom,omitempty"`
	Recency       *float64 `json:"recency,omitempty"`
	TagCollection *float64 `json:"tagCollection,omitempty"`
	TagRelevance  *float64 `json:"tagRelevance,omitempty"`
}
