// Test 24 = test 23 (as saved from the ECU) + longer acceleration enrichment, overrun fuel cut from 50 C,
// and the last lean cruise cell at 2600 rpm / 50 kPa.
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
  [50, 2601, 70],   // ~9 % lean in log 2026-10-19_09.00.57 (test 23)
];
for (const [kpa, rpm, v] of changes) {
  const k = MAP.indexOf(kpa) * 16 + RPM.indexOf(rpm);
  if (v < ve[k] || v - ve[k] > 5) throw new Error(`bad step ${kpa}/${rpm}: ${ve[k]} -> ${v}`);
  console.log(`VE ${kpa} kPa ${rpm} rpm: ${ve[k]} -> ${v}`);
  ve[k] = v; if (k < 144) d1[k] = v; else d2[k - 144] = v;
}
check('result');
write('veTable1', ve); write('veTable1dozen', d1); write('veTable2dozen', d2);

scalar('taeTime', 0.3, 0.4);        // lambda 1.10-1.20 right after the AE pulse ends at 850-1300 rpm
scalar('aeTaperTime', 0.1, 0.2);    // softer end of the AE pulse
scalar('fc_clt', 59.994, 50.0);     // engine lives at 56-70 C (thermostat); cut barely worked below 60

text = text.replace(/tuneComment="[^"]*"/,
  'tuneComment="Teste 24: teste 23 + AE 0.3 para 0.4 s e taper 0.1 para 0.2 s, cutoff acima de 50 C, VE 50 kPa 2600 rpm 66 para 70"');
fs.writeFileSync(out, text, 'latin1');
