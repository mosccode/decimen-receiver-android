/*
 * 壳注入官方接收页的驱动。它只做官方页自己做不到的事：进屏自动开镜头、
 * 暂停/继续、手电筒、把参数面板搬到原生屏上、把参数记下来并在下次回位，
 * 以及在它被系统收回镜头之后替它盯着帧。
 * 所有实际动作都落在页面自己的元素和事件上（点 #start、改它自己的
 * <select> 并派发 change），协议代码一行不绕开。
 *
 * 暂停刻意不叫停轨道：页面里那条停止路径会连解码缓冲一起清（H.resize(0)），
 * 那就不再是“暂停”。这里只把轨道 enabled 关掉并暂停 <video>，
 * 已收下的喷泉块原样留着，继续后接着算。
 */
(function () {
  "use strict";

  if (window.__decimenRxUi) return;

  var BRIDGE = window.AndroidBridge;
  var paused = false;
  var torchOn = false;
  var torchTrack = null;
  var restored = false;

  function el(id) {
    return document.getElementById(id);
  }

  function video() {
    return el("video");
  }

  function track() {
    var stream = video() && video().srcObject;
    return stream && stream.getVideoTracks && stream.getVideoTracks()[0] ? stream.getVideoTracks()[0] : null;
  }

  function started() {
    var p = el("start");
    var zone = el("preview");
    return !!p && !!zone && p.style.display === "none" && zone.style.display !== "none";
  }

  function ready() {
    var p = el("start");
    // 页面的启动按钮是先 await 一串 i18n 才挂上 onclick 的；挂上之前点它没有意义。
    return !!(p && typeof p.onclick === "function");
  }

  function start() {
    var p = el("start");
    if (!ready() || !p || p.disabled || p.style.display === "none") return false;
    // 先回位再点：页面开镜头时读的就是这几个 <select> 的当前值。
    restoreOnce();
    p.click();
    // 镜头这一项此刻还没有真选项可选，只能等页面自己填完列表再换。
    restoreCamera(0);
    return true;
  }

  function setPaused(next) {
    var node = video();
    var stream = node && node.srcObject;
    if (stream && stream.getTracks) {
      stream.getTracks().forEach(function (t) {
        t.enabled = !next;
      });
    }
    if (node) {
      if (next) node.pause();
      else node.play().catch(function () {});
    }
    paused = !!next && !!stream;
    document.body.classList.toggle("decimen-paused", paused);
    return paused;
  }

  function torch() {
    var t = track();
    if (!t) return false;
    if (t !== torchTrack) {
      torchTrack = t;
      torchOn = false;
    }
    var caps = t.getCapabilities ? t.getCapabilities() : {};
    if (!caps.torch) return false;
    var next = !torchOn;
    torchOn = next;
    t.applyConstraints({ advanced: [{ torch: next }] }).catch(function () {
      torchOn = !next;
    });
    return true;
  }

  function options(node) {
    var out = [];
    if (!node) return out;
    for (var i = 0; i < node.options.length; i++) {
      var option = node.options[i];
      out.push({ v: option.value, t: option.textContent, on: !option.disabled });
    }
    return out;
  }

  function settings() {
    var autoshow = el("cfg-autoshow");
    var actual = el("camera-actual");
    return {
      camera: options(el("cfg-camera")),
      cameraValue: el("cfg-camera") ? el("cfg-camera").value : "",
      width: options(el("cfg-width")),
      widthValue: el("cfg-width") ? el("cfg-width").value : "",
      capfps: options(el("cfg-capfps")),
      capfpsValue: el("cfg-capfps") ? el("cfg-capfps").value : "",
      workers: options(el("cfg-workers")),
      workersValue: el("cfg-workers") ? el("cfg-workers").value : "",
      autoshow: !!(autoshow && autoshow.checked),
      actual: actual ? (actual.textContent || "").trim() : ""
    };
  }

  var SELECTS = { camera: "cfg-camera", width: "cfg-width", capfps: "cfg-capfps", workers: "cfg-workers" };

  function pick(key, value) {
    var node = el(SELECTS[key]);
    if (!node) return false;
    node.value = String(value);
    if (node.value !== String(value)) return false;
    // 派发页面自己的 change：换镜头走它的 BB()，改宽度/帧率走它的 QB()。
    node.dispatchEvent(new Event("change", { bubbles: true }));
    return true;
  }

  function setSetting(key, value) {
    if (key === "autoshow") {
      var box = el("cfg-autoshow");
      if (!box) return false;
      box.checked = !!value;
      box.dispatchEvent(new Event("change", { bubbles: true }));
      return true;
    }
    if (!pick(key, value)) return false;
    remember(key, String(value));
    return true;
  }

  // 官方页只往 localStorage 里存两样：语言与「收到即展示」。镜头、采集宽度、
  // 采集帧率、解码线程只活在 DOM 里，所以「重置」和重开应用都会把它们打回
  // 页面自己的默认值。壳改不了它的字节，就把这四个值记在壳自己的键下。
  var PREFS = "decimen:rx-prefs";

  function readPrefs() {
    try {
      return JSON.parse(window.localStorage.getItem(PREFS) || "{}") || {};
    } catch (junk) {
      return {};
    }
  }

  function remember(key, value) {
    var all = readPrefs();
    all[key] = value;
    write(all);
  }

  function forget(key) {
    var all = readPrefs();
    if (!(key in all)) return;
    delete all[key];
    write(all);
  }

  function write(all) {
    try {
      window.localStorage.setItem(PREFS, JSON.stringify(all));
    } catch (full) {
      /* 存不下就这一轮丢，别打断正在传输的东西 */
    }
  }

  // 宽度/帧率/线程的选项在页面标记里就有，页面把线程默认推到硬件上限那句
  // 也跑在挂 onclick 的同一段同步代码里 —— 所以 ready() 为真时再写，才不会被它盖掉。
  function restore() {
    var all = readPrefs();
    var back = [];
    ["width", "capfps", "workers"].forEach(function (key) {
      if (all[key] && pick(key, all[key])) back.push(key + "=" + all[key]);
    });
    return back;
  }

  // 自动开镜头有两条入口（驱动自己等到 ready，和原生进屏时催一次），谁先到都要
  // 先回位，而且只回一次 —— 回位会派发 change，页面挂上热换处理之后每次都会重开镜头。
  function restoreOnce() {
    if (restored) return [];
    restored = true;
    return restore();
  }

  // 镜头这一项要等页面自己 enumerateDevices 之后才有选项（它把列表填在 start 里），
  // 所以只能等它填上再挑回去：这一次换镜头用的是页面自己的换镜头路径。
  function restoreCamera(tries) {
    var all = readPrefs();
    if (!all.camera) return;
    var node = el("cfg-camera");
    if (node && node.options.length > 1) {
      if (node.value === all.camera) return;
      // 认不出这个 deviceId 就是设备号变了（WebView 的 deviceId 不保证跨会话稳定）：
      // 记住的这条作废，别让它一直重试，也别把用户锁在 auto 上。
      if (!pick("camera", all.camera)) forget("camera");
      return;
    }
    if (tries > 40) {
      forget("camera");
      return;
    }
    window.setTimeout(function () {
      restoreCamera(tries + 1);
    }, 250);
  }

  // 官方页一次可见性事件都没监听（visibilitychange / document.hidden 在原件里出现 0 次），
  // 而 Android 把相机交给前台应用时不会通知网页：轨道只是不再来帧，页面那条
  // requestVideoFrameCallback 解码链就永远停在最后一帧上。所以壳自己数帧，
  // 并先做一次最便宜的补救 —— 把被渲染端暂停住的播放重新推起来。
  var watch = { time: -1, misses: 0 };

  function gone() {
    var t = track();
    return !!t && t.readyState !== "live";
  }

  function checkFrames() {
    var node = video();
    if (!started() || paused || !node || !node.srcObject) {
      watch.time = -1;
      watch.misses = 0;
      return;
    }
    // 页面自己换镜头时把那颗 <select> 禁掉，并在旧轨道已停、新画面未接上之间停几百毫秒。
    // 那一段看起来和「被系统收回」一模一样，所以此刻不作数。
    var pickNode = el("cfg-camera");
    if (pickNode && pickNode.disabled) {
      watch.time = -1;
      watch.misses = 0;
      return;
    }
    // 轨道没了就别再看时间：Chromium 在这种 srcObject 上仍然让 currentTime 往前走，
    // 只盯时间就永远数不到 miss。
    if (gone()) {
      watch.misses += 1;
      return;
    }
    var now = node.currentTime;
    if (now !== watch.time) {
      watch.time = now;
      watch.misses = 0;
      return;
    }
    if (node.paused) node.play().catch(function () {});
    watch.misses += 1;
  }

  // 两条路都等满三秒才认输：冷启动开镜头慢的手机本来就有好几秒不来帧，而页面
  // 自己换镜头（BB()）期间旧轨道正是 ended —— 立刻判死等于每次换镜头都重载一次。
  function stalled() {
    if (!started() || paused) return false;
    if (!el("video") || !el("video").srcObject) return false;
    return watch.misses >= 3;
  }

  window.setInterval(checkFrames, 1000);

  function state() {
    var t = track();
    var caps = t && t.getCapabilities ? t.getCapabilities() : {};
    return {
      ready: ready(),
      running: started(),
      paused: paused,
      stalled: stalled(),
      torch: !!(caps && caps.torch),
      torchOn: torchOn
    };
  }

  // 收完自动点它自己的保存链接：文件因此必定落盘、必定进壳的文件列表，
  // 而不是等用户去找那颗「保存 …」。页面上的按钮仍在，再点一次也只是同名同
  // 小数的重复记录，壳那边会合并成一条。
  function harvest(root) {
    var links = (root || document).querySelectorAll("#result a.download");
    for (var i = 0; i < links.length; i++) {
      var link = links[i];
      if (link.dataset.decimenRxSaved) continue;
      link.dataset.decimenRxSaved = "1";
      link.click();
    }
  }

  // WebView 里没有 Web Share，剪贴板 API 也可能整个缺席；页面那句
  // `await navigator.clipboard.writeText(...)` 就会走到「复制失败」。
  function patchClipboard() {
    if (!BRIDGE) return;
    var write = function (text) {
      BRIDGE.copy(String(text === undefined || text === null ? "" : text));
      return Promise.resolve();
    };
    try {
      if (navigator.clipboard) navigator.clipboard.writeText = write;
      else Object.defineProperty(navigator, "clipboard", { value: { writeText: write }, configurable: true });
    } catch (sealed) {
      /* 拿不到就让它红着，不是壳的失职 */
    }
  }

  // 页面按 navigator.languages 选语言；万一它选了英文，钉一次中文再重载。
  // localStorage 本身就是那个「只重来一次」的标记。
  function forceChinese() {
    var lang = document.documentElement.lang || "";
    if (/^zh/i.test(lang)) return;
    var STORED = "decimen:locale";
    try {
      if (window.localStorage.getItem(STORED) === "zh-hans") return;
      window.localStorage.setItem(STORED, "zh-hans");
      window.location.reload();
    } catch (blocked) {
      /* 存储被挡就随它去，中文不是功能 */
    }
  }

  window.__decimenRxUi = {
    state: state,
    start: start,
    setPaused: setPaused,
    torch: torch,
    settings: settings,
    setSetting: setSetting
  };

  patchClipboard();
  if (!ready()) forceChinese();

  var result = el("result");
  if (result && window.MutationObserver) {
    new MutationObserver(function () {
      harvest(result);
    }).observe(result, { childList: true, subtree: true });
  }

  // 等页面把 onclick 挂上再自动开镜头；它要 await 一串翻译目录。
  var waited = 0;
  (function whenReady() {
    if (ready()) {
      start();
      return;
    }
    if (waited > 8000) return;
    waited += 200;
    window.setTimeout(whenReady, 200);
  })();
})();
