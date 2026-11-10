// Test 31 = test 30 (CurrentTune saved on 02/11 10:25, confirmed in the logs of 06-09/11)
// + half of the excess in the warm cruise cells (still 4-8 % rich with the fuel pressure kept at ~2.6 bar idle)
// + ~80 % of the lean light-load cells at 2600-3100 rpm
// + idle VE a bit leaner (EGO ~95 at idle) and egoDelta 1.0 -> 0.5 % (idle lambda cycling 0.92 <-> 1.08).
// Columns 801/1101 and 1401 from 60 kPa up stay as they are: they border the cold drive-aways (still lambda 1.1-1.3).
// Usage: node teste31.js base.msq out.msq
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
// test 30 cells must already be in the base
for (const [kpa, rpm, v] of [[40, 2001, 75], [50, 2001, 74], [65, 2001, 68]])
  if (ve[MAP.indexOf(kpa) * 16 + RPM.indexOf(rpm)] !== v) throw new Error(`VE ${kpa}/${rpm} is not ${v}: base is not test 30`);
const cells = [
  // [kPa, rpm, old, new]  measured % (logs 06/11-09/11, CLT > 70, steady), + = lean
  // cruise, rich -> about half, rounded to the rich side
  [45, 1401, 73, 71],   // -6.1 (n=1722)
  [45, 2001, 71, 70],   // -4.4 (n=2200)
  [50, 1401, 72, 70],   // -7.6 (n=1258)
  [50, 2001, 74, 72],   // -7.0 (n=2039)
  [55, 1401, 71, 70],   // -5.0 (n=1245)
  [55, 2001, 69, 68],   // -4.4 (n=1537)
  [60, 2001, 71, 69],   // -6.4 (n=1355)
  [65, 2001, 68, 67],   // -5.7 (n=1885)
  [70, 2001, 69, 67],   // -6.3 (n=2031)
  [75, 2001, 69, 68],   // -5.7 (n=1947)
  [80, 2001, 70, 68],   // -7.7 (n=737)
  // light load 2600-3100, lean -> ~80 %, max +5
  [30, 2601, 76, 79],   // +7.9 (n=1448)
  [40, 2601, 68, 72],   // +7.5 (n=924)
  [30, 3101, 73, 75],   // +4.7 (n=248)
  [40, 3101, 63, 67],   // +11.5 (n=181, few samples: only part of it)
  [45, 3101, 66, 69],   // +5.7 (n=265)
  // idle (~26 kPa sits between the 20 and 30 rows)
  [30, 1101, 79, 76],   // -8.4 (n=11948)
  [30, 801, 81, 79],    // -7.2 (n=556)
];
for (const [kpa, rpm, old, v] of cells) {
  const k = MAP.indexOf(kpa) * 16 + RPM.indexOf(rpm);
  if (ve[k] !== old) throw new Error(`VE ${kpa}/${rpm} is ${ve[k]}, expected ${old}`);
  if (Math.abs(v - old) / old > 0.07) throw new Error(`VE ${kpa}/${rpm}: step too big`);
  console.log(`VE ${kpa} kPa ${rpm} rpm: ${old} -> ${v}`);
  ve[k] = v; if (k < 144) d1[k] = v; else d2[k - 144] = v;
}
check('result');
write('veTable1', ve); write('veTable1dozen', d1); write('veTable2dozen', d2);

const [ego] = read('egoDelta');
if (ego !== 1) throw new Error(`egoDelta is ${ego}, expected 1.0`);
write('egoDelta', [0.5]);
console.log('egoDelta: 1.0 -> 0.5');

text = text.replace(/tuneComment="[^"]*"/,
  'tuneComment="Teste 31: teste 30 + VE cruzeiro metade do rico, 2600 e 3100 rpm carga leve mais rico, lenta menos 2 ou 3, egoDelta 0.5"');
fs.writeFileSync(out, text, 'latin1');
