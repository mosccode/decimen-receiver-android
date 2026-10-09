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

console.log('落盘带的类型');
// 存进 MediaStore 的类型决定系统给不给出相册：图片被记成 octet-stream，
// 「打开方式」里就永远只有文件管理器。
const shimSource = readFileSync(SHIM, 'utf8');
check('桥接把 blob 自己的类型交给原生', /headers\.get\("content-type"\)/.test(shimSource)
  && !/handOff\(new Uint8Array\(buffer\), name, ""\)/.test(shimSource));
const bridgeSource = readFileSync(BRIDGE, 'utf8');
check('类型缺失或含糊时按扩展名补', /static String usable\(String mime, String name\)/.test(bridgeSource)
  && /MimeTypeMap\.getSingleton\(\)/.test(bridgeSource));
// 类型在这里只重认一次：记录里那句 mime 可能是旧壳写的 octet-stream，而
// .png 的后缀自己就足以把相册请回来。open 与 share 都必须走 usableType。
const savesSource = readFileSync(SAVES, 'utf8');
check('打开与分享都重新认一次类型', /static String usableType\(JSONObject entry\)/.test(savesSource)
  && (savesSource.match(/usableType\(entry\)/g) || []).length >= 2
  && /SaveBridge\.usable\(entry\.optString\("mime"\)/.test(savesSource));

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
for (const name of ['state', 'start', 'setPaused', 'torch', 'settings', 'setSetting', 'clearPrefs']) {
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
check('壳版本号与形态改动同步', /decimen-0\.5\.3-shell-6/.test(build));
// android.jar 与参考实现 org.json 的静态方法签名不完全一致，而本机无法编译验证；
// 过桥的字符串一律由自己那套转义负责。
check('不赌 JSONObject.quote 的签名', !/JSONObject\.quote\(/.test(main));

console.log('参数回位：官方页只存语言与「收到即展示」');
// 镜头/宽度/帧率/线程只活在 DOM 里，重置与重开应用都会把它们打回页面默认值。
// 壳把这几样记在自己的键下，所以「记了」和「在点启动之前记回来」两件事都要成立。
check('壳自己那组参数有独立的键', /decimen:rx-prefs/.test(driver) && /setDomStorageEnabled\(true\)/.test(main));
check('改动写进壳的键', /function setSetting\(key, value\)[\s\S]{0,600}remember\(key, String\(value\)\)/.test(driver));
check('回位在点启动之前发生', /function start\(\)[\s\S]{0,400}restoreOnce\(\);[\s\S]{0,120}p\.click\(\)/.test(driver));
check('回位只发生一次', /function restoreOnce\(\)[\s\S]{0,200}if \(restored\) return/.test(driver));
// 镜头的 deviceId 要等页面自己 enumerateDevices 之后才有选项，而 deviceId 又不保证
// 跨会话稳定：认不出就必须作废，否则每次开应用都空转到超时。
check('镜头等页面填完选项再换', /function start\(\)[\s\S]{0,300}restoreCamera\(0\)/.test(driver));
check('认不出的 deviceId 会被作废而不是死等', /function restoreCamera\(tries\)[\s\S]{0,900}forget\("camera"\)/.test(driver));

console.log('后台收回镜头');
// 这条修复的前提是官方页自己对可见性一无所知；它哪天开始监听了，壳的看门狗就该退让。
check('原件确实没有可见性处理', !/visibilitychange/.test(asset) && !/document\.hidden/.test(asset));
check('生命周期成对冻结与解冻渲染端', /protected void onPause\(\)[\s\S]{0,300}web\.onPause\(\)/.test(main)
  && /protected void onResume\(\)[\s\S]{0,300}web\.onResume\(\)/.test(main));
check('回前台后看门狗重新上岗', /web\.onResume\(\);[\s\S]{0,300}ui\.postDelayed\(pollPage/.test(main));
check('驱动数帧并把结论放进 state', /watch\.misses \+= 1/.test(driver)
  && /function state\(\)[\s\S]{0,400}stalled: stalled\(\)/.test(driver));
// 页面自己换镜头那几百毫秒里旧轨道正是 ended，和「被系统收回」长得一样；
// 它靠那颗 <select> 的禁用标记来区分，认错了就会把正常换镜头判成故障。
check('换镜头期间不计卡住', /if \(pickNode && pickNode\.disabled\)/.test(driver));
check('先自己推播放，实在不行才重载', /if \(node\.paused\) node\.play\(\)/.test(driver)
  && /state\.optBoolean\("stalled"\)/.test(main)
  && /private void recoverStall\(\)[\s\S]{0,1200}web\.reload\(\)/.test(main));
// 重载会把结果条清空，所以「为什么重启了」必须能跨过这次重载活下来。
check('重启的理由活得过那次重载', /private String pendingNote;/.test(main)
  && /pendingNote = "[\s\S]{0,200}web\.reload\(\)/.test(main) && /if \(pendingNote != null\)/.test(main));
check('自动恢复不会连环重载', /recoverStall\(\)[\s\S]{0,400}now - lastRecovery < 20000L/.test(main));
check('手动重置重新拿到一次自动恢复的额度', /private void reset\(\)[\s\S]{0,300}lastRecovery = 0L/.test(main));

console.log('每一件事都要有回应');
// 结果条是壳唯一的说话渠道。一条一直亮着的红既不是故障也不是提示，用户只会把它
// 当成壁纸，于是真正的故障混在里面谁也看不见 —— 所以三档里只有故障不自己走。
check('结果条的三档由同一处渲染', /private void showStatus\(String line, boolean fault, long hold\)/.test(main)
  && /if \(hold > 0L\) ui\.postDelayed\(hideStatus, hold\)/.test(main));
check('提示会自己消失，故障不会', /void say\(String line\) \{\s*showStatus\(line, false, [1-9]\d*L\);/.test(main)
  && /void notice\(String line\) \{\s*showStatus\(line, false, [1-9]\d*L\);/.test(main)
  && /void noteError\(String line\) \{\s*showStatus\(line, true, 0L\);/.test(main));
// 壳自己修好了一次卡顿，要说，但那不是「需要用户处理」的故障。
check('自动恢复的理由只是提示', /pendingNote = null;[\s\S]{0,80}notice\(carried\);/.test(main)
  && !/noteError\(carried\)/.test(main) && /notice\("这颗镜头不支持补光"\)/.test(main));
// Android 8 起调起安装器要求发起方声明 REQUEST_INSTALL_PACKAGES，缺了它系统会把
// 请求静默吞掉 —— 用户看到的就是「点了没反应」。这一版仍不申请该权限，改为说清楚
// 文件在哪、去哪儿点，并留一颗「仍然试一次」。
check('安装包走指引，不靠安装权限', /application\/vnd\.android\.package-archive/.test(saves)
  && /本应用不代你调起安装/.test(saves) && /仍然试一次/.test(saves)
  && !/android\.permission\.REQUEST_INSTALL_PACKAGES/.test(manifest));
check('打不开一定说一句为什么', /catch \(RuntimeException refused\)/.test(saves)
  && /系统拒绝了这次打开/.test(saves) && /这台机器上没有能打开/.test(saves));
check('没有默认处理程序就让用户自己挑一个', /private static void launch\([\s\S]{0,900}if \(!chooser\) \{\s*\/\/[\s\S]{0,120}launch\(activity, entry, mime, true\)/.test(saves));
check('删除先问一句', /删掉这个文件？/.test(main) && /confirmDelete\(entry\)/.test(main));
// 页面会随时自动落盘，每一条新记录都把旧行往下推一位：按第几行动手，删掉的
// 就不是刚才点的那一份。
check('文件动作认记录不认第几行', /Saves\.open\(MainActivity\.this, entry\)/.test(main)
  && /private static int indexOf\(JSONArray all, JSONObject want\)/.test(saves)
  && !/static void (open|share|remove)\(MainActivity activity, int index\)/.test(saves));
check('刚落成的文件在打开的文件屏里就会出现', /activity\.fileLanded\(\);/.test(bridge)
  && /void fileLanded\(\) \{[\s\S]{0,60}if \(screen != SCREEN_FILES\)[\s\S]{0,90}rebuildFiles/.test(main));
// 「恢复默认」只能清壳自己那组键：页面自己的语言与「收到即展示」不是它该管的。
check('恢复默认只清壳自己的键', /function clearPrefs\(\)[\s\S]{0,200}localStorage\.removeItem\(PREFS\)/.test(driver)
  && !/localStorage\.clear\(\)/.test(driver));
check('恢复默认走重载，让页面自己长回默认值', /settingsList\.addView\(setting\("恢复默认参数"/.test(main)
  && /private void restoreDefaults\(\)[\s\S]{0,400}reset\(\);/.test(main));

await javaParse();

console.log(failures ? '\nFAIL: ' + failures + ' 项' : '\nOK 壳与页面两端一致，内联资产未被改动');
process.exit(failures ? 1 : 0);

