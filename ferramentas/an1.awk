BEGIN{FS="\t"}
NR<=4{next}
/^MARK/{marks++; next}
{
 t=$1; rpm=$3; map=$4; tps=$6; lam=$7; mat=$9; clt=$10; batt=$12; ego=$13; wue=$16; gam=$18; ae=$19; aepw=$20; ve=$21; pw=$22; duty=$23; adv=$27; idleadv=$32; matr=$33; tpsdot=$42; mapdot=$43; tgt=$70/14.7; sync=$71; spd=$88; baro=$41
 n++; if(n==1){t0=t; clt0=clt; mat0=mat}
 dt=(n>1)? t-tp : 0; if(dt<0||dt>1) dt=0; tp=t
 dist+=spd/3.6*dt; T+=dt
 # stats
 st("RPM",rpm); st("MAP",map); st("TPS",tps); st("Lambda",lam); st("Alvo",tgt); st("MAT",mat); st("CLT",clt); st("Batt",batt); st("EGO",ego); st("WUE",wue); st("PW",pw); st("Duty",duty); st("VE",ve); st("Avanco",adv); st("Vel",spd); st("Baro",baro)
 # regimes
 running = rpm>400
 if(!running){tOff+=dt; next}
 idle = (tps<5 && spd<3 && rpm<1300)
 decel = (tps<5 && rpm>1300 && map<30)
 if(idle){tIdle+=dt; iR+=rpm*dt; iM+=map*dt; iT+=tps*dt; iL+=lam*dt; iE+=ego*dt; iPW+=pw*dt; if(tps<tpsIdleMin||tpsIdleMin=="")tpsIdleMin=tps; if(tps>tpsIdleMax)tpsIdleMax=tps; iA+=adv*dt; iIA+=idleadv*dt; hT[int(tps*2)/2]+=dt}
 if(decel){tDec+=dt; dPW+=pw*dt; dL+=lam*dt; dR+=rpm*dt; dT+=tps*dt; if(pw<0.5)tCut+=dt}
 if(ae>100.5 || aepw>0.05){tAE+=dt; if(!inAE){aeEv++; if(tps<6 && spd<3) aeIdleEv++}; inAE=1} else inAE=0
 if(tps<5 && (tpsdot>15 || tpsdot< -15)) noisyTpsdot++
 if(wue>100.5) tWue+=dt
 if(spd>=3) tMove+=dt
 # cruise: steady moving, part throttle
 if(spd>=20 && tps>=5 && tps<40 && map>=30 && map<90){tCr+=dt; cL+=lam*dt; cT+=tgt*dt; cE+=ego*dt; cA+=adv*dt; cM+=map*dt; cR+=rpm*dt}
 # flare: rpm high with closed throttle and stopped
 if(tps<5 && spd<3 && rpm>1350){ if(!inF){fl++; fs=t}; inF=1; tFl+=dt } else { if(inF) fdur[fl]=t-fs; inF=0 }
 # lean/rich
 if(lam>tgt+0.05 && map>=60 && tps>10) tLeanLoad+=dt
 if(lam<tgt-0.08) tRich+=dt
 if(ego<=85||ego>=115) tEgoSat+=dt
 if(sync>maxsync) maxsync=sync
 if(map>baro+3) tBoost+=dt
}
function st(k,v){ if(!(k in mn)||v<mn[k])mn[k]=v; if(!(k in mx)||v>mx[k])mx[k]=v; sm[k]+=v; c[k]++ }
END{
 printf "Duracao log: %.1f min (amostras %d, %.1f Hz), marcas=%d\n", T/60, n, n/T, marks
 printf "Distancia GPS: %.2f km | tempo andando %.1f min | parado/lenta %.1f min | motor desligado %.1f min\n", dist/1000, tMove/60, tIdle/60, tOff/60
 printf "Vel media andando: %.1f km/h\n", (tMove>0? dist/tMove*3.6:0)
 split("RPM MAP TPS Lambda Alvo MAT CLT Batt EGO WUE PW Duty VE Avanco Vel Baro",ks," ")
 for(i=1;i<=16;i++){k=ks[i]; printf "  %-7s min %8.2f  med %8.2f  max %8.2f\n",k,mn[k],sm[k]/c[k],mx[k]}
 printf "CLT inicio %.1f -> fim/max %.1f ; MAT inicio %.1f\n", clt0, mx["CLT"], mat0
 printf "\nLENTA (%.1f min): rpm %.0f map %.1f tps %.2f (min %.1f max %.1f) lambda %.3f ego %.1f pw %.2f avanco %.1f idleCorrAdv %.1f\n", tIdle/60, iR/tIdle, iM/tIdle, iT/tIdle, tpsIdleMin, tpsIdleMax, iL/tIdle, iE/tIdle, iPW/tIdle, iA/tIdle, iIA/tIdle
 printf "  distribuicao TPS na lenta (s): "; for(k in hT) printf "%s%%=%.0f ", k, hT[k]; print ""
 printf "DESACEL (%.1f min): rpm %.0f tps %.2f pw medio %.2f ms lambda %.3f | tempo com corte (pw<0.5) %.1f s\n", tDec/60, dR/tDec, dT/tDec, dPW/tDec, dL/tDec, tCut
 printf "CRUZEIRO (%.1f min): rpm %.0f map %.1f lambda %.3f alvo %.3f ego %.1f avanco %.1f\n", tCr/60, cR/tCr, cM/tCr, cL/tCr, cT/tCr, cE/tCr, cA/tCr
 printf "Enriq. aceleracao: %d eventos, %.1f s ativo; eventos com pe fora e parado: %d; amostras TPSdot>15 com TPS<5: %d\n", aeEv, tAE, aeIdleEv, noisyTpsdot
 printf "WUE ativo: %.1f min\n", tWue/60
 printf "Rotacao subindo sem acelerar (parado, >1350): %d eventos, %.1f s total\n", fl, tFl
 printf "Pobre com carga: %.1f s | Rico (>0.08 abaixo alvo): %.1f s | EGO saturado (<=85 ou >=115): %.1f s\n", tLeanLoad, tRich, tEgoSat
 printf "Boost (MAP>baro+3): %.1f s | perda sinc max %d\n", tBoost, maxsync
}
