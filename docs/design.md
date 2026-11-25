# 设计与实测记录

这份文档记录问题的根因、各平台客户端的不同做法、本模块的实现取舍，以及所有能拿数字说话的地方。面向「想知道为什么这么写」的读者；只想装的话看 [README](../README.md)。

## 1. 问题

在聊天里**直接点开**一张图片/视频时，官方 Android 客户端不看这张媒体在会话中的位置，而是先取「最新 20 条」，发现不是目标就开始**一页 40 条地往更旧方向顺序翻页**。频道媒体越多、目标越旧，往返次数越多——百万级消息的频道意味着上千次请求，表现为列表和计数迟迟不出现，甚至永远不出来。

从资料页的「媒体」标签进入则很快，因为那条路径本身就有按位置查询的能力。

## 2. 官方 Android 为什么慢

PhotoViewer 只有 `imagesArr` 一个内存数组，没有「按位置查本地」的抽象：

- `needSearchImageInArr = true` 之后，首屏由 `MediaDataController.loadMedia(dialogId, 20, 0, 0, …)` 提供，语义固定是「最新 20 条」；
- 目标不在其中时，只能靠 `mediaDidLoad` 通知继续往前翻，每次 `offset_id = 已加载最旧 id`、`limit = 40`；
- 每一页都要一次网络往返，且必须串行（下一页的 offset 依赖上一页结果）。

所以慢的根源不是某个 bug，而是**缺少本地媒体索引 + 窗口查询**这一层。

## 3. 四个平台的做法

| 平台 | 点开第 K 张媒体时如何取得列表 |
|---|---|
| **TDesktop (C++)** | `Photos::Controller` 对本地 `Storage::Media::Overview` 做 range query，同步取出 `[K−n, K+n]` 一段，网络只用来补缺 |
| **Telegram-iOS (Swift)** | `GalleryController` + `MessageHistory` / `ChatMediaIndex`，先用 Postbox 本地索引给出窗口，网络 backfill |
| **Telegram X (Android)** | `MediaGalleryProvider` + `CachedMediaDataSource`，本地缓存切片优先 |
| **官方 Android (DrKLO / TelegramMessenger-Android)** | 只有内存数组，靠 `loadMedia` 一页页往回翻 |

前三者都能做到「任意位置点开零等待、与总量无关」；官方 Android 是异类。

这个差距不是实现细节，而是架构层面：前三者都有「按位置查询媒体」的索引表，Android 没有。

## 4. 两种补法

### 4.1 源码补丁（已放弃）

直接改 Android 源码，两处：

1. `MediaDataController.loadMedia`：把 `add_offset = 0` 改成 `-(count / 2 + 1)`，语义从「从锚点往更旧取 40 条」变成「以锚点为中心取前后各约 20 条」；
2. `PhotoViewer`：首屏到达时把官方数组与窗口做 union，重算 `min_id/max_id` 与 `startReached/endReached`，让后续翻页从窗口两端继续。

效果直接，但要把改造版 Telegram 编译并分发：签名变了、无法跟随官方更新、体积与分发成本都高。放弃。

### 4.2 运行时模块（当前方案）

在不改官方包的前提下，于两个边界上替换行为：

- **请求侧**：拦下首次首页加载，改为对该锚点发一次 `messages.search`（`offset_id = 锚点`、`add_offset = -(WINDOW/2+1)`、`limit = WINDOW`），一次拿到锚点前后连续的 WINDOW 条；
- **投递侧**：把响应交给客户端自己的 `processLoadedMedia`，填表、排序、缩略图、通知全部沿用官方逻辑，不重写合并。

区别在于：源码补丁在官方数据结构**内部**动刀，能直接读写 `imagesArr` / `endReached`；模块只能在边界上换「请求」和「投递」，因此**必须依赖官方自己的合并逻辑**——好处是不容易与官方逻辑打架、抗版本漂移，坏处是拿不到本地索引，那一次网络往返省不掉。

## 5. 实现要点

`app/src/main/java/io/github/dunxuan/tgfastviewer/module/`

| 文件 | 职责 |
|---|---|
| `MediaAroundModule.java` | 入口与生命周期：`onPackageLoaded` 安装 hook、热重载协议、日志 |
| `Tg.java` | 结构性绑定：按方法形状发现目标类与方法，peer / InputPeer 解析，类扫描 |
| `Around.java` | 核心逻辑：首页拦截、窗口请求、投递、计数探针、超时与回退 |
| `Viewer.java` | 锚点捕获与计数控件定位 |
| `Ref.java` | 反射小工具 |

### 5.1 不依赖任何被混淆的名字

官方包经 R8 处理，`MediaDataController` 变成 `vi`，`MessagesController` 变成 `tp`。模块全部按形状发现：

