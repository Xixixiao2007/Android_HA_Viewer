# Android_HA_Viewer

把 Home Assistant 里**某一个实体**的状态变化，做成 Android 手机上的实时流水列表。

为 **Android 6.0 老设备**设计：不经过 WebView、不依赖 Google 服务、依赖为零、APK 只有 **36.7 KB**。

```
┌────────────────────────────────────┐
│  sensor.my_text            [设置]  │
│  ⚡ 实时 · 共 128 条变化            │
├────────────────────────────────────┤
│  09-05 14:23:05                    │
│  25.3                              │
│  09-05 14:22:41                    │
│  25.1                              │
│  09-05 14:20:12                    │
│  24.9                              │
└────────────────────────────────────┘
```

---

## 为什么会有这个项目

手上有一台还能用但已经过时的 Android 6.0 手机，想拿它当 HA 的实体状态监视屏，结果发现三条路都堵死了：

| 方案 | 为什么不行 |
|---|---|
| 官方 HA App | 界面由 WebView 渲染。Android 6 的 Chrome / System WebView 最高只能到 **106**（Google 已停止为 6.0 发版），加载 Lovelace 卡顿甚至白屏 |
| 浏览器打开 HA 网页版 | 同上，而且这台机器的浏览器连普通现代网页都会显示异常 |
| 自己做个网页 | 还是要把命运交给那个烂 WebView |

**绕开 WebView 就全通了。** 原生控件（`ListView` / `EditText`）不需要任何浏览器引擎，于是有了这个 App。

同时刻意做到了**零第三方依赖**：不用 androidx、不用 OkHttp、不用 Gson、不用 GMS，连 WebSocket 客户端都是自己实现的。代价是代码多一点，好处是 APK 极小、在弱机上启动快、也不受 AndroidX 版本策略影响。

---

## 功能

- **WebSocket 实时推送**，延迟约 **50~150 毫秒**（局域网）
- **自动重连**：指数退避（1→2→4→…→30 秒），重连期间自动降级为 REST 轮询，列表不中断
- **60 秒对账**：实时在线时仍定期用历史接口核对一遍，兜住重连空档
- **关键词黑白名单**：一个开关切换模式，关键词列表可增删，大小写不敏感的子串匹配
- **提示音**：有通过筛选的新记录时响一声，用系统通知铃声（静音模式下不响），2 秒节流防连环炸响
- **纯原生界面**，不经过 WebView

---

## 环境要求

| | |
|---|---|
| 手机 | Android 6.0 及以上（minSdk 23 / targetSdk 23） |
| Home Assistant | 需启用 `recorder` 组件（**默认开启**）。历史回溯功能依赖它 |
| 权限 | 仅 `INTERNET`（安装即授予，无运行时弹窗） |

---

## 安装

从 [Releases](../../releases) 下载 `Android_HA_Viewer-vX.Y.apk`，拷到手机，开启「未知来源」后安装。

> 装好后桌面上显示的名字是「**HA Viewer**」——仓库名较长，刻意用了短显示名以免在图标下被截断。

---

## 配置

### 1. 获取长期访问令牌

在**电脑浏览器**上：

1. 打开 HA → 左下角点**你的用户名** → 进入「个人资料」
2. 切到「安全」标签页 → 拉到最底部 → 「长期访问令牌」→「创建令牌」
3. 名称随意（如 `android6`）→ 确定
4. 弹出 `eyJhbGciOiJIUzI1NiIs...` 一长串 → **立刻复制**（关闭后不再显示）

> **普通权限用户的令牌就够了，不需要管理员。**
> 读状态、读历史、订阅 WebSocket 事件都只要求已认证用户。唯一限管理员的是
> `POST /api/states/<实体>`（直接改写状态），本 App 不使用。
>
> 但要注意：HA 有**实体级权限**系统。如果管理员给这个用户限制过可见实体，
> 请确认该用户对目标实体有 `read` 权限。

### 2. 在 App 里填写

首次启动会自动弹出设置页：

| 字段 | 说明 |
|---|---|
| 服务器地址 | 如 `http://homeassistant.local:8123`。端口不确定就试 8123；新版 HAOS 也可能是 80 |
| 长期访问令牌 | 上面复制的那串 |
| 实体名 | 要监控的实体 ID，如 `sensor.xxx` |
| 回溯小时数 | 启动时往前读多久的历史，默认 24 |
| 刷新间隔 | 轮询间隔，默认 2 秒。**实时在线时此项不生效**（改为 60 秒对账） |
| 实时推送 | 默认开。关掉则退回纯轮询 |
| 提示音 | 默认开 |
| 筛选模式 / 关键词 | 见下节 |

填完点「**测试连接**」可以一次性验证地址、端口、令牌、实体四件事。

### 3. 白名单 / 黑名单

一个开关在两种模式间切换，下面是关键词列表：

