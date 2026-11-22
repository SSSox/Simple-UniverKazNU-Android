const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// The keyed sender (MobilePageAdapter NATIVE) passes only the prefixes it lists; a message type the app reads but the
// sender does not list never arrives (the exam reminders' first version was dropped this way).
function kotlinFiles(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? kotlinFiles(path.join(dir, e.name)) : e.name.endsWith('.kt') ? [path.join(dir, e.name)] : []);
}
test('every message prefix the app reads is let through by the keyed sender', () => {
  const adapter = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/MobilePageAdapter.kt', 'utf8');
  const allowed = new Set([...adapter.match(/var k=\[([^\]]*)\]/)[1].matchAll(/'(univer-[a-z-]+:)'/g)].map(m => m[1]));
  const read = new Set(kotlinFiles('app/src/main/java').flatMap(f => [...fs.readFileSync(f, 'utf8').matchAll(/PREFIX = "(univer-[a-z-]+:)"/g)].map(m => m[1])));
  assert.ok(read.size >= 7);
  assert.deepEqual([...read].filter(p => !allowed.has(p)), []);
});
