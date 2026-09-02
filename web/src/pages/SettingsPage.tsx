import { useState } from 'react'

/**
 * 设置页（原型 #page-settings 移植）。
 * 阶段 A 纯 mock：表单项为非受控默认值，保存只显示提示、不写真实配置
 * （原型语义：原型不写真实配置，接真实数据走阶段 B）。
 */
export default function SettingsPage() {
  const [saved, setSaved] = useState(false)

  return (
    <div className="page" id="page-settings">
      <div className="settings-card">
        <h3>扫描</h3>
        <p>媒体库扫描与缩略图生成</p>
        <div className="settings-grid">
          <label className="settings-field">
            <span>扫描并发数</span>
            <input type="number" defaultValue={2} min={1} max={4} />
            <small>1–4，超出可能加重 NAS 负载</small>
          </label>
          <label className="settings-field">
            <span>缩略图长边</span>
            <input type="number" defaultValue={800} min={200} max={1600} />
            <small>200–1600 px</small>
          </label>
        </div>
      </div>
      <div className="settings-card">
        <h3>上传</h3>
        <p>客户端上传行为约束</p>
        <label className="settings-field settings-single">
          <span>单文件上限</span>
          <input type="number" defaultValue={2048} min={64} max={8192} />
          <small>64–8192 MB</small>
        </label>
        <label className="settings-switch">
          <input type="checkbox" defaultChecked />
          <i aria-hidden="true" />
          <span>自动接收上传</span>
        </label>
      </div>
      <div className="settings-card">
        <h3>界面</h3>
        <p>外观与主题</p>
        <div className="settings-muted">
          <span>主题模式</span>
          <span>跟随系统（深色由侧栏月亮按钮切换）</span>
        </div>
      </div>
      <div className="settings-actions">
        <button className="save-btn" type="button" onClick={() => setSaved(true)}>
          保存设置
        </button>
        {saved ? <span className="save-tip">已保存（原型演示，未真实写入）</span> : null}
      </div>
    </div>
  )
}
