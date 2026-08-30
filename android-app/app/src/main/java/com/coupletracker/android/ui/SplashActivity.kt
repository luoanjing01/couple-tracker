package com.coupletracker.android.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.coupletracker.android.data.UserRepository
import com.coupletracker.android.service.TrackerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 启动屏（纯主题背景，无布局）
 * 继承普通 Activity 以兼容 @android:style/Theme.DeviceDefault.Light.NoActionBar 主题
 * 使用 Handler + runBlocking 替代协程（避免依赖 LifecycleOwner）
 */
class SplashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Handler(Looper.getMainLooper()).postDelayed({
            // DataStore 读 IO；启动页短时间阻塞完全可接受
            val loggedIn = runBlocking(Dispatchers.IO) {
                UserRepository.get().isLoggedIn()
            }
            if (loggedIn) {
                runCatching { TrackerService.start(this@SplashActivity) }
                startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            } else {
                startActivity(Intent(this@SplashActivity, LoginActivity::class.java))
            }
            finish()
        }, SPLASH_DELAY_MS)
    }

    private companion object {
        private const val SPLASH_DELAY_MS = 500L
    }
}
