// Test 32 = test 31 (never reached the ECU: the logs of 21/11 still fit the test 30 VE table, rms 0.3 vs 1.25)
// + about half of the rich excess measured on 21/11 (D+ wire installed, 14.2-14.4 V) at 1100-2000 rpm, 20-80 kPa.
// Measured on the test 30 table, steady state, CLT 50-68 (engine never got hotter), EGO correction included.
// Rich bogs: lambda below 0.72 at 1000-2000 rpm, 50-75 kPa, 13 per 10 min. Accel enrichment and egoDelta untouched here.
// Usage: node teste32.js mapas/2026-11-09_teste31_ve-cruzeiro-lenta-ego.msq out.msq
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
// test 31 cells must already be in the base
for (const [kpa, rpm, v] of [[50, 2001, 72], [40, 2601, 72], [30, 1101, 76]])
  if (ve[MAP.indexOf(kpa) * 16 + RPM.indexOf(rpm)] !== v) throw new Error(`VE ${kpa}/${rpm} is not ${v}: base is not test 31`);
const cells = [
  // [kPa, rpm, test 31 value, new]  measured % on test 30 (logs 21/11, CLT 50-68, steady), - = rich
  [20, 1101, 85, 83],   // -4.2 (n=2374) idle sits between the 20 and 30 rows
  [20, 1401, 88, 86],   // -3.9 (n=901)
  [30, 801, 79, 77],    // -15 (n=66; cold -17 to -22). test 30 had 81
  [30, 1401, 73, 70],   // -8.2 (n=515)
  [30, 2001, 75, 73],   // -6.2 (n=297)
  [40, 1101, 75, 72],   // -9.3 (n=105; cold -13 to -19)
  [40, 1401, 69, 68],   // -3.6 (n=986)
  [40, 2001, 75, 72],   // -9.5 (n=237)
  [45, 1101, 69, 67],   // -6.0 (n=170)
  [50, 1101, 70, 68],   // -7.1 (n=277)
  [50, 1401, 70, 69],   // -10.6 (n=137). test 30 had 72
  [55, 1101, 68, 66],   // -9.5 (n=103)
  [60, 1101, 68, 67],   // -11.9 (n=66, cold was lean on 06-09/11: small step)
  [60, 1401, 76, 72],   // -11.8 (n=177; cold -7 to -9). was a bump over 71 in the rows around
  [65, 1101, 73, 71],   // -6.7 (n=87)
  [65, 1401, 75, 72],   // -10.1 (n=138; cold -11)
  [65, 2001, 67, 66],   // -5.8 (n=208). test 30 had 68
  [75, 1401, 71, 68],   // -9.2 (n=132; cold -11)
  [75, 2001, 68, 67],   // -6.5 (n=200). test 30 had 69
  [80, 1401, 72, 70],   // -10.6 (n=75)
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

if (read('egoDelta')[0] !== 0.5) throw new Error('egoDelta is not 0.5: base is not test 31');

text = text.replace(/tuneComment="[^"]*"/,
  'tuneComment="Teste 32: teste 31 + VE 1100 a 2000 rpm 20 a 80 kPa metade do rico medido em 21-11 (engasgos ricos em baixa rotacao)"');
fs.writeFileSync(out, text, 'latin1');
