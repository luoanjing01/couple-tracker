// ============================================================================
// 文件说明：本文件定义了「情侣追踪」App 与后端服务器（Supabase）通信的所有"接口"和"数据模型"。
//
// 可以把它想象成一份"合同清单"：
//   - interface 部分：列出 App 能向服务器发送哪些请求（登录、注册、配对、上报位置等）
//   - data class 部分：定义每次请求和响应中数据的"格式"（就像表格的表头）
//
// Supabase 是一个开源后端服务，提供三类接口：
//   1. Auth（认证）：负责登录、登出，验证用户身份
//   2. RPC（远程过程调用）：调用服务器上预先写好的 SQL 函数，处理复杂业务逻辑
//   3. REST（增删改查）：直接对数据库表进行查询、插入、更新操作
//
// 本文件使用 Retrofit 这个网络库，它能把 Kotlin 的 interface 自动转换成真正的 HTTP 请求。
// ============================================================================

// 包声明：声明本文件属于 com.coupletracker.android.data 这个包（命名空间）
// 相当于告诉系统"这个文件里的东西属于 data（数据层）这个模块"
package com.coupletracker.android.data

// ---- 下面是导入（import）语句，相当于"借用别人写好的工具" ----

// Gson 是 Google 提供的 JSON 解析库，用于在 Kotlin 对象和 JSON 文本之间互相转换
import com.google.gson.annotations.SerializedName  // SerializedName：指定字段在 JSON 中的名字（避免 Kotlin 命名和后端字段不一致）
import com.google.gson.JsonObject                  // JsonObject：一个通用的 JSON 对象，字段不固定时使用

// Retrofit 是 Square 公司的网络框架，能根据 interface 自动生成 HTTP 请求代码
import retrofit2.Response                          // Response：包装服务器返回结果，包含状态码、错误信息、数据体
import retrofit2.http.Body                         // @Body：标记请求体参数（要发送给服务器的数据对象）
import retrofit2.http.GET                          // @GET：标记 HTTP GET 请求（用于查询数据）
import retrofit2.http.Header                       // @Header：标记请求头参数（已导入但本文件未使用，保留备用）
import retrofit2.http.PATCH                        // @PATCH：标记 HTTP PATCH 请求（用于部分更新数据）
import retrofit2.http.POST                         // @POST：标记 HTTP POST 请求（用于新增/提交数据）
import retrofit2.http.Query                        // @Query：标记 URL 查询参数（拼接在 ? 后面的参数）

// ============================================================================
// 第一部分：AuthService —— 认证（登录/登出）服务接口
//
// 对应 Supabase 的 /auth/v1/* 路径，专门处理用户身份验证。
// "认证"就是确认"你是谁"的过程，最常见的方式是邮箱+密码登录。
// ============================================================================
interface AuthService {

    /**
     * 邮箱密码登录
     *
     * 工作原理：向 Supabase 的 GoTrue（认证组件）发送 POST 请求到 "token?grant_type=password"，
     * 服务器验证邮箱密码正确后，返回一个"通行证"（access_token），后续所有请求都要带上它。
     *
     * suspend 关键字：表示这是一个"挂起函数"，只能在协程中调用，不会阻塞主线程
     *                 （让 App 在等待网络时不卡顿）。
     *
     * @param body 登录请求体，包含邮箱和密码（见下方 SignInBody）
     * @return 服务器返回的会话信息（包含 access_token 等，见 SupabaseAuthResp）
     */
    @POST("token?grant_type=password")
    suspend fun signIn(
        @Body body: SignInBody
    ): Response<SupabaseAuthResp>

    /**
     * 登出（退出登录）
     *
     * 调用后服务器会让当前 access_token 失效，App 需清除本地保存的登录信息。
     * 返回 Response<Unit> 表示服务器不返回具体数据，只需关心成功/失败状态码。
     */
    @POST("logout")
    suspend fun logout(): Response<Unit>
}

// ============================================================================
// 登录请求需要发送的数据格式
// ============================================================================

/**
 * 登录请求体（要发给服务器的数据）
 *
 * 旧版本 Supabase 用表单格式（FormUrlEncoded）发送，新版本要求用 JSON 格式，
 * 因此这里用 data class 配合 @Body 注解，Retrofit 会自动把它转成 JSON。
 *
 * 字段说明：
 * @param email    用户邮箱（如 "alice@example.com"）
 * @param password 用户密码（明文发送，HTTPS 会加密传输，不在网络中泄露）
 */
