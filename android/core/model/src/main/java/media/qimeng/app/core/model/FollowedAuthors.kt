package media.qimeng.app.core.model

/**
 * 关注列表过滤（C4 拍板：`GET /authors` 无 follow 查询参数（openapi 无任何参数），
 * 关注列表 = 客户端按 `followed == true` 过滤——DOMAIN_RULES §6 关注语义）。
 * 取消关注后服务端置 followed=false，下次拉取该行自然消失。
 */
fun List<AuthorSummary>.filterFollowed(): List<AuthorSummary> = filter { it.followed }
