package mediawrite

import (
	"bytes"
	"errors"
	"testing"
)

func TestUploadSpaceRecheckedBeforeFurtherWrites(t *testing.T) {
	var destination bytes.Buffer
	checks := 0
	full := errors.New("synthetic storage full")
	writer := uploadSpaceWriter{destination: &destination, check: func() error {
		checks++
		if checks > 1 {
			return full
		}
		return nil
	}}
	if _, err := writer.Write([]byte("first")); err != nil {
		t.Fatal(err)
	}
	writer.unchecked = uploadSpaceCheckEvery
	if n, err := writer.Write([]byte("second")); n != 0 || !errors.Is(err, full) {
		t.Fatalf("storage exhaustion must stop writes: n=%d err=%v", n, err)
	}
	if destination.String() != "first" {
		t.Fatal(destination.String())
	}
}
