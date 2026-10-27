// application_traffic.go：只计本服务HTTP正文，不使用全机网卡或持久化私人请求信息。
package httpapi

import (
	"io"
	"net/http"
	"sync/atomic"
)

// applicationTraffic 由单个Server持有，重启归零；Write即时累加，ReadFrom在传输返回时累加。
// 不计算协议头/TLS和其他进程，完整边界见OBSERVABILITY及OpenAPI SystemStatus.app*Bytes。
type applicationTraffic struct {
	rx atomic.Int64
	tx atomic.Int64
}

func (t *applicationTraffic) wrap(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Body != nil {
			r.Body = &trafficBody{ReadCloser: r.Body, bytes: &t.rx}
		}
		writer := &trafficWriter{ResponseWriter: w, bytes: &t.tx, head: r.Method == http.MethodHead}
		// 仅在底层支持时保留Flusher，SSE即时刷新行为不变。
		if flusher, ok := w.(http.Flusher); ok {
			next.ServeHTTP(&trafficFlusher{trafficWriter: writer, Flusher: flusher}, r)
		} else {
			next.ServeHTTP(writer, r)
		}
	})
}

type trafficBody struct {
	io.ReadCloser
	bytes *atomic.Int64
}

func (b *trafficBody) Read(p []byte) (int, error) {
	n, err := b.ReadCloser.Read(p)
	b.bytes.Add(int64(n))
	return n, err
}

type trafficWriter struct {
	http.ResponseWriter
	bytes *atomic.Int64
	head  bool
}

func (w *trafficWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (w *trafficWriter) Write(p []byte) (int, error) {
	n, err := w.ResponseWriter.Write(p)
	if !w.head {
		w.bytes.Add(int64(n))
	}
	return n, err
}

// 保留底层ReaderFrom/sendfile；回退直接写底层，避免Write与ReadFrom重复计数。
func (w *trafficWriter) ReadFrom(src io.Reader) (int64, error) {
	var n int64
	var err error
	if readerFrom, ok := w.ResponseWriter.(io.ReaderFrom); ok {
		n, err = readerFrom.ReadFrom(src)
	} else {
		n, err = io.Copy(w.ResponseWriter, src)
	}
	if !w.head {
		w.bytes.Add(n)
	}
	return n, err
}

type trafficFlusher struct {
	*trafficWriter
	http.Flusher
}
