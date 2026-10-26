# Neko-i2pd Android

这是基于 [PurpleI2P/i2pd-android](https://github.com/PurpleI2P/i2pd-android) 3.0.0 的非官方 Android 分支。当前分支 `android-vpn-webapps` 增加了 Android VPN 分流设置和控制台 WebApps 门户，并保留 i2pd 原有功能。应用图标已替换为本分支定制图稿。

> 当前为实验性分支，不代表 PurpleI2P 官方发行版。源码可按仓库现有许可证及第三方组件许可证使用；Hev Socks5 Tunnel 的 MIT 许可证随项目提供。

## 功能

- **按应用 VPN 分流**：在“设置 → VPN 模式”中选择需要通过 i2pd 的应用，启动后按 Android 系统提示授权。VPN 使用本机 i2pd SOCKS5（默认 `127.0.0.1:4447`）转发所选应用的 TCP/UDP 流量；i2pd 停止时会断开 VPN。数据面使用 Hev Socks5 Tunnel 2.19.3 的 API 23 原生构建，AAR 位于 `service/libs/`。
- **WebApps 门户**：网页控制台提供 I2P Mail（`mail.i2p`）和网页 IRC（`webirc.coldhell.i2p`）入口。链接只会交给已在 VPN 分流列表中、且能处理网页链接的浏览器打开；未选中浏览器时会提示，不会直接外跳。
- **本地地址簿查询**：搜索设备已缓存的 `.i2p` 主机名及 destination；不执行远端查询。
- **控制台背景图**：可从手机照片中选择或清除网页控制台背景图。
- **原有功能保留**：本分支不替换 i2pd 原有控制台或隧道功能。

## 功能边界与安全提示

- Android 同一时间通常只能启用一个 VPN；连接本应用 VPN 时，其他 VPN 可能需要先关闭。
- VPN 仅路由用户选中的应用。被选中的应用访问普通公网地址时，只有在 i2pd 配置了可用 outproxy 等出口服务的情况下才可能连通；本分支不会在 VPN 故障时悄悄回退为手机直连。
- Mail 和网页 IRC 是通过 I2P 网络访问的服务入口，不是此 APK 内置的邮件或 IRC 服务器；服务是否可用取决于 I2P 网络、地址簿和相应服务。
- 当前没有新增独立的 BitTorrent 下载/做种 WebApp；i2pd 原有相关功能未被删除。
- 最低 Android 版本为 API 23（Android 6.0）。构建已验证包含 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`；VPN 的实际路由效果仍需在目标手机上测试。

## 使用步骤

1. 启动 i2pd 路由器并等待其运行。
2. 打开“设置 → VPN 模式”，选中要访问 I2P 的浏览器或其他应用。
3. 点“启动 VPN”，按 Android 系统提示确认 VPN 授权。
4. 从网页控制台进入 **WebApps**；Mail/IRC 会在已选中的浏览器中打开。地址簿入口搜索本地缓存。

## 构建

### 环境

建议使用 JDK 21（上游构建说明要求 OpenJDK 17 或更高版本）、`make`、`cmake`、`perl`、`curl`、`zip`，以及 Android SDK。工程版本配置见 `gradle/libs.versions.toml`：compile SDK 37、最低 SDK 23、NDK `29.0.14206865`。安装相应 SDK 包，例如：

```bash
sdkmanager "platforms;android-37" "build-tools;36.0.0" "ndk;29.0.14206865"
```

### 获取源码并构建

克隆时包含 i2pd 子模块：

```bash
git clone --recurse-submodules --branch android-vpn-webapps \
  https://github.com/ke-lala/Neko-i2pd.git
cd Neko-i2pd
```

设置 SDK 环境变量，并先构建 i2pd 原生依赖：

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
binary/jni/build.sh
```

然后构建通用调试 APK：

```bash
./gradlew :app:assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/`。调试 APK 可用于侧载测试。正式发行请使用自己的签名密钥构建 release 包；不要把密钥、密码或 `keystore.properties` 提交到仓库。

### 修改 VPN 转发引擎

APK 工程内附的是 Hev Socks5 Tunnel 2.19.3、API 23 目标的 AAR 和 MIT 许可证。若要修改其 C 原生实现，请从 [Hev Socks5 Tunnel 上游](https://github.com/heiher/hev-socks5-tunnel/tree/2.19.3) 获取对应源码及子模块，重新编译 AAR 后替换 `service/libs/hev-socks5-tunnel-2.19.3.aar`。

## 上游致谢

本项目基于 [PurpleI2P/i2pd-android](https://github.com/PurpleI2P/i2pd-android) 与 [PurpleI2P/i2pd](https://github.com/PurpleI2P/i2pd)，VPN 数据面使用 [Hev Socks5 Tunnel](https://github.com/heiher/hev-socks5-tunnel)。请查阅各自源码与许可证文件。