| 目标 | 找法 |
|---|---|
| `PhotoViewer.didReceivedNotification` | 按名字 hook（接口实现，名字保留） |
| 媒体控制器 | hook `ConnectionsManager.sendRequest`（6 个重载），从调用栈取类，再按 `(J,I,I,I,I,J,I,I,I,Object,String)V` 形状确认 `loadMedia` |
| 投递口 | 同类的 `(Messages,J,I,I,I,I,J,I,I,[Z,]I)V`，接受 11 或 12 参 |
| `MessagesController` | 扫 dex：同时具备「静态 `(int) -> 自身`」工厂与「实例 `(long) -> InputPeer`」方法的类 |
| 计数控件 | `PhotoViewer` 内嵌 View 子类 + 签名形状（`set(int,int)` / `set(int,int,boolean)` / `updateShow` / `onDraw`）双路探测 |

启动时后台线程扫 dex 完成发现，实测 `checked=1025 in 80~139ms`。

### 5.2 两个拦截点必须同时在

部分构建里 R8 会把 `loadMedia` 的调用点内联掉，只 hook 它会漏；`processLoadedMedia` 是投递点，无法被内联消除。两点同时挂上去才保证 100% 覆盖。

### 5.3 计数只在精确时显示

不猜位置。窗口落地后并行发一次 `limit=1` 的探针取该会话最新媒体 id，仅当它等于窗口顶部才允许计数控件绘制，否则在 `onDraw` 里跳过。宁可不显示，也不显示会跳变的值。

探针延后 300ms，避免与窗口请求抢同一通道。

### 5.4 限流：一次点开只接管头两次

PhotoViewer 打开时会为**每种媒体类型各请求一次首页**，实测一次点开产生 13 次拦截。全部接管会让 13 个 `WINDOW` 条消息的搜索挤在一条连接上，大部分赶不上截止时间。

现在每个会话 2.5 秒内只接管前两次，其余直接走官方路径。

### 5.5 超时与回退

拦截成功后原始调用被吞掉，`loadingMoreImages` 会一直为真，若窗口请求再也不回来就会永久卡死。因此 6 秒未落地即用 `Invoker.Type.ORIGIN` 原样重放被拦下的调用，行为回到官方。

### 5.6 热重载

`onHotReloading` 停掉模块自建扫描线程、作废绑定、把目标 `ClassLoader` 经 `setSavedInstanceState` 交给新代并返回 `true`；`onHotReloaded` 在新代码里 unhook 全部旧句柄后用继承的 `ClassLoader` 重新绑定。包生命周期回调**不会**重放，所以重装 hook 必须自己做。所有 hook 带稳定 id（`viewer` / `network0..5` / `loadMedia` / `deliver`）。

## 6. 实测

设备：Redmi 23049RAD8C / arm64，Telegram 13.3.1（Play 版，versionCode 73461），LSPosed 2.2.1（API 102）。

一次正常打开的主路径日志：

```
scan found org.telegram.messenger.vi  checked=1025 in 139ms
messages controller: org.telegram.messenger.tp
media loading hooked: org.telegram.messenger.vi
delivery hooked: 11 params
around dialog=-1736442333 anchor=36648 type=0 guid=9
window size=40 top=36695 guid=12
delivered via processLoadedMedia params=11
probe newest=1017194 windowTop=36695 exact=false
```

`exact=false` 说明锚点距最新约 98 万条，计数按设计隐藏。

### 窗口请求的真实耗时

| 版本 | 典型往返 | 尾延迟 | 说明 |
|---|---|---|---|
| 1.4.2（window 64） | — | 10/13 超时 | 拦截过密导致互相排队 |
| 1.4.3（限流 + window 40） | 244~291 ms | 767 / 1023 ms | 9 次全部投递，0 超时 |
| 1.4.4（+ 对冲补发） | 306~337 ms | 648~802 ms | 8/15 触发补发 |
| 1.4.5（回退对冲） | 回到 1.4.3 水平 | 同 | 一次打开只发一次请求 |

### 被否决的方案：对冲请求

想法是首包 350ms 未回就补发一次相同的搜索、谁先到用谁。实测是**负优化**：补发请求与原请求走同一条连接，只是推高拥塞，典型往返 244~291ms 升到 306~337ms，尾延迟几乎没变（767~1023ms → 648~802ms）。已回退。

## 7. 已知边界

- **每次打开仍有一次网络往返**（基线 250~300ms，偶发 800ms 级尖峰）。这是「一次往返」架构的物理下限，客户端侧无法再压。
- 断网时没有可滑邻居，与官方一致。
- 计数在窗口未顶到最新时一律隐藏。
- 抓不到目标类或方法时静默不介入，不影响客户端正常运行。

## 8. 若要做到零等待

只有两条路：

1. **上游实现本地媒体索引**（类似 `MessageMediaIndex` 表 + range 查询），让 PhotoViewer 能按位置同步取窗口，与 iOS / 桌面版对齐；
2. **源码补丁**，把「顺序翻页」改成「居中取」，列表可边取边显示。

社区模块做不到第 1 条：`MessagesStorage` 完全混淆、SQL 表结构无稳定句柄，猜测 schema 在官方改表时会带来数据损坏风险。
