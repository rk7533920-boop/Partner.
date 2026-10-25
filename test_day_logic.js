/* Day-logic test for Partner. Run:  node test_day_logic.js   (from the repo folder; needs only Node). Checks night/nap detection, automatic day change, bed/wake repeat safety and duplicate cleanup. */
const fs=require('fs');
const html=fs.readFileSync(process.argv[2]||'index.html','utf8');const src=(html.match(/<script>([\s\S]*?)<\/script>/g)||[]).sort((a,b)=>b.length-a.length)[0].replace(/^<script>|<\/script>$/g,'');
function between(a,b){const i=src.indexOf(a);const j=src.indexOf(b,i);if(i<0||j<0)throw new Error('marker '+a);return src.slice(i,j)}
const pad=n=>String(n).padStart(2,'0'),dayK=t=>{const d=new Date(t);return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate())};
let S={sleep:[],entries:[],set:{}};const put=()=>{};
const code=between('const isNight=','/* ---- data hygiene')+between('/* returns \'ok\', \'dup\' (this bed','/* ---- data hygiene')
 +between('function dedupeSleep','function fixData');
// dayEndHit is defined before isNight? provide stub

eval(code.replace(/const /g,'var ').replace(/\bfunction (\w+)/g,'var $1=function $1'));
const T=(d,h,m=0)=>new Date(2026,9,d,h,m).getTime();
const fmt=t=>new Date(t).toLocaleString('en-GB',{day:'2-digit',month:'short',hour:'2-digit',minute:'2-digit'});
let ok=0,bad=0;const eq=(n,a,b)=>{if(a===b){ok++;console.log('PASS',n)}else{bad++;console.log('FAIL',n,'got',a&&fmt(a),'want',b&&fmt(b))}};
// Scenario from the screenshot: Fri 23 wake 6:32, nap 1:45-4:00pm, bed 2:30 Sat, wake 6:01 Sat
S.sleep=[{id:'a',start:T(22,23,30),end:T(23,6,32)},{id:'b',start:T(23,13,45),end:T(23,16,0)},{id:'c',start:T(24,2,30),end:T(24,6,1)}];
eq('Fri 2 PM is Friday',dayStart(T(23,14)),T(23,6,32));
eq('Sat 2:30 AM bed still Friday',dayStart(T(24,2,30)),T(23,6,32));
eq('Sat 6:42 AM is Saturday (3h31 sleep from 2:30 counts as night)',dayStart(T(24,6,42)),T(24,6,1));
eq('Sat 8:11 AM Saturday',dayStart(T(24,8,11)),T(24,6,1));
console.log('nap classified night?',isNight(S.sleep[1]),'3h31 at 2:30 night?',isNight(S.sleep[2]));
// no wake logged, nothing: rolls at 5 AM
S.sleep=[{id:'a',start:T(22,23,30),end:T(23,6,32)}];
eq('no wake: Sat 4:59 still Friday',dayStart(T(24,4,59)),T(23,6,32));
eq('no wake: Sat 5:01 new day at 5:00',dayStart(T(24,5,1)),T(24,5,0));
eq('no wake: Sun 9:00 -> Sun 5:00',dayStart(T(25,9,0)),T(25,5,0));
// asleep (open bed 2:30), not yet woke: day stays Friday at 7 AM
S.sleep=[{id:'a',start:T(22,23,30),end:T(23,6,32)},{id:'o',start:T(24,2,30),end:null}];
eq('open bed: Sat 7:00 still Friday',dayStart(T(24,7,0)),T(23,6,32));
// woke twice in one night
S.sleep=[{id:'a',start:T(22,23,30),end:T(23,6,32)},{id:'x',start:T(24,2,30),end:T(24,4,0)},{id:'y',start:T(24,4,30),end:T(24,6,1)}];
eq('two wakes in one night: day starts at the later one',dayStart(T(24,7,0)),T(24,6,1));
// late waker 11:30
S.sleep=[{id:'a',start:T(23,4,0),end:T(23,11,30)}];
eq('late waker: day starts 11:30',dayStart(T(23,15)),T(23,11,30));
// bed/wake idempotency
S.sleep=[];
console.log(bedAt(T(24,2,30)),bedAt(T(24,2,30)),wakeAt(T(24,6,1),0),wakeAt(T(24,6,1),0),'records',S.sleep.length);
eq('only one record after repeating bed+wake (inbox merge)',S.sleep.length,1);
console.log('wake before bed ->',(()=>{S.sleep=[{id:'o',start:T(24,5),end:null}];return wakeAt(T(24,4),0)})());
console.log('stale open bed (30h) ->',(()=>{S.sleep=[{id:'o',start:T(22,5),end:null}];const r=wakeAt(T(23,12),0);return r+' recs '+S.sleep.length+' open left '+S.sleep.filter(x=>!x.end).length})());
console.log('nap after wake within 3h with dur ->',(()=>{S.sleep=[{id:'a',start:T(23,0),end:T(23,6,30)}];const r=wakeAt(T(23,9,30),90);return r+' recs '+S.sleep.length})());
console.log('dup wake within 3h no dur ->',(()=>{S.sleep=[{id:'a',start:T(23,0),end:T(23,6,30)}];const r=wakeAt(T(23,8),0);return r+' recs '+S.sleep.length})());
// dedupe duplicate nap rows
S.sleep=[{id:'s1',start:T(24,2,30),end:T(24,6,1)},{id:'s2',start:T(24,2,30),end:T(24,6,1)},{id:'s3',start:T(24,2,32),end:T(24,6,0),night:true}];
dedupeSleep();eq('duplicates collapse to one',S.sleep.length,1);console.log('kept night flag',S.sleep[0].night);

