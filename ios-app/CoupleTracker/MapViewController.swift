//
//  MapViewController.swift
//  CoupleTracker
//
//  地图页：WKWebView 加载本地 index.html
//  对应 Android: MainActivity.PlaceholderScreen(useMapWebView = true)
//  - 加载 Bundle 资源 www/index.html
//  - 注入 Supabase URL / anon key / 当前用户（与 Android buildInjectionJs 一致）
//  - 不重建 WebView（切 Tab 回来保持实时轮询）
//

import UIKit
import WebKit

class MapViewController: UIViewController, WKNavigationDelegate, WKUIDelegate {

    private var webView: WKWebView?
    private var hasLoadedOnce = false

    // MARK: - 生命周期

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "地图"
        view.backgroundColor = UIColor(red: 0xFC/255, green: 0xE7/255, blue: 0xF3/255, alpha: 1)

        // 顶部「上报状态」按钮（点开显示最近上报信息）
        let statusBtn = UIBarButtonItem(
            image: UIImage(systemName: "antenna.radiowaves.left.and.right"),
            style: .plain, target: self, action: #selector(showReportStatus)
        )
        navigationItem.rightBarButtonItem = statusBtn

        setupWebView()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        // 切回 Tab 时注入最新用户信息 + invalidate 地图尺寸
        injectUserInfo()
        evaluateJS("try { var m = (typeof map !== 'undefined' && map); if (m) { m.invalidateSize(true); setTimeout(function(){m.invalidateSize(true);},300); } } catch(e){} try{ if(typeof poll==='function') poll(); } catch(e){}")
    }

    // MARK: - WebView 初始化

    private func setupWebView() {
        let cfg = WKWebViewConfiguration()
        cfg.preferences.javaScriptEnabled = true
        cfg.preferences.javaScriptCanOpenWindowsAutomatically = false
        // iOS14+ 默认所有跨域限制都默认开启，但加载 file:// 仍需 allowFileAccessFromFileURLs
        if #available(iOS 14.0, *) {
            // iOS14+ 的默认 WKWebViewConfiguration 已支持 file:// + localStorage
        }
        // 允许 file:// 加载外部 HTTPS 瓦片图
        cfg.setValue(true, forKey: "allowFileAccessFromFileURLs")
        cfg.setValue(true, forKey: "allowUniversalAccessFromFileURLs")
        // 让 HTML 内的 console.log 走 os_log（可选）
        let userContentController = WKUserContentController()
        cfg.userContentController = userContentController

        let wv = WKWebView(frame: .zero, configuration: cfg)
        wv.translatesAutoresizingMaskIntoConstraints = false
        wv.navigationDelegate = self
        wv.uiDelegate = self
        wv.backgroundColor = UIColor(red: 0xDA/255, green: 0xE1/255, blue: 0xE7/255, alpha: 1)
        wv.scrollView.backgroundColor = .clear
        wv.isOpaque = false
        // 允许 bounce 不影响地图拖动
        wv.scrollView.bounces = false
        view.addSubview(wv)
        NSLayoutConstraint.activate([
            wv.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            wv.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            wv.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            wv.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])
        self.webView = wv

        loadLocalHTML()
    }

    // MARK: - 加载本地 HTML

    private func loadLocalHTML() {
        guard let htmlURL = Bundle.main.url(forResource: "index", withExtension: "html",
                                            subdirectory: "www") else {
            // 兜底：找不到资源 → 显示提示
            webView?.loadHTMLString(fallbackHTML, baseURL: nil)
            return
        }
        let dir = htmlURL.deletingLastPathComponent()
        // hash 路由保留：#/map
        let targetURL = dir.appendingPathComponent("index.html")
        // file:// + allowFileAccessFromFileURLs + 上面配置 → 可以加载外部 https 瓦片
        var components = URLComponents(url: targetURL, resolvingAgainstBaseURL: false)
        components?.fragment = "map"
        if let url = components?.url {
            webView?.loadFileURL(url, allowingReadAccessTo: dir)
        } else {
            webView?.loadFileURL(targetURL, allowingReadAccessTo: dir)
        }
    }

    // MARK: - WKNavigationDelegate

    func webView(_ webView: WKWebView,
                 didStartProvisionalNavigation navigation: WKNavigation!) {
        // 加载开始：注入用户信息
        injectUserInfo()
    }

    func webView(_ webView: WKWebView,
                 didFinish navigation: WKNavigation!) {
        // 加载完成：再次注入 + 踢一脚前端刷新
        injectUserInfo()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) {
            self.evaluateJS("(function(){try{window.__applyAndroidInjection&&window.__applyAndroidInjection();}catch(e){}})();")
        }
        hasLoadedOnce = true
    }

    // MARK: - 注入用户信息

    /// 构造注入脚本（与 Android MainActivity.buildInjectionJs 一致）
    private func buildInjectionJS() -> String {
        let url = SupabaseConfig.url
        let anonKey = SupabaseConfig.anonKey
        let token = UserStore.shared.getToken()
        let tokenJs = (token?.isEmpty ?? true)
            ? "null"
            : "\"\(token!.replacingOccurrences(of: "\"", with: "\\\""))\""

        let user: UserInfo? = UserStore.shared.getUser()
        let userJson: String
        if let u = user {
            // 用 JSONSerialization 保证转义正确
            let dict: [String: Any?] = [
                "id": u.id,
                "username": u.username,
                "nickname": u.nickname,
                "avatar": u.avatar,
                "gender": u.gender,
                "coupleCode": u.coupleCode,
                "partnerId": u.partnerId ?? ""
            ]
            let cleaned: [String: Any] = dict.compactMapValues { $0 }
            let data = try? JSONSerialization.data(withJSONObject: cleaned)
            userJson = String(data: data ?? Data(), encoding: .utf8) ?? "null"
        } else {
            userJson = "null"
        }
        return """
        (function(){
          window.__SUPABASE_URL__ = "\(url)";
          window.__SUPABASE_ANON_KEY__ = "\(anonKey)";
          window.__AUTH_TOKEN__ = \(tokenJs);
          window.__CURRENT_USER__ = \(userJson);
          try {
            localStorage.setItem('sb_url',  window.__SUPABASE_URL__ || '');
            localStorage.setItem('sb_anon', window.__SUPABASE_ANON_KEY__ || '');
            localStorage.setItem('token',   window.__AUTH_TOKEN__ || '');
            localStorage.setItem('user',    typeof window.__CURRENT_USER__==='string' ? window.__CURRENT_USER__ : JSON.stringify(window.__CURRENT_USER__));
          } catch(e){}
          if (typeof window.__applyAndroidInjection === 'function') { try { window.__applyAndroidInjection(); } catch(e){} }
        })();
        """
    }

    private func injectUserInfo() {
        evaluateJS(buildInjectionJS())
    }

    private func evaluateJS(_ js: String) {
        guard let wv = webView else { return }
        wv.evaluateJavaScript(js) { _, _ in
            // 忽略错误（很多 undefined 调用会抛异常）
        }
    }

    // MARK: - WKUIDelegate（处理 JS alert/confirm）

    func webView(_ webView: WKWebView,
                 runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping () -> Void) {
        let a = UIAlertController(title: "提示", message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
        present(a, animated: true)
    }

    // MARK: - 上报状态弹窗

    @objc private func showReportStatus() {
        let msg = """
        📍 位置上报：\(UserStore.shared.lastLocReportStatus)
        📱 APP 上报：\(UserStore.shared.lastAppReportStatus)

        采集频率：位置 \(UserStore.shared.locationIntervalSec)s / 应用 \(UserStore.shared.appIntervalSec)s
        """
        let a = UIAlertController(title: "🛰️ 上报状态", message: msg, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "OK", style: .default))
        present(a, animated: true)
    }

    // MARK: - 资源找不到时的兜底页（与 Android buildFallbackMapHtml 一致）

    private let fallbackHTML = """
    <!doctype html><html><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title>情侣地图 💕</title>
    <style>html,body{margin:0;padding:0;height:100%;background:#fdf2f8;
      font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;}
    .c{display:flex;align-items:center;justify-content:center;height:100%;
      padding:24px;text-align:center;flex-direction:column;}
    h1{color:#e75480;font-size:22px;margin:0 0 10px;}
    p{color:#718096;font-size:13px;line-height:1.8;}
    .e{color:#e53e3e;}</style></head><body>
    <div class="c">
      <div style="font-size:56px;">🗺️</div>
      <h1>正在加载情侣地图</h1>
      <p>如果长时间停留在此页，请退出 APP 后重新打开一次。</p>
      <p class="e">如果持续报错：请确认已授予「定位」「通知」权限</p>
    </div>
    </body></html>
    """
}
