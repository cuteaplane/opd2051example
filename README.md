# 这他妈是个PURE VIBE CODING,use with your own risk
一个**最小示例** Android App：用 Shizuku 的 **UserService** 以 `uid 2000 / u:r:shell:s0`
身份运行 [`../zjns-98260`](../zjns-98260) 里的 `timer/timer13`。

前端就一屏按钮 + 日志，没有做美化。

> **完整 KSU 操作流程见 [使用说明.md](使用说明.md)**（安全警告 → 知道了 →
> 所选管理器软重启；内核不符直接拒绝；成功软重启、失败硬重启）。
> **App 内「使用说明」按钮也是同一份流程**（可滚动查看）。

```
App (普通 uid)
  │  binder（Shizuku API）
  ▼
Shizuku UserService (uid=2000, u:r:shell:s0)
  │  ProcessBuilder
  ▼
/data/local/tmp/timer
```

## 功能

| 按钮 | 做什么 |
|---|---|
| ① 授权 Shizuku | 请求 `moe.shizuku.manager.permission.API_V23` 并绑定 UserService |
| ② 预检 | 打印 `id`、SELinux context、`uname -r`、机型、`boot_id` |
| KSU 管理器下拉 | 选择 KernelSU / SukiSU Ultra / BakaSU，决定安装和运行哪个 timer 变体 |
| ③ 安装 timer | 按当前管理器把对应变体写到 `/data/local/tmp/timer_<mgr>`，`chmod 755`，`sha256sum` 校验 |
| timer ids / timer leak | 同步执行诊断命令，直接回显输出 |
| ④ EXPLOIT · timer | 一次性 real-cred root（真正的 exploit） |
| ⑤ EXPLOIT · timer fake | 按当前管理器执行 KSU late-load（真正的 exploit） |
| 停止日志轮询 | 只停止 UI 刷新，不影响后台进程 |
| 结束 UserService | 诊断阶段清理用；exploit 跑过之后禁用 |

exploit 相关按钮会先做预检，再弹二次确认；输出写到
`/data/local/tmp/timer.log`，App 每 800ms 轮询显示。
**不 wait、不 kill、不 unbind**——因为 `timer fake` 成功后会 park 驻留。

## KSU 管理器切换

三个变体是**同一个 `timer.c` 编译出的三份二进制**，只是 `write_ksu_script()`
里写死的包名不同；App 下拉选中哪个，就安装/运行哪一份：

| 管理器 | 包名 | assets | 运行路径 |
|---|---|---|---|
| KernelSU | `me.weishu.kernelsu` | `timer_ksu` | `/data/local/tmp/timer_ksu` |
| SukiSU / SukiSU Ultra | `com.sukisu.ultra` | `timer_sukisu` | `/data/local/tmp/timer_sukisu` |
| BakaSU / ReSukiSU | `com.resukisu.resukisu` | `timer_bakasu` | `/data/local/tmp/timer_bakasu` |

- `timer_bakasu` 用的是**已验证的 `timer13`**（CLI 里有 banner）；`timer_ksu` /
  `timer_sukisu` 由 `tools/build-timer-variants.sh` 从同一份 `timer.c` 重编译，
  除包名外日志字符串一致。
- 切换管理器后要重新点「③ 安装 timer」，并把对应管理器 App 装好；否则
  `/data/local/tmp/timer_<mgr>` 不存在或 `ksud missing`。
- `late-load --kmi android14-6.1` 仍按本机内核写死；换内核要重新核对。

## 为什么用 UserService 而不是 `Shizuku.newProcess`

`Shizuku#newProcess` 从 13.1.5 起已被标记为待移除（官方说明：
"Prepare to remove `Shizuku#newProcess`, developers should have to use UserService instead."），
而且它没有 tty、可靠性差。官方推荐 **UserService（AIDL）**：

1. 写一个 `IUserService.aidl`，`destroy() = 16777114`（Shizuku 保留事务号）；
2. 实现 `IUserService.Stub` 的类交给 `Shizuku.bindUserService()`；
3. Shizuku 用 shell/root 身份起一个进程，反射实例化你的类，方法调用走 binder。

这个示例就是这么写的，核心文件：

