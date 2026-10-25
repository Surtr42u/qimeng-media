package portablebackup

import "encoding/json"

// 旧 appPrefs 的开放字段要保留，供“忽略非推荐偏好”的兼容口径使用。
// 不借助 gen 或把整份备份往返编码；仅偏好小对象处理额外字段。
func (p *AppPrefs) UnmarshalJSON(data []byte) error {
	type plain AppPrefs
	if err := json.Unmarshal(data, (*plain)(p)); err != nil {
		return err
	}
	var extra map[string]json.RawMessage
	if err := json.Unmarshal(data, &extra); err != nil {
		return err
	}
	delete(extra, "recommendationPrefs")
	delete(extra, "version")
	p.AdditionalProperties = nil
	if len(extra) > 0 {
		p.AdditionalProperties = make(map[string]interface{}, len(extra))
	}
	for key, raw := range extra {
		var value interface{}
		if err := json.Unmarshal(raw, &value); err != nil {
			return err
		}
		p.AdditionalProperties[key] = value
	}
	return nil
}

func (p AppPrefs) MarshalJSON() ([]byte, error) {
	type plain AppPrefs
	data, err := json.Marshal(plain(p))
	if err != nil {
		return nil, err
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(data, &fields); err != nil {
		return nil, err
	}
	for key, value := range p.AdditionalProperties {
		raw, err := json.Marshal(value)
		if err != nil {
			return nil, err
		}
		fields[key] = raw
	}
	return json.Marshal(fields)
}
