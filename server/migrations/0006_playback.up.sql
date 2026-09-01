-- 0006_playback.up：播放端协议基座（M4 播放器接入前置，只加不改，ADR-0011）。
--
-- 本文件一文件两节（同批小改动合并一个迁移，编号不跳号）：
--   1) assets.last_position_seconds 断点续播位置（PUT /assets/{id}/progress 上报的"最新状态"）
--   2) assets.video_codec / audio_codec 编码探测列（scanner ffprobe 产出，Web 端直链兼容性判断）
--
-- ── 与 ViewEvent 事件流的分工（ADR-0005 口径不变）──
-- 进度是"最新状态"而非统计事实：每资产只保留最新一个位置值，不进
-- view_events、不参与 playCount（播放计数走 play 事件 + 会话当日去重）。
-- 因此不建独立表、不加事件 kind——直接挂 assets 行（删除资产即连带清理，
-- 无孤儿数据）。"已看完"口径 = last_position_seconds >= duration_ms/1000，
-- 由客户端推导，服务端不算徽标。

-- ── 第 1 节：assets.last_position_seconds ──
-- 秒（REAL：播放器心跳携带小数秒）。NULL = 未播过/图片资产；
-- 心跳上报只 UPDATE 此列，刻意不动 updated_at（进度是播放器状态而非
-- 内容变化，列表新鲜度只依赖 mtime/created_at）。
ALTER TABLE assets
ADD COLUMN last_position_seconds REAL;

-- ── 第 2 节：assets.video_codec / audio_codec ──
-- ffprobe codec_name（如 h264/hevc/av1/aac/flac）；仅 VIDEO 扫描时探测，
-- 探测失败或存量资产未重探（size+mtime 变更检测跳过 ffprobe）= NULL。
-- 消费方：Web 播放器据此判断浏览器直链兼容性（null = 尝试播放，失败再兜底）。
ALTER TABLE assets
ADD COLUMN video_codec TEXT;
ALTER TABLE assets
ADD COLUMN audio_codec TEXT;