data class SignInBody(
    val email: String,        // 用户邮箱，作为登录账号
    val password: String     // 用户密码
)

/**
 * 登录成功后服务器返回的"会话"信息
 *
 * "会话"就像入住酒店拿到的一张房卡，之后所有操作都要出示它。
 * 所有字段都设为可空（String?）= null，因为服务器有时只返回部分字段，
 * 设为可空可以避免解析失败导致 App 崩溃。
 *
 * 字段说明：
 * @param access_token   访问令牌，最重要的字段，后续所有请求都要带上它证明身份
 * @param token_type     令牌类型，通常是 "bearer"（持有者令牌，谁拿着谁就能用）
 * @param expires_in     令牌有效期（秒），如 3600 表示 1 小时后失效
 * @param refresh_token  刷新令牌，access_token 过期后可用它换新的，免得用户重新登录
 * @param user           登录用户的基本信息（见下方 SupabaseUser）
 */
data class SupabaseAuthResp(
    val access_token: String? = null,
    val token_type: String? = null,
    val expires_in: Int? = null,
    val refresh_token: String? = null,
    val user: SupabaseUser? = null
)

/**
 * 登录用户的基本信息
 *
 * @param id            用户的唯一编号（UUID 格式，数据库主键）
 * @param email         用户邮箱
 * @param user_metadata 用户自定义数据（键值对形式），可能存放昵称、头像等
 */
data class SupabaseUser(
    val id: String? = null,
    val email: String? = null,
    val user_metadata: Map<String, Any>? = null  // Map<String,Any> 表示键值对集合，值可以是任意类型
)

// ============================================================================
// 第二部分：RpcService —— 远程过程调用服务接口
//
// 对应 Supabase 的 /rest/v1/rpc/* 路径，用于调用服务器上预先写好的 SQL 函数。
//
// 什么是 RPC（Remote Procedure Call，远程过程调用）？
//   简单理解：服务器上存了一些"函数"（用 SQL 写的业务逻辑），
//   App 不需要知道函数内部怎么实现，只需要"调用"它并拿到结果即可。
//   就像叫外卖——你只管下单和收货，厨房里的操作由餐厅完成。
//
// 为什么用 RPC 而不是直接用 REST？
//   - 多步骤业务逻辑（如注册时同时创建多个表记录）放服务器端更安全可靠
//   - 可以避免前端发多个请求的复杂性
//   - register_user 这个函数是 SECURITY DEFINER 的（拥有服务器高权限），
//     能直接操作 auth.users 表（普通接口无权限访问）
//
// 重要提示：register_user 函数会直接创建用户并标记邮箱已验证，
//          **永不发送验证邮件**，所以不会触发 Supabase 的发信频率限制（429 错误）。
// ============================================================================
interface RpcService {

    /**
     * 用户注册
     *
     * 调用服务器上的 register_user() 函数，参数必须带 p_ 前缀（Supabase 的命名约定）。
     * 该函数是 SECURITY DEFINER（拥有高权限），能直接：
     *   1. 向 auth.users 表插入新用户
     *   2. 用 bcrypt 算法给密码加密后存入数据库（不存明文）
     *   3. 同时创建对应的 profile（用户档案）记录
     *   4. 设置 email_confirmed_at = now()（假装邮箱已验证，跳过邮件验证）
     *
     * @see public.register_user 服务器端 SQL 函数定义
     *
     * @param body 注册请求体（用户名、密码、昵称、性别），见 RegisterUserReq
     * @return 注册结果（包含 user_id、配对码 couple_code 等），见 RegisterUserResp
     */
    @POST("register_user")
    suspend fun registerUser(
        @Body body: RegisterUserReq
    ): Response<RegisterUserResp>

