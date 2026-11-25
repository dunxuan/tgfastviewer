# TG快点滑

Telegram Android 官方客户端的相册加速模块。在聊天里直接点开任意位置的图片或视频，**约 0.3 秒**就能左右滑，不再逐页等待。

包名 `io.github.dunxuan.tgfastviewer` · 作用域 `org.telegram.messenger` · 需要 LSPosed 2.2+ / Vector 2.2+（libxposed API 102）

## 效果

| | 官方客户端 | 装上本模块 |
|---|---|---|
| 点开最新一张 | 立刻可滑 | 立刻可滑 |
| 点开较早的一张 | 逐页往前翻，越旧越慢。百万消息的频道可能上千次请求，列表和计数长时间不出现 | 一次请求取回锚点前后 40 条，典型 0.25~0.3 秒可滑 |
| 左右滑动范围 | 受限于已加载的那几页 | 以点开那张为中心，前后各约 20 张 |
| 计数 n/m | 先显示一个偏大的值再跳变 | 只在能算准时显示，否则隐藏 |

## 安装

1. 安装 APK：`adb install -r TgFastViewer-1.4.6.apk`，或直接点开安装
2. LSPosed 里启用模块，勾选作用域 **Telegram**（`org.telegram.messenger`）
3. 强制停止 Telegram，重新打开

模块**没有界面、没有启动图标**，装完在桌面找不到入口，这是正常的。

命令行启用（可选）：

```bash
su -c '/data/adb/modules/zygisk_lsposed/lspctl module enable --user=0 io.github.dunxuan.tgfastviewer'
su -c '/data/adb/modules/zygisk_lsposed/lspctl scope add --user=0 io.github.dunxuan.tgfastviewer org.telegram.messenger'
```

## 确认生效

日志 tag `TgFastViewer`。启动后应该有：

```
hooks ready (first load)
scan found org.telegram.messenger.vi  checked=1025 in 66ms
```

每次点开媒体应该有 `around ...` → `window size=40` → `delivered via processLoadedMedia`。

## 生效范围

- **生效**：聊天 / 群 / 频道里直接点开单张图片或视频；评论帖里可连续滑进相邻评论帖
- **不介入**：资料页的「媒体」标签、相册多选、私密聊天、搜索结果、定时消息、网页内嵌媒体——这些场景行为与官方完全一致

## 常见问题

**点开还是滑不动？**
先看日志有没有 `hooks ready`。没有 = 模块没加载：作用域没勾、LSPosed 版本低于 2.2、或装完没强停 Telegram。有 `hooks ready` 但伴随 `bail ...` 行 = 这一版客户端的类形状变了，把日志贴到 issue。

**计数为什么不显示？**
设计如此。客户端 API 不提供任意锚点的精确位置，只有窗口顶到最新时才显示 `n/m`。宁可不显示，也不显示会跳变的值。

**断网能用吗？**
不能拿到邻居，与官方一致——模块没有本地媒体索引可查。

**会不会影响其他功能？**
只替换「首次加载列表」这一个动作，合并交给客户端自己的 `processLoadedMedia`；抓不到目标时静默放行，行为与官方完全一致。

**会不会被判定异常？**
只做只读拦截 + 替换一次列表请求，不改协议、不碰登录、不伪造字段。

## 兼容性

| 项 | 说明 |
|---|---|
| 客户端 | 官方 Telegram for Android（`org.telegram.messenger`） |
| 实测 | 13.3.1（Play 商店全混淆包） |
| 上游比对 | 近两年 20 个版本（11.4.2 → 13.2.1）`loadMedia` / `processLoadedMedia` 签名零漂移 |
| 框架 | LSPosed 2.2+ / Vector 2.2+（libxposed API 102） |
| Android | 6.0+（minSdk 23） |

不依赖任何被混淆的方法名，全部按方法形状在运行时发现，所以不会随 R8 改名失效。

## 原理（30 秒）

官方点开媒体时先取「最新 20 条」，不是目标就一页 40 条往更旧翻，每页一次串行网络往返。模块拦下这次首屏加载，改成对锚点发**一次** `messages.search`，取回锚点前后连续 40 条，再交给客户端自己的 `processLoadedMedia` 完成合并。

与 iOS / 桌面版的差距是架构性的：那两者点开任意位置都零等待，因为它们有按位置查询的本地媒体索引，Android 没有。完整分析、四个平台对比与实测数据见 [docs/design.md](docs/design.md)。

## 已知限制

- 每次打开仍有一次网络往返（典型 0.25~0.3 秒，偶发 0.8 秒级尖峰）。这是「一次往返」架构的下限，客户端侧压不下去。
- 计数在窗口未顶到最新时隐藏。
- 签名密钥不入库，请自行备份 `module.keystore`，否则无法发布可覆盖升级的更新。

## 构建

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-local.ps1
```

`javac` → `d8` → `tools/make_apk.py`（自行生成二进制清单，不依赖 aapt2）→ `apksigner`。工程也可用 Android Studio 打开（AGP 8.13.2 / compileSdk 36）。

热重载实现与 libxposed API 用法见 [docs/design.md](docs/design.md)。

## License

[MIT](LICENSE)
