//
//  ProfileViewController.swift
//  CoupleTracker
//
//  我的页：账户信息 / 配对码 / 采集频率 / 退出登录
//  对应 Android: MainActivity.SettingsScreen
//

import UIKit

class ProfileViewController: UIViewController {

    private let scrollView = UIScrollView()
    private let container = UIStackView()

    private var hasPartner: Bool? = nil
    private var partnerName: String = ""
    private var pairInput: String = ""
    private var pairMsg: String = ""
    private var pairLoading: Bool = false
    private var copyTip: String = ""

    // 控件引用
    private var pairCodeLabel: UILabel?
    private var pairCodeCopyBtn: UIButton?
    private var partnerCard: UIView?
    private var pairInputField: UITextField?
    private var pairStatusLabel: UILabel?
    private var pairActionButton: UIButton?
    private var locSlider: UISlider?
    private var locValueLabel: UILabel?
    private var appSlider: UISlider?
    private var appValueLabel: UILabel?
    private var locStatusLabel: UILabel?
    private var appStatusLabel: UILabel?

    private let pinkColor = UIColor(red: 231/255, green: 84/255, blue: 128/255, alpha: 1)
    private let blueColor = UIColor(red: 102/255, green: 126/255, blue: 234/255, alpha: 1)

    // MARK: - 生命周期

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "我的"
        view.backgroundColor = UIColor(red: 0xFC/255, green: 0xE7/255, blue: 0xF3/255, alpha: 1)