    /**
     * 用户登录验证（绕过 Supabase 默认的 GoTrue 登录流程）
     *
     * 调用服务器上的 verify_login() 函数，自己用 crypt() 函数校验密码。
     * 这样做的好处是完全不依赖 Supabase 的认证服务，避免被限流或出问题。
     *
     * 为什么不直接用 AuthService.signIn？
     *   - signIn 走 GoTrue，可能会触发邮件验证步骤
     *   - 自定义 verify_login 直接查数据库验证，流程更可控
     *
     * @see public.verify_login 服务器端 SQL 函数定义
     *
     * @param body 登录请求体（用户名、密码），见 VerifyLoginReq
     * @return 登录结果（包含 user_id 和 profile JSON），见 VerifyLoginResp
     */
    @POST("verify_login")
    suspend fun verifyLogin(
        @Body body: VerifyLoginReq
    ): Response<VerifyLoginResp>

    /**
     * 发起配对请求（输入对方的配对码）
     *
     * 业务场景：用户 A 想和用户 B 结成"情侣"配对，A 把 B 的配对码输进来，发送请求。
     *
     * 工作流程：
     *   1. 服务器记录 A 向 B 的配对请求
     *   2. 30 秒内重复请求会被拦截（防止误触或恶意刷接口）
     *   3. B 登录后会查到这条请求，可以接受或拒绝
     *
     * @see public.pair_by_code 服务器端 SQL 函数定义（参数：p_my_id, p_their_code）
     *
     * @param body 请求体（自己的 ID + 对方的配对码），见 PairByCodeReq
     * @return 配对结果（可能状态：request_sent、already_paired、waiting 等），见 PairByCodeResp
     */
    @POST("pair_by_code")
    suspend fun pairByCode(
        @Body body: PairByCodeReq
    ): Response<PairByCodeResp>

    /**
     * 查询当前用户的配对状态
     *
     * App 应定期轮询（如每 5 秒查一次）以获取最新配对状态。
     *
     * 可能返回的状态（status 字段）：
     *   - idle              空闲，还没参与任何配对
     *   - incoming_request  有人向我发起配对请求（我可以接受/拒绝）
     *   - waiting           我向对方发了请求，正在等对方回应
     *   - paired            已经成功配对（已找到伴侣）
     *
     * @see public.check_pair_status 服务器端 SQL 函数定义（参数：p_my_id）
     *
     * @param body 请求体（仅含自己的 ID），见 CheckPairStatusReq
     * @return 配对状态详情（含对方信息），见 CheckPairStatusResp
     */
    @POST("check_pair_status")
    suspend fun checkPairStatus(
        @Body body: CheckPairStatusReq
    ): Response<CheckPairStatusResp>

    /**
     * 接受配对请求
     *
     * 业务场景：B 收到 A 的配对请求后，调用此接口表示同意配对。
     * 服务器会把双方都标记为"已配对"，从此 A 和 B 就正式"绑定"为情侣关系。
     *
     * @see public.accept_pair 服务器端 SQL 函数定义（参数：p_my_id, p_their_id）
     *
     * @param body 请求体（自己的 ID + 对方的 ID），见 AcceptPairReq
     * @return 配对结果（成功时返回 partner_nickname 等对方信息），见 AcceptPairResp
     */
    @POST("accept_pair")
    suspend fun acceptPair(
        @Body body: AcceptPairReq
    ): Response<AcceptPairResp>

    /**
     * 取消配对（单方面取消，双方 partner_id 都被清空）
     *
     * 业务场景：A 或 B 任一方在「我的」页点「取消配对」按钮，
     * 服务器把双方的 partner_id / pending_pair / pair_request_at 全部清空，
     * 双方都恢复到未配对状态，可重新发起配对。
     *
     * @see public.unpair 服务器端 SQL 函数定义（参数：p_my_id）
     *
     * @param body 请求体（仅含自己的 ID），见 UnpairReq
     * @return 取消结果，见 UnpairResp
     */
    @POST("unpair")
    suspend fun unpair(
        @Body body: UnpairReq
    ): Response<UnpairResp>

    /**
     * 拒绝配对请求（清掉对方发起的 pending_pair）
     *
     * 业务场景：B 收到 A 的配对请求弹窗，点「拒绝」按钮。
     * 服务器把 A 的 pending_pair / pair_request_at 清空，
     * B 下次轮询 check_pair_status 第②步再也查不到 A 的请求，
     * 不再重复弹窗。
     *
     * 成熟方案参考：腾讯 IM refuseFriendApplication、CSDN 微服务好友管理
     * ——拒绝 = 删掉 pending 记录，否则下次拉列表又会拉到。
     *
     * @see public.reject_pair 服务器端 SQL 函数定义（参数：p_my_id, p_their_id）
     *
     * @param body 请求体（自己 ID + 对方 ID），见 RejectPairReq
     * @return 拒绝结果，见 RejectPairResp
     */
    @POST("reject_pair")
    suspend fun rejectPair(
        @Body body: RejectPairReq
    ): Response<RejectPairResp>
}

