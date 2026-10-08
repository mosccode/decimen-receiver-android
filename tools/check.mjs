// Local gate for a project that cannot be compiled here (no JDK, no SDK).
// It does not replace javac — it catches the class of defect that javac-would-
// have-caught-but-I-cannot-run: the shell and the page drifting apart on a
// method name, an element id, a permission line, or the pinned upstream bytes.
//
//   node tools/check.mjs
//
// A real Java parse is available if the workspace happens to have the pure-JS
// parser installed; that check is reported as SKIPPED rather than faked.

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, existsSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const MAIN = join(ROOT, 'app/src/main/java/net/tare/decimenrx/MainActivity.java');
const BRIDGE = join(ROOT, 'app/src/main/java/net/tare/decimenrx/SaveBridge.java');
const SAVES = join(ROOT, 'app/src/main/java/net/tare/decimenrx/Saves.java');
const STYLES = join(ROOT, 'app/src/main/res/values/styles.xml');
const SHIM = join(ROOT, 'app/src/main/assets/save-shim.js');
const DRIVER = join(ROOT, 'app/src/main/assets/receiver-ui.js');
const INJECTED = join(ROOT, 'app/src/main/assets/receiver-ui.css');
const PROBE = join(ROOT, 'app/src/main/assets/probe.html');
const MANIFEST = join(ROOT, 'app/src/main/AndroidManifest.xml');
const BUILD = join(ROOT, 'app/build.gradle.kts');
const SUMS = join(ROOT, 'ASSET-SHA256SUMS.txt');
const ASSET = join(ROOT, 'app/src/main/assets/decimen-receiver.html');

let failures = 0;
function check(label, ok, detail) {
  console.log((ok ? '  ok   ' : '  FAIL ') + label + (detail ? ' · ' + detail : ''));
  if (!ok) failures++;
}

