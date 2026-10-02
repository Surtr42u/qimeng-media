// schedule_test.go：调度覆盖值解析的纯函数测试（无 IO）。
// 锁定：合法值透传、坏 JSON 拒绝、越界（interval/retention 各两端）拒绝——
// 覆盖值损坏回落启动配置的语义由 ParseScheduleSetting 的 false 返回承载。
package backup

import "testing"

func TestParseScheduleSettingValid(t *testing.T) {
	s, ok := ParseScheduleSetting(`{"enabled":false,"intervalHours":6,"retention":3}`)
	if !ok {
		t.Fatal("合法值应解析成功")
	}
	if s.Enabled || s.IntervalHours != 6 || s.Retention != 3 {
		t.Fatalf("解析结果 = %+v, 期望 {false 6 3}", s)
	}
}

func TestParseScheduleSettingInvalid(t *testing.T) {
	cases := []struct {
		name string
		raw  string
	}{
		{"坏 JSON", "not-json"},
		{"空串", ``},
		{"intervalHours 越下界", `{"enabled":true,"intervalHours":0,"retention":7}`},
		{"intervalHours 越上界", `{"enabled":true,"intervalHours":8761,"retention":7}`},
		{"retention 越下界", `{"enabled":true,"intervalHours":24,"retention":0}`},
		{"retention 越上界", `{"enabled":true,"intervalHours":24,"retention":366}`},
		{"缺键（零值越界）", `{"enabled":true}`},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if s, ok := ParseScheduleSetting(c.raw); ok {
				t.Fatalf("%q 应拒绝，却得到 %+v", c.raw, s)
			}
		})
	}
}