// ============================================================================
// 以下是 RPC 各方法对应的请求体和响应体数据模型
// 命名约定：方法名 + Req（请求） / Resp（响应）
// ============================================================================

/**
 * 用户注册请求体
 *
 * 注意每个字段都用 @SerializedName 指定了 JSON 中的名字，必须以 p_ 开头
 * （Supabase RPC 函数参数的命名规范，p 表示 parameter 参数）。
 *
 * @param username 用户名（登录用，需唯一）
 * @param password 密码（明文传输，HTTPS 加密，服务器端会用 bcrypt 哈希后存储）
 * @param nickname 昵称（展示给其他用户看的友好名字）
 * @param gender   性别（如 "male"/"female"/"unknown"）
 */
data class RegisterUserReq(
    @SerializedName("p_username") val username: String,   // @SerializedName 把 Kotlin 的 username 映射成 JSON 的 p_username
    @SerializedName("p_password") val password: String,
    @SerializedName("p_nickname") val nickname: String,
    @SerializedName("p_gender")   val gender: String
)

/**
 * 用户注册响应体
 *
 * 对应服务器 register_user() 函数返回的 JSON 数据（jsonb 类型）。
 * 所有字段都可空，避免服务器少返回字段时解析崩溃。
 *
 * @param user_id    新创建用户的唯一编号（UUID）
 * @param couple_code 配对码，告诉别人这个码即可邀请你配对
 * @param email      注册时使用的邮箱
 * @param username   用户名
 * @param nickname   昵称
 * @param gender     性别
 * @param avatar     头像 URL（可能为空，未上传头像时）
 */
data class RegisterUserResp(
    val user_id: String? = null,
    val couple_code: String? = null,
    val email: String? = null,
    val username: String? = null,
    val nickname: String? = null,
    val gender: String? = null,
    val avatar: String? = null
)

/**
 * 登录验证请求体
 *
 * @param username 用户名（用户登录时输入）
 * @param password 密码（用户登录时输入）
 */
data class VerifyLoginReq(
    @SerializedName("p_username") val username: String,
    @SerializedName("p_password") val password: String
)

/**
 * 登录验证响应体
 *
 * @param user_id 用户唯一编号（登录成功后用于后续所有请求的身份标识）
 * @param profile 原始 profile JSON（用户档案数据，JsonObject 类型）
 *
 * 关于 profile 字段的兼容性说明：
 *   - cache 中的旧版 verify_login 函数返回 f1~f8 格式（字段编号）
 *   - 新版函数返回命名字段（如 nickname、gender 等）
 *   - 为了兼容两种格式，这里用 JsonObject（通用 JSON 对象）接收，
 *     LoginActivity 内部会手动解析两种结构。
 */
data class VerifyLoginResp(
    val user_id: String? = null,
    val profile: JsonObject? = null
)

/**
 * 发起配对请求的请求体
 *
 * @see public.pair_by_code 服务器端函数（参数：p_my_id, p_their_code）
 *
 * @param myId      自己的用户 ID（UUID 格式）
 * @param theirCode 对方的配对码（couple_code，注册时分配的短码）
 */
data class PairByCodeReq(
    @SerializedName("p_my_id")       val myId: String,
    @SerializedName("p_their_code")  val theirCode: String
)

/**
 * 发起配对请求的响应体
 *
 * 服务器返回多种可能的状态字段（一次只会有其中一两种为 true）：
 * @param ok            请求是否成功（基础成功标志）
 * @param reason        失败原因（ok=false 时给出原因说明）
 * @param couple_code   自己的配对码（部分场景下会回传）
 * @param their_id      对方的用户 ID（找到对方时）
 * @param their_nickname 对方的昵称
 * @param paired         是否已成功配对（true 表示双方已绑定）
 * @param waiting        是否正在等待对方回应
 * @param already_paired 是否已经和别人配对过（不能重复配对）
 * @param request_sent   配对请求是否已成功发送（等对方确认）
 * @param msg            服务器返回的提示消息（人类可读）
 */
