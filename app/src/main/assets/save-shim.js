// Injected by the shell into both pages after load. Two jobs, nothing else:
// turn the page's blob: download link into bytes on disk (WebView's own
// download callback cannot read a blob: URL), and give the self-check page the
// same path so it can prove that pipeline before a real transfer runs.
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

  function handOff(bytes, name, mime, done) {
    var id = String(Date.now()) + "-" + Math.floor(Math.random() * 1000000);
    BRIDGE.saveBegin(id, name, mime || "application/octet-stream", bytes.length);
    for (var sent = 0; sent < bytes.length; sent += CHUNK) {
      BRIDGE.saveChunk(id, base64(bytes, sent, Math.min(sent + CHUNK, bytes.length)));
    }
    BRIDGE.saveEnd(id);
    if (done) done(bytes.length);
  }

  // Callable from the self-check page: same code path a real receive takes.
  window.__decimenRxSave = function (bytes, name, mime, done) {
    handOff(bytes, name, mime, done);
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
          return response.arrayBuffer();
        })
        .then(function (buffer) {
          handOff(new Uint8Array(buffer), name, "", function () {
            // Give the link a visible outcome of its own, so a save that
            // landed is not mistaken for one that silently did nothing.
            var note = document.createElement("div");
            note.textContent = "已写入 Download/Decimen/" + name;
            note.setAttribute("style", "padding:6px 10px;margin-top:8px;border:1px solid #2f6f4f;border-radius:6px;color:#b9f0cf;background:#0c1a14;font-size:14px");
            (node.parentNode || document.body).appendChild(note);
          });
        })
        .catch(function (error) {
          BRIDGE.saveFailed(String(error && error.message ? error.message : error));
        });
    },
    true
  );
})();
