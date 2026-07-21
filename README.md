# NekoLink Android 上传客户端

Kotlin 原生 Android 客户端，对齐 monorepo `packages/protocol` 与 Windows 上传端的配对 / 混合同步 / Privacy Shield 模型。

## 功能

- 未配对：同屏输入服务器 URL、设备显示名、注册码并配对（`platform=android`）
- 已配对：前台服务 + 通知保活；设置页状态摘要与配置
- 采集：UsageStats 前台/后台、MediaSession、电量与系统版本
- 控制：暂停上报、禁止视奸（开启二次确认）、解除配对、改 URL 后重配对

## 构建

需要 **JDK 17**（AGP 8.7 不支持 JDK 24）、`ANDROID_HOME` / `local.properties` 中的 `sdk.dir`。

```bash
# Windows 示例
set JAVA_HOME=F:\Code\Java\jdk-17
set ANDROID_HOME=F:\Code\Andriod

gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`

## 权限

- 使用情况访问（Usage Access）：前台 / 后台列表
- 通知使用权（可选）：媒体会话
- 通知权限（Android 13+）：前台服务通知
- 开机自启（可选开关）

## 协议端点

- `POST /api/v1/device/pair`
- `POST /api/v1/device/heartbeat`
- `PUT /api/v1/device/snapshot`
- `POST /api/v1/device/privacy-shield`
