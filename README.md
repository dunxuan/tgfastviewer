# TG快点滑

Telegram Android 官方客户端的相册查看器加速模块。在聊天里直接点开任意位置的图片或视频，约 **0.3 秒**即可左右滑动，不再逐页等待。

> LSPosed module for the official Telegram Android client. Opens any photo/video in a chat and makes it swipeable in ~0.3s, instead of paging through the whole history.

包名：`io.github.dunxuan.tgfastviewer` · 作用域：`org.telegram.messenger` · 需要 LSPosed 2.2+ / Vector 2.2+（libxposed API **102**）

## 问题

在聊天里点开一张媒体时，Telegram 不看这张媒体在哪，而是先取"最新 20 张"，发现没有就开始一页 40 张地往更旧方向**顺序翻页**。越旧、媒体越多，往返次数越多——百万级消息的频道意味着上千次请求，列表和计数迟迟不出现，甚至永远不出来。

## 做法

1. **就地取窗口**：拦下首次列表加载，改为对该媒体发一次 `messages.search`（`offset_id=锚点`、`add_offset=-(50/2+1)`、`limit=50`），一次拿到锚点前后连续 50 条，交给客户端自己的 `processLoadedMedia` 处理——填表、排序、缩略图、通知全部沿用原逻辑。
2. **计数只在精确时显示**：窗口落地后并行发一个 `limit=1` 探针取该会话最新媒体 id，只有它等于窗口顶部才显示 `n/m`；否则跳过计数控件的 `onDraw`，宁可不显示，也不显示会跳变的值。滑动不被探针阻塞。
3. **失败回退**：请求失败或为空时，用 `Invoker.Type.ORIGIN` 原样调用被拦下的方法，行为回到官方原状。

## 兼容性

模块**不依赖任何被混淆的方法名**，全部靠运行时发现：

| 目标 | 找法 |
|---|---|
| `PhotoViewer.didReceivedNotification` | 按名字 hook（接口实现，名字保留） |
| 媒体控制器（官方包里已被 R8 改名） | hook `ConnectionsManager.sendRequest`（6 个重载），从调用栈取类，按方法形状匹配 |
| `loadMedia` / `processLoadedMedia` | 按参数类型顺序匹配，不看名字；11/12 参都收 |
| 计数控件 | `PhotoViewer` 内嵌 View 子类 + 签名形状双路探测 |

两个拦截点同时生效（`loadMedia` 与投递口 `processLoadedMedia`），因为部分构建里前者会被 R8 内联。

已对上游 `TelegramMessenger/Telegram-Android` 近两年 **20 个版本**（11.4.2 → 13.2.1）的 `MediaDataController.java` 做过逐字节签名比对：`loadMedia`（11 参）与 `processLoadedMedia`（12 参）签名**两年零漂移**，`mediaDidLoad` 通知参数顺序也完全一致。13.3.1 实测可用（Play 商店全混淆包）。

**生效范围**：聊天/频道里直接点开单张图片或视频（含评论帖，可连续滑进相邻评论帖）。

**不介入**：资料页媒体页、相册多选、私密聊天、搜索/过滤、定时消息、群升级旧段、网页内嵌媒体——这些场景行为与官方完全一致。

抓不到类或方法时模块**静默不介入**，不会影响 Telegram 正常运行；日志里 `cand ... shapes=` 行可用于排查新版本。

## 安装

```bash
adb install -r TgFastViewer-1.4.0.apk
```

然后在 LSPosed 中启用并勾选作用域 **Telegram**（`org.telegram.messenger`），强停 Telegram 重新打开。

模块**不带界面**（无 Activity、无启动图标），只作为 hook 载体。如需命令行操作：

```bash
su -c '/data/adb/modules/zygisk_lsposed/lspctl module enable --user=0 io.github.dunxuan.tgfastviewer'
su -c '/data/adb/modules/zygisk_lsposed/lspctl scope add --user=0 io.github.dunxuan.tgfastviewer org.telegram.messenger'
```

日志 tag `TgFastViewer`，正常会看到 `network hooks: 6`、`hooks ready`，以及每次打开媒体的 `around` / `window size` / `delivered`。

## 构建

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-local.ps1
```

流程：`javac`（android.jar + `libs/libxposed-api-102.0.0.jar`）→ `d8` → `tools/make_apk.py` 生成二进制 `AndroidManifest.xml` 并打包 → `apksigner` 签名。

依赖 `io.github.libxposed:api:102.0.0`（`compileOnly`，与声明的 API 102 一致），`minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`。工程也可直接用 Android Studio 打开（AGP 8.13.2 / compileSdk 36 / minSdk 23）。

> 本机 `aapt2 link` 无法创建归档，所以 `tools/make_apk.py` 自行生成二进制清单，不依赖 aapt2。

## 已知限制

- 每次打开仍需一次网络往返（实测 0.25~0.30s）；模块拿不到客户端本地媒体索引，**断网时没有可滑邻居**，与官方一致。
- 计数在"窗口未顶到最新"时一律隐藏：客户端 API 不提供任意锚点的精确位置。
- 签名密钥 `module.keystore` 不入库（口令见 `build-local.ps1`），发布前需自行备份，否则无法发布可覆盖升级的更新。

## License

[MIT](LICENSE)

## 热重载

模块基于 libxposed API 102，完整实现了热重载协议，不需要重启 Telegram 就能换上新版本代码：

- `onHotReloading` 里停掉模块自建的扫描线程、作废当前绑定、把目标 App 的 `ClassLoader` 通过 `setSavedInstanceState` 交给新代，然后返回 `true` 声明旧代可以退役；
- `onHotReloaded` 运行在**新**代码里，先 `unhook()` 掉 `getOldHookHandles()` 给出的全部旧句柄，再用继承下来的 `ClassLoader` 重新绑定并重装 hook——因为热重载**不会重放** `onPackageLoaded` 等生命周期回调；
- 所有 hook 都带稳定 id（`viewer` / `network0..5` / `loadMedia` / `deliver`），便于按 id 核对与替换。

这条链路同时带来一个实际好处：改完代码热重载即可生效，不必为了验证一次改动反复杀进程重开聊天。

## 用到的 libxposed API

| 能力 | 用途 |
|---|---|
| `XposedModule` + `onPackageLoaded` | 入口，按 `org.telegram.messenger` 过滤 |
| `HookBuilder.setId` | 给每个 hook 稳定标识，热重载时可按 id 找回 |
| `HookHandle.unhook` | 热重载时清掉上一代 hook |
| `onHotReloading` / `onHotReloaded` | 热重载协议（停线程、移交 ClassLoader、重装 hook） |
| `Invoker`（`Type.ORIGIN`） | 原样调用被拦下的 `loadMedia` / `processLoadedMedia` / `sendRequest` |
| `module.log` | 统一日志，tag `TgFastViewer` |