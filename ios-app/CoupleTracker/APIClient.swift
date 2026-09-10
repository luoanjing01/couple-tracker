//
//  APIClient.swift
//  CoupleTracker
//
//  Supabase REST API 客户端
//  对应 Android: ApiService.kt + NetworkModule.kt
//  - RPC: /rest/v1/rpc/{function}
//  - REST: /rest/v1/{table}
//

import Foundation

// MARK: - Configuration

/// Supabase 云端配置（从 Android build.gradle.kts BuildConfig 抄过来）
enum SupabaseConfig {
    static let url = "https://gvytqbgangyjjurekyid.supabase.co"
    static let anonKey = "sb_publishable_TmlnyTou7Z7JGt3vNP3TTw_3-KkCiCM"
    static var restBase: String { url + "/rest/v1" }
    static var authBase: String { url + "/auth/v1" }
}

// MARK: - Models

/// 注册请求体（对应 Android RegisterUserReq）
struct RegisterUserReq: Encodable {
    let p_username: String
    let p_password: String
    let p_nickname: String
    let p_gender: String
}

/// 登录验证请求体（对应 Android VerifyLoginReq）
struct VerifyLoginReq: Encodable {
    let p_username: String
    let p_password: String
}

/// 配对请求体（对应 Android PairByCodeReq）
struct PairByCodeReq: Encodable {
    let p_my_id: String
    let p_their_code: String
}

/// 位置上报行（对应 Android LocationRow）
struct LocationRow: Codable {
    var user_id: String
    var couple_id: String?
    var latitude: Double
    var longitude: Double
    var accuracy: Double?
    var speed: Double?
    var battery_level: Int?
    var is_moving: Bool

    enum CodingKeys: String, CodingKey {
        case user_id, couple_id, latitude, longitude, accuracy,
             speed, battery_level, is_moving
    }
}

/// App 使用上报行（对应 Android AppUsageRow）
struct AppUsageRow: Codable {
    var user_id: String
    var couple_id: String?
    var package_name: String
    var app_name: String?
    var category: String?
    var usage_seconds: Int
    var window_start: String?

    enum CodingKeys: String, CodingKey {
        case user_id, couple_id, package_name, app_name,
             category, usage_seconds, window_start
    }
}

/// Profile 行（对应 Android Profile）
struct Profile: Decodable {
    var id: String = ""
    var username: String = ""
    var nickname: String = ""
    var avatar: String = ""
    var gender: String = "unknown"
    var couple_code: String = ""
    var couple_id: String?
    var partner_id: String?
    var created_at: String?
}

/// RPC pair_by_code 返回体（动态字段，部分可能缺失）
struct PairByCodeResp: Decodable {
    let ok: Bool?
    let reason: String?
    let couple_code: String?
    let their_id: String?
    let their_nickname: String?
    let paired: Bool?
    let waiting: Bool?
    let already_paired: Bool?
    let msg: String?
}

/// RPC verify_login 返回的原始 JSON（profile 可能是各种结构）
struct VerifyLoginResp: Decodable {
    let user_id: String?
    /// profile 是动态 JSON，我们解析成字典再用 string(forKey:) 取值
    let profile: [String: AnyCodable]?

    /// 兼容 cache 旧函数（f1~f8）+ 新函数（命名字段）取字段
    func str(_ key: String, _ fallback: String) -> String {
        if let v = profile?[key]?.asString, !v.isEmpty, v != "null" { return v }
        if let v = profile?[fallback]?.asString, !v.isEmpty, v != "null" { return v }
        return ""
    }
    func strN(_ key: String, _ fallback: String) -> String? {
        if let v = profile?[key]?.asString, !v.isEmpty, v != "null" { return v }
        if let v = profile?[fallback]?.asString, !v.isEmpty, v != "null" { return v }
        return nil
    }
}

/// 通用 JSON 值包装（兼容 RPC 返回的动态 jsonb）
enum AnyCodable: Decodable {
    case string(String)
    case int(Int)
    case double(Double)
    case bool(Bool)
    case null

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null; return }
        if let v = try? c.decode(String.self) { self = .string(v); return }
        if let v = try? c.decode(Int.self) { self = .int(v); return }
        if let v = try? c.decode(Double.self) { self = .double(v); return }
        if let v = try? c.decode(Bool.self) { self = .bool(v); return }
        self = .null
    }

    var asString: String? {
        switch self {
        case .string(let s): return s
        case .int(let i): return String(i)
        case .double(let d): return String(d)
        case .bool(let b): return String(b)
        case .null: return nil
        }
    }
}

