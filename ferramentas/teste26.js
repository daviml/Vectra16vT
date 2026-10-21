// Test 26 = test 25 (never loaded) + richer high-load cells 65-90 kPa x 2600-4300 rpm, lean 5-11 % in the
// logs of 20-21/10 with EGO already adding +4..+9 %. Base is still test 24 as saved from the ECU.
// From test 25: slower EGO (it was learning the AE richness and leaning
// the mixture ~1 s after each tip-in), leaner warm-up at 30 C, and half of the measured richness removed
// from the low-rpm pull-away cells (50-80 kPa x 800-1100 rpm, warm engine, 5 logs of 19-20/10).
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
function scalar(name, from, to) {
  const v = read(name);
  if (v.length !== 1 || v[0] !== from) throw new Error(`${name} is ${v}, expected ${from}`);
  write(name, [to]);
  console.log(`${name}: ${from} -> ${to}`);
}

const ve = read('veTable1'), d1 = read('veTable1dozen'), d2 = read('veTable2dozen');
const check = (label) => { for (let k = 0; k < 256; k++) if ((k < 144 ? d1[k] : d2[k - 144]) !== ve[k]) throw new Error(`${label}: alias differs at ${k}`); };
check('base');
const MAP = [20, 30, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 100, 150, 200];
const RPM = [501, 801, 1101, 1401, 2001, 2601, 3101, 3700, 4300, 4900, 5400, 6000, 6500, 7000, 7200, 7500];
const leaner = [
  // [kPa, rpm, new]  measured correction in the comment; ~half applied
  [50, 801, 67], [50, 1101, 70],    // 0.952, 0.951
  [55, 801, 66], [55, 1101, 68],    // 0.928, 0.944
  [60, 801, 66], [60, 1101, 68],    // 0.911, 0.923
  [65, 801, 75], [65, 1101, 73],    // 0.915, 0.942
  [70, 801, 76], [70, 1101, 73],    // 0.909, 0.948
  [75, 1101, 75], [80, 1101, 70],   // 0.951, 0.937
];
const richer = [
  [65, 2601, 71], [70, 2601, 71],   // ~5 % lean
  [75, 3101, 76],                   // 9 %
  [80, 3101, 71], [80, 3700, 74],   // 7 %, 11 %
  [85, 3101, 78], [85, 3700, 80],   // 6 %, 10 %
  [90, 3101, 78], [90, 3700, 78],   // no data: follow the 85 kPa row (richer only)
  [80, 4300, 71], [85, 4300, 78], [90, 4300, 76], // no data: same trend (richer only)
];
const changes = [...leaner.map(c => [...c, -1]), ...richer.map(c => [...c, +1])];
for (const [kpa, rpm, v, dir] of changes) {
  const k = MAP.indexOf(kpa) * 16 + RPM.indexOf(rpm);
  const step = v - ve[k];
  if (dir < 0 ? (step >= 0 || step < -4) : (step <= 0 || step > 6)) throw new Error(`bad step ${kpa}/${rpm}: ${ve[k]} -> ${v}`);
  console.log(`VE ${kpa} kPa ${rpm} rpm: ${ve[k]} -> ${v}`);
  ve[k] = v; if (k < 144) d1[k] = v; else d2[k - 144] = v;
}
check('result');
write('veTable1', ve); write('veTable1dozen', d1); write('veTable2dozen', d2);

scalar('egoCount', 8, 16);          // half the EGO speed: stops it pulling ~3 % after every AE pulse

const wue = read('wueBins');
if (wue[0] !== 125) throw new Error('wueBins[0] is ' + wue[0]);
wue[0] = 117;                       // 30 C: ~13 % richer than target with EGO at -5 %
write('wueBins', wue);
console.log('wueBins 30 C: 125 -> 117');

text = text.replace(/tuneComment="[^"]*"/,
  'tuneComment="Teste 26: teste 24 + sonda mais lenta (egoCount 16), aquecimento 30 C 117, VE baixa rotacao -2 a -4, VE 65-90 kPa 2600-4300 rpm +3 a +6 (pobre em carga)"');
fs.writeFileSync(out, text, 'latin1');
