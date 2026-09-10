//
//  LoginViewController.swift
//  CoupleTracker
//
//  登录/注册页（程序化 UIKit）
//  对应 Android: LoginActivity.kt
//  - 单页多步骤：登录/注册 → 配对 → 权限引导
//  - 调 register_user RPC + verify_login RPC（与 Android 完全一致的认证流程）
//

import UIKit
import CoreLocation

class LoginViewController: UIViewController {

    // MARK: - 步骤

    private enum Step { case login, pair, perms }

    private var step: Step = .login {
        didSet { rebuildForStep() }
    }

    // MARK: - 登录页状态

    private var mode: String = "login" // login | register
    private var username: String = ""
    private var password: String = ""
    private var displayName: String = ""
    private var genderIdx: Int = 0
    private var pwdVisible: Bool = false
    private var loading: Bool = false
    private var pairCode: String = ""

    // MARK: - 配对页状态

    private var pairInput: String = ""
    private var pairMsg: String = ""
    private var pairLoading: Bool = false
    private var copyTip: String = ""

    // MARK: - 通用 UI

    private let scroll = UIScrollView()
    private let container = UIStackView()
    private var msgLabel: UILabel?
    private var modeSegmented: UISegmentedControl?
    private var usernameField: UITextField?
    private var passwordField: UITextField?
    private var nicknameField: UITextField?
    private var genderSegmented: UISegmentedControl?
    private var actionButton: UIButton?
    private var pairCodeLabel: UILabel?
    private var pairCodeCopyBtn: UIButton?
    private var pairInputField: UITextField?
    private var pairStatusLabel: UILabel?
    private var pairActionButton: UIButton?

    private let pinkColor = UIColor(red: 231/255, green: 84/255, blue: 128/255, alpha: 1)
    private let blueColor = UIColor(red: 102/255, green: 126/255, blue: 234/255, alpha: 1)
    private let bgPink = UIColor(red: 1.0, green: 0xF2/255, blue: 0xF8/255, alpha: 1)

