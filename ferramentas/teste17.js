// Test 17 = test 16 (as saved from the ECU) + VE dip at 800 rpm / 85-90 kPa filled,
// longer acceleration enrichment, and a tighter valid range for the key-on baro reading.
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
const changes = [
  [85, 801, 72], [90, 801, 72],     // dip: 76 at 80 kPa fell to 67 (log: lambda 1.32 accelerating at 750-1000 rpm)
  [85, 1101, 73], [90, 1101, 72],   // lean lasted until ~1150 rpm
];
for (const [kpa, rpm, v] of changes) {
  const k = MAP.indexOf(kpa) * 16 + RPM.indexOf(rpm);
  if (v < ve[k] || v - ve[k] > 5) throw new Error(`bad step ${kpa}/${rpm}: ${ve[k]} -> ${v}`);
  console.log(`VE ${kpa} kPa ${rpm} rpm: ${ve[k]} -> ${v}`);
  ve[k] = v; if (k < 144) d1[k] = v; else d2[k - 144] = v;
}
check('result');
write('veTable1', ve); write('veTable1dozen', d1); write('veTable2dozen', d2);

scalar('taeTime', 0.2, 0.3);          // acceleration enrichment lasts a bit longer (ethanol wets the manifold)
scalar('baro_lower', 80.0, 89.0);     // readings 85.8 / 90.2 / 100.0 seen; real ~92.3 kPa
scalar('baro_upper', 105.0, 96.0);
scalar('baro_default', 91.0, 92.0);   // used when the key-on reading is out of range

text = text.replace(/tuneComment="[^"]*"/,
  'tuneComment="Teste 17: teste 16 + VE 85-90 kPa 800-1100 rpm (+2 a +5), enriquecimento de aceleracao 0.2 para 0.3 s, baro valido 89-96 kPa (padrao 92)"');
fs.writeFileSync(out, text, 'latin1');
