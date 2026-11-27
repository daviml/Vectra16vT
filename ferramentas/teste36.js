// Test 36 = test 35 (confirmed in the ECU by the logs of 26-27/11: VE fit rms 0.28-0.36, cold advance 0.0 from 62 C;
// base = CurrentTune.msq saved by TunerStudio on 26/11 09:10).
//
// 1. Cold AE adder taeColdA 3.0 -> 4.0 ms. Cold pull-aways on 27/11 08:36 (33-35 C, 670-950 rpm, MAP 38 -> 70-88 kPa)
//    still went to lambda 1.31 for 0.7-1 s, starting during the tail of the 3.6 ms pulse; one nearly stalled (360 rpm).
//    The adder fades linearly along tempTable (30..80 C): ~4.5 ms at 34 C (was 3.6), 1.3 ms at 70 C (was 1.1).
// 2. VE column 1101 rpm, 40-55 kPa, and 45 x 1401: steady state, CLT >= 60, EGO included. Half of the measured excess,
//    rounded to the rich side. Column 801 and everything from 60 kPa up at 801/1101 untouched: that is where the cold
//    pull-aways run lean.
// 3. no_skip_pulses 1 -> 3 (the ini calls 3 the default). Tooth logs of 26/11: in the two bad starts the ECU locked on a
//    false sync and then, once per revolution, dropped sync for exactly 3 teeth and came back without having seen the
//    gap. Experiment: not confirmed in the firmware source that this setting changes it.
// Usage: node teste36.js CurrentTune.msq out.msq
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

// ---- base must be test 35
if (read('ego_startdelay')[0] !== 30 || read('taeTime')[0] !== 0.5 || read('aeEndPW')[0] !== 0.5) throw new Error('base is not test 35');
const wue = read('wueBins');
if (wue[0] !== 110 || wue[3] !== 103) throw new Error('base is not test 35 (WUE)');

// ---- 2. VE
const ve = read('veTable1'), d1 = read('veTable1dozen'), d2 = read('veTable2dozen');
const check = (label) => { for (let i = 0; i < 256; i++) if ((i < 144 ? d1[i] : d2[i - 144]) !== ve[i]) throw new Error(`${label}: alias differs at ${i}`); };
check('base');
const MAP = [20, 30, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 100, 150, 200];
const RPM = [501, 801, 1101, 1401, 2001, 2601, 3101, 3700, 4300, 4900, 5400, 6000, 6500, 7000, 7200, 7500];
const cells = [
  // [kPa, rpm, test 35 value, new]  measured % (n), half applied, rounded up; - = rich
  [40, 1101, 68, 67],   // -5.0 (n=396)
  [45, 1101, 66, 64],   // -6.3 (n=255)
  [45, 1401, 70, 69],   // -4.5 (n=524)
  [50, 1101, 68, 66],   // -6.7 (n=260)
  [55, 1101, 66, 65],   // -4.5 (n=248)
];
for (const [kpa, rpm, old, v] of cells) {
  const i = MAP.indexOf(kpa) * 16 + RPM.indexOf(rpm);
  if (ve[i] !== old) throw new Error(`VE ${kpa}/${rpm} is ${ve[i]}, expected ${old}`);
  if (v >= old || (old - v) / old > 0.035) throw new Error(`VE ${kpa}/${rpm}: bad step`);
  console.log(`VE ${kpa} kPa ${rpm} rpm: ${old} -> ${v}`);
  ve[i] = v; if (i < 144) d1[i] = v; else d2[i - 144] = v;
}
check('result');
write('veTable1', ve); write('veTable1dozen', d1); write('veTable2dozen', d2);

// ---- 1 and 3. scalars
function scalar(name, from, to) {
  const re = new RegExp(`(<constant[^>]*name="${name}"[^>]*>)${from.toFixed(1).replace('.', '\\.')}(</constant>)`);
  if ((text.match(new RegExp(re.source, 'g')) || []).length !== 1) throw new Error(`${name} is not a single ${from} entry`);
  text = text.replace(re, `$1${to.toFixed(1)}$2`);
  if (read(name)[0] !== to) throw new Error(`${name} not written`);
  console.log(`${name}: ${from} -> ${to}`);
}
scalar('taeColdA', 3.0, 4.0);
scalar('no_skip_pulses', 1, 3);

if (!/tuneComment="[^"]*"/.test(text)) throw new Error('tuneComment not found');
text = text.replace(/tuneComment="[^"]*"/,
  'tuneComment="Teste 36: teste 35 + AE frio 4,0 ms + VE 40 a 55 kPa x 1101 e 45 x 1401 + 3 pulsos ignorados na partida"');
fs.writeFileSync(out, text, 'latin1');
