// Test 29 = test 28 (saved from the ECU on 27/10 08:21) + the two cells still lean in the 5 logs of 27-28/10
// + more cold acceleration enrichment (lean tip-ins at 31-46 C, three cold drive-away stalls in a row).
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
  [40, 2001, 72, 75],   // 1.048 (n=330)
  [80, 2601, 68, 70],   // 1.043 (n=99)
];
for (const [kpa, rpm, old, v] of richer) {
  const k = MAP.indexOf(kpa) * 16 + RPM.indexOf(rpm);
  if (ve[k] !== old) throw new Error(`VE ${kpa}/${rpm} is ${ve[k]}, expected ${old}`);
  console.log(`VE ${kpa} kPa ${rpm} rpm: ${old} -> ${v}`);
  ve[k] = v; if (k < 144) d1[k] = v; else d2[k - 144] = v;
}
check('result');
write('veTable1', ve); write('veTable1dozen', d1); write('veTable2dozen', d2);

function scalar(name, old, v) {
  const [cur] = read(name);
  if (cur !== old) throw new Error(`${name} is ${cur}, expected ${old}`);
  console.log(`${name}: ${old} -> ${v}`);
  write(name, [v]);
}
scalar('taeColdM', 180, 220);   // cold AE multiplier; no effect once warm

text = text.replace(/tuneComment="[^"]*"/,
  'tuneComment="Teste 29: teste 28 + VE 40 kPa 2000 rpm 72 para 75 + 80 kPa 2600 rpm 68 para 70 + AE frio 180 para 220 por cento"');
fs.writeFileSync(out, text, 'latin1');
