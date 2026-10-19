BEGIN{FS="\t"; FLOW=441/60; DT0=0.95; SL=0.15; DISP=1.998; R=287; AFR=8.7; RHO=0.809}
NR<=4 || /^MARK/ {next}
$1+0==$1 {
  t=$1; dt=(n++>0)? t-tp : 0; if(dt<0||dt>1) dt=0; tp=t
  rpm=$3; pw=$22; v=$12; spd=$88; map=$4; mat=$9; lam=$7; ve=$21
  dist += spd/3.6*dt
  if(rpm>300){
    dtr = DT0 + SL*(14-v); eff = pw - dtr; if(eff<0) eff=0
    vol += 4*eff/1000*rpm/120*FLOW*dt              # cm3 pelos bicos
    if(lam<1.3 && lam>0.5 && pw>0.5){             # pelo ar (so quando ha combustao)
      T=mat+273.15+10; air = (ve/100)*map*1000*DISP/1000/(R*T)*1000 * rpm/120*dt   # g de ar
      fuel_g += air/(lam*AFR)
    }
    tr+=dt; if(spd<3) tid+=dt
  }
}
END{ km=dist/1000; L1=vol/1000; L2=fuel_g/RHO/1000;
  printf "%-26s %5.1f min %5.2f km | bicos: %5.2f L -> %4.1f km/l | ar: %5.2f L -> %4.1f km/l | %2.0f%% parado\n", FILENAME, tr/60, km, L1, (L1>0?km/L1:0), L2, (L2>0?km/L2:0), 100*tid/tr }
