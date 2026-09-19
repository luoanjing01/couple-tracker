/**
 * 数据模型文件 (Models.kt)
 *
 * 本文件定义了情侣定位 App 与后端服务器交互时使用的所有数据模型。
 * 这些数据类（data class）主要用于网络请求的发送与接收，
 * 例如登录、注册、上报位置、上报应用使用情况等。
 *
 * 名词解释（初学者先看这里）：
 * - data class：Kotlin 中专门用来"装数据"的类，
 *   只需声明字段，Kotlin 会自动帮我们生成 equals / hashCode / toString 等方法，
 *   省去手写样板代码的麻烦。
 * - val：只读变量（赋值后不可更改），数据类字段一般都用 val。
 * - 可空类型（String? / Int? 等）：带问号表示该字段可以为 null（空值），
 *   用来表达"暂时没有这个值"的情况。
 * - @SerializedName：Gson 库提供的注解，作用是指定 JSON 字段名
 *   与 Kotlin 字段名的对应关系。当服务器返回的 JSON 键名
 *   和 Kotlin 字段名不一致时，就用它来"翻译"。
 * - 默认值（如 = "unknown"）：调用时如果不传该参数，就使用默认值。
 */
package com.coupletracker.android.data.model

import com.google.gson.annotations.SerializedName

// ============== 用户/登录 ==============

/**
 * 登录请求
 *
 * 用户在登录界面输入账号密码后，App 会把这个对象转换成 JSON
 * 发送给服务器。服务器校验账号密码正确后，会返回用户信息和登录令牌。
 *
 * 通俗理解：相当于"登录时递给服务器的名片"，上面写着"我是谁、密码是多少"。
 */
data class LoginRequest(
    val username: String,    // 用户名（账号），注册时设置的唯一标识
    val password: String     // 登录密码（实际项目应通过 HTTPS 加密传输，避免明文泄露）
)

/**
 * 注册请求
 *
 * 新用户注册账号时需要提交的信息。
 * 除了账号密码，还包含昵称（在地图上显示给另一半看）、性别和头像。
 *
 * 通俗理解：相当于"办一张新会员卡"时填的资料表。
 */
data class RegisterRequest(
    val username: String,               // 用户名（账号），登录时使用，需唯一
    val password: String,               // 登录密码
    val nickname: String,               // 昵称（地图上显示给伴侣看的名字）
    val gender: String = "unknown",     // 性别：male（男）/female（女）/unknown（未知），默认未知
    val avatar: String = ""             // 头像（emoji或URL均可）
)

/**
 * 用户信息
 *
 * 服务器返回的当前用户完整资料对象，登录/注册成功后由服务器返回。
 * 既包含基本资料（昵称、头像、性别），也包含情侣配对相关字段。
 *
 * 通俗理解：相当于"个人档案卡"，里面写清了"我是谁、和谁配对、什么时候注册的"。
 *
 * 重点字段说明：
 * - id：用户唯一标识，由 Supabase 数据库自动生成的 UUID 字符串。
 * - coupleCode：配对码，告诉另一半输入这个码即可完成情侣绑定，未配对时为 null。
 * - partnerId：伴侣的用户 id，配对成功后才有值，未配对时为 null。
 */
data class UserInfo(
    val id: String = "",            // 用户唯一标识，Supabase UUID (String)
    val username: String = "",      // 用户名（账号）
    val nickname: String = "",      // 昵称，在地图上显示
    val avatar: String = "",        // 头像（emoji 或图片 URL）
    val gender: String = "unknown", // 性别：male（男）/female（女）/unknown（未知）
    @SerializedName("coupleCode")
    val coupleCode: String? = null,   // 配对码，用于绑定另一半；未配对时为 null
    @SerializedName("createdAt")
    val createdAt: String? = null,    // 账号创建时间字符串
    @SerializedName("partner_id")
    val partnerId: String? = null    // 伴侣的用户 id；未配对时为 null
) {
    // 显示名：优先使用昵称；昵称为空则回退使用用户名（避免地图上空空如也）
    val displayName: String get() = nickname.ifBlank { username }
    // 主题色：女性用粉色 #FF6B9D，男性或其他用蓝色 #4C9AFF（用于地图标记、头像描边等）
    val color: String get() = if (gender == "female") "#FF6B9D" else "#4C9AFF"
}