data class PairByCodeResp(
    @SerializedName("ok")            val ok: Boolean = false,
    @SerializedName("reason")        val reason: String? = null,
    @SerializedName("couple_code")  val couple_code: String? = null,
    @SerializedName("their_id")      val their_id: String? = null,
    @SerializedName("their_nickname") val their_nickname: String? = null,
    @SerializedName("paired")        val paired: Boolean? = null,
    @SerializedName("waiting")       val waiting: Boolean? = null,
    @SerializedName("already_paired") val already_paired: Boolean? = null,
    @SerializedName("request_sent")  val request_sent: Boolean? = null,
    @SerializedName("msg")           val msg: String? = null
)

/**
 * 查询配对状态的请求体
 *
 * @param myId 自己的用户 ID
 */
data class CheckPairStatusReq(
    @SerializedName("p_my_id") val myId: String
)

/**
 * 查询配对状态的响应体
 *
 * 包含当前状态及可能的"对方信息"或"请求者信息"。
 * 根据不同的 status，会有不同的字段被填充：
 *
 * @param status            当前状态：idle / incoming_request / waiting / paired
 * @param partnerId         伴侣的用户 ID（已配对时填充）
 * @param partnerNickname   伴侣的昵称
 * @param partnerCode       伴侣的配对码
 * @param partnerGender     伴侣的性别
 * @param partnerAvatar     伴侣的头像 URL
 * @param requesterId       向我发起请求的人的 ID（incoming_request 时填充）
 * @param requesterNickname 请求者的昵称
 * @param requesterCode     请求者的配对码
 * @param requesterGender   请求者的性别
 * @param requesterAvatar   请求者的头像 URL
 * @param theirId           "对方"的 ID（兼容字段，部分场景和 partnerId 重叠）
 * @param theirNickname     "对方"的昵称（兼容字段）
 * @param reason            失败或异常的原因说明
 */
data class CheckPairStatusResp(
    @SerializedName("status")            val status: String = "idle",
    @SerializedName("partner_id")        val partnerId: String? = null,
    @SerializedName("partner_nickname")  val partnerNickname: String? = null,
    @SerializedName("partner_code")      val partnerCode: String? = null,
    @SerializedName("partner_gender")    val partnerGender: String? = null,
    @SerializedName("partner_avatar")    val partnerAvatar: String? = null,
    @SerializedName("requester_id")      val requesterId: String? = null,
    @SerializedName("requester_nickname") val requesterNickname: String? = null,
    @SerializedName("requester_code")    val requesterCode: String? = null,
    @SerializedName("requester_gender")  val requesterGender: String? = null,
    @SerializedName("requester_avatar")  val requesterAvatar: String? = null,
    @SerializedName("their_id")          val theirId: String? = null,
    @SerializedName("their_nickname")    val theirNickname: String? = null,
    @SerializedName("reason")            val reason: String? = null
)

/**
 * 接受配对请求的请求体
 *
 * @param myId    自己的用户 ID
 * @param theirId 对方的用户 ID（即请求者的 ID）
 */
data class AcceptPairReq(
    @SerializedName("p_my_id")   val myId: String,
    @SerializedName("p_their_id") val theirId: String
)

/**
 * 接受配对请求的响应体
 *
 * @param ok             请求是否成功
 * @param paired         是否已成功配对（true 表示双方绑定成功）
 * @param partnerId      伴侣的用户 ID
 * @param partnerNickname 伴侣的昵称
 * @param reason         失败原因（如对方已和其他人配对）
 * @param msg            服务器返回的提示消息
 */
data class AcceptPairResp(
    @SerializedName("ok")              val ok: Boolean = false,
    @SerializedName("paired")         val paired: Boolean? = null,
    @SerializedName("partner_id")     val partnerId: String? = null,
    @SerializedName("partner_nickname") val partnerNickname: String? = null,
    @SerializedName("reason")         val reason: String? = null,
    @SerializedName("msg")            val msg: String? = null
)

/**
 * 取消配对请求体
 *
 * @param myId 自己的用户 ID（取消操作发起方）
 */
data class UnpairReq(
    @SerializedName("p_my_id") val myId: String
)

