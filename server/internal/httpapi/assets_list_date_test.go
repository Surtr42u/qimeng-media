package httpapi

import (
	"database/sql"
	"reflect"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

func TestFileDatePageEligibilityDoesNotDropFilters(t *testing.T) {
	base := newAssetFilters(gen.GetApiV1AssetsParams{}, nil, 0)
	if !canUseFileDatePage(base) {
		t.Fatal("默认日期列表应可走索引分页")
	}
	// 逐字段检查，未来添加筛选也必须拒绝未经支持的窄查询。包括
	// favorite=false 和 directory=''：它们是有效筛选，不能当作未提供。
	allowed := map[string]bool{"LibraryID": true, "IncludeCos": true, "CosOnly": true, "TagMode": true}
	for i := 0; i < reflect.TypeFor[assetFilters]().NumField(); i++ {
		field := reflect.TypeFor[assetFilters]().Field(i)
		if allowed[field.Name] {
			continue
		}
		t.Run(field.Name, func(t *testing.T) {
			filters := base
			value := reflect.ValueOf(&filters).Elem().Field(i)
			switch value.Kind() {
			case reflect.Interface:
				value.Set(reflect.ValueOf("synthetic-filter"))
			case reflect.Int64:
				value.SetInt(1)
			default:
				t.Fatalf("新筛选类型需补验证: %v", value.Kind())
			}
			if canUseFileDatePage(filters) {
				t.Fatalf("%s 有效时不得遗漏筛选", field.Name)
			}
		})
	}
	for _, filters := range []assetFilters{
		{Favorite: sql.NullBool{Bool: false, Valid: true}},
		{Directory: sql.NullString{String: "", Valid: true}},
	} {
		if canUseFileDatePage(filters) {
			t.Fatal("有效空目录/false 布尔值被当作未提供")
		}
	}
}
