# 咕咕咕 Android

这里是独立的原生 Android / Java 工程。后端为可选服务；使用邮件转发时无需部署。

界面和转发邮件支持中文（简体、繁体）、英语、日语，自动跟随系统语言，其他语言回退到英语。Android 13+ 可通过系统的应用语言设置单独选择。英文为默认资源，翻译位于 `values-zh`、`values-b+zh+Hant`、`values-ja`；新增文案时同步维护各语言资源。后台状态保存资源名称和参数，切换语言后重新渲染；用户填写的配置名、号码和短信正文保持原样。

在本目录的本地 `local.properties` 中设置 `sdk.dir`，然后运行：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows 使用 `gradlew.bat`。依赖 JDK 17、Android SDK 34 和 Build Tools 34.0.0。APK 位于 `app/build/outputs/apk/debug/`。

个人稳定调试密钥仍从仓库根目录 `.secrets/android-debug.keystore` 读取，覆盖升级需保持签名。局域网 HTTP 的本地覆盖文件为 `app/src/debug/res/xml/network_security_config.xml`，已忽略。实际手机号、服务令牌和 SMTP 授权码不要写入源码。

首页“更多操作 → 关于咕咕咕”可查看当前安装版本和构建号。

数据库向导或配置编辑页提供“扫码配置”，扫描服务面板的配对二维码后先显示服务名称、地址；确认仅填入表单，保存后才生效。不会因扫码自动切换当前配置或开启转发。扫码使用本机相机和 ZXing，不依赖 Google Play 服务；拒绝相机权限可继续手动配置。

使用和独立预览测试见[仓库说明](../../README.md)。
