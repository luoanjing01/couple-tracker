package com.coupletracker.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.coupletracker.android.data.UserRepository
import com.coupletracker.android.service.TrackerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机自启 + 应用升级后自启
 * 只有在用户已登录的情况下才拉起前台服务
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED
            || action == Intent.ACTION_LOCKED_BOOT_COMPLETED
            || action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                if (UserRepository.get().isLoggedIn()) {
                    runCatching { TrackerService.start(context) }
                }
            }
        }
    }
}
