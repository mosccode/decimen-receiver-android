/*
 * 壳注入官方接收页的驱动。它只做官方页自己做不到的四件事：
 * 进屏自动开镜头、暂停/继续、手电筒、把参数面板搬到原生屏上。
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
    p.click();
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

  function setSetting(key, value) {
    if (key === "autoshow") {
      var box = el("cfg-autoshow");
      if (!box) return false;
      box.checked = !!value;
      box.dispatchEvent(new Event("change", { bubbles: true }));
      return true;
    }
    var node = el(SELECTS[key]);
    if (!node) return false;
    node.value = String(value);
    if (node.value !== String(value)) return false;
    node.dispatchEvent(new Event("change", { bubbles: true }));
    return true;
  }

  function state() {
    var t = track();
    var caps = t && t.getCapabilities ? t.getCapabilities() : {};
    return {
      ready: ready(),
      running: started(),
      paused: paused,
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
