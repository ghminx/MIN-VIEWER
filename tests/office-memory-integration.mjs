import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
execFileSync('node', ['tests/lok-integration.mjs', 'large', 'keep'], { stdio: 'inherit' });
const memory = execFileSync(adb, ['shell', 'dumpsys', 'meminfo', 'org.minviewer.app'], { encoding: 'utf8' });
assert.match(memory, /WebViews:\s+0\b/, 'Office 화면에서 사용하지 않는 WebView가 유지됩니다');
