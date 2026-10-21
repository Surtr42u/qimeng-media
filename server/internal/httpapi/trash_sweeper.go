// 回收站定时任务的组合根接线；业务和配置口径归 mediawrite。
package httpapi

import "context"

func (s *Server) StartTrashSweeper(ctx context.Context) { s.writes.StartTrashSweeper(ctx) }
func (s *Server) sweepTrashOnce()                       { s.writes.SweepTrashOnce() }
