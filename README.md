# NekoLink-Android

[NekoLink](https://github.com/Todysheep/NekoLink) 的 **Android 状态上传客户端**（Kotlin + Compose）：配对后以前台服务保持心跳，上报前台/后台（UsageStats 近似）、媒体会话与设备信息，并支持 **禁止视奸（Privacy Shield）**。

相关仓库：

- 服务端 / 看板：[NekoLink](https://github.com/Todysheep/NekoLink)
- Windows 客户端：[NekoLink-Windows](https://github.com/Todysheep/NekoLink-Windows)

## 下载

正式构建见 **[Releases](https://github.com/Todysheep/NekoLink-Android/releases)**（打 `v*` tag 后由 GitHub Actions 上传签名 **release APK**）：

| 附件 | 用途 |
|------|------|
| `nekolink-android-vX.Y.Z.apk` | 侧载安装（MVP 不上架应用商店） |

安装未知来源 APK 需在系统设置中允许对应来源。

## 使用

1. 部署 [NekoLink](https://github.com/Todysheep/NekoLink) 服务端，在 `/admin` 生成注册码。
2. 安装 APK，填写 **站点根 URL**、设备显示名、注册码完成配对（`platform=android`）。
3. 按提示授予权限后保持前台服务运行。

### 权限

| 权限 | 用途 |
|------|------|
| 使用情况访问（Usage Access） | 前台 / 后台应用列表 |
| 查询所有软件包（`QUERY_ALL_PACKAGES`） | 将包名解析为应用显示名（前台 / 后台 / 媒体来源）；侧载自用，不上架应用商店 |
| 通知使用权（可选） | 媒体会话 |
| 通知（Android 13+） | 前台服务通知 |
| 电池优化白名单（建议） | 降低被系统杀后台概率 |

厂商 ROM 对后台限制差异大；无法采集的字段为 `null`，看板不展示假数据。

### 后台应用上报策略（v2）

Android「后台」来自 UsageStats **近期用量近似**，不是进程列表：

| 项 | 默认 |
|----|------|
| 时间窗 | 近 **2 小时** 内使用过 |
| 活跃度 | `totalTimeInForeground` ≥ **5 秒** |
| 排除 | 本应用 + **当前前台包** |
| 过滤 | 系统/OEM 噪声 denylist；默认隐藏无桌面入口的系统应用 |
| 条数上限 | **12**（开启「显示系统项」时 **24**） |
| 变更检测 | 仅比较包名 **集合**（排序/改名不触发重传） |
| 全量刷新 | 约 **90 秒** 强制带一次后台列表 |

开启客户端「显示系统后台」可放宽系统过滤，仍受硬上限约束。

## 协议端点

- `POST /api/v1/device/pair`
- `POST /api/v1/device/heartbeat`
- `PUT /api/v1/device/snapshot`
- `POST /api/v1/device/privacy-shield`
- `HEAD /api/v1/device/assets/{hash}` — 封面是否已存在（条件上传）
- `PUT /api/v1/device/assets/{hash}` — 上传媒体封面原始字节（SHA-256 与 path 一致，≤512 KiB）

## 本地开发

需要 **JDK 17**、Android SDK（`ANDROID_HOME` 或 `local.properties` 中 `sdk.dir`）。

```bash
# Linux / macOS
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug

# Windows
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleDebug
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`

Release 本地签名（可选）：

```bash
# 设置环境变量后
# NEKO_UPLOAD_STORE_FILE / NEKO_UPLOAD_STORE_PASSWORD / NEKO_UPLOAD_KEY_ALIAS / NEKO_UPLOAD_KEY_PASSWORD
./gradlew :app:assembleRelease
```

## CI / 发版

与主仓一致：

| 触发 | 行为 |
|------|------|
| PR / `main` | 单测 + `assembleRelease` 校验（无上传） |
| `vX.Y.Z` tag | 签名 release APK → **GitHub Release** |

仓库 Secrets（正式签名，推荐长期固定同一 keystore）：

| Secret | 说明 |
|--------|------|
| `ANDROID_KEYSTORE_BASE64` | keystore 文件 base64 |
| `ANDROID_KEYSTORE_PASSWORD` | store 密码 |
| `ANDROID_KEY_ALIAS` | key 别名 |
| `ANDROID_KEY_PASSWORD` | key 密码 |

未配置 Secrets 时，tag 流水线会在 CI 内生成**一次性** release keystore 并签名（**升级安装可能无法覆盖**，仅便于首次发版；请尽快配置固定 Secrets）。

```bash
git tag v0.1.0
git push origin v0.1.0
```

## 许可

[MIT License](./LICENSE)
