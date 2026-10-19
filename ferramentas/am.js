const fs=require('fs');const lines=fs.readFileSync(process.argv[2],'utf8').split('\n');const re=new RegExp(process.argv[3],'i');
for(const l of lines){if(!l)continue;let o;try{o=JSON.parse(l)}catch{continue}
if(o.type!=='assistant'||!o.message)continue;let c=o.message.content;if(!Array.isArray(c))continue;
const t=c.filter(x=>x.type==='text').map(x=>x.text).join(' ');
for(const s of t.split(/\n/)) if(re.test(s)) console.log('*',s.slice(0,400));}
