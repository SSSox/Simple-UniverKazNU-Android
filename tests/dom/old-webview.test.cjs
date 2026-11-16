const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// Android 7–8 ship WebView 51–64: Element.replaceChildren (Chrome 86) and a selector with :is() (Chrome 88) throw there
// and stop the whole page script. CSS rules may use them (an unknown rule is only dropped), script calls may not.
const assets = 'app/src/main/assets';
const scripts = fs.readdirSync(assets).filter(f => f.endsWith('.js')).map(f => [f, fs.readFileSync(path.join(assets, f), 'utf8')]);

test('page scripts do not call DOM methods that old Android WebViews lack', () => {
  const found = scripts.filter(([, text]) => /\.replaceChildren\s*\(/.test(text)).map(([f]) => f);
  assert.deepEqual(found, []);
});

test('page scripts do not pass :is() selectors to querySelector, matches or closest', () => {
  const call = /\.(?:querySelector(?:All)?|matches|closest)\(\s*(['"`])((?:(?!\1).)*)\1/g;
  const found = [];
  for (const [file, text] of scripts) for (const match of text.matchAll(call)) if (/:is\(/.test(match[2])) found.push(file + ': ' + match[2]);
  assert.deepEqual(found, []);
});