```
app/src/main/aidl/dev/cuteaplane/timerrunner/IUserService.aidl
app/src/main/java/dev/cuteaplane/timerrunner/UserService.kt   ← 跑在 uid 2000 进程里
app/src/main/java/dev/cuteaplane/timerrunner/MainActivity.kt  ← UI + 授权/绑定/轮询
app/src/main/assets/timer_{ksu,sukisu,bakasu}                ← 三份 arm64 静态 ELF
```

## 构建（本机已编译通过）

本机工具链：

| 工具 | 位置 |
|---|---|
| JDK 21 | `/tmp/ysu-jdk`（Temurin 21.0.13） |
| Android SDK | `/tmp/ysu-android`（platform **android-36**，build-tools 35.0.0 / 36.0.0） |
| Android NDK | `~/android/ndk-r28c`（r28c / 28.2.13676358） |
| Gradle | 8.14.3（仓库已带 wrapper） |

`local.properties` 已指向 `sdk.dir=/tmp/ysu-android`。构建：

```sh
export JAVA_HOME=/tmp/ysu-jdk
export ANDROID_HOME=/tmp/ysu-android

# 重新生成三份 timer 变体（可选，assets 里已带）
NDK=$HOME/android/ndk-r28c ./tools/build-timer-variants.sh

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

产物：`app/build/outputs/apk/debug/app-debug.apk`
（APK 自身 hash 每次构建可能不同；`timer_bakasu` 的 sha256 为 `74234249…61cc`，
`timer_ksu`/`timer_sukisu` 的 hash 见脚本输出）。

构建相关的两处适配：

- `compileSdk = 36` + `buildToolsVersion = "35.0.0"`：本机只装了 platform 36，
  AGP 8.7.3 对 compileSdk 36 只警告（已在 `gradle.properties` suppress）。
- `settings.gradle.kts` 没有用 `FAIL_ON_PROJECT_REPOS`：本机
  `~/.gradle/init.d/mirrors.gradle` 会向 project repositories 注入镜像，
  强制模式会让配置直接失败。

> `/tmp` 重启会清空。想长期用，把 `/tmp/ysu-android`、`/tmp/ysu-jdk` 移到
> `~/android/`（`.bashrc` 本来就会 source `~/android/env.sh`），再改
> `local.properties`。换到 Android Studio / SDK 35 的话，把 `compileSdk` 改回 35、
> 去掉 `buildToolsVersion` 也行。

## 签名

自定义签名已经接好：

| 项 | 值 |
|---|---|
| keystore | `keystore/timerrunner.jks`（PKCS12） |
| alias | `timerrunner` |
| 证书 | `CN=cuteaplane, OU=TimerRunner, O=cuteaplane, C=CN` |
| 密钥 | RSA 4096 / SHA256withRSA / 36500 天 |
| 配置 | `keystore.properties`（`storeFile/storePassword/keyAlias/keyPassword`） |

`debug` 和 `release` 都使用 `signingConfigs.custom`。改签名信息就改
`keystore.properties`，或者重新生成 keystore。

验证：

```sh
PATH=/tmp/ysu-jdk/bin:$PATH /tmp/ysu-android/build-tools/35.0.0/apksigner \
  verify -v --print-certs app/build/outputs/apk/debug/app-debug.apk
