// Test 34 = test 33 (confirmed in the ECU by the logs of 22-23/11: AE tail now tapers to 1.6-1.7 ms, VE fit rms 0.31;
// the CurrentTune.msq saved by TunerStudio on 22/11 14:57 is identical to test 33 except for the comment line).
//
// 1. tempTable 30..120 C -> 30..90 C. The firmware fades the COLD accel enrichment (taeColdA 3.0 ms, taeColdM 220%)
//    linearly from the first to the last tempTable bin. Measured main AE pulse by CLT: 3.7 ms at 32 C, 3.4 at 42,
//    3.0 at 52, 2.65 at 62, 2.4 at 67, 2.3 at 72; fits 0.4*(1+1.2f) + 3.0f with f = (120-CLT)/90 within 0.1 ms.
//    The engine runs at 67-75 C, so more than half of the cold AE never switched off: lambda under 0.72 on 57-64% of
//    hot tip-ins. With the table ending at 90 C: ~3.6 ms at 35 C (unchanged, cold pull-aways still go lean),
//    2.7 at 50, 2.1 at 60, 1.6 at 70, 1.0 at 80, 0.4 at 90.
//    tempTable is also the x axis of WUE, cold advance and the (unused) idle valve warmup tables: those are
//    re-interpolated here so their curves stay the same. Cranking, prime and ASE use temp_table_p5: untouched.
// 2. VE: steady state, CLT >= 65, n >= 100 per cell, EGO correction included, logs of 22-23/11 (19k samples).
//    Whole table 3-9% rich. 80% of the measured excess up to 65 kPa, 50% at 70-75 kPa and next to the cold lean
//    pull-aways (65-70 kPa x 1101 rpm), rounded up, nothing at 80 kPa and above (fuel pressure droops with flow there).
// 3. ego_startdelay 5 -> 20 s: the wideband reads a fixed 0.63-0.65 for ~10 s after some starts (sensor warming up)
//    and the EGO correction was already pulling 4-6% on that reading.
// 4. egoLF 60 -> 30 (more smoothing on the lambda reading at idle).
// Usage: node teste34.js mapas/2026-11-22_teste33_ae-pulso-final.msq out.msq
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

// ---- base must be test 33
if (read('aeEndPW')[0] !== 1) throw new Error('aeEndPW is not 1.0: base is not test 33');
if (read('taeColdA')[0] !== 3 || read('taeColdM')[0] !== 220) throw new Error('cold AE is not 3.0 ms / 220%');

// ---- 1. tempTable and the curves that use it
const OLD_C = [30, 40, 50, 60, 70, 80, 90, 100, 110, 120];
const NEW_C = [30, 35, 40, 45, 50, 55, 60, 70, 80, 90];
const msqC = (c) => Number(((c * 1.8 + 32) * 10 - 320) * 0.05555).toFixed(3).replace(/0+$/, '').replace(/\.$/, '.0');
if (!same(read('tempTable'), OLD_C.map((c) => Number(msqC(c))))) throw new Error('tempTable is not 30..120 C');
const interp = (ys) => NEW_C.map((c) => {
  let i = 0;
  while (i < OLD_C.length - 2 && c > OLD_C[i + 1]) i++;
  return ys[i] + (ys[i + 1] - ys[i]) * (c - OLD_C[i]) / (OLD_C[i + 1] - OLD_C[i]);
});
let k = 0;
write('tempTable', NEW_C, () => msqC(NEW_C[k++]));
console.log('tempTable:', OLD_C.join(' '), '->', NEW_C.join(' '));
const curves = [
  // [name, expected current values or null, rounding]
  ['wueBins', [117, 108, 102, 100, 100, 100, 100, 100, 100, 100], (v) => Math.ceil(v - 1e-9)],
  ['cold_adv_table', [6, 5, 4, 3, 2, 1, 0, 0, 0, 0], (v) => Math.round(v * 10) / 10],
  ['iacstepTable', null, (v) => Math.round(v)],        // idle valve not fitted (IdleCtl = none)
  ['ipwmTable', null, (v) => Math.round(v * 1000) / 1000],
];
for (const [name, expected, round] of curves) {
  const old = read(name);
  if (old.length !== 10) throw new Error(`${name}: ${old.length} values`);
  if (expected && !same(old, expected)) throw new Error(`${name} is not the expected curve`);
  const now = interp(old).map(round);
  write(name, now, (v) => (Number.isInteger(v) ? v.toFixed(1) : String(v)));
  console.log(`${name}: ${old.join(' ')} -> ${now.join(' ')}`);
}
if (read('wueBins')[9] !== 100) throw new Error('last WUE bin must be 100');

