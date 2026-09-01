package com.coupletracker.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI 层持久状态单例。
 * Kotlin object 在进程存活期间永远不重建，比 remember / rememberSaveable 都可靠。
 */
object AppSessionTracker {

    // ===== 心情 =====
    private val _mood = MutableStateFlow("😐")
    val mood = _mood.asStateFlow()
    fun setMood(emoji: String) { _mood.value = emoji }

    // ===== 当前 APP 会话 =====
    private var sessionStartAt = 0L
    private val _currentPkg = MutableStateFlow("")
    private val _currentName = MutableStateFlow("")

    fun setCurrentApp(pkg: String, name: String) {
        if (pkg.isNotEmpty() && pkg != _currentPkg.value) {
            _currentPkg.value = pkg
            _currentName.value = name
            sessionStartAt = System.currentTimeMillis()
        } else if (pkg.isNotEmpty()) {
            _currentName.value = name
        }
    }

    fun sessionSeconds(): Int {
        if (sessionStartAt == 0L) return 0
        return ((System.currentTimeMillis() - sessionStartAt) / 1000).toInt().coerceAtLeast(0)
    }
}