// MARK: - Errors

enum APIError: Error, LocalizedError {
    case http(Int, String)
    case network(Error)
    case decode(String)
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .http(let code, let body):
            return "HTTP \(code): \(body.prefix(120))"
        case .network(let e):
            return "网络异常：\(e.localizedDescription)"
        case .decode(let m):
            return "解析失败：\(m)"
        case .invalidResponse:
            return "无效响应"
        }
    }
}

// MARK: - APIClient

/// Supabase REST API 客户端
/// 对应 Android: ApiService + NetworkModule
final class APIClient {

    static let shared = APIClient()

    private let session: URLSession
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder

    private init() {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 15
        cfg.timeoutIntervalForResource = 30
        cfg.waitsForConnectivity = true
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        self.session = URLSession(configuration: cfg)
        self.decoder = JSONDecoder()
        self.encoder = JSONEncoder()
    }

    // MARK: - 通用请求

    /// 构造带 Supabase 头的 request
    /// - Parameter auth: 是否带 Authorization Bearer（默认 false，因为 RPC 走 anon key）
    private func makeRequest(
        path: String,
        method: String,
        body: Encodable? = nil,
        query: [URLQueryItem] = [],
        auth: Bool = false
    ) throws -> URLRequest {
        var comp = URLComponents(string: SupabaseConfig.restBase + path)
        if !query.isEmpty {
            // PostgREST 的 and(gte.x,lt.y) 这种值包含 `,` `()` 不能直接 URL 编码
            // 我们手动 append 而不通过 queryItems
            var fullQuery = comp?.percentEncodedQuery ?? ""
            for item in query {
                let q = "\(item.name)=\(item.value ?? "")"
                fullQuery = fullQuery.isEmpty ? q : fullQuery + "&" + q
            }
            comp?.percentEncodedQuery = fullQuery
        }
        guard let url = comp?.url else {
            throw APIError.invalidResponse
        }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        // Supabase 关键头：anon key + schema
        req.setValue(SupabaseConfig.anonKey, forHTTPHeaderField: "apikey")
        req.setValue("public", forHTTPHeaderField: "Accept-Profile")
        if auth, let token = UserStore.shared.getToken() {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let body = body {
            // 用 AnyEncodable 包装避免泛型协议问题
            req.httpBody = try encoder.encode(AnyEncodable(body))
        }
        return req
    }

    /// 通用执行（返回 data + httpCode + bodyString）
    private func execute(_ req: URLRequest) async throws -> (Data, Int, String) {
        do {
            let (data, resp) = try await session.data(for: req)
            guard let http = resp as? HTTPURLResponse else {
                throw APIError.invalidResponse
            }
            let body = String(data: data, encoding: .utf8) ?? ""
            return (data, http.statusCode, body)
        } catch let e as APIError {
            throw e
        } catch {
            throw APIError.network(error)
        }
    }

    /// 解析成功响应，失败抛 APIError.http
    private func parse<T: Decodable>(_ type: T.Type, data: Data, code: Int, body: String) throws -> T {
        guard (200..<300).contains(code) else {
            throw APIError.http(code, body)
        }
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw APIError.decode("\(error)")
        }
    }

    // MARK: - RPC: register_user

    /// 对应 Android: RpcService.registerUser
    func registerUser(username: String, password: String,
                      nickname: String, gender: String) async throws {
        let body = RegisterUserReq(
            p_username: username, p_password: password,
            p_nickname: nickname, p_gender: gender
        )
        let req = try makeRequest(path: "/rpc/register_user", method: "POST", body: body)
        let (data, code, bodyStr) = try await execute(req)
        // register_user 返回 jsonb，成功返回 200/204，失败抛 APIError.http
        guard (200..<300).contains(code) else {
            throw APIError.http(code, bodyStr)
        }
        _ = data // 不需要解析返回值（后续调 verify_login）
    }

    // MARK: - RPC: verify_login

    /// 对应 Android: RpcService.verifyLogin
    /// 返回 user_id + profile（兼容 cache 旧函数 f1~f8 格式）
    func verifyLogin(username: String, password: String) async throws -> VerifyLoginResp {
        let body = VerifyLoginReq(p_username: username, p_password: password)
        let req = try makeRequest(path: "/rpc/verify_login", method: "POST", body: body)
        let (data, code, bodyStr) = try await execute(req)
        guard (200..<300).contains(code) else {
            throw APIError.http(code, bodyStr)
        }
        do {
            return try decoder.decode(VerifyLoginResp.self, from: data)
        } catch {
            throw APIError.decode("\(error)")
        }
    }

