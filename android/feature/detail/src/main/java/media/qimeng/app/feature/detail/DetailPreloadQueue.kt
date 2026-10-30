package media.qimeng.app.feature.detail

/** 单屏预取队列：只跑一个任务，清单扩充保留在途任务；离屏取消，迟到回调不能续生。 */
internal class DetailPreloadQueue(
    private val start: (target: DetailPreloadTarget, onFinished: () -> Unit) -> (() -> Unit),
) {
    private data class Key(val assetId: String, val url: String, val isVideo: Boolean)
    private fun key(target: DetailPreloadTarget) = Key(target.assetId, target.url, target.isVideo)
    private class Running(val key: Key, var cancel: () -> Unit = {})
    private var targets = emptyList<DetailPreloadTarget>()
    private val finished = mutableSetOf<Key>()
    private var running: Running? = null
    private var enabled = false

    fun update(targets: List<DetailPreloadTarget>, enabled: Boolean) {
        this.targets = targets.distinctBy { it.assetId }
        this.enabled = enabled
        val keys = this.targets.map(::key).toSet()
        finished.retainAll(keys)
        running?.takeIf { !enabled || it.key !in keys }?.let {
            running = null
            it.cancel()
        }
        advance()
    }

    fun clear() = update(emptyList(), false)
    fun pause() = update(targets, false)

    private fun advance() {
        if (!enabled || running != null) return
        val target = targets.firstOrNull { key(it) !in finished } ?: return
        val task = Running(key(target))
        running = task
        task.cancel = start(target) {
            if (running === task) {
                running = null
                // 成功或失败都不因无关重组自动重试；换页/新URL重新准入。
                finished += task.key
                advance()
            }
        }
        // 内存命中可能同步完成；此时仍释放返回的句柄，不能取消后续任务。
        if (running !== task) task.cancel()
    }
}
