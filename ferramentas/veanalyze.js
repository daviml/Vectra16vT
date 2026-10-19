// VE analysis (like TunerStudio VE Analyze) from TurboDash .msl logs against a .msq.
// usage: node veanalyze.js tune.msq log1.msl [log2.msl ...]
const fs = require('fs');
const [msqPath, ...logs] = process.argv.slice(2);
const msq = fs.readFileSync(msqPath, 'latin1');
function arr(name) {
  const m = msq.match(new RegExp(`name="${name}"[^>]*>([^<]*)<`));
  return m[1].trim().split(/\s+/).map(Number);
}
const rpmBins = arr('frpm_table1'), mapBins = arr('fmap_table1'), ve = arr('veTable1');
const NR = rpmBins.length, NM = mapBins.length;
const veAt = (mi, ri) => ve[mi * NR + ri];

function weights(bins, v) {
  if (v <= bins[0]) return [[0, 1]];
  if (v >= bins[bins.length - 1]) return [[bins.length - 1, 1]];
  for (let i = 0; i < bins.length - 1; i++) if (v <= bins[i + 1]) {
    const f = (v - bins[i]) / (bins[i + 1] - bins[i]);
    return [[i, 1 - f], [i + 1, f]];
  }
}

const acc = {}; // key mi,ri -> {w, r, lam, tgt, ego}
const lean = [];
const LAG = 5; // ~0.3 s sensor/transport delay at 16 Hz
for (const path of logs) {
  const rows = fs.readFileSync(path, 'latin1').split(/\r?\n/).slice(4).filter(l => l && !l.startsWith('MARK')).map(l => l.split('\t').map(Number));
  for (let i = 0; i + LAG < rows.length; i++) {
    const r = rows[i], later = rows[i + LAG];
    const t = r[0], rpm = r[2], map = r[3], tps = r[5], clt = r[9], ego = r[12], wue = r[15], ae = r[18], aepw = r[19], pw = r[21], tpsdot = r[41], mapdot = r[42], tgt = r[69] / 14.7;
    const lam = later[6];
    const aeOn = ae > 100.5 || aepw > 0.05;
    // lean with load, any temperature
    if (lam > tgt + 0.05 && map >= 60 && tps > 10 && rpm > 800) lean.push({ path, t, rpm, map, tps, lam, tgt, ego, aeOn, clt, wue });
    const steady = rpm > 650 && pw > 0.9 && !aeOn && Math.abs(tpsdot) < 8 && Math.abs(mapdot) < 30 && wue <= 101 && clt >= 60 && lam > 0.7 && lam < 1.25 && Math.abs(later[3] - map) < 6;
    if (!steady) continue;
    const corr = (lam / tgt) * (ego / 100);
    for (const [mi, wm] of weights(mapBins, map)) for (const [ri, wr] of weights(rpmBins, rpm)) {
      const w = wm * wr; if (w < 0.05) continue;
      const k = mi + ',' + ri; const a = acc[k] || (acc[k] = { w: 0, r: 0, lam: 0, tgt: 0, ego: 0 });
      a.w += w; a.r += corr * w; a.lam += lam * w; a.tgt += tgt * w; a.ego += ego * w;
    }
  }
}
console.log('RPM bins:', rpmBins.join(' '));
console.log('MAP bins:', mapBins.join(' '));
console.log('\nCelulas com dados (peso>=25 amostras): map  rpm  VE  lambda alvo ego  corr  VEsugerido');
const out = [];
for (const k in acc) {
  const a = acc[k]; if (a.w < 25) continue;
  const [mi, ri] = k.split(',').map(Number);
  const c = a.r / a.w;
  out.push({ mi, ri, map: mapBins[mi], rpm: rpmBins[ri], ve: veAt(mi, ri), lam: a.lam / a.w, tgt: a.tgt / a.w, ego: a.ego / a.w, c, w: a.w });
}
out.sort((x, y) => x.mi - y.mi || x.ri - y.ri);
for (const o of out) console.log(`  ${String(o.map).padStart(4)} ${String(o.rpm).padStart(5)}  ${String(o.ve).padStart(3)}  ${o.lam.toFixed(3)} ${o.tgt.toFixed(3)} ${o.ego.toFixed(1).padStart(5)}  ${o.c.toFixed(3)}  ${(o.ve * o.c).toFixed(1).padStart(5)}  (n=${o.w.toFixed(0)})${Math.abs(o.c - 1) >= 0.04 ? '  <==' : ''}`);
fs.writeFileSync(process.argv[1].replace(/[^/\\]*$/, 've_result.json'), JSON.stringify(out));

// group lean events
console.log(`\nPobre com carga: ${lean.length} amostras`);
let ev = [], cur = null;
for (const s of lean) {
  if (cur && s.path === cur.path && s.t - cur.t1 < 0.6) { cur.t1 = s.t; cur.n++; cur.lamMax = Math.max(cur.lamMax, s.lam); cur.ae = cur.ae || s.aeOn; cur.rpm.push(s.rpm); cur.map.push(s.map); }
  else { cur = { path: s.path, t0: s.t, t1: s.t, n: 1, lamMax: s.lam, tgt: s.tgt, ae: s.aeOn, rpm: [s.rpm], map: [s.map], tps: s.tps, clt: s.clt, wue: s.wue, ego: s.ego }; ev.push(cur); }
}
ev = ev.filter(e => e.n >= 3);
const avg = a => a.reduce((x, y) => x + y, 0) / a.length;
for (const e of ev) console.log(`  ${e.path.slice(11, 19)} t=${e.t0.toFixed(1)}-${e.t1.toFixed(1)}s rpm~${avg(e.rpm).toFixed(0)} map~${avg(e.map).toFixed(0)} tps ${e.tps.toFixed(0)} lambda max ${e.lamMax.toFixed(2)} alvo ${e.tgt.toFixed(2)} ego ${e.ego.toFixed(0)} clt ${e.clt.toFixed(0)} wue ${e.wue.toFixed(0)} ${e.ae ? 'AE' : ''}`);