        setupLayout()
        refreshUser()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        refreshUser()
        // 上报状态显示
        locStatusLabel?.text = "📍 \(UserStore.shared.lastLocReportStatus)"
        appStatusLabel?.text = "📱 \(UserStore.shared.lastAppReportStatus)"
        checkPartnerStatus()
    }

    // MARK: - 布局

    private func setupLayout() {
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.alwaysBounceVertical = true
        view.addSubview(scrollView)
        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])

        container.translatesAutoresizingMaskIntoConstraints = false
        container.axis = .vertical
        container.alignment = .fill
        container.spacing = 14
        scrollView.addSubview(container)

        NSLayoutConstraint.activate([
            container.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor, constant: 24),
            container.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor, constant: -24),
            container.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor, constant: 20),
            container.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor, constant: -20),
            container.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor, constant: -40)
        ])
    }

    private func refreshUser() {
        container.arrangedSubviews.forEach { $0.removeFromSuperview() }

        let user = UserStore.shared.getUser()
        let name = user?.displayName ?? "未登录"
        let username = user?.username ?? "-"

        // 顶部用户卡片
        let userCard = makeCard { vStack in
            let row = UIView()
            let emoji = UILabel()
            emoji.text = "💕"
            emoji.font = .systemFont(ofSize: 48)
            emoji.setContentHuggingPriority(.required, for: .horizontal)
            let nameStack = UIStackView(arrangedSubviews: [
                self.makeLabel(name, font: .systemFont(ofSize: 22, weight: .bold),
                               color: UIColor(red: 45/255, green: 55/255, blue: 72/255, alpha: 1)),
                self.makeLabel("@" + username, font: .systemFont(ofSize: 13),
                               color: UIColor(red: 113/255, green: 128/255, blue: 150/255, alpha: 1))
            ])
            nameStack.axis = .vertical
            nameStack.spacing = 2
            let hRow = UIStackView(arrangedSubviews: [emoji, nameStack])
            hRow.axis = .horizontal
            hRow.alignment = .center
            hRow.spacing = 12
            vStack.addArrangedSubview(hRow)
        }
        container.addArrangedSubview(userCard)

        // 配对 / 已配对卡片（占位）
        if hasPartner == true {
            container.addArrangedSubview(buildPartnerCard())
        } else {
            container.addArrangedSubview(buildPairCodeCard())
            container.addArrangedSubview(buildPairInputCard())
        }

        // 采集频率卡片
        container.addArrangedSubview(buildFrequencyCard())

        // 账号管理卡片
        container.addArrangedSubview(buildAccountCard())

        // 底部版本号
        let versionLabel = makeLabel(
            "版本 0.170（iOS）\n后端 \(SupabaseConfig.url)",
            font: .systemFont(ofSize: 10),
            color: UIColor(red: 113/255, green: 128/255, blue: 150/255, alpha: 1),
            alignment: .center
        )
        versionLabel.numberOfLines = 0
        container.addArrangedSubview(versionLabel)
    }

    // MARK: - 卡片构造

    private func makeCard(_ build: (UIStackView) -> Void) -> UIView {
        let card = UIView()
        card.backgroundColor = .white
        card.layer.cornerRadius = 16
        card.layer.masksToBounds = true
        card.layer.shadowColor = UIColor.black.cgColor
        card.layer.shadowOffset = CGSize(width: 0, height: 2)
        card.layer.shadowOpacity = 0.04
        card.layer.shadowRadius = 6
        let vStack = UIStackView()
        vStack.axis = .vertical
        vStack.spacing = 8
        vStack.translatesAutoresizingMaskIntoConstraints = false
        vStack.isLayoutMarginsRelativeArrangement = true
        vStack.layoutMargins = UIEdgeInsets(top: 16, left: 16, bottom: 16, right: 16)
        card.addSubview(vStack)
        NSLayoutConstraint.activate([
            vStack.topAnchor.constraint(equalTo: card.topAnchor),
            vStack.bottomAnchor.constraint(equalTo: card.bottomAnchor),
            vStack.leadingAnchor.constraint(equalTo: card.leadingAnchor),
            vStack.trailingAnchor.constraint(equalTo: card.trailingAnchor)
        ])
        build(vStack)
        return card
    }

    private func makeLabel(_ text: String, font: UIFont, color: UIColor,
                           alignment: NSTextAlignment = .left) -> UILabel {
        let l = UILabel()
        l.text = text
        l.font = font
        l.textColor = color
        l.textAlignment = alignment
        return l
    }

    private func buildPartnerCard() -> UIView {
        return makeCard { vStack in
            let title = self.makeLabel(
                "❤️ 已与 \(self.partnerName.isEmpty ? "TA" : self.partnerName) 绑定",
                font: .systemFont(ofSize: 18, weight: .heavy),
                color: UIColor(red: 47/255, green: 133/255, blue: 90/255, alpha: 1)
            )
            let sub = self.makeLabel(
                "💡 去地图页查看彼此实时位置\n💡 去应用/统计页查看TA的动态",
                font: .systemFont(ofSize: 12),
                color: UIColor(red: 56/255, green: 161/255, blue: 105/255, alpha: 1)
            )
            sub.numberOfLines = 0
            vStack.addArrangedSubview(title)
            vStack.addArrangedSubview(sub)
        }
    }

    private func buildPairCodeCard() -> UIView {
        let card = makeCard { vStack in
            vStack.addArrangedSubview(self.makeLabel("配对码",
                font: .systemFont(ofSize: 12),
                color: UIColor(red: 113/255, green: 128/255, blue: 150/255, alpha: 1)))
            let user = UserStore.shared.getUser()
            let code = (user?.coupleCode ?? "").uppercased()
            let codeLabel = self.makeLabel(code.isEmpty ? "暂无" : code,
                font: .systemFont(ofSize: 30, weight: .heavy),
                color: self.pinkColor)
            self.pairCodeLabel = codeLabel

            let copyBtn = UIButton(type: .system)
            copyBtn.setTitle("📋 复制", for: .normal)
            copyBtn.setTitleColor(self.pinkColor, for: .normal)
            copyBtn.titleLabel?.font = .systemFont(ofSize: 12)
            copyBtn.layer.borderWidth = 1
            copyBtn.layer.borderColor = self.pinkColor.cgColor
            copyBtn.layer.cornerRadius = 16
            copyBtn.contentEdgeInsets = UIEdgeInsets(top: 4, left: 10, bottom: 4, right: 10)
            copyBtn.addTarget(self, action: #selector(self.copyPairCode), for: .touchUpInside)
            self.pairCodeCopyBtn = copyBtn

            let hStack = UIStackView(arrangedSubviews: [codeLabel, copyBtn])
            hStack.axis = .horizontal
            hStack.alignment = .center
            hStack.distribution = .equalSpacing
            vStack.addArrangedSubview(hStack)

            let hint = self.makeLabel(
                "把这串码发给TA，让TA在下方或登录页「配对」输入即可绑定",
                font: .systemFont(ofSize: 12),
                color: UIColor(red: 113/255, green: 128/255, blue: 150/255, alpha: 1)
            )
            hint.numberOfLines = 0
            vStack.addArrangedSubview(hint)
        }
        return card
    }

    private func buildPairInputCard() -> UIView {
        let card = makeCard { vStack in
            vStack.addArrangedSubview(self.makeLabel("🔗 还没绑定？在这里输入TA的配对码",
                font: .systemFont(ofSize: 15, weight: .bold),
                color: UIColor(red: 45/255, green: 55/255, blue: 72/255, alpha: 1)))

            let field = self.makeTextField(placeholder: "TA 的配对码（6 位）")
            field.text = self.pairInput
            field.addTarget(self, action: #selector(self.pairInputChanged(_:)),
                            for: .editingChanged)
            self.pairInputField = field
            vStack.addArrangedSubview(field)

            let statusLabel = self.makeLabel(self.pairMsg,
                font: .systemFont(ofSize: 12),
                color: UIColor.red)
            statusLabel.numberOfLines = 0
            self.pairStatusLabel = statusLabel
            vStack.addArrangedSubview(statusLabel)

            let btn = UIButton(type: .system)
            btn.backgroundColor = self.blueColor
            btn.tintColor = .white
            btn.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
            btn.setTitle("立即配对 💕", for: .normal)
            btn.heightAnchor.constraint(equalToConstant: 48).isActive = true
            btn.layer.cornerRadius = 24
            btn.layer.masksToBounds = true
            btn.addTarget(self, action: #selector(self.doPair), for: .touchUpInside)
            self.pairActionButton = btn
            vStack.addArrangedSubview(btn)
        }
        return card
    }

    private func buildFrequencyCard() -> UIView {
        return makeCard { vStack in
            vStack.addArrangedSubview(self.makeLabel("⚙️ 采集频率（调大可降低卡顿/省电）",
                font: .systemFont(ofSize: 15, weight: .bold),
                color: UIColor(red: 45/255, green: 55/255, blue: 72/255, alpha: 1)))

            // 位置 slider
            let locHeader = UIStackView(arrangedSubviews: [
                self.makeLabel("📍 位置上报", font: .systemFont(ofSize: 13, weight: .semibold),
                               color: UIColor(red: 74/255, green: 85/255, blue: 104/255, alpha: 1))
            ])
            vStack.addArrangedSubview(locHeader)

            let locValue = self.makeLabel(
                "每 \(UserStore.shared.locationIntervalSec) 秒",
                font: .systemFont(ofSize: 18, weight: .heavy),
                color: self.pinkColor
            )
            self.locValueLabel = locValue
            let locRow = UIStackView(arrangedSubviews: [
                locValue,
                self.makeLabel("范围 \(UserStore.minLocIntervalSec)-\(UserStore.maxLocIntervalSec)s",
                    font: .systemFont(ofSize: 10),
                    color: UIColor.systemGray3, alignment: .right)
            ])
            locRow.axis = .horizontal
            locRow.distribution = .equalSpacing
            vStack.addArrangedSubview(locRow)

            let locSlider = UISlider()
            locSlider.minimumValue = Float(UserStore.minLocIntervalSec)
            locSlider.maximumValue = Float(UserStore.maxLocIntervalSec)
            locSlider.value = Float(UserStore.shared.locationIntervalSec)
            locSlider.tintColor = self.pinkColor
            locSlider.addTarget(self, action: #selector(self.locSliderChanged(_:)),
                                for: .valueChanged)
            vStack.addArrangedSubview(locSlider)
            self.locSlider = locSlider

            vStack.addArrangedSubview(self.makeSpacer(10))

            // APP slider
            vStack.addArrangedSubview(self.makeLabel("📱 APP 使用检测",
                font: .systemFont(ofSize: 13, weight: .semibold),
                color: UIColor(red: 74/255, green: 85/255, blue: 104/255, alpha: 1)))

            let appValue = self.makeLabel(
                "每 \(UserStore.shared.appIntervalSec) 秒",
                font: .systemFont(ofSize: 18, weight: .heavy),
                color: self.blueColor
            )
            self.appValueLabel = appValue
            let appRow = UIStackView(arrangedSubviews: [
                appValue,
                self.makeLabel("范围 \(UserStore.minAppIntervalSec)-\(UserStore.maxAppIntervalSec)s",
                    font: .systemFont(ofSize: 10),
                    color: UIColor.systemGray3, alignment: .right)
            ])
            appRow.axis = .horizontal
            appRow.distribution = .equalSpacing
            vStack.addArrangedSubview(appRow)

            let appSlider = UISlider()
            appSlider.minimumValue = Float(UserStore.minAppIntervalSec)
            appSlider.maximumValue = Float(UserStore.maxAppIntervalSec)
            appSlider.value = Float(UserStore.shared.appIntervalSec)
            appSlider.tintColor = self.blueColor
            appSlider.addTarget(self, action: #selector(self.appSliderChanged(_:)),
                                for: .valueChanged)
            vStack.addArrangedSubview(appSlider)
            self.appSlider = appSlider

            vStack.addArrangedSubview(self.makeSpacer(6))
            vStack.addArrangedSubview(self.makeLabel("✅ 调整后立即生效，无需重启APP",
                font: .systemFont(ofSize: 11, weight: .medium),
                color: UIColor(red: 72/255, green: 187/255, blue: 120/255, alpha: 1)))

            // 上报状态显示
            let div = UIView()
            div.backgroundColor = UIColor.systemGray6
            div.heightAnchor.constraint(equalToConstant: 1).isActive = true
            vStack.addArrangedSubview(div)
            vStack.addArrangedSubview(self.makeSpacer(4))
            vStack.addArrangedSubview(self.makeLabel("🛰️ 上报状态 · 供排查参考",
                font: .systemFont(ofSize: 12, weight: .semibold),
                color: UIColor(red: 74/255, green: 85/255, blue: 104/255, alpha: 1)))

            let locStatus = self.makeLabel("📍 \(UserStore.shared.lastLocReportStatus)",
                font: .systemFont(ofSize: 10), color: UIColor.systemGray)
            locStatus.numberOfLines = 0
            self.locStatusLabel = locStatus
            vStack.addArrangedSubview(locStatus)

            let appStatus = self.makeLabel("📱 \(UserStore.shared.lastAppReportStatus)",
                font: .systemFont(ofSize: 10), color: UIColor.systemGray)
            appStatus.numberOfLines = 0
            self.appStatusLabel = appStatus
            vStack.addArrangedSubview(appStatus)

            let tip = self.makeLabel(
                "如果「位置上报」连续失败：设置 → 隐私 → 定位服务 → 找到「小世界」 → 始终 → 再打开一次APP",
                font: .systemFont(ofSize: 10),
                color: UIColor.systemGray3)
            tip.numberOfLines = 0
            vStack.addArrangedSubview(tip)
        }
    }

    private func buildAccountCard() -> UIView {
        return makeCard { vStack in
            vStack.addArrangedSubview(self.makeLabel("👤 账号管理",
                font: .systemFont(ofSize: 15, weight: .bold),
                color: UIColor(red: 45/255, green: 55/255, blue: 72/255, alpha: 1)))

            let div = UIView()
            div.backgroundColor = UIColor.systemGray6
            div.heightAnchor.constraint(equalToConstant: 1).isActive = true
            vStack.addArrangedSubview(div)

            let user = UserStore.shared.getUser()
            vStack.addArrangedSubview(self.makeRow("账号", user?.username ?? "-"))
            vStack.addArrangedSubview(self.makeRow("昵称", user?.displayName ?? "-"))
            vStack.addArrangedSubview(self.makeRow("配对",
                self.hasPartner == true ? "已与 \(self.partnerName.isEmpty ? "TA" : self.partnerName) 绑定" : "未配对"))

            let btn = UIButton(type: .system)
            btn.setTitle("退出登录", for: .normal)
            btn.tintColor = UIColor(red: 229/255, green: 62/255, blue: 62/255, alpha: 1)
            btn.titleLabel?.font = .systemFont(ofSize: 14)
            btn.heightAnchor.constraint(equalToConstant: 48).isActive = true
            btn.layer.borderWidth = 1
            btn.layer.borderColor = UIColor(red: 229/255, green: 62/255, blue: 62/255, alpha: 1).cgColor
            btn.layer.cornerRadius = 24
            btn.layer.masksToBounds = true
            btn.addTarget(self, action: #selector(self.logout), for: .touchUpInside)
            vStack.addArrangedSubview(btn)
        }
    }

    private func makeRow(_ k: String, _ v: String) -> UIStackView {
        let key = makeLabel(k, font: .systemFont(ofSize: 13),
                            color: UIColor.systemGray)
        key.setContentHuggingPriority(.required, for: .horizontal)
        let val = makeLabel(v, font: .systemFont(ofSize: 13),
                            color: UIColor(red: 45/255, green: 55/255, blue: 72/255, alpha: 1))
        let stack = UIStackView(arrangedSubviews: [key, val])
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = 8
        return stack
    }

    private func makeTextField(placeholder: String) -> UITextField {
        let f = UITextField()
        f.placeholder = placeholder
        f.font = .systemFont(ofSize: 16)
        f.backgroundColor = UIColor.systemGray6
        f.layer.cornerRadius = 12
        f.layer.masksToBounds = true
        f.borderStyle = .none
        f.heightAnchor.constraint(equalToConstant: 48).isActive = true
        f.autocapitalizationType = .allCharacters
        f.autocorrectionType = .no
        f.returnKeyType = .done
        let left = UIView(frame: CGRect(x: 0, y: 0, width: 12, height: 48))
        f.leftView = left
        f.leftViewMode = .always
        return f
    }

    private func makeSpacer(_ h: CGFloat) -> UIView {
        let v = UIView()
        v.heightAnchor.constraint(equalToConstant: h).isActive = true
        return v
    }

    // MARK: - 配对状态检查

    private func checkPartnerStatus() {
        guard let me = UserStore.shared.getUser() else { return }
        let uuidRegex = try! NSRegularExpression(
            pattern: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            options: .caseInsensitive
        )
        let partnerId = me.partnerId ?? ""
        let range = NSRange(partnerId.startIndex..<partnerId.endIndex, in: partnerId)
        if !partnerId.isEmpty && uuidRegex.firstMatch(in: partnerId, range: range) != nil {
            hasPartner = true
            if partnerName.isEmpty {
                Task { [weak self] in
                    guard let self = self else { return }
                    if let partner = try? await APIClient.shared.getProfile(id: partnerId) {
                        let name = partner.nickname.isEmpty ? partner.username : partner.nickname
                        await MainActor.run {
                            self.partnerName = name
                            self.refreshUser()
                        }
                    }
                }
            }
        } else {
            hasPartner = false
            partnerName = ""
        }
        refreshUser()
    }

    // MARK: - 事件

    @objc private func copyPairCode() {
        guard let user = UserStore.shared.getUser(), !user.coupleCode.isEmpty else { return }
        UIPasteboard.general.string = user.coupleCode.uppercased()
        pairCodeCopyBtn?.setTitle("✅ 已复制", for: .normal)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            self?.pairCodeCopyBtn?.setTitle("📋 复制", for: .normal)
        }
    }

    @objc private func pairInputChanged(_ f: UITextField) {
        let t = (f.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        f.text = t
        pairInput = t
    }

    @objc private func doPair() {
        guard let me = UserStore.shared.getUser() else {
            pairStatusLabel?.text = "账号信息丢失，请重登"; return
        }
        let theirCode = pairInput.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if theirCode.count < 4 {
            pairStatusLabel?.text = "请输入完整的TA的配对码（至少4位）💕"; return
        }
        pairLoading = true
        pairActionButton?.isEnabled = false
        pairActionButton?.setTitle("配对中...", for: .normal)
        pairStatusLabel?.text = ""

        Task { [weak self] in
            guard let self = self else { return }
            do {
                let resp = try await APIClient.shared.pairByCode(myId: me.id, theirCode: theirCode)
                await MainActor.run {
                    self.pairLoading = false
                    self.pairActionButton?.isEnabled = true
                    self.pairActionButton?.setTitle("立即配对 💕", for: .normal)
                    if resp.ok == true {
                        var newMe = me
                        newMe.partnerId = resp.their_id
                        UserStore.shared.setUser(newMe)
                        let nick = (resp.their_nickname?.isEmpty ?? true) ? "TA" : (resp.their_nickname ?? "TA")
                        self.pairStatusLabel?.text = "✅ 配对成功！已和 \(nick) 绑定"
                        // 刷新 UI 显示已配对状态
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                            self?.checkPartnerStatus()
                        }
                    } else if let reason = resp.reason {
                        self.pairStatusLabel?.text = self.translatePairReason(reason)
                    } else {
                        self.pairStatusLabel?.text = resp.msg ?? "配对失败，请稍后再试"
                    }
                }
            } catch {
                await MainActor.run {
                    self.pairLoading = false
                    self.pairActionButton?.isEnabled = true
                    self.pairActionButton?.setTitle("立即配对 💕", for: .normal)
                    self.pairStatusLabel?.text = "配对失败：\(error.localizedDescription.prefix(50))"
                }
            }
        }
    }

    @objc private func locSliderChanged(_ s: UISlider) {
        let v = Int(s.value.rounded())
        locValueLabel?.text = "每 \(v) 秒"
        UserStore.shared.locationIntervalSec = v
        LocationManager.shared.restart()
    }

    @objc private func appSliderChanged(_ s: UISlider) {
        let v = Int(s.value.rounded())
        appValueLabel?.text = "每 \(v) 秒"
        UserStore.shared.appIntervalSec = v
    }

    @objc private func logout() {
        let alert = UIAlertController(
            title: "退出登录", message: "确认退出当前账号？所有本地数据将被清除。",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "退出", style: .destructive) { _ in
            UserStore.shared.logout()
            LocationManager.shared.stop()
            if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
               let delegate = scene.delegate as? SceneDelegate {
                delegate.showLogin()
            }
        })
        present(alert, animated: true)
    }

    // MARK: - 错误翻译

    private func translatePairReason(_ reason: String) -> String {
        switch reason {
        case "CODE_NOT_FOUND": return "❌ 配对码不存在：让TA打开「我的」页确认TA的码"
        case "CANNOT_PAIR_SELF": return "😅 不能和自己配对哦"
        case "ME_NOT_FOUND": return "账号信息丢失，请退出后重新登录"
        case "INVALID_ARGS": return "参数错误：请确认输入的是完整的 6 位字母+数字码"
        default: return "配对失败：\(reason)"
        }
    }
}
