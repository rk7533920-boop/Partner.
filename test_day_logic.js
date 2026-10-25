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

/* ---- ingredient-level meal tracking: calculation, edit, remove, missing data, day boundaries ---- */
const q2=(n,a,b)=>{if(JSON.stringify(a)===JSON.stringify(b)){ok++;console.log('PASS',n)}else{bad++;console.log('FAIL',n,'got',JSON.stringify(a),'want',JSON.stringify(b))}};
var norm=eval(between('const norm=','\n').replace('const norm=','(').replace(/;\s*$/,'')+')');
S.foods=[{id:'f1',name:'Rice (cooked)',al:[],per:'g',k:130,p:2.7,c:28,f:0.3,u:{bowl:150}},{id:'f2',name:'Rice (raw)',al:[],per:'g',k:360,p:7,c:79,f:0.6,u:{}},
 {id:'f3',name:'Chapati',al:['roti'],per:'pc',k:100,p:3,c:18,f:1,u:{g:40}},{id:'f4',name:'Dal',al:[],per:'g',k:110,p:6,c:15,f:2.5,u:{katori:150}},{id:'f5',name:'Ghee',al:[],per:'g',k:900,p:0,c:0,f:100,u:{tsp:5}}];
var FOODN=[],FOODE=[];
eval(between('const SEP=','function foodSave').replace(/\blet /g,'var ').replace(/const /g,'var ').replace(/\bfunction (\w+)/g,'var $1=function $1'));
const near=(a,b)=>Math.abs(a-b)<=0.05;
q2('150 g cooked rice = 195 kcal (per 100 g x grams / 100)',foodCalc({food:'Rice (cooked)',qty:150,unit:'g'}).item.k,195);
q2('cooked and raw rice are different references',[foodCalc({food:'Rice (cooked)',qty:100,unit:'g'}).item.k,foodCalc({food:'Rice (raw)',qty:100,unit:'g'}).item.k],[130,360]);
q2('3 chapatis = 300 kcal (per piece food)',foodCalc({food:'roti',qty:3,unit:'pc'}).item.k,300);
q2('a unit the food has no weight for gives no number, not zero',foodCalc({food:'Chapati',qty:1,unit:'bowl'}).item,undefined);
q2('a food that is not in the databank gives no number',foodCalc({food:'Paneer',qty:50,unit:'g'}).item,undefined);
const M={id:'m1',ts:T(25,13),kind:'meal',text:'mess lunch',dur:0,cal:0,p:0,c:0,f:0};
foodFill(M,[{food:'Chapati',qty:4,unit:'pc'},{food:'Rice (cooked)',qty:150,unit:'g'},{food:'Dal',qty:1,unit:'katori'},{food:'Paneer',qty:50,unit:'g'}]);
q2('a meal keeps every ingredient, unknown one marked na',M.items.map(x=>!!x.na),[false,false,false,true]);
q2('unknown ingredient is not counted as zero: total is the sum of the 3 known',[M.cal,M.p,M.c,M.f],[400+195+165,12+4.1+9,72+42+22.5,4+0.5+3.8]);
q2('meal is incomplete while an ingredient has no nutrition',mealInc(M),true);
const sumK=e=>e.items.filter(x=>!x.na).reduce((a,x)=>a+x.k,0),sumP=e=>e.items.filter(x=>!x.na).reduce((a,x)=>a+x.p,0);
q2('meal total equals the sum of its ingredients (kcal exact, macros within 0.05 g)',[M.cal===sumK(M),near(M.p,sumP(M))],[true,true]);
const before=JSON.stringify(M.items.filter((x,i)=>i!==0));
q2('edit chapati 4 -> 3: only that ingredient and the totals change',(()=>{const r=mealEditItem(M,0,S.foods[2],3,'pc');return[r.ok,M.items[0].k,JSON.stringify(M.items.filter((x,i)=>i!==0))===before,M.cal]})(),[true,300,true,660]);
q2('editing an ingredient does not create another entry or lose the id',(()=>{S.entries=[M];mealEditItem(M,1,S.foods[0],100,'g');return[S.entries.length,M.id,M.items.length]})(),[1,'m1',4]);
q2('edit to a unit that cannot be converted is refused and changes nothing',(()=>{const k=M.cal,r=mealEditItem(M,0,S.foods[2],2,'bowl');return[!!r.err,M.cal===k]})(),[true,true]);
q2('adding the same ingredient twice at once (double tap) adds it once',(()=>{const n=M.items.length;mealAddItem(M,S.foods[4],1,'tsp');mealAddItem(M,S.foods[4],1,'tsp');return M.items.length-n})(),1);
q2('ghee 1 tsp adds calories and fat only',(()=>{const g=M.items[M.items.length-1];return[g.k,g.f,g.p,g.c]})(),[45,5,0,0]);
q2('removing one ingredient updates the total and keeps the rest',(()=>{const k=M.cal,x=mealDelItem(M,M.items.length-1);return[M.cal===k-x.k,M.items.length]})(),[true,4]);
q2('unknown ingredient resolves by itself once the food is in the databank',(()=>{S.foods.push({id:'f6',name:'Paneer',al:[],per:'g',k:260,p:18,c:3,f:20,u:{}});const ch=mealResolve(M);return[ch,mealInc(M),M.items[3].k]})(),[true,false,130]);
q2('removing every ingredient clears items and zeroes the total',(()=>{const e={id:'m2',kind:'meal',items:[{food:'Dal',q:100,u:'g',k:110,p:6,c:15,f:2.5}],cal:110,p:6,c:15,f:2.5};mealDelItem(e,0);return[e.items,e.cal]})(),[undefined,0]);
q2('an old meal with only a total and no items is left alone',(()=>{const e={id:'m3',kind:'meal',cal:540,p:31,c:40,f:28};mealResolve(e);return[e.cal,e.items,mealInc(e)]})(),[540,undefined,false]);
q2('an old meal with legacy miss text is shown as incomplete',mealInc({kind:'meal',cal:300,miss:['Paneer is not in the food databank']}),true);
S.sleep=[{id:'w0',start:T(23,23,0),end:T(24,6,0)},{id:'w1',start:T(24,23,0),end:T(25,6,0)}];S.entries=[{id:'a',kind:'meal',ts:T(25,1,0),cal:100},{id:'b',kind:'meal',ts:T(25,8,0),cal:200},{id:'c',kind:'meal',ts:T(25,7,0),cal:50}];
var hk=()=>dayK(T(25,12)),dayMeals=eval(between('const dayMeals=','\n').replace('const dayMeals=','(').replace(/;\s*$/,'')+')');
q2('meals are listed by Partner day, in time order (01:00 after a late night belongs to the day before the wake-up)',[dayMeals(dayK(T(25,12))).map(e=>e.id),dayMeals(dayK(T(24,12))).map(e=>e.id)],[['c','b'],['a']]);
console.log(ok,'passed',bad,'failed');
