package com.coupletracker.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.coupletracker.android.data.NetworkModule
import com.coupletracker.android.data.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 情侣报备 APP - Application 入口
 * 负责：初始化网络模块、用户仓库、前台服务通知渠道
 */
class TrackerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 1. 创建前台服务通知渠道（Android 8.0+）
        createNotificationChannels()
        // 2. 初始化网络模块和用户仓库
        UserRepository.init(this)
        NetworkModule.init()
        // 3. ✅ 主动清洗旧版本残留的假 token（如 "rpc_auth_xxx"）
        //    升级安装时 DataStore 保留旧数据，脏 token 会导致所有请求 401
        runBlocking(Dispatchers.IO) {
            UserRepository.get().sanitizeToken()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channelId = getString(R.string.tracker_channel_id)
            val existing = nm.getNotificationChannel(channelId)
            if (existing == null) {
                val channel = NotificationChannel(
                    channelId,
                    getString(R.string.tracker_channel_name),
                    NotificationManager.IMPORTANCE_LOW  // 低重要性，不弹出
                ).apply {
                    description = getString(R.string.tracker_channel_desc)
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        lateinit var instance: TrackerApp
            private set
    }
}
