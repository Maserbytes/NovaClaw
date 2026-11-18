const fs = require('fs');
const path = require('path');
const f = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'index.html');
const html = fs.readFileSync(f, 'utf8');
const blocks = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1]);
let ok = 0;
for (const b of blocks) {
  try { new Function(b); ok++; }
  catch (e) { console.error('INLINE JS ERROR:', e.message); process.exitCode = 1; }
}
console.log('inline blocks=' + blocks.length + ' ok=' + ok);