    // MARK: - RPC: pair_by_code

    /// 对应 Android: RpcService.pairByCode
    func pairByCode(myId: String, theirCode: String) async throws -> PairByCodeResp {
        let body = PairByCodeReq(p_my_id: myId, p_their_code: theirCode)
        let req = try makeRequest(path: "/rpc/pair_by_code", method: "POST", body: body)
        let (data, code, bodyStr) = try await execute(req)
        guard (200..<300).contains(code) else {
            throw APIError.http(code, bodyStr)
        }
        do {
            return try decoder.decode(PairByCodeResp.self, from: data)
        } catch {
            throw APIError.decode("\(error)")
        }
    }

    // MARK: - REST: profiles

    /// 查单个 profile by id
    /// 对应 Android: RestService.getProfile(id=...)
    func getProfile(id: String) async throws -> Profile? {
        let req = try makeRequest(
            path: "/profiles",
            method: "GET",
            query: [
                URLQueryItem(name: "select", value: "*"),
                URLQueryItem(name: "id", value: "eq.\(id)"),
                URLQueryItem(name: "limit", value: "1")
            ]
        )
        let (data, code, body) = try await execute(req)
        guard (200..<300).contains(code) else {
            throw APIError.http(code, body)
        }
        let arr = try decoder.decode([Profile].self, from: data)
        return arr.first
    }

    // MARK: - REST: locations

    /// 上报位置（对应 Android: RestService.reportLocation）
    func reportLocation(_ row: LocationRow) async throws {
        let req = try makeRequest(path: "/locations", method: "POST", body: row)
        let (_, code, body) = try await execute(req)
        guard (200..<300).contains(code) else {
            throw APIError.http(code, body)
        }
    }

    /// 查情侣的最近位置（对应 Android: RestService.getCoupleLocations）
    func getCoupleLocations(coupleId: String, limit: Int = 20) async throws -> [LocationRow] {
        let req = try makeRequest(
            path: "/locations",
            method: "GET",
            query: [
                URLQueryItem(name: "couple_id", value: "eq.\(coupleId)"),
                URLQueryItem(name: "order", value: "created_at.desc"),
                URLQueryItem(name: "limit", value: String(limit))
            ]
        )
        let (data, code, body) = try await execute(req)
        guard (200..<300).contains(code) else {
            throw APIError.http(code, body)
        }
        return (try? decoder.decode([LocationRow].self, from: data)) ?? []
    }

    // MARK: - REST: app_usage

    /// 上报 APP 使用（对应 Android: RestService.reportAppUsage）
    func reportAppUsage(_ row: AppUsageRow) async throws {
        let req = try makeRequest(path: "/app_usage", method: "POST", body: row)
        let (_, code, body) = try await execute(req)
        guard (200..<300).contains(code) else {
            throw APIError.http(code, body)
        }
    }

    /// 查某用户在指定日期范围内的 APP 使用记录
    /// 对应 Android: RestService.getAppUsageInRange
    func getAppUsage(userId: String, rangeDays: Int = 7) async throws -> [AppUsageRow] {
        let cal = Calendar.current
        let now = Date()
        let start = cal.date(byAdding: .day, value: -(rangeDays - 1), to: cal.startOfDay(for: now))!
        let end = cal.date(byAdding: .day, value: 1, to: start)!

        let fmt = ISO8601DateFormatter()
        let s = fmt.string(from: start)
        let e = fmt.string(from: end)
        // PostgREST and(gte.x,lt.y) 同一列多条件合并
        let filter = "and(gte.\(s),lt.\(e))"

        let req = try makeRequest(
            path: "/app_usage",
            method: "GET",
            query: [
                URLQueryItem(name: "user_id", value: "eq.\(userId)"),
                URLQueryItem(name: "created_at", value: filter),
                URLQueryItem(name: "order", value: "created_at.desc"),
                URLQueryItem(name: "limit", value: "1000")
            ]
        )
        let (data, code, body) = try await execute(req)
        guard (200..<300).contains(code) else {
            throw APIError.http(code, body)
        }
        return (try? decoder.decode([AppUsageRow].self, from: data)) ?? []
    }
}

// MARK: - AnyEncodable wrapper

/// 用于把任意 Encodable 装箱成 URLRequest.httpBody 可用类型
struct AnyEncodable: Encodable {
    private let _encode: (Encoder) throws -> Void
    init(_ wrapped: Encodable) {
        self._encode = wrapped.encode
    }
    func encode(to encoder: Encoder) throws {
        try _encode(encoder)
    }
}
