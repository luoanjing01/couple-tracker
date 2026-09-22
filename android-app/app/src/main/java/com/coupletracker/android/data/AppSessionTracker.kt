// ============================================================================
// 包声明：本文件属于 data 子包，专门放数据层相关的类
// ============================================================================
package com.coupletracker.android.data

// ----------------------------------------------------------------------------
// 导入区域
// ----------------------------------------------------------------------------

import kotlinx.coroutines.flow.MutableStateFlow // 可变状态流（UI 订阅的"数据源"）
import kotlinx.coroutines.flow.asStateFlow      // 转为只读 StateFlow 暴露给外部

/**
 * ============================================================================
 * UI 层持久状态单例（心情 / 当前 APP / 会话秒数）。
 * ============================================================================
 *
 * 【它要解决什么问题？】
 *
 * BottomNavigation 切 Tab 时，Compose 的 remember / rememberSaveable 会丢失状态
 * → 用 Kotlin object（单例）保存状态，整个 App 生命周期都在。
 *
 * 【为什么用 object？】
 *   Kotlin 的 object = 全局唯一的单例，无需手动 new，类名直接调用方法。
 *
 * 【注意】本类不做任何云端上报——上报统一由后台 AppUsageMonitor 负责
 * （每 15 秒增量 + 切换收尾增量），双通道上报会导致同一时段重复计数。
 * ============================================================================
 */
object AppSessionTracker {

    // ===========================================================================
    // 一、心情状态：用户当前的心情 emoji，UI 可订阅显示
    // ===========================================================================

    // 私有可变 StateFlow，默认值 "😐"（中性表情）
    // 下划线开头是 Kotlin 惯例：表示"可变的内部实现版本"
    private val _mood = MutableStateFlow("😐")

    // 对外暴露只读版本，UI 通过 collect 订阅变化
    val mood = _mood.asStateFlow()

    // 修改心情：UI 调用 setMood("😊") 即可，会自动通知订阅者
    fun setMood(emoji: String) { _mood.value = emoji }


    // ===========================================================================
    // 二、当前 APP 会话状态：记录"当前正在用哪个 APP，从何时开始"
    // ===========================================================================

    // 当前 APP 的会话起始时间（毫秒时间戳），0L 表示尚未开始任何会话
    private var sessionStartAt = 0L

    // 上次上报时间（保留字段，会话起始时同步刷新）
    private var sessionReportAt = 0L   // 上次上报时间

    // 当前 APP 的包名（如 com.tencent.mm）和用户可读名称（如 "微信"）
    // 用 StateFlow 包装，是为了让 UI 自动响应 APP 切换
    private val _currentPkg = MutableStateFlow("")
    private val _currentName = MutableStateFlow("")

    /**
     * 上报状态（UI 调试用）
     * 比如 "✅ 上报成功 · 微信 30s" 或 "⚠️ 上报失败 HTTP 401"
     * UI 可以显示这个字符串让用户看到上报情况
     */
    private val _lastReportStatus = MutableStateFlow("等待上报...")
    val lastReportStatus = _lastReportStatus.asStateFlow()

    // ===========================================================================
    // 三、核心方法：设置当前正在使用的 APP
    // ===========================================================================

    /**
     * 设置当前 APP —— 只负责 UI 显示（当前 APP / 会话起始时间）
     *
     * 【设计意图】
     * - 上报统一由后台 AppUsageMonitor 负责（每 15 秒增量 + 切换收尾增量），
     *   本类不再做任何上报——之前这里切换 APP 时补报完整会话时长，
     *   和后台通道的同一段时间重复计入，导致云端统计一小时超过 60 分钟。
     *
     * @param pkg  APP 包名，例如 "com.tencent.mm"
     * @param name APP 用户可读名称，例如 "微信"
     */
    fun setCurrentApp(pkg: String, name: String) {
        // 包名为空 → 数据无效，直接返回
        if (pkg.isEmpty()) return

        if (pkg != _currentPkg.value) {
            // 切换到新 APP：只更新 UI 层会话状态，不上报（上报归 AppUsageMonitor）
            _currentPkg.value = pkg
            _currentName.value = name
            sessionStartAt = System.currentTimeMillis()
            sessionReportAt = sessionStartAt
        } else {
            // ===================================================================
            // 情况 B：还是同一个 APP —— 只更新可读名称（包名没变）
            // ===================================================================
            _currentName.value = name
        }
        // ❌ 不做任何上报 —— 后台 AppUsageMonitor 的增量上报已完整覆盖
    }

    /**
     * 返回当前 APP 已使用的秒数
     * 如果还没启动会话，返回 0
     * coerceAtLeast(0) 保证不会返回负数（系统时间回拨时可能出现）
     */
    fun sessionSeconds(): Int {
        if (sessionStartAt == 0L) return 0
        return ((System.currentTimeMillis() - sessionStartAt) / 1000).toInt().coerceAtLeast(0)
    }
}

