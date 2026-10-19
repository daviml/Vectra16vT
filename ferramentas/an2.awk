BEGIN{FS="\t"}
NR<=4||/^MARK/{next}
{
 t=$1; rpm=$3; map=$4; tps=$6; lam=$7; clt=$10; batt=$12; ego=$13; wue=$16; ae=$19; aepw=$20; pw=$22; adv=$27; tgt=$70/14.7; sync=$71; spd=$88; tpsdot=$42; ve=$21
 dt=(n++>0)? t-tp:0; if(dt<0||dt>1)dt=0; tp=t
 if(rpm<400) next
 f=(pw>0.7? pw-0.7:0)*rpm*dt   # ~fuel mass (arbitrary units)
 F+=f
 aeOn=(ae>100.5||aepw>0.05)
 if(tps<5&&spd<3&&rpm<1300) reg="lenta"; else if(tps<5&&rpm>1300&&map<30) reg="desacel"; else if(aeOn) reg="acel_rapida"; else if(tps>=40) reg="carga_alta"; else reg="cruzeiro/parcial"
 FR[reg]+=f; TR[reg]+=dt
 if(wue>100.5) FW+=f*(1-100/wue)   # fuel share due to warmup enrichment
 # lambda by CLT band (not AE, not decel)
 if(!aeOn && reg!="desacel"){b=int(clt/10)*10; LB[b]+=lam*dt; TB[b]+=dt; GB[b]+=tgt*dt; EB[b]+=ego*dt; WB[b]+=wue*dt}
 # sync loss increments
 if(n>1 && sync>ps){ printf "PERDA SINC t=%.1fs rpm=%d tps=%.1f map=%.1f batt=%.1f (cont %d)\n", t, rpm, tps, map, batt, sync }
 ps=sync
 # flares
 fl=(tps<5&&spd<3&&rpm>1350)
 if(fl&&!inF){fs=t; fmax=0; ftps=0; fmap=0; fadv=0; fpw=0; flam=0; k=0; pre_rpm=prev_rpm; pre_map=prev_map}
 if(fl){k++; if(rpm>fmax)fmax=rpm; ftps+=tps; fmap+=map; fadv+=adv; fpw+=pw; flam+=lam}
 if(!fl&&inF && k>3) printf "SUBIDA t=%.1f-%.1fs  rpm max %d  tps %.1f  map %.1f  avanco %.1f  pw %.2f  lambda %.2f  (antes: rpm %d map %.1f)\n", fs, t, fmax, ftps/k, fmap/k, fadv/k, fpw/k, flam/k, pre_rpm, pre_map
 inF=fl; prev_rpm=rpm; prev_map=map
 # AE false triggers: AE starting while TPS<5
 if(aeOn&&!wasAE&&tps<6) falseAE++
 wasAE=aeOn
 if(tps<5 && tpsdot!=0) {tn++; if(tpsdot>mxd)mxd=tpsdot}
}
END{
 print "\nCOMBUSTIVEL POR REGIME (estimado por PW x RPM):"
 for(r in FR) printf "  %-17s %5.1f%% do combustivel em %5.1f%% do tempo\n", r, 100*FR[r]/F, 100*TR[r]/(TR["lenta"]+TR["desacel"]+TR["acel_rapida"]+TR["carga_alta"]+TR["cruzeiro/parcial"])
 printf "  parte do combustivel so pelo enriquecimento de aquecimento: %.1f%%\n", 100*FW/F
 print "\nLAMBDA POR TEMPERATURA DO MOTOR (sem acel. rapida/desacel):"
 for(b=30;b<=80;b+=10) if(TB[b]>0) printf "  CLT %d-%d C: %5.1f min  lambda %.3f  alvo %.3f  EGO %.1f  WUE %.0f%%\n", b, b+9, TB[b]/60, LB[b]/TB[b], GB[b]/TB[b], EB[b]/TB[b], WB[b]/TB[b]
 printf "\nAE iniciado com TPS<6%%: %d vezes | TPSdot nao-zero com TPS<5: %d amostras, max %.1f %%/s\n", falseAE, tn, mxd
}
