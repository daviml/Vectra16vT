const fs=require('fs');const lines=fs.readFileSync(process.argv[2],'utf8').split('\n');
for(const l of lines){if(!l)continue;let o;try{o=JSON.parse(l)}catch{continue}
if(o.type!=='user'||!o.message)continue;let c=o.message.content;
if(Array.isArray(c))c=c.filter(x=>x.type==='text').map(x=>x.text).join(' ');
if(typeof c!=='string'||!c.trim()||c.includes('tool_use_id')||c.startsWith('<'))continue;
console.log('---',o.timestamp||'',c.slice(0,1500).replace(/\s+/g,' '));}
