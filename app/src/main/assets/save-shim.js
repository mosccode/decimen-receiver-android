// Injected by the shell into both pages after load. Three jobs, nothing else:
// turn the page's blob: download link into bytes on disk (WebView's own
// download callback cannot read a blob: URL), release the camera when the shell
// switches pages, and give the self-check page the same save path so it can
// prove that pipeline before a real transfer runs.
(function () {
  "use strict";

  if (window.__decimenRxShim) return;
  window.__decimenRxShim = true;

  var BRIDGE = window.AndroidBridge;
  var CHUNK = 32766; // divisible by 3, so every chunk is standalone base64

  function base64(bytes, from, to) {
    var slice = bytes.subarray(from, to);
    var text = "";
    for (var i = 0; i < slice.length; i += 8192) {
      text += String.fromCharCode.apply(null, slice.subarray(i, Math.min(i + 8192, slice.length)));
    }
    return btoa(text);
  }

  function handOff(bytes, name, mime) {
    var id = String(Date.now()) + "-" + Math.floor(Math.random() * 1000000);
    BRIDGE.saveBegin(id, name, mime || "application/octet-stream", bytes.length);
    for (var sent = 0; sent < bytes.length; sent += CHUNK) {
      BRIDGE.saveChunk(id, base64(bytes, sent, Math.min(sent + CHUNK, bytes.length)));
    }
    BRIDGE.saveEnd(id);
  }

  // Callable from the self-check page: same code path a real receive takes.
  window.__decimenRxSave = function (bytes, name, mime) {
    handOff(bytes, name, mime);
  };

  // The shell calls this before switching pages. A phone opens one camera at a
  // time, and the page keeps its stream in a closure the shell cannot reach —
  // but every track is attached to a <video> element in the DOM.
  window.__decimenRxEjectCamera = function () {
    var videos = document.querySelectorAll("video");
    for (var i = 0; i < videos.length; i++) {
      var source = videos[i].srcObject;
      if (!source || !source.getTracks) continue;
      var tracks = source.getTracks();
      for (var t = 0; t < tracks.length; t++) tracks[t].stop();
      videos[i].srcObject = null;
    }
    return videos.length;
  };

  document.addEventListener(
    "click",
    function (event) {
      if (!BRIDGE) return;
      var node = event.target;
      while (node && node !== document.body) {
        if (node.tagName === "A" && node.hasAttribute("download")) break;
        node = node.parentNode;
      }
      if (!node || node.tagName !== "A") return;

      var href = node.href || "";
      // Only blob: — the page's CSP allows fetch of it, and this is the shape
      // the receiver's own Save link uses.
      if (!/^blob:/i.test(href)) return;

      event.preventDefault();
      event.stopPropagation();

      var name = node.getAttribute("download") || "decimen.bin";
      fetch(href)
        .then(function (response) {
          if (!response.ok) throw new Error("HTTP " + response.status);
          return Promise.all([response.arrayBuffer(), response.headers.get("content-type")]);
        })
        .then(function (parts) {
          // The shell reports where the bytes landed — on its own bar, and in
          // 已收文件 — because the real path depends on the Android version.
          //
          // The type is the blob's own: a PNG handed over as octet-stream is a
          // PNG the gallery will not offer to open. Chromium answers a blob:
          // request with the type the Blob was built with, which is exactly the
          // one the receiver page put there.
          handOff(new Uint8Array(parts[0]), name, String(parts[1] || "").split(";")[0].trim());
        })
        .catch(function (error) {
          BRIDGE.saveFailed(String(error && error.message ? error.message : error));
        });
    },
    true
  );
})();