// ---- screen-time hygiene and the "said" guard ----
eval(between('const cleanUsage=','function usClean').replace(/const /g,'var ')+between('const wdl=','async function send').replace(/const /g,'var ').replace(/\bfunction (\w+)/g,'var $1=function $1'));
const U0=[{p:'com.android.settings',a:'Settings',s:T(22,22,13),e:T(23,6,30)},{p:'com.whatsapp',a:'WhatsApp',s:T(23,12,0),e:T(23,12,10)},{p:'com.whatsapp',a:'WhatsApp',s:T(23,12,2),e:T(23,12,10)},{p:'com.snapchat',a:'Snapchat',s:T(23,13,0),e:T(23,13,5)}];
const U1=cleanUsage(U0);
eq('phantom Settings night block removed, duplicate WhatsApp merged',U1.length,2);
eq('merged WhatsApp keeps the earlier start',U1.find(b=>b.a==='WhatsApp').s,T(23,12,0));
eq('AI reply copied into "said" is rejected',saidIsReply('Theek hai, raat 2 baje ke baad ki entries naye din ki maan li hain, khatam hone par batana','Theek hai, raat 2 baje ke baad ki entries naye din ki maan li hain, khatam hone par batana, phir targets dekhenge','ठीक है'),true);
eq('a real Roman transliteration is accepted',saidIsReply('theek hai sone ja raha hu','Theek hai, soja, kal subah jaldi uthna','ठीक है सोने जा रहा हूं'),false);
// routine "all done" backup rule and lockdown schedule helpers
eval(between('const RTALL=','function rtAllRx').replace(/const /g,'var ')+between('const hmn=','const SCD=').replace(/const /g,'var ').replace(/\bfunction (\w+)/g,'var $1=function $1'));
const rtYes=s=>RTALL.test(s)&&!RTNO.test(s);
eq('"morning routine ho gya" counts as done',rtYes('morning routine ho gya')?1:0,1);
eq('"routine complete" counts as done',rtYes('my routine complete')?1:0,1);
eq('"routine abhi nahi hua" does not count',rtYes('morning routine abhi nahi hua')?1:0,0);
eq('"routine kal karunga" does not count',rtYes('routine ho jayega kal karunga')?1:0,0);
eq('overnight schedule length 22:00 to 06:00 is 8 h',schedLen({from:'22:00',to:'06:00'}),480);
eq('same start and end is not a schedule',schedLen({from:'22:00',to:'22:00'}),0);
const sA={from:'22:00',to:'06:00',days:[1,2,3,4,5]};
eq('longer schedule covers a shorter one',schedCovers({from:'21:00',to:'07:00',days:[0,1,2,3,4,5,6]},sA)?1:0,1);
eq('later start does not cover',schedCovers({from:'23:00',to:'06:00',days:[1,2,3,4,5]},sA)?1:0,0);
eq('fewer days does not cover',schedCovers({from:'22:00',to:'06:00',days:[1,2,3]},sA)?1:0,0);
eq('switched-off schedule does not cover',schedCovers({from:'21:00',to:'07:00',days:[0,1,2,3,4,5,6],on:false},sA)?1:0,0);
console.log(ok,'passed',bad,'failed');
