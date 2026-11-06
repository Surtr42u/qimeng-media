package main

import (
	"testing"
	"time"
)

func TestConfigureLocalTimeZone(t *testing.T) {
	previous := time.Local
	t.Cleanup(func() { time.Local = previous })
	if err := configureLocalTimeZone("Asia/Shanghai"); err != nil {
		t.Fatal(err)
	}
	at := time.Date(2026, 11, 5, 20, 0, 0, 0, time.UTC).In(time.Local)
	if at.Day() != 6 || at.Hour() != 4 {
		t.Fatalf("local day boundary is wrong: %s", at)
	}
	configured := time.Local
	if err := configureLocalTimeZone(""); err != nil || time.Local != configured {
		t.Fatal("empty setting changed the host location")
	}
	if err := configureLocalTimeZone("invalid/synthetic"); err == nil || time.Local != configured {
		t.Fatal("invalid location was accepted or changed the current location")
	}
}