/**
 * 取消配对响应体
 *
 * @param ok        请求是否成功
 * @param msg       服务器返回的提示消息
 * @param partnerId 被解除的对方 ID（取消成功后回传，便于前端记录日志）
 * @param reason    失败原因（如 NOT_PAIRED 表示本来就没配对）
 */
data class UnpairResp(
    @SerializedName("ok")          val ok: Boolean = false,
    @SerializedName("msg")        val msg: String? = null,
    @SerializedName("partner_id") val partnerId: String? = null,
    @SerializedName("reason")     val reason: String? = null
)

/**
 * 拒绝配对请求体
 *
 * @param myId   自己的 user ID（被请求方 B）
 * @param theirId 对方的 user ID（请求方 A）
 */
data class RejectPairReq(
    @SerializedName("p_my_id")    val myId: String,
    @SerializedName("p_their_id") val theirId: String
)

/**
 * 拒绝配对响应体
 *
 * @param ok     请求是否成功
 * @param msg    服务器返回的提示消息
 * @param reason 失败原因（如 NO_PENDING_REQUEST 表示没待处理的请求）
 */
data class RejectPairResp(
    @SerializedName("ok")     val ok: Boolean = false,
    @SerializedName("msg")    val msg: String? = null,
    @SerializedName("reason") val reason: String? = null
)

/**
 * PostgREST RPC 错误响应体
 *
 * 当 RPC 调用失败时（HTTP 状态码 4xx 客户端错误 / 5xx 服务器错误），
 * Supabase 返回的错误信息格式。
 *
 * @param code    错误代码（如 "PGRST116" 表示未找到数据）
 * @param message 错误描述（人类可读，如 "JSON object requested, returned 0 rows"）
 * @param hint    解决提示（部分错误会给出建议）
 * @param details 详细信息（可能包含 SQL 上下文）
 */
data class RpcErrorResp(
    val code: String? = null,
    val message: String? = null,
    val hint: String? = null,
    val details: String? = null
)

// ============================================================================
// 第三部分：RestService —— 数据库增删改查服务接口
//
// 对应 Supabase 的 /rest/v1/* 路径，提供对数据库表的直接操作。
// PostgREST 是一个把 PostgreSQL 数据库自动转换成 REST API 的工具。
//
// 与 RpcService 的区别：
//   - RpcService 调用"函数"，处理复杂业务逻辑
//   - RestService 直接"对表操作"，比如查询 profiles 表、向 locations 表插入一条记录
//
// 本接口包含四组操作：
//   1. profiles  —— 用户档案（昵称、头像、配对码等）
//   2. couples   —— 情侣关系（A 和 B 的绑定关系）
//   3. locations —— 位置记录（GPS 上报）
//   4. app_usage —— APP 使用时长记录
// ============================================================================
interface RestService {

    // ========================================================================
    // 用户档案（profiles）表操作
    // ========================================================================

    /**
     * 查询用户档案
     *
     * 通过 id、username 或 couple_code 中任一条件查询。
     * 返回列表（一般只取第一条），即使没匹配也返回空列表而非报错。
     *
     * @param select    要返回哪些字段，"*" 表示所有字段
     * @param id        按 user_id 过滤（可空，不传则忽略该条件）
     * @param username  按用户名过滤（可空）
     * @param coupleCode 按配对码过滤（可空）
     * @return 匹配的 Profile 列表（可能为空）
     */
    @GET("profiles")
    suspend fun getProfile(
        @Query("select") select: String = "*",
        @Query("id") id: String? = null,
        @Query("username") username: String? = null,
        @Query("couple_code") coupleCode: String? = null
    ): Response<List<Profile>>

    /**
     * 更新用户档案
     *
     * 用 PATCH 方法部分更新（只更新 body 中包含的字段，其他字段不变）。
     *
     * @param id  要更新的用户 ID（按 id 过滤定位记录）
     * @param body 要更新的字段（如 {"nickname": "新昵称"}）
     * @return Response<Unit> 表示无具体返回数据，看状态码判断成功失败
     */
    @PATCH("profiles")
    suspend fun updateProfile(
        @Query("id") id: String,
        @Body body: Map<String, Any>
    ): Response<Unit>

    // ========================================================================
    // 情侣关系（couples）表操作
    // ========================================================================

