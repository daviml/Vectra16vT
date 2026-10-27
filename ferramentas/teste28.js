// Test 28 = test 26 (saved from the ECU on 22/10 09:02) + the two test 27 cells (never loaded) + the cells still lean
// in the 16 logs of 24-26/10, the first ones with a trustworthy coolant reading (82-88 C, cutoff working).
// Only enrichment; the rich low-rpm cells (60-80 kPa x 800-1400 rpm) stay rich on purpose.
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
const richer = [
  // [kPa, rpm, old, new]  measured correction in the comment; ~80 % applied
  [60, 3101, 63, 67],   // test 27: 1.065
  [70, 3101, 63, 67],   // test 27: 1.066 (65 kPa in between is 73)
  [45, 2601, 68, 71],   // 1.051
  [50, 2601, 70, 73],   // 1.049
  [20, 2601, 75, 79],   // 1.064
  [30, 2601, 73, 76],   // 1.051
  [20, 1101, 82, 85],   // 1.041 (warm idle, EGO +2 %)
];
for (const [kpa, rpm, old, v] of richer) {
  const k = MAP.indexOf(kpa) * 16 + RPM.indexOf(rpm);
  if (ve[k] !== old) throw new Error(`VE ${kpa}/${rpm} is ${ve[k]}, expected ${old}`);
  console.log(`VE ${kpa} kPa ${rpm} rpm: ${old} -> ${v}`);
  ve[k] = v; if (k < 144) d1[k] = v; else d2[k - 144] = v;
}
check('result');
write('veTable1', ve); write('veTable1dozen', d1); write('veTable2dozen', d2);

text = text.replace(/tuneComment="[^"]*"/,
  'tuneComment="Teste 28: teste 26 + VE 60 e 70 kPa 3100 rpm 63 para 67 (teste 27) + 45 50 kPa 2600 rpm +3 + 20 30 kPa 2600 rpm +4 +3 + lenta 20 kPa 1100 rpm 82 para 85"');
fs.writeFileSync(out, text, 'latin1');
