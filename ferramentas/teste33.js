// Test 33 = test 32 (confirmed in the ECU by the logs of 22/11, VE fit rms 0.31) + aeEndPW 2.0 -> 1.0 ms. Nothing else.
// Logs of 22/11: accel enrichment sits at ~2.4 ms on every tip-in (table alone would give ~0.4 ms at 15-110 %/s),
// about +80% over a ~3 ms base pulse; lambda hits the sensor floor (0.63-0.66) on 75% of tip-ins, lean on 5%.
// Half step on purpose: cold pull-aways have gone lean before (lambda 1.1-1.32). Next steps if still rich: 0.5, then 0.
// Usage: node teste33.js mapas/2026-11-21_teste32_ve-baixa-rotacao.msq out.msq
const fs = require('fs');
const [base, out] = process.argv.slice(2);
let text = fs.readFileSync(base, 'latin1');

const read = (name) => {
  const m = text.match(new RegExp(`name="${name}"[^>]*>([^<]*)<`));
  if (!m) throw new Error('missing ' + name);
  return m[1].trim().split(/\s+/).map(Number);
};
const MAP = [20, 30, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 100, 150, 200];
const RPM = [501, 801, 1101, 1401, 2001, 2601, 3101, 3700, 4300, 4900, 5400, 6000, 6500, 7000, 7200, 7500];
const ve = read('veTable1');
for (const [kpa, rpm, v] of [[60, 1401, 72], [30, 801, 77], [40, 2001, 72]])
  if (ve[MAP.indexOf(kpa) * 16 + RPM.indexOf(rpm)] !== v) throw new Error(`VE ${kpa}/${rpm} is not ${v}: base is not test 32`);

const re = /(<constant[^>]*name="aeEndPW"[^>]*>)2\.0(<\/constant>)/;
if ((text.match(new RegExp(re.source, 'g')) || []).length !== 1) throw new Error('aeEndPW is not a single 2.0 entry');
text = text.replace(re, '$11.0$2');
if (read('aeEndPW')[0] !== 1) throw new Error('aeEndPW not written');
console.log('aeEndPW: 2.0 -> 1.0 ms');

text = text.replace(/tuneComment="[^"]*"/,
  'tuneComment="Teste 33: teste 32 + Largura de Pulso Final da aceleracao (aeEndPW) 2.0 para 1.0 ms (engasgos ricos nas pisadas)"');
fs.writeFileSync(out, text, 'latin1');