    /**
     * 创建情侣关系记录
     *
     * @param body 情侣信息（通常包含 user_a、code 等）
     * @return 创建成功的 Couple 对象（含生成的 id）
     */
    @POST("couples")
    suspend fun createCouple(
        @Body body: Map<String, Any>
    ): Response<Couple>

    /**
     * 查询情侣关系
     *
     * @param select 要返回的字段，"*" 表示全部
     * @param code   按配对码查（可空）
     * @param userA  按 A 方用户 ID 查（可空）
     * @return 匹配的 Couple 列表
     */
    @GET("couples")
    suspend fun getCouple(
        @Query("select") select: String = "*",
        @Query("code") code: String? = null,
        @Query("user_a") userA: String? = null
    ): Response<List<Couple>>

    /**
     * 更新情侣关系（典型场景：B 加入时把 user_b 填上）
     *
     * @param id  情侣记录 ID
     * @param body 要更新的字段（如 {"user_b": "xxx"}）
     * @return 无具体返回数据
     */
    @PATCH("couples")
    suspend fun updateCouple(
        @Query("id") id: String,
        @Body body: Map<String, Any>
    ): Response<Unit>

    // ========================================================================
    // 位置记录（locations）表操作
    // ========================================================================

    /**
     * 上报当前位置
     *
     * App 定期采集 GPS 坐标后调用此接口上传。
     *
     * @param body 位置数据（经纬度、电量、速度等），见 LocationRow
     * @return 无具体返回数据
     */
    @POST("locations")
    suspend fun reportLocation(
        @Body body: LocationRow
    ): Response<Unit>

    /**
     * 查询情侣双方的位置记录
     *
     * @param coupleId 情侣关系 ID
     * @param order   排序方式，默认按 created_at 倒序（最新的在前）
     * @param limit   返回条数，默认 20 条
     * @return 位置记录列表
     */
    @GET("locations")
    suspend fun getCoupleLocations(
        @Query("couple_id") coupleId: String,
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 20
    ): Response<List<LocationRow>>

    /**
     * 查询单个用户的位置记录
     *
     * @param userId 用户 ID
     * @param order  排序方式（默认最新在前）
     * @param limit  返回条数（默认 5 条，足够看最近位置）
     * @return 位置记录列表
     */
    @GET("locations")
    suspend fun getUserLocations(
        @Query("user_id") userId: String,
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 5
    ): Response<List<LocationRow>>

    // ========================================================================
    // APP 使用时长（app_usage）表操作
    // ========================================================================

    /**
     * 上报 APP 使用时长
     *
     * @param body 使用记录数据，见 AppUsageRow
     * @return 无具体返回数据
     */
    @POST("app_usage")
    suspend fun reportAppUsage(
        @Body body: AppUsageRow
    ): Response<Unit>

    /**
     * 查询某用户的 APP 使用记录（旧接口）
     *
     * 注意：此接口没有日期过滤，limit 100 仅用于排查问题。
     * 正式查询应使用下方 getAppUsageInRange 接口。
     *
     * @param userId 用户 ID
     * @param order  排序方式
     * @param limit  返回条数，默认 100
     * @return 使用记录列表
     */
    @GET("app_usage")
    suspend fun getAppUsage(
        @Query("user_id") userId: String,
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 100
    ): Response<List<AppUsageRow>>

    /**
     * 查询某用户在指定日期范围内的 APP 使用记录
     *
     * 使用 PostgREST 的 and(gte.x,lt.y) 语法，把"大于等于起始时间"和"小于结束时间"
     * 合并成一个 created_at 查询参数，避免 Retrofit 因同名参数覆盖而出错。
     *
     * @param userId           用户 ID
     * @param createdAtFilter  时间过滤条件，形如 and(gte.2025-09-01T00:00:00Z,lt.2025-09-08T00:00:00Z)
     * @param order            排序方式（默认最新在前）
     * @param limit            返回条数，默认 1000
     * @return 使用记录列表
     */
    @GET("app_usage")
    suspend fun getAppUsageInRange(
        @Query("user_id") userId: String,
        /** 形如 and(gte.2025-09-01T00:00:00Z,lt.2025-09-08T00:00:00Z) */
        @Query("created_at") createdAtFilter: String,
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 1000
    ): Response<List<AppUsageRow>>
}