// ---- 2. VE
const ve = read('veTable1'), d1 = read('veTable1dozen'), d2 = read('veTable2dozen');
const check = (label) => { for (let i = 0; i < 256; i++) if ((i < 144 ? d1[i] : d2[i - 144]) !== ve[i]) throw new Error(`${label}: alias differs at ${i}`); };
check('base');
const MAP = [20, 30, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 100, 150, 200];
const RPM = [501, 801, 1101, 1401, 2001, 2601, 3101, 3700, 4300, 4900, 5400, 6000, 6500, 7000, 7200, 7500];
const cells = [
  // [kPa, rpm, test 33 value, new]  measured % (n) x share applied; - = rich
  [20, 1401, 86, 84],   // -3.3 (n=1006) x 0.8
  [20, 2001, 84, 79],   // -8.2 (n=133) x 0.8, capped at -6%
  [30, 801, 77, 74],    // -4.9 (n=211) x 0.8
  [30, 1101, 76, 73],   // -5.3 (n=2990) x 0.8  hot idle sits between the 20 and 30 rows
  [30, 2001, 73, 71],   // -4.5 (n=360) x 0.8
  [40, 1101, 72, 68],   // -7.4 (n=246) x 0.8
  [40, 2001, 72, 70],   // -4.2 (n=397) x 0.8
  [45, 1101, 67, 66],   // -2.7 (n=246) x 0.8
  [45, 1401, 71, 70],   // -2.8 (n=593) x 0.8
  [45, 2001, 70, 67],   // -5.8 (n=163) x 0.8
  [50, 1401, 69, 68],   // -3.4 (n=717) x 0.8
  [50, 2001, 72, 69],   // -5.9 (n=238) x 0.8
  [50, 2601, 73, 70],   // -6.0 (n=141) x 0.8
  [55, 1401, 70, 68],   // -4.2 (n=524) x 0.8
  [60, 1401, 72, 68],   // -7.3 (n=225) x 0.8
  [60, 2001, 69, 66],   // -6.8 (n=143) x 0.8
  [60, 2601, 70, 68],   // -3.7 (n=220) x 0.8
  [65, 1101, 71, 68],   // -9.2 (n=142) x 0.5  cold pull-aways at 700-900 rpm, 67-80 kPa went lean on 23/11
  [65, 1401, 72, 69],   // -6.6 (n=240) x 0.8
  [65, 2001, 66, 64],   // -5.1 (n=165) x 0.8
  [65, 2601, 71, 68],   // -5.7 (n=135) x 0.8
  [70, 1101, 73, 70],   // -11.0 (n=112) x 0.4  same cold caveat
  [70, 1401, 71, 69],   // -5.7 (n=199) x 0.5
  [70, 2001, 67, 66],   // -5.5 (n=232) x 0.5
  [75, 1401, 68, 66],   // -6.0 (n=104) x 0.5
  [75, 2001, 67, 66],   // -5.5 (n=169) x 0.5
  [75, 2601, 73, 71],   // -6.8 (n=124) x 0.5
];
for (const [kpa, rpm, old, v] of cells) {
  const i = MAP.indexOf(kpa) * 16 + RPM.indexOf(rpm);
  if (ve[i] !== old) throw new Error(`VE ${kpa}/${rpm} is ${ve[i]}, expected ${old}`);
  if (v >= old || (old - v) / old > 0.061) throw new Error(`VE ${kpa}/${rpm}: bad step`);
  console.log(`VE ${kpa} kPa ${rpm} rpm: ${old} -> ${v}`);
  ve[i] = v; if (i < 144) d1[i] = v; else d2[i - 144] = v;
}
check('result');
write('veTable1', ve); write('veTable1dozen', d1); write('veTable2dozen', d2);

// ---- 3 and 4. EGO
function scalar(name, from, to) {
  const re = new RegExp(`(<constant[^>]*name="${name}"[^>]*>)${from.toFixed(1).replace('.', '\\.')}(</constant>)`);
  if ((text.match(new RegExp(re.source, 'g')) || []).length !== 1) throw new Error(`${name} is not a single ${from} entry`);
  text = text.replace(re, `$1${to.toFixed(1)}$2`);
  if (read(name)[0] !== to) throw new Error(`${name} not written`);
  console.log(`${name}: ${from} -> ${to}`);
}
scalar('ego_startdelay', 5, 20);
scalar('egoLF', 60, 30);

text = text.replace(/tuneComment="[^"]*"/,
  'tuneComment="Teste 34: teste 33 + tempTable 30 a 90 C (AE a frio desliga a 90 em vez de 120) + VE 20 a 75 kPa mais pobre + atraso da sonda 20 s + egoLF 30"');
fs.writeFileSync(out, text, 'latin1');
