// Test 15 = test 14 + VE fill in the pull-away valley + less cranking fuel when warm.
// Edits numbers in place (keeps every line ending) and keeps all VE alias copies equal.
const fs = require('fs');
const [base, out] = process.argv.slice(2);
let text = fs.readFileSync(base, 'latin1');

function block(name) {
  const start = text.indexOf(`name="${name}"`);
  const open = text.indexOf('>', start) + 1;
  const close = text.indexOf('</constant>', open);
  return { open, close };
}
function read(name) {
  const { open, close } = block(name);
  return text.slice(open, close).trim().split(/\s+/).map(Number);
}
// Replace the n-th number of a block, keeping the surrounding whitespace.
function write(name, values) {
  const { open, close } = block(name);
  let i = 0;
  const body = text.slice(open, close).replace(/-?\d+(\.\d+)?/g, () => values[i++].toFixed(1));
  if (i !== values.length) throw new Error(`${name}: ${i} numbers, expected ${values.length}`);
  text = text.slice(0, open) + body + text.slice(close);
}

const ve = read('veTable1'), d1 = read('veTable1dozen'), d2 = read('veTable2dozen');
const check = (label) => {
  for (let k = 0; k < 256; k++) {
    const alias = k < 144 ? d1[k] : d2[k - 144];
    if (alias !== ve[k]) throw new Error(`${label}: alias differs at k=${k}: ${ve[k]} vs ${alias}`);
  }
};
check('base');

const MAP = [20, 30, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 100, 150, 200];
const RPM = [501, 801, 1101, 1401, 2001, 2601, 3101, 3700, 4300, 4900, 5400, 6000, 6500, 7000, 7200, 7500];
// [kPa, rpm, new VE] - enrich only, from the log VE analysis.
const changes = [
  [30, 801, 81], [30, 1101, 79],                    // idle: EGO was adding ~3-4 %
  [50, 1101, 72], [50, 1401, 73],
  [55, 801, 68], [55, 1101, 70], [55, 1401, 72],    // pull-away valley (log: 4-9 % lean)
  [60, 801, 69], [60, 1101, 71], [60, 1401, 73],
  [80, 2001, 67], [85, 2001, 68],                   // mid load acceleration (~4 % lean)
];
for (const [kpa, rpm, v] of changes) {
  const k = MAP.indexOf(kpa) * 16 + RPM.indexOf(rpm);
  if (k < 0) throw new Error('bin');
  if (v < ve[k]) throw new Error(`would lean ${kpa}/${rpm}`);
  if (v - ve[k] > 5) throw new Error(`step too big ${kpa}/${rpm}`);
  console.log(`VE ${kpa} kPa ${rpm} rpm: ${ve[k]} -> ${v}`);
  ve[k] = v;
  if (k < 144) d1[k] = v; else d2[k - 144] = v;
}
check('result');
write('veTable1', ve); write('veTable1dozen', d1); write('veTable2dozen', d2);

// Cranking % vs coolant (30..120 C): less fuel from 50 C (warm restart flooded at 52 C).
const crank = read('crankPctTable');
const newCrank = [300, 275, 220, 200, 180, 170, 145, 125, 110, 95];
console.log('crankPctTable:', crank.join(' '), '->', newCrank.join(' '));
write('crankPctTable', newCrank);

text = text.replace(/tuneComment="[^"]*"/,
  'tuneComment="Teste 15: teste 14 + VE da saida da lenta 50-60 kPa 800-1400 rpm (+3 a +5), 80-85 kPa 2000 rpm (+2), lenta 30 kPa (+2) + partida morna com menos combustivel (50-70 C: 220/200/180 %)"');
fs.writeFileSync(out, text, 'latin1');
