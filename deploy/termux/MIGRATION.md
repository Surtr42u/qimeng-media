# 旧项目（绮梦影库）数据迁移指引——qimeng_backup.json → 新服务端

> 适用场景：把旧绮梦影库 App 的行为数据（浏览/点赞/收藏/作者/标签/时间轴/推荐偏好）
> 迁进本系统（M6 单机形态或 PC/NAS 形态均可，端点相同）。媒体文件本身**不迁移**——
> 手机上原地不动，注册为库即可（见第 3 步）。
>
> 端点行为已按 DOMAIN_RULES §10 用虚构备份全量演练验证（129 项断言，2026-09-13）。

## 0. 前置确认

- [ ] 新服务端已启动并能打开（Termux 形态见同目录 README.md；PC 形态直接可用）
- [ ] 旧 App 里导出备份：旧绮梦影库「设置 → 导出备份」得到 `qimeng_backup.json`
- [ ] 备份文件已传到能访问服务端的设备上（PC 或手机本机均可）

## 1. 注册媒体库（媒体原地不动）

旧 App 的媒体文件留在手机存储原目录（例如 `/storage/emulated/0/Download/媒体/`）。
在新系统里把这个目录注册为库并扫描：

- Web 界面：登录 → 媒体库 → 注册库 → 填根路径（Termux 形态下即
  `~/storage/shared/...` 对应的路径）→ 扫描。
- 旧「COS 作品」目录：注册时选择 **COS 类型**（作品/角色聚合依赖它）——
  旧备份的 cosWorks 段不迁移（作品是目录派生信息），文件与作者关联由
  COS 库重新扫描自动重建。

## 2. 导入备份

在能访问服务端的终端执行（`<地址>` 换成实际地址：Termux 本机
`http://127.0.0.1:18430`；PC 形态 `http://127.0.0.1:8420`）：

```bash
# 先登录拿 token（密码 = 服务端初始化时设置的密码）
curl -s -X POST <地址>/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"password":"你的密码"}'
# 响应里的 token 用于下面两步
curl -s -X POST <地址>/api/v1/import/qimeng-backup \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json' \
  --data-binary @qimeng_backup.json
```

Web 界面的「数据管理 → 备份导入」入口做同样的事（选文件即可，无需命令行）。

**导入是幂等的**：同一份备份（同 exportedAtMillis）重复导入不会翻倍计数，
导错了可修可重导，不需要清库重来。

## 3. 看懂导入结果（warnings 都是什么意思）

导入响应的 `warnings` 固定包含以下提示，**绝大多数是正常迁移说明不是错误**：

| warning 关键字 | 含义 | 需要你做什么 |
|---|---|---|
| 累计点赞次数无法逐日还原 | 旧备份只有"最后点赞日"，无逐日明细 | 无需处理（热度按现有行数计） |
| 关注 authorId 未匹配 | 备份里关注了 authors 段没有的作者 | 可忽略（条目级遗留） |
| scanSources 不导入 | 旧 SAF 目录概念不存在 | 已在第 1 步重新注册，无需处理 |
| settings/TXT 分片 | 旧 TXT 作者关联不自动迁 | 用过 TXT 导入的：重新走「作者导入 TXT」 |
| albumRules 忽略 | 新架构无对应能力 | 无需处理 |
| cosWorks 不逐条导入 | 作品为 COS 目录派生信息 | 第 1 步注册 COS 库后自动重建 |
| 库目录配置需重新确认 | 提醒核对注册库与旧目录对应 | 按第 4 步核对表抽查即可 |

## 4. 迁移完整性核对表（逐项勾）

导入响应里有计数，对照旧 App 导出前的数字：

- [ ] `mediaFilesTotal` 与 `assetsMatched` 相等，且 ≈ 旧库媒体文件总数
      （有文件已删/改名会少，属正常；差得多 = 媒体目录注册不全）
- [ ] `authorsImported` ≈ 旧库作者总数（cos_ 开头的 COS 作者在内）
- [ ] `tagsImported` ≈ 旧库标签总数
- [ ] `likesImported` / `favoritesImported` ≈ 旧库点赞/收藏文件数
- [ ] `timelineTagsImported` ≈ 旧库时间轴标签数
- [ ] `eventsReplayed` > 0（浏览历史已回放；数字 = 明细+差额+补漏之和，
      不等于旧库浏览总数属正常口径）
- [ ] App 抽查：随便点开几个旧熟文件，浏览计数/收藏/点赞状态与旧 App 一致
- [ ] 推荐偏好：新系统「推荐偏好」页数值与旧 App 设置一致

任何一项对不上：先重导一次（幂等安全）再看；仍异常时把导入响应
JSON 和旧 App 对应数字记下来反馈。

## 5. 迁移之后

- 数据以新系统为准后，旧绮梦影库可以退役（**归档前建议保留备份文件至少一份**）。
- 后续新系统自己的备份走「数据管理 → 备份导出」（GET /api/v1/export/qimeng-backup，
  与旧格式互通，可再导回）。