function syntax(file, label) {
  const dir = mkdtempSync(join(tmpdir(), 'decimen-rx-check-'));
  const target = join(dir, 'snippet.js');
  writeFileSync(target, readFileSync(file, 'utf8'));
  try {
    execFileSync(process.execPath, ['--check', target], { stdio: 'pipe' });
    check(label + ' 语法', true);
  } catch (error) {
    check(label + ' 语法', false, String(error.stderr || error.message).split('\n').slice(0, 3).join(' ⏎ '));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

async function javaParse() {
  const entry = resolve(ROOT, '../_test/javacheck/node_modules/java-parser');
  if (!existsSync(entry)) {
    console.log('  SKIP 真语法解析（_test/javacheck 里没装 java-parser）');
    return;
  }
  // java-parser is ESM-only with no "main", so resolve its exports target by
  // hand — a bare directory import fails under ESM.
  const pkg = JSON.parse(readFileSync(join(entry, 'package.json'), 'utf8'));
  const dot = (pkg.exports || {})['.'] || pkg.main;
  const target = typeof dot === 'string' ? dot : dot.import || dot.default;
  const { parse } = await import(pathToFileURL(resolve(entry, target)).href);
  for (const [file, label] of [
    [MAIN, 'MainActivity.java'],
    [BRIDGE, 'SaveBridge.java'],
    [SAVES, 'Saves.java'],
  ]) {
    try {
      parse(readFileSync(file, 'utf8'));
      check(label + ' 解析', true);
    } catch (error) {
      check(label + ' 解析', false, String(error.message).split('\n')[0]);
    }
  }
}

console.log('内联的官方页面');
const expected = readFileSync(SUMS, 'utf8')
  .split('\n')
  .map((line) => line.trim().split(/\s+/))
  .filter((pair) => /^[0-9a-f]{64}$/.test(pair[0]) && pair.length >= 2);
for (const [hash, path] of expected) {
  const actual = createHash('sha256').update(readFileSync(join(ROOT, path))).digest('hex');
  check(path, actual === hash, actual.slice(0, 16) + '…');
}
check('页面确实带 default-src none', /default-src\s'none'/.test(readFileSync(ASSET, 'utf8').replace(/&#39;/g, "'")));

console.log('桥的两端必须同名');
const bridge = readFileSync(BRIDGE, 'utf8');
const pages = readFileSync(SHIM, 'utf8') + readFileSync(PROBE, 'utf8');
for (const method of ['saveBegin', 'saveChunk', 'saveEnd', 'saveFailed', 'copy', 'openReceiver', 'shellVersion']) {
  const declared = new RegExp('\\bvoid ' + method + '\\(|\\bString ' + method + '\\(').test(bridge);
  const called = pages.includes('.' + method + '(');
  check('AndroidBridge.' + method, declared && called, declared ? (called ? '' : 'JS 侧没调用') : 'Java 侧没声明');
}
check('注册名与页面取用的名字一致', readFileSync(MAIN, 'utf8').includes('"AndroidBridge"') && pages.includes('window.AndroidBridge'));

console.log('自检页');
const probe = readFileSync(PROBE, 'utf8');
const ids = [...probe.matchAll(/getElementById\("([^"]+)"\)/g)].map((match) => match[1]);
for (const id of new Set(ids)) {
  check('#' + id, probe.includes('id="' + id + '"'));
}
check('按钮都有落点', ['run', 'save', 'copy', 'go'].every((id) => probe.includes('id="' + id + '"')));

syntax(SHIM, 'save-shim.js');
syntax(DRIVER, 'receiver-ui.js');
const dir = mkdtempSync(join(tmpdir(), 'decimen-rx-probe-'));
const inline = join(dir, 'probe-inline.js');
writeFileSync(inline, probe.match(/<script>([\s\S]*?)<\/script>/)[1]);
try {
  execFileSync(process.execPath, ['--check', inline], { stdio: 'pipe' });
  check('probe.html 内联脚本 语法', true);
} catch (error) {
  check('probe.html 内联脚本 语法', false, String(error.stderr || error.message).split('\n').slice(0, 3).join(' ⏎ '));
} finally {
  rmSync(dir, { recursive: true, force: true });
}

console.log('边界');
const manifest = readFileSync(MANIFEST, 'utf8');
check('没有 INTERNET 权限', !/android\.permission\.INTERNET/.test(manifest));
check('申请了 CAMERA', /android\.permission\.CAMERA/.test(manifest));
check('allowBackup 关闭', /android:allowBackup="false"/.test(manifest));
const build = readFileSync(BUILD, 'utf8');
check('minSdk 26 起（安全上下文与自适应图标）', /minSdk = 26/.test(build));
check('摄像头壳加载的是 asset 而非网络', /appassets\.androidplatform\.net/.test(readFileSync(MAIN, 'utf8')));

console.log('注入层：壳改排版，不改协议字节');
const asset = readFileSync(ASSET, 'utf8');
const driver = readFileSync(DRIVER, 'utf8');
const css = readFileSync(INJECTED, 'utf8');
check('CSS 与驱动都在工程里且非空', css.length > 800 && driver.length > 1500,
  css.length + ' / ' + driver.length + ' 字节');
// 驱动只靠 id 找到官方页的元素；官方页一旦换掉这些 id，自动开镜头、暂停、
// 参数面板会一起静默失效，所以这里把每个 el("x") 都对到原件上。
const wanted = [...new Set([...driver.matchAll(/\bel\("([^"]+)"\)/g)].map((m) => m[1]))];
for (const id of wanted) check('驱动要的 #' + id, asset.includes('id="' + id + '"'));
check('至少认得启动按钮与结果卡', wanted.includes('start') && wanted.includes('result'));
// 自动保存点在官方页自己那颗下载链接上；选择器与页内 class 必须对得上。
check('自动落盘点的是页自己的保存链接', /#result a\.download/.test(driver)
  && /a\.className="download"/.test(asset));
// 注入样式表里被我们隐形的每一条 class，都必须是官方页真的有的名字，
// 否则「设置面板搬走了」其实只是一个拼错的规则。
const hidden = [...css.matchAll(/^\.([a-z][\w-]*)[,\s]/gm)].map((m) => m[1]);
for (const name of new Set(hidden)) {
  if (name === 'preview' || name === 'decimen-paused') continue;
  check('样式针对 .' + name, asset.includes('.' + name) || asset.includes('"' + name + '"')
    || asset.includes(name + '"'));
}
check('暂停由 CSS 收掉「无信号」提示', /body\.decimen-paused \.no-signal-toast/.test(css)
  && asset.includes('no-signal-toast'));

console.log('壳的手感');
const main = readFileSync(MAIN, 'utf8');
const saves = readFileSync(SAVES, 'utf8');
const styles = readFileSync(STYLES, 'utf8');
const shim = readFileSync(SHIM, 'utf8');
check('打开就是接收端，不是调试台', /web\.loadUrl\(RECEIVER_URL\);/.test(main));
for (const label of ['接收', '文件', '设置']) {
  check('底栏有「' + label + '」这一项', main.includes('"' + label + '", () ->'));
}
check('自检没有消失，只是搬到设置里', /摄像头自检/.test(main) && /openProbe\(\)/.test(main)
  && !/tab\("自检"/.test(main));
check('控制条三颗都接得到实现', ['togglePause()', 'toggleTorch()', 'reset()'].every((n) => main.includes(n)));
check('控制条按页面实况点亮，灰的就是点不动', /paintControl\(TextView button, boolean active, boolean enabled\)/.test(main)
  && /button\.setEnabled\(enabled\)/.test(main));
// 原生屏与注入驱动之间的只有这几个名字，两侧任意一边改名都会静默失灵。
for (const name of ['state', 'start', 'setPaused', 'torch', 'settings', 'setSetting']) {
  const nativeSide = main.includes('__decimenRxUi.' + name);
  check('__decimenRxUi.' + name, nativeSide && new RegExp('\\b' + name + '\\s*:\\s*' + name + '\\b').test(driver));
}
check('进接收屏即自动开镜头', /__decimenRxUi && __decimenRxUi\.start\(\)/.test(main));
check('WebView 高度恒定，面板压在它下方', /fullFrame\(bars\)/.test(main) && /dp\(NAV_BAR_DP\)/.test(main)
  && !/web\.setLayoutParams/.test(main));
check('自检是子页：返回键回到接收端', /onBackPressed\(\)[\s\S]{0,320}openReceiver\(\);/.test(main));
check('不再全屏遮挡，状态栏可见', !/windowFullscreen/.test(styles));
check('双指缩放开着', /setSupportZoom\(true\)/.test(main));
check('结果条只在错误时用红', /fault \? FAULT/.test(main) && /postDelayed\(hideStatus/.test(main));
check('切页前释放镜头', /__decimenRxEjectCamera = function/.test(shim) && /__decimenRxEjectCamera/.test(main));
check('文件列表读得到、打得开、能分享、能删',
  ['Saves.entries(', 'Saves.open(', 'Saves.share(', 'Saves.remove('].every((n) => main.includes(n))
  && /static JSONArray entries\(/.test(saves) && /static void open\(/.test(saves)
  && /static void share\(/.test(saves) && /static void remove\(/.test(saves));
check('落成功才进历史，重名重大小只记一条', /Saves\.record\(/.test(bridge)
  && /static void record\(/.test(saves) && /old\.optLong\("size"\) == size/.test(saves));
check('已完成的文件不会被下一次保存当残料删掉',
  /void saveEnd\(String session\)[\s\S]{0,1200}mediaUri = null;[\s\S]{0,160}plainFile = null;/.test(bridge));
check('壳版本号与形态改动同步', /decimen-0\.5\.3-shell-3/.test(build));
// android.jar 与参考实现 org.json 的静态方法签名不完全一致，而本机无法编译验证；
// 过桥的字符串一律由自己那套转义负责。
check('不赌 JSONObject.quote 的签名', !/JSONObject\.quote\(/.test(main));

await javaParse();

console.log(failures ? '\nFAIL: ' + failures + ' 项' : '\nOK 壳与页面两端一致，内联资产未被改动');
process.exit(failures ? 1 : 0);

