# 咕咕咕 Android

这里是独立的原生 Android / Java 工程。后端为可选服务；使用邮件转发时无需部署。

在本目录的本地 `local.properties` 中设置 `sdk.dir`，然后运行：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows 使用 `gradlew.bat`。依赖 JDK 17、Android SDK 34 和 Build Tools 34.0.0。APK 位于 `app/build/outputs/apk/debug/`。

个人稳定调试密钥仍从仓库根目录 `.secrets/android-debug.keystore` 读取，覆盖升级需保持签名。局域网 HTTP 的本地覆盖文件为 `app/src/debug/res/xml/network_security_config.xml`，已忽略。实际手机号、服务令牌和 SMTP 授权码不要写入源码。

首页“更多操作 → 关于咕咕咕”可查看当前安装版本和构建号。

使用和独立预览测试见[仓库说明](../../README.md)。
