// Test 35 = test 34 (confirmed in the ECU by the 7 logs of 23-24/11: VE fit rms 0.30; the CurrentTune.msq saved by
// TunerStudio on 23/11 18:36 equals test 34 apart from rounding of tempTable / ipwmTable).
//
// 1. Cold advance off from 60 C. The engine lives at 68-72 C and cold_adv_table was still adding +3.3 deg at 55-60 C,
//    +2.2 at 65-70, +1.9 at 70-75, +1.4 at 75-85 at any load (81 kPa x 3095 rpm ran 31.9 deg with 29.6 in the table).
// 2. tempTable 30..90 -> 30..80 C. Cold AE fades linearly to the last bin (model checked twice: 1.6 ms at 70 C and
//    1.2 at 75-85 C measured with the table ending at 90). Ending at 80: ~3.5 ms at 35 C (same), 1.1 at 70, 0.75 at 75.
//    Curves on the same axis are rewritten on the new bins: WUE, cold advance, idle valve tables (valve not fitted).
// 3. WUE 30/35/40/45 C 117/113/108/105 -> 110/107/104/103. Steady state was 15-18% rich below 36 C, 12-15% at 36-40,
//    6-8% at 40-45, on target from 45 C up. About half of the excess (the cold idle VE cells are ~6% rich by themselves).
// 4. AE longer and with a lower tail: taeTime 0.4 -> 0.5 s, aeTaperTime 0.2 -> 0.4 s, aeEndPW 1.0 -> 0.5 ms.
//    Cold pull-aways (34-38 C, 600-900 rpm, 67-76 kPa) went to lambda 1.30 for ~1 s starting right when the 3.6 ms pulse
//    ended (it lasts 0.3-0.4 s). Fuel area per tip-in: cold 1.9 -> 2.6 ms.s (+38%), hot at 70 C 0.88 -> 0.87 (same).
// 5. VE: steady state, CLT >= 65, EGO included, 14.8k samples. Half of the measured excess, rounded up (rich side).
//    First time the 80-85 kPa rows are touched: lambda there ran 0.84-0.87 against a 0.87-0.90 target, and the hot
//    tip-ins that still hit lambda 0.67 follow the MAP reached (80-95 kPa), not the AE size.
//    Skipped: 70/80 x 1401 (n 40-51, next to the cold pull-aways), everything from 3101 rpm up (no steady data).
// 6. ego_startdelay 20 -> 30 s: the wideband still read 0.64-0.65 for 10-20 s after hot starts.
// Usage: node teste35.js mapas/2026-11-23_teste34_ae-frio-ve-sonda.msq out.msq
const fs = require('fs');
const [base, out] = process.argv.slice(2);
let text = fs.readFileSync(base, 'latin1');

function block(name) {
  const hits = text.split(`name="${name}"`).length - 1;
  if (hits !== 1) throw new Error(`${name}: ${hits} entries`);
  const start = text.indexOf(`name="${name}"`);
  const open = text.indexOf('>', start) + 1;
  return { open, close: text.indexOf('</constant>', open) };
}
const read = (name) => { const { open, close } = block(name); return text.slice(open, close).trim().split(/\s+/).map(Number); };
function write(name, values, fmt = (v) => v.toFixed(1)) {
  const { open, close } = block(name);
  let i = 0;
  const body = text.slice(open, close).replace(/-?\d+(\.\d+)?/g, () => fmt(values[i++]));
  if (i !== values.length) throw new Error(`${name}: ${i} numbers, expected ${values.length}`);
  text = text.slice(0, open) + body + text.slice(close);
}
const same = (a, b) => a.length === b.length && a.every((v, i) => Math.abs(v - b[i]) < 0.02);
const num = (v) => (Number.isInteger(v) ? v.toFixed(1) : String(v));

// ---- base must be test 34
if (read('ego_startdelay')[0] !== 20 || read('egoLF')[0] !== 30) throw new Error('base is not test 34 (EGO)');
if (read('taeColdA')[0] !== 3 || read('taeColdM')[0] !== 220) throw new Error('cold AE is not 3.0 ms / 220%');