# Signer #1 certificate DN: CN=cuteaplane, OU=TimerRunner, O=cuteaplane, C=CN
```

注意：

- `keystore.properties` 和 `keystore/` 已 gitignore，**务必备份**；丢了就只能卸载重装。
- 换成自定义签名后**不能覆盖安装**之前 debug 签名的 APK，必须先卸载。
  卸载会清 App 数据并重置 Shizuku 授权。
- 如果当前 KSU 会话还在用（`timer` 还 park 着），**先硬重启再卸载/安装**，
  不要直接卸载或强停 App，避免碰到 parked 进程。

## 使用

1. 安装你要用的 KSU 管理器（KernelSU / SukiSU Ultra / BakaSU），
   并安装、启动 **Shizuku**（无线调试 / ADB 启动，不要 root 启动）。
2. 打开 TimerRunner，在顶部「KSU 管理器」下拉里选中刚装的管理器；
   「① 授权 Shizuku」→ 在 Shizuku 弹窗里允许。
3. 「② 预检」：日志里应看到类似

   ```
   uid=2000(shell) gid=2000(shell) context=u:r:shell:s0
   6.1.157-android14-11-o-gd9a844c1f21f
   OPD2501
   <boot_id>
   ```

   如果 `uid=0`，说明 Shizuku 是 root 启动的，这个示例的前提不成立。
4. 「③ 安装 timer」：把当前管理器对应的变体装到
   `/data/local/tmp/timer_<mgr>`，并核对 sha256（BakaSU 为 `74234249…61cc`）。
5. 先点 `timer ids` / `timer leak` 确认 CPU/tracefs 正常。
6. 再点「④ EXPLOIT · timer」或「⑤ EXPLOIT · timer fake」。
   **内核不匹配时 App 会直接拒绝运行。**
   - 想用 KSU：点 ⑤。运行中系统弹出「安全警告」时，点「知道了」，
     **不要点「立即重启」**；日志出现 `KSU_LOADED` 后，在**所选管理器**里**软重启**一次。
   - 一次性 root：点 ④，日志出现 `ROOT_SHELL_OK` 即成功。
   - 失败（`KSU_MISSING` / `parking without reap` 等）：**硬重启**后再试。

### Shizuku 权限被限制？

如果 Shizuku 启动后仍然各种权限受限 / UserService 起不来，在**开发者选项**里打开
**「禁止权限监控」**（Disable permission monitoring），再重启 Shizuku 试试。
部分 ColorOS / Android 16 机型需要这个，否则 shell 身份会被额外限制。

## 重要规则（来自上游 README，别跳过）

- **一次开机只跑一次 exploit**；失败就**硬重启**，不要在同一次 boot 里重试。
  App 用 `/proc/sys/kernel/random/boot_id` 做了兜底（软重启不会改变 boot_id），
  但清除 App 数据可以绕过它。
- **绝对不要 kill park 住的 `timer` 进程**（包括「结束 UserService」按钮在 exploit 后已被禁用）。
  `release_task()` 碰到 stale node 可能直接 panic。
- 成功（fake）：按 [使用说明](使用说明.md) 在**所选管理器**里**软重启**一次让 KSU 生效；
  用完之后要彻底清理，再**硬重启**。
- `timer fake` 成功后 fake cred 页被 held；只有硬重启能清掉。
- 硬重启会清空 `/data/local/tmp`，重新点「③ 安装 timer」即可。
- `u:r:shell:s0` 仍是 shell 域；完整 root 用 fake 链加载的 KSU。
  本示例不提供交互式 root shell（`timer shell` 需要 tty，建议用 `rish`）。
- 仅用于**你自己拥有**的设备；内核 exploit 可能导致 panic/重启/数据丢失。

## 换成别的 timer / 重新编译

1. 用 `NDK=$HOME/android/ndk-r28c ./tools/build-timer-variants.sh [timer.c]`
   重新生成 `assets/timer_{ksu,sukisu,bakasu}`；
2. 把脚本输出的三个 sha256 填进 `MainActivity.kt` 的 `MANAGER_SHA`；
3. 如果换了机型/内核，改 `EXPECTED_KERNEL` / `EXPECTED_DEVICE` 和
   `timer.c` 里的 `--kmi`；
4. 改了 `UserService` 的代码后，把 `serviceArgs.version(2)` 加 1，让 Shizuku 重建服务进程。

## 请我喝奶茶

软件完全免费，但是如果你觉得他帮到你了，可以请我喝奶茶，蟹蟹～

App 里点「☕ 请我喝奶茶」也能看到同样的收款码。

### 微信支付

<img src="app/src/main/res/drawable-nodpi/donate_wechat.png" width="320" alt="微信支付">

### 支付宝

<img src="app/src/main/res/drawable-nodpi/donate_alipay.jpg" width="320" alt="支付宝">

## 许可

- 示例 App 代码：随本仓库 MIT。
- `app/src/main/assets/timer_bakasu` 来自 `../zjns-98260` 的预编译产物，
  适用其 `LICENSE-PREBUILT`；`timer_ksu` / `timer_sukisu` 是从同一份 MIT 源码
  （`../zjns-98260/timer/timer.c`）重新编译的变体。源码本身为 MIT，保留上游
  `@zjns` / `@woshigenm` 的版权声明。
