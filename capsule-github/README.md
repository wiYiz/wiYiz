# 潮汐胶囊（Tidal Capsule）

一套「**一个 H5 单文件 SPA + 两套 Android WebView 壳 + 一个静态官网**」的个人记账产品矩阵。三个产物共用一套设计语言（紫色 `#6c63ff → #a78bfa`，胶囊母题）和同一把签名密钥。

> 业务逻辑 **100% 在前端 HTML 里**，原生层只提供 WebView 壳与系统能力（通知监听、桌面小组件）。

## 三个产物

| 产物 | 包名 | 版本 | 说明 |
|---|---|---|---|
| 胶囊记账 | `com.capsulebudget.app` | v2.16（versionCode 19） | 完整记账 App：预算胶囊·液面·月度结算·攒钱目标·统计三图·桌面小组件·成就墙·航海皮肤 |
| 胶囊钱包 | `com.capsulewallet.app` | v1.0（versionCode 1） | 记账 App「充值功能」的剥离版，只管充值 + 储值卡 |
| 潮汐胶囊官网 | — | — | 单页静态品牌站（7 个 section） |

## 目录结构

```
.
├── CapsuleBudget/          # 记账 Android 工程
├── CapsuleWallet/          # 钱包 Android 工程
├── 前端源/
│   ├── src/index.html          # 记账前端唯一真源
│   └── recharge-only/index.html # 钱包前端唯一真源
├── 官网/index.html             # 单页品牌站
├── update_capsule.ps1          # 记账热更新脚本
├── update_wallet.ps1           # 钱包热更新脚本
├── .gitignore
├── LICENSE
└── README.md
```

## 技术形态

- **前端**：单文件 SPA，一个 `<style>` + 一个 `<script>`，零依赖、零构建步骤，任何浏览器双击即可运行。
- **存储**：`localStorage`，单键 `capsule_budget_v2`；钱包版故意复用同名键，靠包名隔离，两 App 数据不冲突。
- **原生**：WebView 全屏注入（优先读外部 `index.html` 以支持热更新，回退 `assets`）；`CapsuleNotificationListener` 监听支付通知实现「支付即记」；`CapsuleWidgetProvider` 纯代码绘制桌面小组件（液面胶囊 + 航海语录 + 呼吸动画）。
- **JS↔原生桥**：`window.CapsuleBridge` 暴露 `pushWidgetData` / `shareImage` / `saveToGallery` / `isNotificationListenerEnabled` / `openNotificationListenerSettings`。

## 构建

1. 安装 JDK 17+ 与 Android SDK（需 `build-tools 35.0.0`）。
2. 配置本地环境（替换成你自己的路径）：

```powershell
$env:JAVA_HOME = "你的 JDK 路径"
$env:ANDROID_HOME = "你的 Android SDK 路径"
```

3. **签名密钥不在仓库里**。先在工程目录复制并填写密钥信息：

```powershell
Copy-Item CapsuleBudget\keystore.properties.example CapsuleBudget\keystore.properties
Copy-Item CapsuleWallet\keystore.properties.example   CapsuleWallet\keystore.properties
# 编辑两个 keystore.properties，填入 storeFile / storePassword / keyAlias / keyPassword
```

4. 构建 release（用 wrapper，禁用 gradlew.bat）：

```powershell
cd CapsuleBudget
java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleRelease --no-daemon
```

> 钱包工程同样操作。产出 APK 位于 `app/build/outputs/apk/release/`。

## 热更新（只改前端，不动原生、不丢数据）

改完 `前端源/` 下的 HTML 后，直接推送单文件到手机：

```powershell
.\update_capsule.ps1     # 记账
.\update_wallet.ps1      # 钱包
```

原理：App 启动时优先加载 `getExternalFilesDir(null)/index.html`，并用 `loadDataWithBaseURL("file:///android_asset/", …)` 注入，保证外部文件仍然拥有 `assets` 的 localStorage 作用域。脚本只 push 代码、重启 App，绝不触碰本地数据。

## 发布铁律

- **永远 `adb install -r`，绝不 `uninstall`** —— 数据在 localStorage，卸载即全部丢失。
- **换密钥 = 老用户无法覆盖升级**。密钥必须永久妥善保管，绝不进入 Git。

## 官网与下载

- 官网：https://tidal-capsule-site.app.workbuddy.link/
- APK 下载：直接走官网的「下载」区（同签名，可并存）。

## 许可证

[MIT](./LICENSE)
