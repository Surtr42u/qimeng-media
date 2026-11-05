package portablebackup

import "qimeng-media/server/internal/creatorprofile"

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
