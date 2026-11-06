package portablebackup

import (
	"qimeng-media/server/internal/creatorprofile"
	"reflect"
)

func profilesConflict(current, incoming creatorprofile.Profile) bool {
	local, source := current.Write, incoming.Write
	local.Revision, source.Revision = 0, 0
	local.AutoRefresh, source.AutoRefresh = false, false
	local.TrackUpdates, source.TrackUpdates = false, false
	if canFillXProfile(current, incoming) {
		local.XProfile = source.XProfile
	}
	if canFillXTranslation(current, incoming) {
		x := *local.XProfile
		x.BiographyZh = source.XProfile.BiographyZh
		local.XProfile = &x
	}
	return !reflect.DeepEqual(local, source)
}

// 已有人工资料只补尚未保存的X原文，且账号须与本端已核实来源对应。
func canFillXProfile(current, incoming creatorprofile.Profile) bool {
	if current.XProfile != nil || incoming.XProfile == nil || current.Status != "verified" || incoming.Status != "verified" {
		return false
	}
	handle := creatorprofile.XHandle(incoming.XProfile.URL)
	if handle == "" {
		return false
	}
	for _, link := range append([]string{current.HomepageURL}, current.EvidenceURLs...) {
		if creatorprofile.XHandle(link) == handle {
			return true
		}
	}
	return false
}

// 译文只补同一账号、同一原文的缺省字段，人工空字符串也视为已编辑。
func canFillXTranslation(current, incoming creatorprofile.Profile) bool {
	local, source := current.XProfile, incoming.XProfile
	return current.Status == "verified" && incoming.Status == "verified" && local != nil && source != nil &&
		local.BiographyZh == nil && source.BiographyZh != nil && *source.BiographyZh != "" &&
		creatorprofile.XHandle(local.URL) != "" && creatorprofile.XHandle(local.URL) == creatorprofile.XHandle(source.URL) &&
		local.Biography == source.Biography
}
