package httpapi

import "context"

// StartLocalSyncRunner 是存量启动适配；轮询和业务均由 syncchannel 持有。
func (s *Server) StartLocalSyncRunner(ctx context.Context) { s.sync.Start(ctx) }