// ============================================================================
// 第四部分：数据库表对应的数据模型（data class）
//
// 这些类对应数据库中的表结构，Retrofit 接收到 JSON 响应后会自动转换成这些对象。
// 字段名必须和数据库列名一致（或用 @SerializedName 指定映射）。
// ============================================================================

/**
 * 用户档案（对应数据库 profiles 表）
 *
 * 存储用户的基本信息，每个用户一条记录。
 *
 * @param id          用户唯一编号（UUID，主键，对应 auth.users.id）
 * @param username    用户名（登录用，唯一）
 * @param nickname    昵称（展示给其他用户看的名字）
 * @param avatar      头像 URL（头像图片的网络地址）
 * @param gender      性别（male / female / unknown）
 * @param couple_code 配对码（邀请别人和自己配对时用，类似邀请码）
 * @param couple_id   所属情侣关系 ID（已配对时才有值）
 * @param partner_id  伴侣的用户 ID（已配对时才有值）
 * @param created_at   创建时间（数据库自动生成）
 */
data class Profile(
    val id: String = "",
    val username: String = "",
    val nickname: String = "",
    val avatar: String = "",
    val gender: String = "unknown",
    val couple_code: String = "",
    val couple_id: String? = null,
    @SerializedName("partner_id") val partner_id: String? = null,
    val created_at: String? = null
)

/**
 * 情侣关系（对应数据库 couples 表）
 *
 * 一条记录代表一对情侣的绑定关系。
 *
 * @param id        情侣关系记录 ID（UUID，主键）
 * @param code      情侣配对码（双方共用，区别于个人的 couple_code）
 * @param user_a    A 方用户 ID（通常是先注册的那位）
 * @param user_b    B 方用户 ID（B 加入配对后才会填上，初始为 null）
 * @param created_at 创建时间
 */
data class Couple(
    val id: String = "",
    val code: String = "",
    val user_a: String = "",
    val user_b: String? = null,
    val created_at: String? = null
)

/**
 * 位置记录（对应数据库 locations 表的一行）
 *
 * 每次上报 GPS 位置就插入一条新记录。
 *
 * @param id            记录唯一 ID（数据库自动生成，所以可空）
 * @param user_id       上报位置的用户 ID
 * @param couple_id     所属情侣关系 ID
 *
 * ⚠️ 重要：couple_id 必须是可空类型（String?）。
 *   原因：未配对的用户上传位置时，couple_id 应为 null。
 *   旧代码写成 `val couple_id: String = ""`（非空类型），会导致传 null 时编译失败，
 *   而数据库有外键约束（FK）要求 couple_id 必须是有效的或为 null，
 *   否则服务器会拒绝并返回 409 冲突错误。
 *
 * @param latitude       纬度（如 31.2304 表示上海）
 * @param longitude      经度（如 121.4737 表示上海）
 * @param accuracy       定位精度（米，数值越小越精确）
 * @param speed          移动速度（米/秒）
 * @param battery_level  电量百分比（0~100）
 * @param is_moving      是否在移动中（true/false）
 * @param created_at     记录创建时间
 */
data class LocationRow(
    val id: String? = null,
    val user_id: String = "",
    val couple_id: String? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Double? = null,
    val speed: Double? = null,
    val battery_level: Int? = null,
    val is_moving: Boolean = false,
    val created_at: String? = null
)

/**
 * APP 使用记录（对应数据库 app_usage 表的一行）
 *
 * 记录用户在某个 APP 上花了多少时间。
 *
 * @param id             记录唯一 ID（数据库自动生成）
 * @param user_id        用户 ID
 * @param couple_id      所属情侣关系 ID（同 LocationRow，必须可空，未配对用户也要能上报）
 * @param package_name   APP 包名（如 "com.tencent.mm" 是微信）
 * @param app_name       APP 显示名称（如"微信"，可能为空）
 * @param category        APP 分类（如"社交"、"游戏"）
 * @param usage_seconds  使用时长（秒，如 600 表示用了 10 分钟）
 * @param window_start    统计窗口起始时间
 * @param created_at     记录创建时间
 */
data class AppUsageRow(
    val id: String? = null,
    val user_id: String = "",
    val couple_id: String? = null,
    val package_name: String = "",
    val app_name: String? = null,
    val category: String? = null,
    val usage_seconds: Int = 0,
    val window_start: String? = null,
    val created_at: String? = null
)
