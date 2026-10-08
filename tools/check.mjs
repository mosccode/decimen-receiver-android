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
const SHIM = join(ROOT, 'app/src/main/assets/save-shim.js');
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
  for (const [file, label] of [[MAIN, 'MainActivity.java'], [BRIDGE, 'SaveBridge.java']]) {
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

await javaParse();

console.log(failures ? '\nFAIL: ' + failures + ' 项' : '\nOK 壳与页面两端一致，内联资产未被改动');
process.exit(failures ? 1 : 0);

