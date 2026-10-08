# Decimen 接收端 · Android WebView 壳

把官方 v0.5.3 的单文件接收端原样装进手机，不需要服务器、不需要 https 证书、不需要联网。

## 为什么需要这个壳

`decimen-receiver.html` 本身是好东西，但在手机上有个绕不过去的坎：**Android Chrome 和 iOS Safari 都不给 `file://` 打开的页面摄像头**（见官方 [install-and-offline.md](https://github.com/bashalarmistalt/decimen-optical-transfer/blob/main/docs/user/install-and-offline.md)）。所以官方对手机的建议是把托管站点装成 PWA——代价是首访要联网、且信第三方主机。

这个壳的做法是：用 `WebViewAssetLoader` 把 APK 内的 asset 挂到 `https://appassets.androidplatform.net/assets/…`。那是个真实安全上下文，摄像头策略就此满足，而字节一直留在本机。接收端的代码一行没改——协议风险为零，变的只是"谁来喂摄像头、谁来收文件"。

## 壳只补浏览器白送的那几件事

| 缺什么 | 壳做了什么 |
|---|---|
| `blob:` 下载 | WebView 自带的下载回调读不了 `blob:` URL。页面加载完注入一小段脚本（`assets/save-shim.js`），在捕获阶段接住 `<a download>`，`fetch` 那个 blob（页面自己的 CSP 就允许 `connect-src blob:`），分块 base64 过 JS 桥交给原生写入 `Download/Decimen/`。写完校验字节数，缺了就当场说明并丢弃 |
| 屏幕常亮 | 页面的 `navigator.wakeLock?.request()` 是可选链，WebView 里静默什么也不做 → 原生 `FLAG_KEEP_SCREEN_ON` |
| 存储权限 | API 29+ 走 `MediaStore.Downloads`（不申请存储权限）；26–28 写应用外部目录 |
| 结果在哪看、文件去哪找 | 页面自己不留历史。壳加一条原生底部栏（自检 / 已收文件 / 关于）和一条结果条：成功是深底白字、七秒收起，错误才用红且不自动消失。「已收文件」按落盘成功的记录列出来，点一条交给系统应用打开；26–28 那批文件没有可分享的 `content:` URI，点一条是复制完整路径 |
| 一次只开一个摄像头 | 壳在切到自检页之前先注入 `__decimenRxEjectCamera()`，把页面 `<video>` 上的轨道全部 `stop()`，再导航。否则自检页只会拿到一个 `NotReadableError`，那条结论毫无用处 |

外加一条硬边界：**清单里没有 `INTERNET` 权限**。页面本来就有 `default-src 'none'` 的 CSP，去掉权限是把"不联网"从页面自觉降级为操作系统强制——将来谁升了依赖也没法悄悄打电话回家。

## 构建

推送到 GitHub 后由 `.github/workflows/build.yml` 出包（本机不需要 JDK / Android SDK）：

1. `git init` + 建仓库 + push（见下）
2. Actions 里跑 `build apk`，下载 artifact `decimen-receiver-debug`
3. 校验步骤会先跑 `sha256sum --check ASSET-SHA256SUMS.txt`——红了就说明有人动过内联的官方页面，那个 APK 不能再当作对上游行为的验证

出的是 **debug** 包，用自动生成的 debug key 签名，可以直接点开安装，仓库里不留任何 keystore 秘密。也因此：**换一次 CI 构建就得先卸载旧版**——每次运行生成的 debug key 不同，签名不符时系统只会说"未安装"。

## 使用

打开应用直接就是官方接收端：对着 PC 上的 `decimen-sender.html`，页面自己显示 capture/decode fps、goodput、K 这些实时诊断。底栏三项：自检、已收文件、关于。

自检页是通道测试台，不参与协议，返回键从它回到接收端。按「测摄像头」逐行打印，再按「复制结果」导出：

- `安全上下文` 必须是 `✓ https://appassets.androidplatform.net`
- `WebView` 行报的是这台机器的 Chromium 内核版本号
- `实际分辨率 / 实际帧率 / 实测帧率`——要的是 1280×720@60，报回多少是多少
- `对焦` 行：`applyConstraints({focusMode:'continuous'})` 是被接受还是拒绝。上游把"手持抖动导致对焦反复"列为第一大吞吐杀手，这一行直接决定实测速度
- `可用镜头`：自动挑选在部分机型上会抓长焦或前置，列出来才知道要不要手动选
- 「测保存」写一个 1 KB 探针文件，先证明落盘通路，再跑真实接收

一台实机（后置 `camera2 4`）的读法：开流 791 ms，`getSettings()` 报 60.0 fps 而 3 秒实测只有 25.1 fps——设置值是那台机器"接受"的值，不是它吐帧的值，**发送端帧率设到 25 以上只是丢帧**；对焦接受 `continuous`，能力里 `torch` 有但没有 `zoom`。

## 许可与来源

- 整个仓库 **AGPL-3.0-or-later**（`LICENSE`）：接收端页面就是 AGPL 的，壳与它构成同一作品。
- `app/src/main/assets/decimen-receiver.html` 是 [`bashalarmistalt/decimen-optical-transfer` v0.5.3](https://github.com/bashalarmistalt/decimen-optical-transfer/releases/tag/v0.5.3) 的发布资产，逐字节未改，SHA-256 钉在 `ASSET-SHA256SUMS.txt`（`0a800d51…5f5`，与官方 `SHA256SUMS.txt` 一致）。
- 解码引擎是他们单独发布的 `decimen-codec`（zxing-cpp 的 QR-only 构建，Apache-2.0，见 `LICENSE.zxing-cpp` 与 `NOTICE.decimen-codec.md`），以 `data:` URI 内联在那份页面里。
- 壳自己的代码（`MainActivity.java`、`SaveBridge.java`、`assets/probe.html`、`assets/save-shim.js`）同样按 AGPL-3.0-or-later 提供。

## 已知边界

- 自检页不参与协议，只测通道；真接收走的是官方页面本身。
- 官方页在手机上依然"网页味"（字小、下拉框密），那是上游 v0.5.3 的排版，按决定一行样式都不覆盖：壳只在运行时注入落盘桥和释放镜头那两个函数，资产字节保持钉死，界面问题可以直接对官方网页版复现。
- WebView 内核过旧（Chromium < 大约 87）可能缺 `requestVideoFrameCallback`，那时"实测帧率"一行会直接说明，不影响其它结论。
- 页面里 `navigator.serviceWorker?.controller` 那条路径在 WebView 中永远拿不到 SW（WebView 不支持 Service Worker），因此收到的媒体走它自己的 blob 回退分支——这正是官方文档写明的预期行为，不是壳的退化。
