# Android release 签名

Android 是独立的 Gradle included build；签名插件仅加载在 `android-app`，不影响 JVM/桌面构建。
使用与 Kica 一致的 `me.okonecny.gradle-keyring` 0.2 和 release 私钥，不提交 keystore 或密码。

## 本机配置

在被 Git 忽略的 `android-app/local.properties` 中配置非密码信息：

```properties
sdk.dir=/path/to/Android/Sdk
keyringStoreFile=/path/to/release.keystore
keyringKeyAlias=key0
```

本机复用 Kica 的 `/home/zero/.android/keystores/ntutn.top` 和 `key0`。
相对 keystore 路径以 `android-app` 为基准，也可以通过 `-PkeyringStoreFile`、
`-PkeyringKeyAlias` 覆盖。构建不读取或依赖 Kica 的工程目录。

## 密码初始化

插件按根项目名隔离系统密钥环：Kica 使用 `Gradle Project Kica`，本项目使用
`Gradle Project android-app`。同名 secret 不会自动跨项目共享，需要在本项目初始化一次。
当前本机已将 Kica 的两个签名密码安全写入本项目密钥环，未保存明文密码文件。

在交互式终端执行以下命令，通过环境变量传递密码，不把密码写进命令参数或 shell 历史：

```bash
read -r -s -p 'Keystore password: ' SIGN_STORE_PASSWORD; printf '\n'
export SIGN_STORE_PASSWORD
./gradlew -p android-app --no-daemon --no-configuration-cache -PkeyringSigning=false \
    setSecretValue --name=android_store_password
unset SIGN_STORE_PASSWORD

read -r -s -p 'Private key password: ' SIGN_KEY_PASSWORD; printf '\n'
export SIGN_KEY_PASSWORD
./gradlew -p android-app --no-daemon --no-configuration-cache -PkeyringSigning=false \
    setSecretValue --name=android_key_password
unset SIGN_KEY_PASSWORD
```

CI 可直接注入 `SIGN_STORE_PASSWORD`、`SIGN_KEY_PASSWORD`，不要求存在系统密钥环。
不要将密码放入 `local.properties`、Gradle 属性文件或命令行参数；不要执行
`listSecretValues`，它会输出密码。

## 验证及打包

```bash
./gradlew -p android-app --no-configuration-cache verifyReleaseSigning testDebugUnitTest assembleRelease bundleRelease
# 或从根构建调用：
./gradlew -PwithAndroid=true --no-configuration-cache :android-app:assembleRelease
```

`verifyReleaseSigning` 验证 keystore、私钥、证书链并打印公开的证书 SHA-256 指纹。
release 预构建依赖该验证，即使显式设置 `-PkeyringSigning=false`，也不会静默生成未签名 release。
该开关仅用于凭据初始化或无需签名的 debug 构建。默认启用签名，缺少配置或密码时明确失败。

输出位于 `android-app/build/outputs/apk/release/android-app-release.apk` 和
`android-app/build/outputs/bundle/release/android-app-release.aab`。
可使用 SDK 的 `apksigner verify --verbose --print-certs` 验证 APK，证书指纹应与上述输出一致。

AGP 模型包含签名密码，因此签名启用时强制要求关闭 configuration cache。
不要使用 `--debug` 日志收集敏感构建信息，也不要把本机 Gradle 缓存或 keystore 作为发布产物。

## 应用内更新

Android 1.2.0 起使用 [KMP Updater v0.3.0](https://github.com/zerofancy/kmp-updater/tree/v0.3.0)，
公开查询 `https://zp.ntutn.top` 中 `agent-im-bridge-kt` 的 Android / 当前架构 / apk 序列。
本地版本取自 BuildConfig，与 APK manifest 同源；更新客户端不使用聊天登录凭据或发布 Token。

启动时静默检查一次；登录页及会话的“登录状态”弹窗提供“检查更新”。发现新版本后可在应用内
下载、查看进度、取消或重试，下载器校验服务端声明的摘要。用户点击“安装更新”后，由 SDK
检查 APK 包名、版本及签名兼容性，再交给 Android 系统确认安装。需要未知来源授权时，界面
提供设置入口，返回后再次点击安装。32 位设备显示不支持应用内更新，不进行宽泛版本查询。

检查和下载由 ViewModel 管理，旋转屏幕保留状态；更新弹窗与安装操作仅在 Activity 前台恢复
状态展示。应用关闭会取消任务，不提供后台下载保活。SDK 的 Material 3 主题仅用于更新弹窗。
Android 网络依赖及测试组件统一使用 SDK 所需的 OkHttp 5，JVM 服务仍使用其原有版本。