    // MARK: - 生命周期

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        rebuildForStep()
    }

    private func rebuildForStep() {
        view.subviews.forEach { $0.removeFromSuperview() }
        switch step {
        case .login: buildLoginScreen()
        case .pair:  buildPairScreen()
        case .perms: buildPermsScreen()
        }
    }

    // MARK: - 渐变背景

    /// 用 CAGradientLayer 模拟 Android linearGradient
    private func applyGradientBackground() -> CAGradientLayer {
        let g = CAGradientLayer()
        g.colors = [
            UIColor(red: 1.0, green: 107/255, blue: 157/255, alpha: 1).cgColor,
            UIColor(red: 213/255, green: 63/255, blue: 140/255, alpha: 1).cgColor,
            blueColor.cgColor
        ]
        g.startPoint = CGPoint(x: 0, y: 0)
        g.endPoint = CGPoint(x: 1, y: 1)
        g.frame = view.bounds
        view.layer.insertSublayer(g, at: 0)
        return g
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        (view.layer.sublayers?.first as? CAGradientLayer)?.frame = view.bounds
        // scroll 内 content size
        scroll.frame = view.bounds
        // 让 container 在 scroll 内有合适的 content size
        let h = container.systemLayoutSizeFitting(
            CGSize(width: view.bounds.width - 56, height: 0),
            withHorizontalFittingPriority: .required,
            verticalFittingPriority: .fittingSizeLevel
        ).height
        container.frame = CGRect(x: 28, y: 36, width: view.bounds.width - 56, height: h)
        scroll.contentSize = CGSize(width: view.bounds.width, height: h + 72)
    }

    // MARK: - 步骤 1：登录/注册

    private func buildLoginScreen() {
        _ = applyGradientBackground()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.alwaysBounceVertical = true
        view.addSubview(scroll)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])

        container.translatesAutoresizingMaskIntoConstraints = false
        container.axis = .vertical
        container.alignment = .fill
        container.spacing = 12
        scroll.addSubview(container)

        // 标题
        let title = UILabel()
        title.text = "小世界"
        title.font = .systemFont(ofSize: 32, weight: .bold)
        title.textColor = .white
        title.textAlignment = .center

        let spacerSmall = makeSpacer(20)

        // 模式切换 segmented
        let seg = UISegmentedControl(items: ["登录", "注册"])
        seg.selectedSegmentIndex = 0
        seg.backgroundColor = UIColor.white.withAlphaComponent(0.2)
        seg.selectedSegmentTintColor = .white
        seg.setTitleTextAttributes([.foregroundColor: pinkColor, .font: UIFont.boldSystemFont(ofSize: 14)],
                                  for: .selected)
        seg.setTitleTextAttributes([.foregroundColor: UIColor.white], for: .normal)
        seg.addTarget(self, action: #selector(modeChanged(_:)), for: .valueChanged)
        self.modeSegmented = seg

        // 用户名 / 密码 / 昵称（注册才显示）
        let userField = makeTextField(placeholder: "用户名", icon: "👤")
        userField.text = username
        userField.addTarget(self, action: #selector(textChanged(_:)), for: .editingChanged)
        userField.tag = 100
        self.usernameField = userField

        let passField = makeTextField(placeholder: "密码", icon: "🔒")
        passField.text = password
        passField.isSecureTextEntry = !pwdVisible
        passField.keyboardType = .default
        passField.returnKeyType = .done
        passField.rightView = makeEyeButton()
        passField.rightViewMode = .always
        passField.addTarget(self, action: #selector(textChanged(_:)), for: .editingChanged)
        passField.tag = 101
        self.passwordField = passField

        let nickField = makeTextField(placeholder: "昵称（在地图上显示）", icon: "💗")
        nickField.text = displayName
        nickField.addTarget(self, action: #selector(textChanged(_:)), for: .editingChanged)
        nickField.tag = 102
        self.nicknameField = nickField

        // 性别选择
        let gender = UISegmentedControl(items: ["女生", "男生"])
        gender.selectedSegmentIndex = genderIdx
        gender.backgroundColor = UIColor.white.withAlphaComponent(0.2)
        gender.selectedSegmentTintColor = pinkColor
        gender.setTitleTextAttributes([.foregroundColor: UIColor.white], for: .normal)
        gender.setTitleTextAttributes([.foregroundColor: UIColor.white], for: .selected)
        gender.addTarget(self, action: #selector(genderChanged(_:)), for: .valueChanged)
        self.genderSegmented = gender

        // 提示标签
        let msg = UILabel()
        msg.numberOfLines = 0
        msg.font = .systemFont(ofSize: 13)
        msg.textColor = .white
        msg.textAlignment = .center
        self.msgLabel = msg

        // 主按钮
        let btn = UIButton(type: .system)
        btn.backgroundColor = pinkColor
        btn.tintColor = .white
        btn.titleLabel?.font = .systemFont(ofSize: 18, weight: .semibold)
        btn.setTitle("登录", for: .normal)
        btn.heightAnchor.constraint(equalToConstant: 52).isActive = true
        btn.layer.cornerRadius = 26
        btn.layer.masksToBounds = true
        btn.addTarget(self, action: #selector(doAuth), for: .touchUpInside)
        self.actionButton = btn

        container.addArrangedSubview(title)
        container.addArrangedSubview(spacerSmall)
        container.addArrangedSubview(seg)
        container.addArrangedSubview(makeSpacer(12))
        container.addArrangedSubview(userField)
        container.addArrangedSubview(passField)
        // 注册模式才显示昵称/性别
        if mode == "register" {
            container.addArrangedSubview(makeSpacer(12))
            container.addArrangedSubview(nickField)
            container.addArrangedSubview(makeSpacer(12))
            container.addArrangedSubview(gender)
            container.addArrangedSubview(makeSpacer(6))
            container.addArrangedSubview(makeRulesCard())
        }
        container.addArrangedSubview(makeSpacer(26))
        container.addArrangedSubview(btn)
        container.addArrangedSubview(makeSpacer(10))
        container.addArrangedSubview(msg)
    }

    // MARK: - 步骤 2：配对

    private func buildPairScreen() {
        _ = applyGradientBackground()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])

        container.translatesAutoresizingMaskIntoConstraints = false
        container.axis = .vertical
        container.alignment = .fill
        container.spacing = 12
        scroll.addSubview(container)

        // 标题
        let emoji = UILabel()
        emoji.text = "💑"
        emoji.font = .systemFont(ofSize: 72)
        emoji.textAlignment = .center
        let title = UILabel()
        title.text = "邀请TA，开启旅程"
        title.font = .systemFont(ofSize: 24, weight: .bold)
        title.textColor = .white
        title.textAlignment = .center

        // 配对码卡片
        let cardView = UIView()
        cardView.backgroundColor = .white
        cardView.layer.cornerRadius = 16
        cardView.layer.masksToBounds = true

        let myCodeLabel = UILabel()
        myCodeLabel.text = "我的配对码："
        myCodeLabel.font = .systemFont(ofSize: 13)
        myCodeLabel.textColor = UIColor(red: 113/255, green: 128/255, blue: 150/255, alpha: 1)

        let codeLabel = UILabel()
        codeLabel.text = pairCode.isEmpty ? "等待生成..." : pairCode
        codeLabel.font = .systemFont(ofSize: 32, weight: .heavy)
        codeLabel.textColor = pinkColor
        codeLabel.textAlignment = .left
        self.pairCodeLabel = codeLabel

        let copyBtn = UIButton(type: .system)
        copyBtn.setTitle("📋 复制", for: .normal)
        copyBtn.setTitleColor(pinkColor, for: .normal)
        copyBtn.titleLabel?.font = .systemFont(ofSize: 12)
        copyBtn.layer.borderWidth = 1
        copyBtn.layer.borderColor = pinkColor.cgColor
        copyBtn.layer.cornerRadius = 16
        copyBtn.contentEdgeInsets = UIEdgeInsets(top: 4, left: 10, bottom: 4, right: 10)
        copyBtn.addTarget(self, action: #selector(copyPairCode), for: .touchUpInside)
        self.pairCodeCopyBtn = copyBtn

        let hintLabel = UILabel()
        hintLabel.text = "把这串6位码发给TA，让TA在下面输入 👇"
        hintLabel.font = .systemFont(ofSize: 12)
        hintLabel.textColor = UIColor(red: 113/255, green: 128/255, blue: 150/255, alpha: 1)
        hintLabel.numberOfLines = 0

        let hStack = UIStackView(arrangedSubviews: [codeLabel, copyBtn])
        hStack.axis = .horizontal
        hStack.alignment = .center
        hStack.distribution = .fill

        let vStack = UIStackView(arrangedSubviews: [myCodeLabel, hStack, hintLabel])
        vStack.axis = .vertical
        vStack.spacing = 6
        vStack.translatesAutoresizingMaskIntoConstraints = false
        vStack.isLayoutMarginsRelativeArrangement = true
        vStack.layoutMargins = UIEdgeInsets(top: 16, left: 16, bottom: 16, right: 16)

        cardView.addSubview(vStack)
        NSLayoutConstraint.activate([
            vStack.topAnchor.constraint(equalTo: cardView.topAnchor),
            vStack.bottomAnchor.constraint(equalTo: cardView.bottomAnchor),
            vStack.leadingAnchor.constraint(equalTo: cardView.leadingAnchor),
            vStack.trailingAnchor.constraint(equalTo: cardView.trailingAnchor)
        ])

        // 输入 TA 的配对码
        let inputHint = UILabel()
        inputHint.text = "输入TA的配对码"
        inputHint.font = .systemFont(ofSize: 14)
        inputHint.textColor = .white

        let inputField = makeTextField(placeholder: "TA的配对码", icon: "🔗")
        inputField.text = pairInput
        inputField.addTarget(self, action: #selector(pairInputChanged(_:)), for: .editingChanged)
        self.pairInputField = inputField

        let statusLabel = UILabel()
        statusLabel.numberOfLines = 0
        statusLabel.font = .systemFont(ofSize: 13)
        statusLabel.textColor = .white
        statusLabel.text = pairMsg
        self.pairStatusLabel = statusLabel

        let pairBtn = UIButton(type: .system)
        pairBtn.backgroundColor = blueColor
        pairBtn.tintColor = .white
        pairBtn.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        pairBtn.setTitle("立即配对 💕", for: .normal)
        pairBtn.heightAnchor.constraint(equalToConstant: 52).isActive = true
        pairBtn.layer.cornerRadius = 26
        pairBtn.layer.masksToBounds = true
        pairBtn.addTarget(self, action: #selector(doPair), for: .touchUpInside)
        self.pairActionButton = pairBtn

        let skipBtn = UIButton(type: .system)
        skipBtn.setTitle("稍后再说，先进入APP →", for: .normal)
        skipBtn.setTitleColor(.white, for: .normal)
        skipBtn.titleLabel?.font = .systemFont(ofSize: 13)
        skipBtn.addTarget(self, action: #selector(goPerms), for: .touchUpInside)

        container.addArrangedSubview(emoji)
        container.addArrangedSubview(title)
        container.addArrangedSubview(makeSpacer(18))
        container.addArrangedSubview(cardView)
        container.addArrangedSubview(makeSpacer(18))
        container.addArrangedSubview(inputHint)
        container.addArrangedSubview(makeSpacer(8))
        container.addArrangedSubview(inputField)
        container.addArrangedSubview(statusLabel)
        container.addArrangedSubview(makeSpacer(16))
        container.addArrangedSubview(pairBtn)
        container.addArrangedSubview(makeSpacer(10))
        container.addArrangedSubview(skipBtn)
    }

    // MARK: - 步骤 3：权限引导

    private func buildPermsScreen() {
        _ = applyGradientBackground()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])

        container.translatesAutoresizingMaskIntoConstraints = false
        container.axis = .vertical
        container.alignment = .fill
        container.spacing = 10
        scroll.addSubview(container)

        let title = makeWhiteLabel("🔐 开开启必要权限", size: 26, weight: .bold)
        let desc = makeWhiteLabel("为了实时报备给TA，我们需要这些权限", size: 14, weight: .regular, alpha: 0.85)

        // 三个权限卡片
        let permCards = [
            permCard(icon: "📍", title: "前台定位",
                     desc: "在地图上显示你的位置", granted: LocationManager.shared.hasWhenInUsePermission),
            permCard(icon: "📡", title: "后台定位",
                     desc: "切到后台后也能让TA看到你", granted: LocationManager.shared.hasAlwaysPermission),
            permCard(icon: "🔔", title: "通知",
                     desc: "用于上报状态提示", granted: false)
        ]

        let tipCard = UIView()
        tipCard.backgroundColor = UIColor.white.withAlphaComponent(0.15)
        tipCard.layer.cornerRadius = 16
        tipCard.layer.masksToBounds = true
        let tipLabel = UILabel()
        tipLabel.text = "💡 温馨提示\niOS 后台定位需要「始终允许」权限，且系统会在状态栏显示蓝色定位指示条。"
        tipLabel.numberOfLines = 0
        tipLabel.font = .systemFont(ofSize: 12)
        tipLabel.textColor = .white
        tipLabel.translatesAutoresizingMaskIntoConstraints = false
        tipCard.addSubview(tipLabel)
        NSLayoutConstraint.activate([
            tipLabel.topAnchor.constraint(equalTo: tipCard.topAnchor, constant: 14),
            tipLabel.bottomAnchor.constraint(equalTo: tipCard.bottomAnchor, constant: -14),
            tipLabel.leadingAnchor.constraint(equalTo: tipCard.leadingAnchor, constant: 14),
            tipLabel.trailingAnchor.constraint(equalTo: tipCard.trailingAnchor, constant: -14)
        ])

        let startBtn = UIButton(type: .system)
        startBtn.backgroundColor = pinkColor
        startBtn.tintColor = .white
        startBtn.titleLabel?.font = .systemFont(ofSize: 18, weight: .semibold)
        startBtn.setTitle("开始使用 💕", for: .normal)
        startBtn.heightAnchor.constraint(equalToConstant: 52).isActive = true
        startBtn.layer.cornerRadius = 26
        startBtn.layer.masksToBounds = true
        startBtn.addTarget(self, action: #selector(enterApp), for: .touchUpInside)

        container.addArrangedSubview(title)
        container.addArrangedSubview(desc)
        container.addArrangedSubview(makeSpacer(18))
        for c in permCards { container.addArrangedSubview(c); container.addArrangedSubview(makeSpacer(10)) }
        container.addArrangedSubview(tipCard)
        container.addArrangedSubview(makeSpacer(26))
        container.addArrangedSubview(startBtn)
    }

    // MARK: - 通用 UI 构造

    private func makeTextField(placeholder: String, icon: String) -> UITextField {
        let f = UITextField()
        f.placeholder = placeholder
        f.font = .systemFont(ofSize: 16)
        f.backgroundColor = .white
        f.layer.cornerRadius = 12
        f.layer.masksToBounds = true
        f.borderStyle = .none
        // 左侧图标
        let lView = UIView(frame: CGRect(x: 0, y: 0, width: 36, height: 36))
        let img = UILabel()
        img.text = icon
        img.font = .systemFont(ofSize: 16)
        img.textAlignment = .center
        img.frame = lView.bounds
        lView.addSubview(img)
        f.leftView = lView
        f.leftViewMode = .always
        // padding
        f.heightAnchor.constraint(equalToConstant: 52).isActive = true
        let pad = UIView(frame: CGRect(x: 0, y: 0, width: 12, height: 52))
        f.rightView = pad
        f.rightViewMode = .always
        f.autocapitalizationType = .none
        f.autocorrectionType = .no
        f.returnKeyType = .next
        return f
    }

    private func makeEyeButton() -> UIView {
        let btn = UIButton(type: .system)
        btn.setTitle(pwdVisible ? "🙈" : "👁️", for: .normal)
        btn.titleLabel?.font = .systemFont(ofSize: 18)
        btn.frame = CGRect(x: 0, y: 0, width: 36, height: 36)
        btn.addTarget(self, action: #selector(togglePwd), for: .touchUpInside)
        let v = UIView(frame: CGRect(x: 0, y: 0, width: 36, height: 36))
        v.addSubview(btn)
        return v
    }

    private func makeSpacer(_ height: CGFloat) -> UIView {
        let v = UIView()
        v.heightAnchor.constraint(equalToConstant: height).isActive = true
        return v
    }

    private func makeWhiteLabel(_ text: String, size: CGFloat, weight: UIFont.Weight,
                                alpha: CGFloat = 1) -> UILabel {
        let l = UILabel()
        l.text = text
        l.font = .systemFont(ofSize: size, weight: weight)
        l.textColor = .white.withAlphaComponent(alpha)
        l.numberOfLines = 0
        return l
    }

    private func makeRulesCard() -> UIView {
        let v = UIView()
        v.backgroundColor = UIColor.white.withAlphaComponent(0.12)
        v.layer.cornerRadius = 12
        v.layer.masksToBounds = true
        let lbl = UILabel()
        lbl.text = "📌 账号密码要求\n• 用户名 ≥ 3 位（字母/数字，推荐 6 位以上）\n• 密码 ≥ 8 位，建议同时包含字母和数字\n• 昵称将显示在地图上，给对方看的"
        lbl.font = .systemFont(ofSize: 11)
        lbl.numberOfLines = 0
        lbl.textColor = .white.withAlphaComponent(0.85)
        lbl.translatesAutoresizingMaskIntoConstraints = false
        v.addSubview(lbl)
        NSLayoutConstraint.activate([
            lbl.topAnchor.constraint(equalTo: v.topAnchor, constant: 10),
            lbl.bottomAnchor.constraint(equalTo: v.bottomAnchor, constant: -10),
            lbl.leadingAnchor.constraint(equalTo: v.leadingAnchor, constant: 14),
            lbl.trailingAnchor.constraint(equalTo: v.trailingAnchor, constant: -14)
        ])
        return v
    }

    private func permCard(icon: String, title: String, desc: String, granted: Bool) -> UIView {
        let card = UIView()
        card.backgroundColor = .white
        card.layer.cornerRadius = 16
        card.layer.masksToBounds = true

        let iconL = UILabel()
        iconL.text = icon
        iconL.font = .systemFont(ofSize: 24)

        let titleL = UILabel()
        titleL.text = title
        titleL.font = .systemFont(ofSize: 15, weight: .semibold)
        titleL.textColor = UIColor(red: 45/255, green: 55/255, blue: 72/255, alpha: 1)

        let descL = UILabel()
        descL.text = desc
        descL.font = .systemFont(ofSize: 12)
        descL.numberOfLines = 0
        descL.textColor = UIColor(red: 113/255, green: 128/255, blue: 150/255, alpha: 1)

        let textStack = UIStackView(arrangedSubviews: [titleL, descL])
        textStack.axis = .vertical
        textStack.spacing = 4

        let btn = UIButton(type: .system)
        btn.backgroundColor = granted ? UIColor(red: 72/255, green: 187/255, blue: 120/255, alpha: 1) : pinkColor
        btn.setTitle(granted ? "✓ 已开启" : "去开启", for: .normal)
        btn.tintColor = .white
        btn.titleLabel?.font = .systemFont(ofSize: 13, weight: .medium)
        btn.layer.cornerRadius = 18
        btn.layer.masksToBounds = true
        btn.contentEdgeInsets = UIEdgeInsets(top: 6, left: 12, bottom: 6, right: 12)
        btn.heightAnchor.constraint(equalToConstant: 34).isActive = true

        let row = UIStackView(arrangedSubviews: [iconL, textStack, btn])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = 12
        row.translatesAutoresizingMaskIntoConstraints = false
        row.isLayoutMarginsRelativeArrangement = true
        row.layoutMargins = UIEdgeInsets(top: 14, left: 14, bottom: 14, right: 14)

        card.addSubview(row)
        NSLayoutConstraint.activate([
            row.topAnchor.constraint(equalTo: card.topAnchor),
            row.bottomAnchor.constraint(equalTo: card.bottomAnchor),
            row.leadingAnchor.constraint(equalTo: card.leadingAnchor),
            row.trailingAnchor.constraint(equalTo: card.trailingAnchor)
        ])
        return card
    }

    // MARK: - 事件

    @objc private func modeChanged(_ s: UISegmentedControl) {
        mode = s.selectedSegmentIndex == 0 ? "login" : "register"
        rebuildForStep()
    }

    @objc private func genderChanged(_ s: UISegmentedControl) {
        genderIdx = s.selectedSegmentIndex
    }

    @objc private func textChanged(_ f: UITextField) {
        switch f.tag {
        case 100: username = f.text ?? ""
        case 101: password = f.text ?? ""
        case 102: displayName = f.text ?? ""
        default: break
        }
    }

    @objc private func pairInputChanged(_ f: UITextField) {
        let t = (f.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        f.text = t
        pairInput = t
    }

    @objc private func togglePwd() {
        pwdVisible.toggle()
        passwordField?.isSecureTextEntry = !pwdVisible
        // 重建右视图
        passwordField?.rightView = makeEyeButton()
    }

    @objc private func copyPairCode() {
        guard !pairCode.isEmpty else { return }
        UIPasteboard.general.string = pairCode
        copyTip = "✅ 已复制"
        pairCodeCopyBtn?.setTitle("✅ 已复制", for: .normal)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            self?.pairCodeCopyBtn?.setTitle("📋 复制", for: .normal)
        }
    }

    // MARK: - 认证逻辑

    @objc private func doAuth() {
        // 客户端格式校验（与 Android 一致）
        let cleanUser = username.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanPass = password.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanName = displayName.trimmingCharacters(in: .whitespacesAndNewlines)

        if cleanUser.count < 3 {
            showMsg("❌ 用户名至少 3 位（字母/数字，推荐 6 位以上）"); return
        }
        if cleanPass.count < 8 {
            showMsg("❌ 密码至少 8 位，建议同时包含字母和数字"); return
        }
        if mode == "register" && cleanName.isEmpty {
            showMsg("❌ 请填写昵称（会在地图上显示给TA）"); return
        }

        loading = true
        updateAuthButton()
        msgLabel?.text = ""

        Task.detached { [weak self] in
            guard let self = self else { return }
            let gender = self.genderIdx == 0 ? "female" : "male"
            let avatar = self.genderIdx == 0 ? "💗" : "💙"

            // 步骤 1：注册分支先调 register_user RPC
            if self.mode == "register" {
                do {
                    try await APIClient.shared.registerUser(
                        username: cleanUser, password: cleanPass,
                        nickname: cleanName.isEmpty ? cleanUser : cleanName,
                        gender: gender
                    )
                } catch {
                    let msg = self.translateRegisterError(error)
                    await MainActor.run {
                        self.loading = false
                        self.showMsg(msg)
                        self.updateAuthButton()
                    }
                    return
                }
            }

            // 步骤 2：verify_login RPC 验证密码 + 拿 profile
            do {
                let resp = try await APIClient.shared.verifyLogin(
                    username: cleanUser, password: cleanPass
                )

                // 步骤 3：兼容 cache 旧函数 f1~f8 + 新函数命名字段
                let userId = resp.user_id
                    ?? resp.profile?["id"]?.asString
                    ?? resp.profile?["f1"]?.asString
                guard let uid = userId, !uid.isEmpty else {
                    await MainActor.run {
                        self.loading = false
                        self.showMsg("登录返回数据异常，请联系开发者")
                        self.updateAuthButton()
                    }
                    return
                }

                let pUsername   = resp.str("username", "f2")
                let pNickname   = resp.str("nickname", "f3")
                let pAvatar     = resp.str("avatar", "f4")
                let pGender     = resp.str("gender", "f5")
                let pCoupleCode = resp.str("couple_code", "f6")
                let pCoupleId   = resp.strN("couple_id", "f7")
                let pPartnerId  = resp.strN("partner_id", "f8")

                // RLS 全放开，anon key 就能读写，不需要 JWT token
                UserStore.shared.setToken(nil)

                let finalGender = pGender.isEmpty ? gender : pGender
                let finalAvatar = pAvatar.isEmpty ? avatar : pAvatar
                let finalNickname = pNickname.isEmpty
                    ? (cleanName.isEmpty ? cleanUser : cleanName) : pNickname
                let finalCoupleCode = pCoupleCode

                let user = UserInfo(
                    id: uid,
                    username: pUsername.isEmpty ? cleanUser : pUsername,
                    nickname: finalNickname,
                    gender: finalGender,
                    avatar: finalAvatar,
                    coupleCode: finalCoupleCode,
                    partnerId: pPartnerId
                )
                UserStore.shared.setUser(user)
                self.pairCode = finalCoupleCode

                await MainActor.run {
                    self.loading = false
                    self.updateAuthButton()
                    self.step = .pair
                }
            } catch let e as APIError {
                let msg = self.translateLoginError(e, passwordLen: cleanPass.count)
                await MainActor.run {
                    self.loading = false
                    self.showMsg(msg)
                    self.updateAuthButton()
                }
            } catch {
                let msg = "网络异常：\(error.localizedDescription)"
                await MainActor.run {
                    self.loading = false
                    self.showMsg(msg)
                    self.updateAuthButton()
                }
            }
        }
    }

    private func showMsg(_ text: String) {
        msgLabel?.text = text
    }

    private func updateAuthButton() {
        let btn = actionButton
        btn?.setTitle(mode == "login" ? "登录" : "创建账号", for: .normal)
        btn?.isEnabled = !loading
        btn?.alpha = loading ? 0.7 : 1.0
    }

    // MARK: - 配对逻辑

    @objc private func doPair() {
        let me = UserStore.shared.getUser()
        guard let me = me else {
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
                        // 保存 partnerId
                        var newMe = me
                        newMe.partnerId = resp.their_id
                        UserStore.shared.setUser(newMe)
                        let nick = (resp.their_nickname?.isEmpty ?? true) ? "TA" : (resp.their_nickname ?? "TA")
                        self.pairStatusLabel?.text = "✅ 配对成功！已和 \(nick) 绑定 💕"
                        // 1.8s 后进入权限页
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) { [weak self] in
                            self?.step = .perms
                        }
                    } else if let reason = resp.reason {
                        self.pairStatusLabel?.text = self.translatePairReason(reason)
                    } else {
                        self.pairStatusLabel?.text = resp.msg ?? "配对失败，请稍后再试"
                    }
                }
            } catch {
                let msg: String
                if let e = error as? APIError {
                    msg = "配对失败：\(e.localizedDescription.prefix(50))"
                } else {
                    msg = "网络异常：\(error.localizedDescription.prefix(50))"
                }
                await MainActor.run {
                    self.pairLoading = false
                    self.pairActionButton?.isEnabled = true
                    self.pairActionButton?.setTitle("立即配对 💕", for: .normal)
                    self.pairStatusLabel?.text = msg
                }
            }
        }
    }

    @objc private func goPerms() {
        step = .perms
    }

    @objc private func enterApp() {
        // 申请定位权限
        LocationManager.shared.requestWhenInUse()
        // 切换到主界面
        if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
           let delegate = scene.delegate as? SceneDelegate {
            delegate.showMain()
        }
    }

    // MARK: - 错误翻译（与 Android LoginActivity 一致）

    private func translateRegisterError(_ error: Error) -> String {
        guard case let APIError.http(code, body) = error else {
            return "网络异常：\(error.localizedDescription.prefix(50))\n请检查手机网络（4G/WiFi）"
        }
        let low = body.lowercased()
        if body.contains("USERNAME_EXISTS") || body.contains("username_exists")
            || body.contains("already exists") || low.contains("duplicate")
            || low.contains("unique") {
            return "这个用户名已经注册啦，直接用它「登录」就行 💕\n如果忘记密码，换一个用户名重新注册也可以"
        }
        if low.contains("permission denied") || low.contains("execute") {
            return "注册功能还没准备好（数据库缺少授权），请联系开发者检查 SQL"
        }
        if low.contains("password") && low.contains("length") {
            return "密码至少 8 位，请修改后重试"
        }
        if (400..<500).contains(code) {
            return "注册失败 (HTTP \(code))\n密码至少 8 位 / 用户名 3 位"
        }
        if (500..<600).contains(code) {
            return "服务器开小差啦（HTTP \(code)），稍等 10 秒再点一下试试"
        }
        return "注册失败，请稍后再试"
    }

    private func translateLoginError(_ error: APIError, passwordLen: Int) -> String {
        switch error {
        case .http(let code, let body):
            if code == 429 || body.contains("email rate limit")
                || body.contains("over_email_send_rate_limit") {
                return "登录请求太多啦，稍等 1 分钟再试"
            }
            if code == 400 {
                if body.contains("password") && body.contains("length") {
                    return "密码至少 8 位，请修改后重试"
                }
                if body.contains("bad_json") {
                    return "请求格式错误，请更新到最新版 APP"
                }
                if body.contains("Email not confirmed") {
                    return "账号未激活（罕见），请用同一个用户名重新注册一次"
                }
                if body.contains("Invalid login") || body.contains("Invalid credentials")
                    || body.contains("invalid_grant") {
                    return "用户名或密码不对，请重新输入"
                }
                return "请求失败 (\(code))：请检查密码至少 8 位"
            }
            if (401..<500).contains(code) {
                if body.contains("already") || body.contains("already_registered") {
                    return "账号已存在，请直接登录"
                }
                if body.contains("User not found") || body.contains("not_found") {
                    return "账号不存在，请先注册"
                }
                if body.contains("Invalid") || body.contains("invalid") {
                    return "用户名或密码不对"
                }
                if body.contains("password") { return "密码不对，再想想？" }
                return "请求失败 (\(code))"
            }
            return "请求失败 (\(code))"
        case .network(let e):
            return "网络异常：\(e.localizedDescription.prefix(50))\n请检查手机网络（4G/WiFi）"
        case .decode(let m):
            return "解析失败：\(m)"
        case .invalidResponse:
            return "无效响应"
        }
    }

    private func translatePairReason(_ reason: String) -> String {
        switch reason {
        case "CODE_NOT_FOUND":
            return "配对码不存在：请让TA打开「我的」查看TA自己的配对码，确认和你输入的完全一致 💕"
        case "CANNOT_PAIR_SELF":
            return "不能和自己配对哦 😅 这是发给TA输入的码"
        case "ME_NOT_FOUND":
            return "你的账号信息丢失了，请退出重新登录一次"
        case "INVALID_ARGS":
            return "参数错误：请确认输入的是完整的 6 位字母+数字码"
        default:
            return "配对失败：\(reason)"
        }
    }
}
