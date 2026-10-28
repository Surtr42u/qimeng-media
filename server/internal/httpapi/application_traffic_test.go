package httpapi

import (
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestApplicationTrafficConsumedBodyAndStreaming(t *testing.T) {
	var traffic applicationTraffic
	bottom := httptest.NewRecorder()
	traffic.wrap(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body := make([]byte, 3)
		if _, err := io.ReadFull(r.Body, body); err != nil {
			t.Fatal(err)
		}
		if _, err := w.Write([]byte("hello")); err != nil {
			t.Fatal(err)
		}
		w.(http.Flusher).Flush()
		if traffic.tx.Load() != 5 {
			t.Fatal("streaming bytes must be visible before handler returns")
		}
	})).ServeHTTP(bottom, httptest.NewRequest(http.MethodPost, "/synthetic", strings.NewReader("123456789")))
	if traffic.rx.Load() != 3 || traffic.tx.Load() != 5 || !bottom.Flushed {
		t.Fatalf("rx=%d tx=%d flushed=%v", traffic.rx.Load(), traffic.tx.Load(), bottom.Flushed)
	}
}

func TestApplicationTrafficRangeAndHead(t *testing.T) {
	for _, method := range []string{http.MethodGet, http.MethodHead} {
		t.Run(method, func(t *testing.T) {
			var traffic applicationTraffic
			request := httptest.NewRequest(method, "/media/synthetic", nil)
			request.Header.Set("Range", "bytes=2-4")
			bottom := httptest.NewRecorder()
			traffic.wrap(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				http.ServeContent(w, r, "synthetic.txt", time.Time{}, strings.NewReader("0123456789"))
			})).ServeHTTP(bottom, request)
			want := int64(3)
			if method == http.MethodHead {
				want = 0
			}
			if bottom.Code != http.StatusPartialContent || traffic.tx.Load() != want {
				t.Fatalf("code=%d tx=%d want=%d", bottom.Code, traffic.tx.Load(), want)
			}
		})
	}
}

func TestApplicationTrafficReadFromDelegatesExactlyOnce(t *testing.T) {
	var traffic applicationTraffic
	bottom := &rfRecorder{ResponseWriter: httptest.NewRecorder()}
	writer := &trafficWriter{ResponseWriter: bottom, bytes: &traffic.tx}
	n, err := writer.ReadFrom(strings.NewReader("payload"))
	if err != nil || !bottom.readFromCalled || n != 7 || traffic.tx.Load() != 7 {
		t.Fatalf("n=%d tx=%d delegated=%v err=%v", n, traffic.tx.Load(), bottom.readFromCalled, err)
	}
	// 没有ReaderFrom时也不能被Write二次计数。
	writer.ResponseWriter = httptest.NewRecorder()
	if _, err := writer.ReadFrom(strings.NewReader("abc")); err != nil {
		t.Fatal(err)
	}
	if traffic.tx.Load() != 10 {
		t.Fatalf("fallback double counted: %d", traffic.tx.Load())
	}
}

type partialTrafficWriter struct{ http.ResponseWriter }

func (w partialTrafficWriter) Write([]byte) (int, error) {
	return 2, errors.New("synthetic disconnect")
}

func TestApplicationTrafficPartialWriteAndConcurrentIsolation(t *testing.T) {
	var traffic, other applicationTraffic
	writer := &trafficWriter{ResponseWriter: partialTrafficWriter{httptest.NewRecorder()}, bytes: &traffic.tx}
	if n, err := writer.Write([]byte("payload")); n != 2 || err == nil || traffic.tx.Load() != 2 {
		t.Fatalf("partial response n=%d tx=%d err=%v", n, traffic.tx.Load(), err)
	}
	var group sync.WaitGroup
	for range 20 {
		group.Go(func() {
			traffic.wrap(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if _, err := io.Copy(io.Discard, r.Body); err != nil {
					t.Error(err)
				}
				if _, err := w.Write([]byte("ab")); err != nil {
					t.Error(err)
				}
			})).ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodPost, "/synthetic", strings.NewReader("abc")))
		})
	}
	group.Wait()
	if traffic.rx.Load() != 60 || traffic.tx.Load() != 42 || other.rx.Load() != 0 || other.tx.Load() != 0 {
		t.Fatalf("rx=%d tx=%d; instances must remain independent", traffic.rx.Load(), traffic.tx.Load())
	}
}
