package main

import (
	"fmt"
	"time"
)

// Android's Go runtime defaults time.Local to UTC even when the phone uses
// another zone. Apply the launcher-provided location before starting workers.
func configureLocalTimeZone(name string) error {
	if name == "" {
		return nil
	}
	location, err := time.LoadLocation(name)
	if err != nil {
		return fmt.Errorf("time_zone %q: %w", name, err)
	}
	time.Local = location
	return nil
}
