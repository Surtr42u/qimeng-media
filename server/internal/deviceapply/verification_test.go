package deviceapply

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"testing"
)

// buildVerificationTask 创建带 expectedState 的结果包并返回任务 ID。
func buildVerificationTask(t *testing.T, svc *Service, expected map[string]int64) (Task, []byte, []byte) {
	t.Helper()
	master := masterBytes(16)
	backup := masterBytes(32)
	manifest := Manifest{
		TargetDeviceID:   "phone-verify",
		ProtocolVersion:  CurrentProtocolVersion,
		RulesFingerprint: testFingerprint,
		ExpectedState:    expected,
		Segments: []ManifestSegment{
			segmentSpec(SegmentMasterTxt, master),
			segmentSpec(SegmentV2Backup, backup),
		},
	}
	raw, err := json.Marshal(manifest)
	if err != nil {
		t.Fatalf("序列化 manifest 失败: %v", err)
	}
	task, err := svc.Create(context.Background(), CreateInput{
		Manifest: raw,
		Segments: map[SegmentKind]io.Reader{
			SegmentMasterTxt: bytes.NewReader(master),
			SegmentV2Backup:  bytes.NewReader(backup),
		},
	})
	if err != nil {
		t.Fatalf("创建结果包失败: %v", err)
	}
	return task, master, backup
}

// TestResultAuthoritativeStateAndVerification 覆盖回传时服务端计算的权威
// 业务状态、expectedState 差异与段摘要核验；幂等重放不重算验证快照。
func TestResultAuthoritativeStateAndVerification(t *testing.T) {
	svc, _ := newTestService(t)
	ctx := context.Background()
	task, master, backup := buildVerificationTask(t, svc, map[string]int64{
		"authors": 0,
		"tags":    0,
		"assets":  1, // 空库实际为 0 -> 期望不一致
	})
	if _, err := svc.Claim(ctx, task.ID); err != nil {
		t.Fatalf("领取失败: %v", err)
	}
	hashes := []LocalHash{
		{Kind: SegmentMasterTxt, SHA256: digest(master)},
		{Kind: SegmentV2Backup, SHA256: digest(backup)},
	}
	finished, err := svc.Result(ctx, task.ID, ResultInput{Status: StatusApplied, DeviceLocalHashes: hashes})
	if err != nil {
		t.Fatalf("回传失败: %v", err)
	}
	verification := finished.Verification
	if verification == nil {
		t.Fatal("回传后缺少 verification")
	}
	if verification.AuthoritativeState["assets"] != 0 ||
		verification.AuthoritativeState["authors"] != 0 ||
		verification.AuthoritativeState["viewEvents"] != 0 {
		t.Fatalf("权威状态异常: %+v", verification.AuthoritativeState)
	}
	if verification.ExpectedStateMatches == nil || *verification.ExpectedStateMatches {
		t.Fatalf("expectedState 应判定不一致: %+v", verification.ExpectedStateMatches)
	}
	diff, ok := verification.ExpectedStateDiff["assets"]
	if !ok || diff.Expected != 1 || diff.Actual != 0 {
		t.Fatalf("差异异常: %+v", verification.ExpectedStateDiff)
	}
	if verification.HashVerified == nil || !*verification.HashVerified {
		t.Fatalf("全段摘要一致应判定通过: %+v", verification.HashVerified)
	}
	finishedAt := *finished.FinishedAt

	replay, err := svc.Result(ctx, task.ID, ResultInput{Status: StatusApplied, DeviceLocalHashes: hashes})
	if err != nil {
		t.Fatalf("相同结果重放应幂等: %v", err)
	}
	if replay.FinishedAt == nil || !replay.FinishedAt.Equal(finishedAt) {
		t.Fatalf("重放改变 finishedAt: %+v", replay.FinishedAt)
	}
	if replay.Verification == nil || replay.Verification.AuthoritativeState["assets"] != 0 {
		t.Fatalf("重放丢失验证快照: %+v", replay.Verification)
	}

	// 摘要不符（且只回传一段）必须判 false。
	badTask, badMaster, _ := buildVerificationTask(t, svc, nil)
	if _, err := svc.Claim(ctx, badTask.ID); err != nil {
		t.Fatalf("领取失败: %v", err)
	}
	bad, err := svc.Result(ctx, badTask.ID, ResultInput{
		Status:            StatusPartial,
		DeviceLocalHashes: []LocalHash{{Kind: SegmentMasterTxt, SHA256: digest(badMaster)}},
	})
	if err != nil {
		t.Fatalf("回传失败: %v", err)
	}
	if bad.Verification == nil || bad.Verification.HashVerified == nil || *bad.Verification.HashVerified {
		t.Fatalf("未回传全部段/摘要不符应判 false: %+v", bad.Verification)
	}
	if bad.Verification.ExpectedStateMatches != nil {
		t.Fatalf("未声明 expectedState 时不应产生匹配结论: %+v", bad.Verification.ExpectedStateMatches)
	}
}

// TestManifestExpectedStateValidation 覆盖 expectedState 的键与取值校验。
func TestManifestExpectedStateValidation(t *testing.T) {
	svc, _ := newTestService(t)
	for name, expected := range map[string]map[string]int64{
		"未知键": {"bogus": 1},
		"负值":  {"authors": -1},
	} {
		manifest := Manifest{
			TargetDeviceID:   "phone-verify",
			ProtocolVersion:  CurrentProtocolVersion,
			RulesFingerprint: testFingerprint,
			ExpectedState:    expected,
			Segments:         []ManifestSegment{segmentSpec(SegmentMasterTxt, masterBytes(8))},
		}
		raw, err := json.Marshal(manifest)
		if err != nil {
			t.Fatalf("%s: 序列化失败: %v", name, err)
		}
		_, err = svc.Create(context.Background(), CreateInput{
			Manifest: raw,
			Segments: map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(masterBytes(8))},
		})
		if !errors.Is(err, ErrInvalidInput) {
			t.Fatalf("%s: err = %v，期望 ErrInvalidInput", name, err)
		}
	}
}
