/**
 * 兼容转发层：组件已升格为两端共用的 PlayerControls（player-controls.tsx）。
 * 保留本文件以保证旧引用无缝可用。
 */
export { default } from './player-controls'
export type {
  PlayerControlsCapabilities,
  PlayerControlsProps,
  PlayerControlsProps as NativeControlsProps,
  PlayerMediaInfo,
  PlayerMediaInfo as NativeMediaInfo,
  PlayerSubtitleTrack,
  PlayerSubtitleTrack as NativeSubtitleTrack,
  TrayKind,
} from './player-controls'
