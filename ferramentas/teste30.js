// Test 30 = test 29 (CurrentTune saved on 29/10 08:40, never burned: logs of 29-30/10 still ran test 28)
// + half of the excess in the warm cruise cells 45-65 kPa x 1400-2000 rpm, rich 4-7 % in two batches in a row.
// The 1401 column from 60 kPa up stays rich: it borders the cold drive-aways that test 29 is meant to fix.
const fs = require('fs');
const [base, out] = process.argv.slice(2);
let text = fs.readFileSync(base, 'latin1');

function block(name) {
  const start = text.indexOf(`name="${name}"`);
  if (start < 0) throw new Error('missing ' + name);
  const open = text.indexOf('>', start) + 1;
  return { open, close: text.indexOf('</constant>', open) };
}
const read = (name) => { const { open, close } = block(name); return text.slice(open, close).trim().split(/\s+/).map(Number); };
function write(name, values) {
  const { open, close } = block(name);
  let i = 0;
  const body = text.slice(open, close).replace(/-?\d+(\.\d+)?/g, () => values[i++].toFixed(1));
  if (i !== values.length) throw new Error(`${name}: ${i} numbers, expected ${values.length}`);
  text = text.slice(0, open) + body + text.slice(close);
}

const ve = read('veTable1'), d1 = read('veTable1dozen'), d2 = read('veTable2dozen');
const check = (label) => { for (let k = 0; k < 256; k++) if ((k < 144 ? d1[k] : d2[k - 144]) !== ve[k]) throw new Error(`${label}: alias differs at ${k}`); };
check('base');
const MAP = [20, 30, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 100, 150, 200];
const RPM = [501, 801, 1101, 1401, 2001, 2601, 3101, 3700, 4300, 4900, 5400, 6000, 6500, 7000, 7200, 7500];
// test 29 cells must already be in the base (proves it is the test 29 tune)
for (const [kpa, rpm, v] of [[40, 2001, 75], [80, 2601, 70]])
  if (ve[MAP.indexOf(kpa) * 16 + RPM.indexOf(rpm)] !== v) throw new Error(`VE ${kpa}/${rpm} is not ${v}: base is not test 29`);
const cells = [
  // [kPa, rpm, old, new]  measured correction (logs 29/10 17:24 - 30/10 08:43) in the comment; ~50 % applied
  [45, 1401, 74, 73],   // 0.964 (n=792)
  [45, 2001, 72, 71],   // 0.964 (n=377)
  [50, 1401, 73, 72],   // 0.959 (n=966)
  [50, 2001, 76, 74],   // 0.957 (n=571)
  [55, 1401, 72, 71],   // 0.963 (n=982)
  [55, 2001, 70, 69],   // 0.961 (n=663)
  [60, 2001, 73, 71],   // 0.937 (n=366)
  [65, 2001, 70, 68],   // 0.942 (n=294)
];
for (const [kpa, rpm, old, v] of cells) {
  const k = MAP.indexOf(kpa) * 16 + RPM.indexOf(rpm);
  if (ve[k] !== old) throw new Error(`VE ${kpa}/${rpm} is ${ve[k]}, expected ${old}`);
  console.log(`VE ${kpa} kPa ${rpm} rpm: ${old} -> ${v}`);
  ve[k] = v; if (k < 144) d1[k] = v; else d2[k - 144] = v;
}
check('result');
write('veTable1', ve); write('veTable1dozen', d1); write('veTable2dozen', d2);

const [tae] = read('taeColdM');
if (tae !== 220) throw new Error(`taeColdM is ${tae}, expected 220 (test 29)`);

text = text.replace(/tuneComment="[^"]*"/,
  'tuneComment="Teste 30: teste 29 + VE cruzeiro 45 a 65 kPa 1400 e 2000 rpm menos 1 ou 2 (metade do rico)"');
fs.writeFileSync(out, text, 'latin1');