// ---- 1, 2, 3. tempTable and the curves on it
const OLD_C = [30, 35, 40, 45, 50, 55, 60, 70, 80, 90];
const NEW_C = [30, 35, 40, 45, 50, 55, 60, 65, 70, 80];
const msqC = (c) => Number(((c * 1.8 + 32) * 10 - 320) * 0.05555).toFixed(3).replace(/0+$/, '').replace(/\.$/, '.0');
if (!same(read('tempTable'), OLD_C.map((c) => Number(msqC(c))))) throw new Error('tempTable is not 30..90 C');
let k = 0;
write('tempTable', NEW_C, () => msqC(NEW_C[k++]));
console.log('tempTable:', OLD_C.join(' '), '->', NEW_C.join(' '));
function curve(name, expected, now) {
  const old = read(name);
  if (!same(old, expected)) throw new Error(`${name} is not the expected curve: ${old.join(' ')}`);
  write(name, now, num);
  console.log(`${name}: ${old.join(' ')} -> ${now.join(' ')}`);
}
curve('cold_adv_table', [6, 5.5, 5, 4.5, 4, 3.5, 3, 2, 1, 0], [6, 5.5, 5, 4, 3, 1.5, 0, 0, 0, 0]);
curve('wueBins', [117, 113, 108, 105, 102, 101, 100, 100, 100, 100], [110, 107, 104, 103, 102, 101, 100, 100, 100, 100]);
const interp = (ys) => NEW_C.map((c) => {
  let i = 0;
  while (i < OLD_C.length - 2 && c > OLD_C[i + 1]) i++;
  return ys[i] + (ys[i + 1] - ys[i]) * (c - OLD_C[i]) / (OLD_C[i + 1] - OLD_C[i]);
});
for (const [name, round] of [['iacstepTable', (v) => Math.round(v)], ['ipwmTable', (v) => Math.round(v * 1000) / 1000]]) {
  const old = read(name);                       // idle valve not fitted (IdleCtl = none): same curve on the new bins
  if (old.length !== 10) throw new Error(`${name}: ${old.length} values`);
  const now = interp(old).map(round);
  write(name, now, num);
  console.log(`${name}: ${old.join(' ')} -> ${now.join(' ')}`);
}

// ---- 5. VE
const ve = read('veTable1'), d1 = read('veTable1dozen'), d2 = read('veTable2dozen');
const check = (label) => { for (let i = 0; i < 256; i++) if ((i < 144 ? d1[i] : d2[i - 144]) !== ve[i]) throw new Error(`${label}: alias differs at ${i}`); };
check('base');
const MAP = [20, 30, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 100, 150, 200];
const RPM = [501, 801, 1101, 1401, 2001, 2601, 3101, 3700, 4300, 4900, 5400, 6000, 6500, 7000, 7200, 7500];
const cells = [
  // [kPa, rpm, test 34 value, new]  measured % (n), half applied, rounded up; - = rich
  [50, 2001, 69, 68],   // -5.1 (n=122)
  [55, 2001, 68, 67],   // -5.1 (n=141)
  [60, 2001, 66, 65],   // -4.8 (n=306)
  [70, 2001, 66, 65],   // -4.9 (n=166)
  [70, 2601, 71, 69],   // -6.3 (n=105)
  [75, 2001, 66, 65],   // -5.2 (n=241)
  [75, 2601, 71, 69],   // -7.6 (n=116)
  [80, 2001, 68, 66],   // -6.8 (n=215)
  [80, 2601, 70, 68],   // -7.1 (n=63)
  [85, 2001, 73, 70],   // -8.4 (n=100)
  [85, 2601, 76, 73],   // -8.7 (n=89)
];
for (const [kpa, rpm, old, v] of cells) {
  const i = MAP.indexOf(kpa) * 16 + RPM.indexOf(rpm);
  if (ve[i] !== old) throw new Error(`VE ${kpa}/${rpm} is ${ve[i]}, expected ${old}`);
  if (v >= old || (old - v) / old > 0.045) throw new Error(`VE ${kpa}/${rpm}: bad step`);
  console.log(`VE ${kpa} kPa ${rpm} rpm: ${old} -> ${v}`);
  ve[i] = v; if (i < 144) d1[i] = v; else d2[i - 144] = v;
}
check('result');
write('veTable1', ve); write('veTable1dozen', d1); write('veTable2dozen', d2);

// ---- 4 and 6. scalars
function scalar(name, from, to) {
  const re = new RegExp(`(<constant[^>]*name="${name}"[^>]*>)${from.toFixed(1).replace('.', '\\.')}(</constant>)`);
  if ((text.match(new RegExp(re.source, 'g')) || []).length !== 1) throw new Error(`${name} is not a single ${from} entry`);
  text = text.replace(re, `$1${to.toFixed(1)}$2`);
  if (read(name)[0] !== to) throw new Error(`${name} not written`);
  console.log(`${name}: ${from} -> ${to}`);
}
scalar('taeTime', 0.4, 0.5);
scalar('aeTaperTime', 0.2, 0.4);
scalar('aeEndPW', 1.0, 0.5);
scalar('ego_startdelay', 20, 30);

text = text.replace(/tuneComment="[^"]*"/,
  'tuneComment="Teste 35: teste 34 + avanco a frio zerado de 60 C + tempTable 30 a 80 C + WUE menor ate 45 C + AE 0,5 s com cauda 0,4 s ate 0,5 ms + VE 50 a 85 kPa x 2001 e 2601 + atraso da sonda 30 s"');
fs.writeFileSync(out, text, 'latin1');