| 模式 | 行为 |
|---|---|
| **白名单** | 只显示**命中**任一关键词的记录；列表为空 = 全部显示 |
| **黑名单** | **隐藏**命中任一关键词的记录；列表为空 = 不过滤 |

匹配对象是「变化后的值文本」，大小写不敏感、子串匹配。切换模式时关键词保留，只是语义反转。

---

## 延迟

标题栏会直接显示当前走的是哪条通道：

- `⚡ 实时` —— WebSocket 已连接
- `⏳ 连接中` —— 正在建立或重连
- `⏱ 轮询` —— 实时不可用，已降级

| 通道 | 环节 | 耗时 |
|---|---|---|
| **WebSocket** | 事件经网络直接推送，**不经过数据库** | **约 50~150 ms** |
| **REST 轮询** | recorder 落库(`commit_interval` 默认 5s) + 轮询等待 | 平均约 3.5 s，最坏约 7 s |

**为什么轮询这么慢**：`/api/history/period` 读的是 recorder 的**数据库**，而 HA 默认每 5 秒才把状态变化批量写库（[`commit_interval`](https://www.home-assistant.io/integrations/recorder/#commit_interval)）。官方文档说「activity 和 history 不会滞后，因为变化是即时流式推送给它们的」——那说的是 HA 自己的面板，因为面板额外订阅了 WebSocket；REST 客户端享受不到，所以有这层延迟。这也正是本项目要自己实现 WebSocket 的原因。

---

## 从源码构建

**不需要 Android Studio，不需要 Gradle。**

### 前置

- Python 3.8+
- JDK 8 ~ 17（脚本会自动探测 `JAVA_HOME` 和常见安装位置，也可用 `HAF1_JDK` 指定）

> 为什么用 build-tools **30.0.3**：它的 `d8` 在 JDK 8 下就能运行。
> build-tools 31+ 的 `d8` 要求 JDK 11，会把整条链路的前置要求抬高。

### 步骤

```bash
# 0. 取得源码
git clone https://github.com/Xixixiao2007/Android_HA_Viewer.git
cd Android_HA_Viewer

# 1. 下载最小 Android 工具集（build-tools 30.0.3 + android-23，约 119 MB，
#    解压后 236 MB，落在 tools/android-sdk/，已被 .gitignore 排除）
python tools/fetch_sdk.py

# 2. 跑单元测试（58 项：ISO8601 时间解析、黑白名单、WebSocket 帧编解码）
python tools/run_tests.py

# 3. 构建 APK
python tools/build_apk.py --out Android_HA_Viewer.apk
```

一次完整构建约 10 秒。产物默认签名为 debug 密钥（首次构建自动生成 `tools/debug.keystore`）。

### 关于签名密钥

`tools/debug.keystore` **已被 `.gitignore` 排除，请勿提交**。

原因：这个 App 里保存着你的 HA 长期访问令牌。如果签名私钥公开，任何人都能签一个
包名相同、签名相同的 APK，Android 会把它当作**你的应用的更新**来安装。

如果丢失密钥，就无法再发布能覆盖安装的更新——**请自行备份**。

---

## 项目结构

```
app/
  AndroidManifest.xml
  res/mipmap-*/ic_launcher.png
  src/com/haf1/entitylist/
    WsFrame.java            RFC 6455 帧编解码（纯逻辑，被单测覆盖）
    HaWebSocket.java        握手 / 鉴权 / 订阅 / 事件解析 / 心跳
    HaClient.java           REST：历史查询、连通性测试、ISO8601 解析（被单测覆盖）
    Prefs.java              配置存取 + 黑白名单判定（被单测覆盖）
    Notifier.java           提示音
    MainActivity.java       列表、实时/轮询双通道调度、重连退避
    SettingsActivity.java   设置页
tools/
  fetch_sdk.py              下载最小 Android 工具集
  build_apk.py              手工 APK 构建流水线
  run_tests.py              桌面端单元测试
  make_icon.py              生成应用图标（纯标准库）
  tztest/                   单测源码与 android/org.json 桩
docs/
  USAGE.md                  使用说明（含故障排查）
```

### 界面为什么全用 Java 代码写，没有 res/layout

这样构建流水线就**不需要生成 `R.java`**，省掉资源符号解析这一整块。代价是界面代码啰嗦一些，
好处是整条构建链路更短、更少出错点。

---

## 免责声明

- 本应用**不收集任何数据**。所有通信只发生在你的手机与你的 Home Assistant 之间。
- 令牌保存在手机本地的 `SharedPreferences` 中。**建议只在局域网内使用明文 `http://`**；
  如需公网访问，请配置 SSL 或使用 WireGuard / Tailscale 等 VPN。
- 若 HA 对非管理员用户设置了实体级权限限制，本应用只能读取到被授权的实体。

## 许可证

[MIT](LICENSE)
