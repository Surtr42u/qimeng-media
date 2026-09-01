-- 0006_playback.down：回滚 0006（逆序 DROP 新增列）。
-- last_position_seconds 是可再生的播放器状态（客户端重播即重建），
-- codec 列由扫描重探回填——丢弃均无不可恢复数据。
ALTER TABLE assets DROP COLUMN audio_codec;
ALTER TABLE assets DROP COLUMN video_codec;
ALTER TABLE assets DROP COLUMN last_position_seconds;
