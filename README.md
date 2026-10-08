# Decimen 接收端 · Android WebView 壳

把官方 v0.5.3 的单文件接收端原样装进手机，不需要服务器、不需要 https 证书、不需要联网。

## 为什么需要这个壳

`decimen-receiver.html` 本身是好东西，但在手机上有个绕不过去的坎：**Android Chrome 和 iOS Safari 都不给 `file://` 打开的页面摄像头**（见官方 [install-and-offline.md](https://github.com/bashalarmistalt/decimen-optical-transfer/blob/main/docs/user/install-and-offline.md)）。所以官方对手机的建议是把托管站点装成 PWA——代价是首访要联网、且信第三方主机。

这个壳的做法是：用 `WebViewAssetLoader` 把 APK 内的 asset 挂到 `https://appassets.androidplatform.net/assets/…`。那是个真实安全上下文，摄像头策略就此满足，而字节一直留在本机。接收端的代码一行没改——协议风险为零，变的只是"谁来喂摄像头、谁来收文件、它在屏幕上长什么样"（排版由运行时注入改写，资产字节照旧钉死，见下面两节）。

## 壳只补浏览器白送的那几件事

| 缺什么 | 壳做了什么 |
|---|---|
| `blob:` 下载 | WebView 自带的下载回调读不了 `blob:` URL。页面加载完注入一小段脚本（`assets/save-shim.js`），在捕获阶段接住 `<a download>`，`fetch` 那个 blob（页面自己的 CSP 就允许 `connect-src blob:`），分块 base64 过 JS 桥交给原生写入 `Download/Decimen/`。写完校验字节数，缺了就当场说明并丢弃 |
| 屏幕常亮 | 页面的 `navigator.wakeLock?.request()` 是可选链，WebView 里静默什么也不做 → 原生 `FLAG_KEEP_SCREEN_ON` |
| 存储权限 | API 29+ 走 `MediaStore.Downloads`（不申请存储权限）；26–28 写应用外部目录 |
| 手机上的操作面 | 原生三屏：**接收 / 文件 / 设置**（底栏），接收屏下面一条控制条：**暂停·手电筒·重置**。官方页没有暂停键，也没有补光和"重来一次"，这三件事由注入的驱动落在页面自己的元素上（见下节） |
| 收到文件要自己去点保存 | 注入的 `receiver-ui.js` 用 `MutationObserver` 盯住 `#result`，出现页自己那颗 `a.download` 就点一次——字节必定走 `save-shim.js` 那条通路落盘并进文件列表；页面上的按钮仍在，再点一次也不会重复列（历史按名字+大小合并） |
| 文件去哪找、怎么给别人 | 「文件」屏按落盘成功的记录列出名字、大小、时间与位置，每条三颗：**打开**（`ACTION_VIEW`）·**分享**（`ACTION_SEND` + 选择器）·**删除**（连记录一起）。26–28 那批文件在应用自己的目录里、没有可授权的 `content:` URI，打开与分享都退化为复制完整路径 |
| 参数与诊断 | 「设置」屏读页面自己那几个 `<select>`（镜头 / 采集宽度 / 采集帧率 / 解码线程 / 收到即展示），改值就是写它并派发 `change`，实时生效路径仍是页面自己的；摄像头自检挪到这里的「诊断」段，返回键从自检回到接收端 |
| 一次只开一个摄像头 | 壳在切到自检页之前先注入 `__decimenRxEjectCamera()`，把页面 `<video>` 上的轨道全部 `stop()`，再导航。否则自检页只会拿到一个 `NotReadableError`，那条结论毫无用处 |

外加一条硬边界：**清单里没有 `INTERNET` 权限**。页面本来就有 `default-src 'none'` 的 CSP，去掉权限是把"不联网"从页面自觉降级为操作系统强制——将来谁升了依赖也没法悄悄打电话回家。

## 排版归壳，协议归官方页

官方页在手机上依然"网页味"：页头页脚、大标题、字小的下拉框，占掉近半屏高。上一版决定一行样式都不覆盖，实机用下来判定是错的——不是协议错，是手感错。

现在**资产字节仍然一个都不改**（CI 每次 `sha256sum --check ASSET-SHA256SUMS.txt`），改的是它在屏幕上长成什么样：页面加载完，壳往 `<head>` 末尾追加 `assets/receiver-ui.css`（CSP 里有 `style-src 'unsafe-inline'`，注入 `<style>` 元素被允许），再用 `assets/receiver-ui.js` 驱动页面自己的元素。注入的脚本是 user script，不受页面 CSP 约束；驱动只做四件事——进屏自动开镜头、暂停/继续、手电筒、把参数搬到原生设置屏——每一次实际动作都落在官方页自己的控件和事件上（点 `#start`、改它自己的 `<select>` 并派发 `change`）。**解码、喷泉缓冲、帧回调、完成判定没有一行被替换或绕过**，所以"官方网页版复现得出"这条判据照旧成立。

两个刻意的设计：

- **暂停 ≠ 停轨道。** 页面自己的停止路径会连解码缓冲一起清（`resize(0)`），那就不是暂停而是作废。这里只把 `track.enabled` 关掉并 `video.pause()`——`requestVideoFrameCallback` 那条链在下次 `play()` 时会重新点火，已经收下的喷泉块原样留着，继续后接着算。CSS 顺手压掉它的「无信号」提示，因为停下来的画面本来就没有信号。
- **WebView 高度恒定。** 原生两条底栏是固定高度，WebView 按两者之和做下边距，并且切屏时控制条用 `INVISIBLE`（占位不吃触摸）而不是 `GONE`。页面按 `100dvh` 排版预览，若容器高度随屏幕变化，正在传输的取景框会被重排一次。

自动开镜头有个诚实的后备：合成点击在部分内核里不算真实用户手势，那时页面自己的失败路径会把 `#start` 留在屏上，注入样式已把它放大成整屏——一次轻触即开，跟原来一样。

## 构建

推送到 GitHub 后由 `.github/workflows/build.yml` 出包（本机不需要 JDK / Android SDK）：

1. `git init` + 建仓库 + push（见下）
2. Actions 里跑 `build apk`，下载 artifact `decimen-receiver-debug`
3. 校验步骤会先跑 `sha256sum --check ASSET-SHA256SUMS.txt`——红了就说明有人动过内联的官方页面，那个 APK 不能再当作对上游行为的验证

出的是 **debug** 包，用自动生成的 debug key 签名，可以直接点开安装，仓库里不留任何 keystore 秘密。也因此：**换一次 CI 构建就得先卸载旧版**——每次运行生成的 debug key 不同，签名不符时系统只会说"未安装"。

## 使用

打开应用就是官方接收端，并且镜头自己开：对着 PC 上的 `decimen-sender.html`，页面自己显示 capture/decode fps、goodput、K 这些实时诊断。控制条上「暂停」让取景冻住而缓冲不清，「手电筒」补光（这颗镜头不支持时会直接说明），「重置」等价于页面自己的「接收另一个文件」。收完自动落盘，「文件」屏可打开、分享、删除；「设置」屏改采集参数与看自检。

自检页是通道测试台，不参与协议，返回键从它回到接收端。在「设置 → 诊断 → 摄像头自检」进去。按「测摄像头」逐行打印，再按「复制结果」导出：

- `安全上下文` 必须是 `✓ https://appassets.androidplatform.net`
- `WebView` 行报的是这台机器的 Chromium 内核版本号
- `实际分辨率 / 实际帧率 / 实测帧率`——要的是 1280×720@60，报回多少是多少
- `对焦` 行：`applyConstraints({focusMode:'continuous'})` 是被接受还是拒绝。上游把"手持抖动导致对焦反复"列为第一大吞吐杀手，这一行直接决定实测速度
- `可用镜头`：自动挑选在部分机型上会抓长焦或前置，列出来才知道要不要手动选
- 「测保存」写一个 1 KB 探针文件，先证明落盘通路，再跑真实接收

一台实机（后置 `camera2 4`，Chrome 138 内核）两次自检的读法：开流 791 ms → 752 ms，`getSettings()` 报 60.0 fps 而 3 秒实测分别是 **25.1 → 16.3 fps**——设置值是那台机器"接受"的值，不是它吐帧的值，**发送端帧率设到 16 以上就已经在丢帧**（同一台机器两次相差 8.8 fps，所以每次用之前重新测，别照抄别人的数字）；对焦接受 `continuous`，能力里 `torch` 有但没有 `zoom`；请求 720×1280、5 颗镜头可挑。

## 许可与来源

- 整个仓库 **AGPL-3.0-or-later**（`LICENSE`）：接收端页面就是 AGPL 的，壳与它构成同一作品。
- `app/src/main/assets/decimen-receiver.html` 是 [`bashalarmistalt/decimen-optical-transfer` v0.5.3](https://github.com/bashalarmistalt/decimen-optical-transfer/releases/tag/v0.5.3) 的发布资产，逐字节未改，SHA-256 钉在 `ASSET-SHA256SUMS.txt`（`0a800d51…5f5`，与官方 `SHA256SUMS.txt` 一致）。
- 解码引擎是他们单独发布的 `decimen-codec`（zxing-cpp 的 QR-only 构建，Apache-2.0，见 `LICENSE.zxing-cpp` 与 `NOTICE.decimen-codec.md`），以 `data:` URI 内联在那份页面里。
- 壳自己的代码（`MainActivity.java`、`SaveBridge.java`、`Saves.java`、`assets/probe.html`、`assets/save-shim.js`、`assets/receiver-ui.css`、`assets/receiver-ui.js`）同样按 AGPL-3.0-or-later 提供。

## 已知边界

- 自检页不参与协议，只测通道；真接收走的是官方页面本身。
- 注入层只认官方页的 id 与 class 名字。上游若改名，自动开镜头、暂停、参数面板会一起静默失灵——所以 `node tools/check.mjs` 把驱动要的每个 `#id`、样式针对的每个 class、原生与 `__decimenRxUi` 之间的六个方法名都对到钉死的资产上逐条断言。本机没有 JDK/SDK，这个脚本是唯一离线门禁，CI 之前必须先跑绿。
- 官方页的字节依旧逐字节未改，界面问题仍然可以直接对官方网页版复现：改写只发生在运行时，不发生在资产里。
- 26–28 落盘的文件在应用自己的外部目录里，没有可授权的 `content:` URI（工程里没有 FileProvider），所以那批文件的「打开 / 分享」是复制路径。
- WebView 内核过旧（Chromium < 大约 87）可能缺 `requestVideoFrameCallback`，那时"实测帧率"一行会直接说明，不影响其它结论。
- 页面里 `navigator.serviceWorker?.controller` 那条路径在 WebView 中永远拿不到 SW（WebView 不支持 Service Worker），因此收到的媒体走它自己的 blob 回退分支——这正是官方文档写明的预期行为，不是壳的退化。