/**
 * 情侣信息
 *
 * 配对成功后，服务器返回的双方用户信息。
 * user 字段是当前登录用户自己，partner 字段是另一半。
 * 通俗理解：相当于"两张档案卡打包送回来"，告诉你和伴侣分别是谁。
 */
data class CoupleInfo(
    val user: UserInfo?,        // 当前登录用户的信息（理论上不应为空）
    val partner: UserInfo?      // 另一半的用户信息；未配对时为 null
)

/**
 * 配对请求
 *
 * 用户输入另一半分享的配对码后，App 把配对码发给服务器，
 * 服务器校验后将两人绑定为一对情侣。
 * 通俗理解：相当于"结对申请表"，只填一项——对方的配对码。
 */
data class PairRequest(val code: String)   // code：另一半提供的配对码

// ============== 位置上报 ==============

/**
 * 位置上报请求
 *
 * App 定期（或检测到位置变化时）调用，把当前 GPS 位置、电量等信息
 * 上报到服务器；另一半即可在地图上看到实时位置和电量状态。
 *
 * 通俗理解：相当于"定时给伴侣发一条位置短信"，告诉对方"我在哪、在不在动、还有多少电"。
 *
 * 字段说明：
 * - latitude / longitude：地球坐标（纬度 / 经度），由手机 GPS 或定位服务提供。
 * - address：根据经纬度反向查到的文字地址（如"北京市朝阳区 xx 路"），可空。
 * - accuracy：定位精度，单位米；数值越小越精准。
 * - isMoving：是否正在移动（通常根据 speed 判断）。
 * - speed：移动速度，单位米/秒；静止时一般为 0。
 * - batteryLevel：电量百分比（0-100）。
 * - batteryCharging：是否正在充电（让伴侣知道对方手机快没电了 / 在充电）。
 */
data class LocationRequest(
    val latitude: Double,                  // 纬度（地球坐标系的纬度值，北纬为正、南纬为负）
    val longitude: Double,                 // 经度（地球坐标系的经度值，东经为正、西经为负）
    val address: String? = null,           // 反向地理编码得到的文字地址（可空）
    val accuracy: Float? = null,           // 定位精度，单位米（数值越小越准）
    @SerializedName("isMoving")
    val isMoving: Boolean? = null,         // 是否正在移动（基于速度判断）
    val speed: Float? = null,              // 移动速度，单位米/秒
    val batteryLevel: Int? = null,         // 电量百分比（0-100）
    val batteryCharging: Boolean? = null   // 是否正在充电
)

// ============== APP使用上报 ==============

/**
 * 前台应用上报请求
 *
 * 当用户切换到新的前台应用（即手机当前屏幕上显示的 App）时，
 * 本 App 会把当前正在使用的应用信息上报给服务器，让另一半了解
 * 对方正在用手机做什么（例如玩游戏、看视频、聊微信等）。
 *
 * 通俗理解：相当于"告诉伴侣我现在手机屏幕上开着哪个 App"。
 *
 * 字段说明：
 * - packageName：应用的包名，Android 系统里每个 App 的唯一标识
 *   （如微信是 com.tencent.mm，抖音是 com.ss.android.ugc.aweme）。
 * - appName：给用户看的友好名字（如"微信""抖音"）。
 * - appCategory：应用分类，便于在 UI 上归类展示，
 *   例如 game（游戏）/video（视频）/social（社交）等，默认 other（其他）。
 */
data class ForegroundAppRequest(
    val packageName: String,                // 应用包名（如 com.tencent.mm）
    val appName: String,                    // 应用显示名（如"微信"）
    val appCategory: String = "other"      // 应用分类，默认 other（其他）
)
