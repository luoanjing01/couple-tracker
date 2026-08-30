package com.coupletracker.android.data.model

import com.google.gson.annotations.SerializedName

// ============== 用户/登录 ==============
data class LoginRequest(
    val username: String,
    val password: String
)

data class RegisterRequest(
    val username: String,
    val password: String,
    val nickname: String,              // 昵称（地图显示）
    val gender: String = "unknown",
    val avatar: String = ""            // 头像（emoji或URL均可）
)

data class UserInfo(
    val id: Int,
    val username: String,
    val nickname: String,              // 对应后端 nickname
    val avatar: String,                // 头像
    val gender: String,
    @SerializedName("coupleCode")      // 后端直接返回 coupleCode（驼峰）
    val coupleCode: String?,
    @SerializedName("createdAt")
    val createdAt: String? = null
) {
    // 方便旧代码读取
    val displayName: String get() = nickname
    val color: String get() = if (gender == "female") "#FF6B9D" else "#4C9AFF"
    @SerializedName("partner_id")
    val partnerId: Int? = null
}

data class CoupleInfo(
    val user: UserInfo?,
    val partner: UserInfo?
)

data class PairRequest(val code: String)

// ============== 位置上报 ==============
data class LocationRequest(
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    val accuracy: Float? = null,
    @SerializedName("isMoving")
    val isMoving: Boolean? = null,
    val speed: Float? = null,
    val batteryLevel: Int? = null,
    val batteryCharging: Boolean? = null
)

// ============== APP使用上报 ==============
/** 切换前台应用（app-usage/foreground） */
data class ForegroundAppRequest(
    val packageName: String,
    val appName: String,
    val appCategory: String = "other"
)
