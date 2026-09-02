package com.dnsoverride.app

import android.os.Handler
import android.os.Looper
import com.dnsoverride.app.service.DnsInterceptor
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 进程级查询日志缓冲：DnsVpnService 写入，UI（StatsFragment）订阅读取。
 *
 * 解决 Fragment 切换时日志丢失问题：日志存在进程级单例中，
 * Fragment 任意时刻订阅都能拿到完整历史。
 *
 * 线程模型：
 * - 写入方是 VPN 的 worker 线程（每条查询一次），因此所有修改都在锁内完成；
 * - 通知一律派发到主线程 —— 监听者会直接操作 RecyclerView / Adapter，
 *   在后台线程回调会触发 `IllegalStateException`。
 * - 对外暴露的是一次生成的不可变快照，避免每次读取都复制整个列表。
 */
object LogBuffer {

    private const val MAX_SIZE = 200

    private val lock = Any()

    /** 不可变快照，写入时整体替换，读取零拷贝。 */
    @Volatile
    private var snapshot: List<DnsInterceptor.QueryLog> = emptyList()

    val logs: List<DnsInterceptor.QueryLog> get() = snapshot

    private val listeners = CopyOnWriteArrayList<(List<DnsInterceptor.QueryLog>) -> Unit>()

    /** 主线程 Handler；单元测试环境无 Looper 时为 null，退化为同步回调。 */
    private val mainHandler: Handler? = runCatching { Handler(Looper.getMainLooper()) }.getOrNull()

    fun add(log: DnsInterceptor.QueryLog) {
        val snap = synchronized(lock) {
            val next = ArrayList<DnsInterceptor.QueryLog>(MAX_SIZE)
            next.add(log)
            // 最新的在前，超过容量丢弃最旧的
            next.addAll(snapshot.take(MAX_SIZE - 1))
            snapshot = next
            next
        }
        notifyListeners(snap)
    }

    fun clear() {
        val snap = synchronized(lock) {
            snapshot = emptyList()
            snapshot
        }
        notifyListeners(snap)
    }

    /** 订阅日志变化。订阅时会立即收到一次当前快照（在主线程投递）。 */
    fun subscribe(listener: (List<DnsInterceptor.QueryLog>) -> Unit) {
        listeners.add(listener)
        notifyListeners(snapshot, target = listener)
    }

    fun unsubscribe(listener: (List<DnsInterceptor.QueryLog>) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(
        snapshot: List<DnsInterceptor.QueryLog>,
        target: ((List<DnsInterceptor.QueryLog>) -> Unit)? = null
    ) {
        val receivers: List<(List<DnsInterceptor.QueryLog>) -> Unit> =
            target?.let { listOf(it) } ?: listeners
        if (receivers.isEmpty()) return

        val deliver = { receivers.forEach { it(snapshot) } }
        val handler = mainHandler
        when {
            handler == null -> deliver()
            Looper.myLooper() == Looper.getMainLooper() -> deliver()
            else -> handler.post(deliver)
        }
    }
}
