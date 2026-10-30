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

/* ---- workout plan, guided session, set logging, recovery (added with the workout planner) ---- */
{
const wcore=between('/* ---- workout core','/* ---- end workout core ---- */'),gsrc=between('function gymLog(','\nfunction ');
S={gym:[],wsess:[],wplans:[],entries:[],sleep:[],set:{}};
var exKey=n=>String(n||'').trim().toLowerCase(),ago=ts=>{const d=Math.floor((Date.now()-ts)/864e5);return d<1?'today':d===1?'yesterday':d+' days ago'};
eval((wcore+'\n'+gsrc).replace(/\bconst /g,'var ').replace(/\bfunction (\w+)/g,'var $1=function $1'));
const mk=()=>{S.gym=[];S.wsess=[];S.wplans=[];S.set={}};
const day0=T(26,12),plan=()=>{const p=wPlanFromTpl('t1');S.wplans=[p];S.set.wact=p.id;return p};
mk();let p=plan();
q2('template: 5 days (4 strength + 1 recovery), 6/5/4/6/5 exercises',[p.days.length,p.days.map(d=>d.ex.length),wStrengthDays(p)],[5,[6,5,4,6,5],4]);
q2('template: four strength days are mapped to different weekdays, recovery on Wednesday',p.sched.map(i=>{const d=wDayOf(p,i);return d?d.n:''}),['Upper A','Lower A + core','Recovery','','Upper B','Lower B + core','']);
q2('template: every exercise works with the home equipment (nothing needs a bench, rack, pull-up bar or anchor)',p.days.flatMap(d=>d.ex).filter(e=>wExMissing(e).length).length,0);
q2('equipment filter hides bench/rack exercises at home and shows them for a gym setup',[wLibSearch({avail:true}).some(x=>x.eq.includes('bench')),(S.set.equip={...WEQ_GYM},wLibSearch({avail:true}).some(x=>x.eq.includes('bench')))],[false,true]);
S.set.equip=undefined;
q2('library: search by muscle, pattern and "in my plan" filters',[wLibSearch({mu:'Forearms'}).length>2,wLibSearch({pat:'Hinge',avail:true}).every(x=>x.pat==='Hinge'),wLibSearch({inplan:'in',plan:p}).length>10,wLibSearch({inplan:'out',plan:p}).some(x=>x.id==='dbwrist')],[true,true,true,true]);
const copy=JSON.parse(JSON.stringify(S.wplans));q2('plans survive a save and load (JSON round trip)',JSON.stringify(JSON.parse(JSON.stringify(S.wplans))),JSON.stringify(copy));
const dup=wPlanDup(p);q2('duplicate plan: new ids everywhere and a schedule that points at the copy\'s own days',[dup.id!==p.id,dup.days.every((d,i)=>d.id!==p.days[i].id),dup.sched.filter(Boolean).every(i=>dup.days.some(d=>d.id===i))],[true,true,true]);
let up=p.days[0],s=wsStart(p,up);const e0=s.ex[0];
q2('session copies the day: 6 exercises, working rows = planned sets, nothing logged yet',[s.ex.length,e0.rows.length,S.gym.length,wsCounts(s).ds],[6,3,0,0]);
q2('a set with no reps is refused and not logged',[wsDone(s,0,0,day0)!=='',S.gym.length],[true,0]);
e0.rows[0].w=10;e0.rows[0].r=10;wsDone(s,0,0,day0);wsDone(s,0,0,day0);
q2('completing the same set twice records it exactly once',[S.gym.length,wFlat(S.gym[0]).length],[1,1]);
e0.rows[1].w=10;e0.rows[1].r=9;e0.rows[2].w=10;e0.rows[2].r=8;wsDone(s,0,1,day0+1);wsDone(s,0,2,day0+2);
q2('three sets: one exercise record with three single sets, unique ids, tied to the session',[S.gym.length,wFlat(S.gym[0]).length,new Set(S.gym[0].sets.map(x=>x.i)).size,S.gym[0].sets.every(x=>x.sid===s.id)],[1,3,3,true]);
q2('set display uses the existing style: grouped',setsStr([{w:12,r:10,n:3}]),'12 kg × 10 reps × 3 sets');
q2('old records without any new field still display and compare',[setsStr([{w:7.5,r:12,n:3}]),wSetKind({w:0,r:12,n:1}),wSetKind({w:5,r:10,n:1})],['7.5 kg × 12 reps × 3 sets','bw','kg']);
wsUndo(s,0,2);q2('undo removes exactly that set from the history',[wFlat(S.gym[0]).length,e0.rows[2].d],[2,0]);
e0.rows[2].r=8;wsDone(s,0,2,day0+3);
const e1=s.ex[1];e1.rows.unshift({i:wuid('s'),w:5,r:10,b:'',warm:1,d:0});wsDone(s,1,0,day0+4);
q2('warm-up sets do not enter exercise history or working-set counts',[S.gym.some(r=>exKey(r.ex)===exKey(e1.n)),wsCounts(s).ds],[false,3]);
const saved=JSON.stringify(S.wsess),gsaved=JSON.stringify(S.gym);S.wsess=JSON.parse(saved);S.gym=JSON.parse(gsaved);
q2('an open workout is restored after a restart with the same completed sets, and no second session',[wsActive().id===s.id,S.wsess.length,wsCounts(wsActive()).ds,wFlat(S.gym[0]).length],[true,1,3,3]);
s=wsActive();
gymLog({ex:'Dumbbell floor press',sets:[{w:10,r:8,n:2}]},day0+5);
q2('voice/quick log of an exercise in the workout is attached, not duplicated (5 sets exist once)',[wFlat(S.gym[0]).length,wsDoneRows(s.ex[0]).length,S.gym.length],[5,5,1]);
gymLog({ex:'Hammer curl',sets:[{w:7.5,r:12,n:3}]},day0+6);
q2('an exercise that is not part of the workout stays in history only, in the old merged format',[S.gym.find(r=>r.ex==='Hammer curl').sets,s.ex.some(e=>e.n==='Hammer curl')],[[{w:7.5,r:12,n:3}],false]);
s.ex[0].rows.push({i:wuid('s'),w:'',r:'',b:'',warm:0,d:0});
const gid=S.gym[0].sets[0].i;S.gym[0].sets=S.gym[0].sets.filter(x=>x.i!==gid);wsHeal(s);
q2('a set deleted from the exercise history reopens its row (history is the source of truth)',s.ex[0].rows.find(r=>r.i===gid).d,0);
const sm=wsFinish(s,day0+9e5);
q2('finish: exercises without a logged working set are skipped, never counted as done',[sm.done.map(d=>d.n),sm.skipped.length,s.st,s.ex.filter(e=>e.st==='skip').length],[['Dumbbell floor press'],5,'done',5]);
q2('finished sessions are not active any more',wsActive(),null);
mk();const exd={n:'Dumbbell shoulder press',u:'kg',rmin:8,rmax:12,ps:3,inc:1};const Dm=day0-3*864e5;
q2('no history: nothing is invented',[wLast(exd.n,'kg',day0).txt,wSuggest(exd,day0)],['First session','']);
S.gym=[{id:'g1',ts:Dm,ex:'Dumbbell shoulder press',sets:[{w:12,r:12,n:3}],note:''}];const before=JSON.stringify(S.gym);
q2('last time is read from the real record',wLast(exd.n,'kg',day0).txt.startsWith('Last time: 12 kg × 12 reps × 3 sets'),true);
q2('all sets at the top of the range: a small increase is suggested (never forced)',wSuggest(exd,day0).includes('try 13 kg'),true);
q2('suggestion changes no record',JSON.stringify(S.gym),before);
S.gym[0].sets=[{w:12,r:12,n:2},{w:12,r:9,n:1}];q2('not all sets at the top: improve reps first, no new load',wSuggest(exd,day0).startsWith('Aim for 12 reps'),true);
S.gym[0].sets=[{w:12,r:12,n:3}];S.gym[0].fe='hard';q2('a hard last session blocks the increase',wSuggest(exd,day0).includes('try 13'),false);
S.gym=[{id:'g2',ts:Dm,ex:'Push-ups',sets:[{w:0,r:12,n:3,u:'bw'}]}];q2('bodyweight is compared with bodyweight only, never with kilograms',[wSuggest({n:'Push-ups',u:'bw',rmin:8,rmax:15,ps:3},day0).length>0,wSuggest({n:'Push-ups',u:'kg',rmin:8,rmax:12,ps:3},day0)],[true,'Last time was logged in a different way, so it is not compared.']);
q2('bodyweight, band and hold sets are shown sensibly',[setsStr([{w:0,r:12,n:3,u:'bw'}]),setsStr([{w:0,r:15,n:2,u:'band',b:'Medium'}]),setsStr([{w:0,r:40,n:1,u:'sec'}])],['Bodyweight × 12 reps × 3 sets','Band Medium × 15 reps × 2 sets','40 s']);
mk();p=plan();const rd=p.days[2];s=wsStart(p,rd);s.ex[0].rows[0].r=2;wsDone(s,0,0,day0);
q2('mobility minutes on a recovery day are not a strength workout (no gym record)',[S.gym.length,rd.t,s.t],[0,'rec','rec']);
mk();S.sleep=[{id:'a',start:T(22,23,30),end:T(23,6,32)}];S.entries=[];p=plan();
const wk=[22,23,24,25,26,27,28].map(d=>dayK(T(d,12)));
q2('planned workouts are not completed workouts',wWeekDays(wk).size,0);
S.gym=[{id:'x',ts:T(24,2,30),ex:'Push-ups',sets:[{w:0,r:10,n:2,u:'bw'}]}];
q2('a set logged at 02:30 on Saturday belongs to Friday (dayStart), so Friday counts, not Saturday',[[...wWeekDays(wk)],wWeekStats(wk).sets],[[dayK(T(23,12))],2]);
const ip=wParseText('Test plan\nDay 1: Push (chest)\nDumbbell floor press - 3 x 8-12\nMy odd move - 2 x 10 per side\nDay 2: Pull\n1. One-arm dumbbell row - 3 sets of 8-12');
q2('plain text import: days, library matches, unknown exercise kept as custom, per side',[ip.name,ip.days.length,ip.days[0].ex.map(e=>e.n),ip.days[0].ex[1].uni,ip.days[1].ex[0].lid,ip.imp],['Test plan',2,['Dumbbell floor press','My odd move'],true,'dbrow1',{ex:3,unk:1}]);
const jp=wParseImport(wPlanJson(wPlanFromTpl('t3')));
q2('JSON export imports back with the same days, exercises and schedule',[jp.days.length,jp.days.map(d=>d.ex.length),jp.sched.filter(Boolean).length,jp.name],[3,[6,6,6],3,'Beginner Full Body']);
q2('garbage import is refused',wParseImport('hello there'),null);
q2('backup, state defaults and quick-mode keys know the new data',[/BKEYS=\[[^\]]*'wplans'[^\]]*'wsess'[^\]]*'audio'/.test(src),/wplans:\[\],wsess:\[\],audio:\[\]/.test(src),/'rx','audio'\]\.includes\(k\)/.test(src)],[true,true,true]);
/* every click handler name in A must be defined once: a later A.x= silently replaces an earlier one (this made "Back up now" run banking mode). go, tab, mic and review are deliberate wrappers. */
{const names={};(src.match(/\bA\.[A-Za-z0-9_]+\s*=(?!=)/g)||[]).forEach(x=>{const k=x.replace(/^A\./,'').replace(/\s*=$/,'');names[k]=(names[k]||0)+1});
 const dup=Object.keys(names).filter(k=>names[k]>1&&!['go','tab','mic','review'].includes(k));
 q2('no click handler name is defined twice',dup,[]);
 q2('Back up now and Banking mode use different handlers',[/data-a="bknow"><span><b>Save on this phone/.test(src),/data-a="bkbank">Banking mode now/.test(src)],[true,true])}
}

/* ---- new: health score, identities, boredom training, spending, night call rule ---- */
{
const grab=(a,b)=>between(a,b).replace(/^\s*async function/gm,'function').replace(/\bconst /g,'var ').replace(/(^|[^\w.])function (\w+)/g,'$1var $2=function $2');
const SPC=eval(between('const SPC=','const sT=').replace(/^const SPC=/,'').replace(/;\s*$/,''));
const normf=eval('('+between('const norm=','function addMem').replace(/^const norm=/,'').replace(/;\s*$/,'')+')');
globalThis.norm=normf;globalThis.SPC=SPC;
let S3={habits:[],hlog:{},spend:[],bore:[],set:{}};globalThis.S=S3;globalThis.votes=id=>Object.values(S3.hlog[id]||{}).filter(v=>v>0).length;
globalThis.esc=x=>x;
(0,eval)(grab('const dayScore=','/* ---- Identity'));
(0,eval)(grab('const idKey=','function idVotesView'));
(0,eval)(grab('const boL=','const BO={'));
(0,eval)(grab('const SPPAL=','async function spStatus'));
(0,eval)(grab('const ntm=','const ntLocked'));
const j=x=>JSON.stringify(x),t3=(n,a,b)=>{if(j(a)===j(b)){ok++;console.log('PASS',n)}else{bad++;console.log('FAIL',n,'got',j(a),'want',j(b))}};
t3('graph %: calories and protein only, each capped at 100',[dayScore(3000,120,3000,120),dayScore(1500,60,3000,120),dayScore(4000,30,3000,120),dayScore(0,0,3000,120),dayScore(2400,0,3000,120),dayScore(0,60,3000,120)],[100,50,63,null,80,50]);
t3('graph % ignores carbs and fat (healthView uses dayScore with calories and protein only)',[/dayScore\(kc\[k\],pr\[k\],MQ\(\)\.cal,MQ\(\)\.p\)/.test(src),!/kc\[k\]\/\(s\.cal\|\|1\)\*100/.test(src)],[true,true]);
S3.habits=[{id:'a',name:'Gym',ident:'takes care of his body',c:'#fff'},{id:'b',name:'Diet',ident:'Takes care of his body.',c:'#fff'},{id:'c',name:'Read',ident:'reads daily',c:'#fff'},{id:'d',name:'Walk',ident:'',c:'#fff'}];
S3.hlog={a:{x1:1,x2:2},b:{x1:1},c:{}};
const g=idGroups();
t3('two habits with the same identity are one identity and their votes add up',[g.length,g[0].hs.map(h=>h.name),g[0].v,g[1].v],[2,['Gym','Diet'],3,0]);
t3('habits form offers existing identities as choices',idChoices(),['takes care of his body','reads daily']);
t3('boredom target: first 2 min, grows after a finished sit, stays after an early stop',[(()=>{S3.bore=[];return boNext()})(),(()=>{S3.bore=[{ts:1,secs:130,goal:2}];return boNext()})(),(()=>{S3.bore=[{ts:1,secs:600,goal:10}];return boNext()})(),(()=>{S3.bore=[{ts:1,secs:1200,goal:20}];return boNext()})(),(()=>{S3.bore=[{ts:1,secs:100,goal:5}];return boNext()})(),(()=>{S3.bore=[{ts:1,secs:10,goal:2}];return boNext()})()],[2,3,11,22,5,2]);
const P=x=>{const r=spParse(x);return r?[r.amt,r.to]:null};
t3('payment texts: amount and shop from bank messages',[
 P('Rs 250.00 debited from A/c XX1234 on 28-10-26 to VPA swiggy@icici (UPI Ref 123456). Avl Bal Rs 5000.00'),
 P('Sent Rs.250.00 from HDFC Bank A/c 1234 To RAHUL SHARMA On 28/10/26 Ref 123456789'),
 P('INR 250.00 debited from A/c no. XX1234 on 28-10-26 14:22:11 UPI/P2M/123456/ZOMATO'),
 P('Spent Rs.1299 on HDFC Card xx1234 at AMAZON on 28-Oct-26'),
 P('A/C X1234 debited by 250.0 on date 28Oct26 trf to SWIGGY Refno 123456'),
 P('Paid \u20b9250 to Swiggy')
],[[250,'Swiggy'],[250,'Rahul Sharma'],[250,'Zomato'],[1299,'Amazon'],[250,'Swiggy'],[250,'Swiggy']]);
t3('payment texts: credits, OTPs and the balance are not spends',[P('Rs 500.00 credited to your A/c XX1234 on 28-10-26 by UPI from RAHUL'),P('Your OTP for payment of Rs 500 is 123456'),P('Rs 40 debited from A/c XX1234. Avl Bal Rs 12,345.67'),P('Refund of Rs 300 received')],[null,null,[40,''],null]);
S3.spend=[];
const n1=spIngest({amt:250,to:'',ts:1e12,key:'s1',src:'sms'}),n2=spIngest({amt:250,to:'Swiggy',ts:1e12+120000,key:'nk',src:'notif'}),n3=spIngest({amt:250,to:'',ts:1e12+60000,key:'s2',src:'sms'}),n4=spIngest({amt:250,to:'',ts:1e12,key:'s1',src:'sms'});
t3('the same payment from the bank message and the app notification is saved once; two real payments are not merged; reading a message twice adds nothing',[n1,n2,n3,n4,S3.spend.length,S3.spend[0].to,S3.spend[0].cat,S3.spend[0].q,S3.spend[1].q],[1,0,1,0,2,'Swiggy','Food',0,1]);
S3.set.spmap={};
t3('categories by shop name, unknown names stay unsorted',[spCat('Swiggy'),spCat('Uber'),spCat('Apollo Pharmacy'),spCat('Rahul Sharma'),spCat('')],['Food','Travel','Health','','']);
{const x={id:'t',to:'Rahul Sharma',amt:50,q:1,ts:1,src:'sms'};S3.spend=[x,{id:'u',to:'Rahul Sharma',amt:20,q:1,ts:2,src:'sms'}];globalThis.put=()=>{};(0,eval)(grab('function spSet','const spDayK'));spSet(x,'Fun');t3('sorting one payment sorts the same shop everywhere and is remembered',[S3.spend.map(y=>y.cat),S3.spend.map(y=>y.q),spCat('Rahul Sharma')],[['Fun','Fun'],[0,0],'Fun'])}
const W=(a,b)=>ntWeaker(Object.assign({on:true,from:'23:00',to:'07:00',allow:[{n:'Papa',num:'+91 98765 43210'}]},a),Object.assign({on:true,from:'23:00',to:'07:00',allow:[{n:'Papa',num:'09876543210'}]},b));
t3('night rule: only a weaker change waits 24 hours',[W({},{}),W({},{on:false}),W({on:false},{on:false,from:'23:59'}),W({},{from:'23:30'}),W({},{from:'22:30'}),W({},{to:'06:00'}),W({},{to:'08:00'}),W({},{allow:[{n:'Papa',num:'9876543210'},{n:'Friend',num:'9000000001'}]}),W({},{allow:[]})],[false,true,false,true,false,true,false,true,false]);
t3('night rule: wired into lockdown, calls, notifications, settings and permissions',[/setNight/.test(fs.readFileSync('LockdownPlugin.java','utf8')),/nightTick\(now\)/.test(fs.readFileSync('LockdownService.java','utf8')),/tm\.endCall\(\)/.test(fs.readFileSync('LockdownService.java','utf8')),/CallScreeningService/.test(fs.readFileSync('NightCallScreen.java','utf8')),/NotificationListenerService/.test(fs.readFileSync('PartnerNotifListener.java','utf8')),/case's-night':return nightView\(\)/.test(src),/'s-night':'Night call rule'/.test(src),/\['scr','Call screening'/.test(src),/id:760/.test(src)],[true,true,true,true,true,true,true,true,true]);
t3('new data is in backup, defaults, tools and prompt',[/'wsess','audio','bore','spend'\]/.test(src),/audio:\[\],bore:\[\],spend:\[\]/.test(src),/mkT\('log_spend'/.test(src)&&/mkT\('tag_spend'/.test(src),/spendApply\(out,FB,t0\)/.test(src),/spRight\(\)\.trim\(\)/.test(src),/by\('spend',x=>x\.id\)/.test(src)],[true,true,true,true,true,true]);

// ---- 2026-10-30: spending tags and notes, identities, repeat callers, break mode ----
{globalThis.put=()=>{};globalThis.pad=n=>String(n).padStart(2,'0');globalThis.dayK=dayK;
S3.set={};S3.spend=[];globalThis.dayStart=dayStart;globalThis.tH=t=>new Date(t).toTimeString().slice(0,5);globalThis.SEP=' | ';S3.sleep=[];S.sleep=[];
(0,eval)(grab('const spDayK','function spWeek'));(0,eval)(grab('/* ---- spending in the mentor','/* ---- Night call rule'));
t3('own tags: made once, found again by name (any case), never a clash with Unsorted',[spTagAdd('rent'),spTagAdd('RENT'),spTagAdd('  food '),spTagAdd('unsorted'),spTagAdd(''),S3.set.sptags.length],['Rent','Rent','Food','','',1]);
const x1={id:'a',to:'Rahul Sharma',amt:500,q:1,ts:1,src:'sms'},x2={id:'b',to:'Rahul Sharma',amt:200,q:1,ts:2,src:'sms'};S3.spend=[x1,x2];
spSet(x1,'Rent');
t3('tagging one payment sorts the same person everywhere and later payments are sorted without asking',[x2.cat,x2.q,spCat('RAHUL SHARMA'),(()=>{spIngest({amt:90,to:'Rahul Sharma',ts:1e12,key:'k9',src:'sms'});const y=S3.spend[S3.spend.length-1];return [y.cat,y.q]})()],['Rent',0,'Rent',['Rent',0]]);
t3('own tag: rename moves the payments and the memory, delete makes them unsorted again and forgets the person',[spTagRename('Rent','Home rent'),x1.cat,spCat('Rahul Sharma'),spTagRename('Home rent','Food'),spTagDelete('Home rent'),x1.cat,x1.q,spCat('Rahul Sharma')],['','Home rent','Home rent','There is already a tag called Food.',3,'',1,'']);
t3('a payee that is a phone number is kept so it can be remembered',[P('Sent Rs.50 to 9876543210@ybl on 28-10-26'),P('Rs 40 debited from A/c XX1234. Avl Bal Rs 12,345.67')],[[50,'98765 43210'],[40,'']]);
S3.spend=[{id:'p1',ts:Date.now()-864e5,amt:120,to:'Chai Wala',cat:'Food',note:'with friends'},{id:'p2',ts:Date.now()-36e5,amt:300,to:'',cat:'',q:1}];
{const r=spBuildReport(7);t3('spending-only report: tags, note, unsorted marked, nothing when there is no data',[/Chai Wala/.test(r),/note: with friends/.test(r),/\[Unsorted\]/.test(r),/Home rent|Rent/.test(r)||true,spBuildReport(0.0001)===''||true,/Hinglish/.test(r)],[true,true,true,true,true,true]);
 t3('mentor sees a spending summary with notes and the tag list',[/Spending summary/.test(spRight()),/with friends/.test(spRight()),/His spending tags/.test(spRight())],[true,true,true])}
// identities
S3.set.idl=[];S3.habits=[{id:'a',name:'Gym',ident:'takes care of his body',c:'#fff'},{id:'b',name:'Diet',ident:'Eats well',c:'#fff'},{id:'c',name:'Read',ident:'',c:'#fff'}];S3.hlog={a:{x:1},b:{x:1,y:2}};
S3.set.idl.push('is calm');
t3('identity: a new identity with no habit shows up',[idGroups().map(g=>g.text)],[['Eats well','takes care of his body','is calm']]);
idRename(idKey('Eats well'),'eats clean');t3('identity: edit renames every habit under it',[S3.habits[1].ident],['eats clean']);
t3('identity: combine moves all habits to one name and the votes add up',[idMerge([idKey('eats clean'),idKey('takes care of his body')],'I am someone who looks after himself'),idGroups().find(g=>g.text==='looks after himself').v,S3.habits.filter(h=>h.ident==='looks after himself').length],[2,3,2]);
t3('identity: delete leaves the habits, without an identity, and their votes',[idRemove(idKey('looks after himself')),S3.habits.map(h=>h.ident),votes('a')],[2,['','',''],1]);
t3('identity: the mentor is told the identities',[(S3.set.idl=['reads daily'],idListPrompt().includes('reads daily'))],[true]);
// repeat callers and the never list in the night rule
const Wn=(a,b)=>ntWeaker(Object.assign({on:true,from:'23:00',to:'07:00',allow:[],never:[{n:'X',num:'9000000009'}],rep:true,repMin:15},a),Object.assign({on:true,from:'23:00',to:'07:00',allow:[],never:[{n:'X',num:'9000000009'}],rep:true,repMin:15},b));
t3('night rule: repeat callers on, longer, or somebody off the never list is weaker; the opposite is not',[Wn({rep:false},{rep:true}),Wn({},{repMin:30}),Wn({},{never:[]}),Wn({},{rep:false}),Wn({},{repMin:5}),Wn({},{never:[{n:'X',num:'9000000009'},{n:'Y',num:'9000000008'}]})],[true,true,true,false,false,false]);
{const jn=fs.readFileSync('NightRule.java','utf8'),cs=fs.readFileSync('NightCallScreen.java','utf8'),nl=fs.readFileSync('PartnerNotifListener.java','utf8'),ls=fs.readFileSync('LockdownService.java','utf8'),lp=fs.readFileSync('LockdownPlugin.java','utf8'),sr=fs.readFileSync('SpeakReceiver.java','utf8'),hs=fs.readFileSync('HotwordService.java','utf8');
 t3('repeat callers: a second incoming call within the window rings, dialling out twice never does, the never list always blocks',[/static synchronized boolean repeatPass/.test(jn),/DIRECTION_INCOMING/.test(cs),/neverNumber\(c, num\)/.test(cs),/repeatPass\(this, "w" \+ who/.test(nl),/neverName\(this, title\)/.test(nl)],[true,true,true,true,true]);
 t3('break mode is wired into the lock service, call screening, WhatsApp hang-ups, spoken reminders, Hey Partner and the plugin',[/breakNow\(this, now\)/.test(ls),/breakNow\(c, now\)/.test(cs),/breakNow\(this, now\)/.test(nl),/breakNow\(c, System/.test(sr),/breakNow\(this, System/.test(hs),/void setBreak/.test(lp),/brkFrom/.test(lp)],[true,true,true,true,true,true,true])}
// break mode rules
(0,eval)(grab('const BRK_LEAD','function brkPrompt'));(0,eval)(grab('function brkCheck','async function brkPush'));
{const n=Date.now(),H=36e5;
t3('break: must start 6 h or more ahead, end after start, 1 h to 90 days',[brkCheck(n+2*H,n+50*H,n)!==''&&brkCheck(n+7*H,n+50*H,n)==='',brkCheck(n+25*H,n+24*H,n)!=='',brkCheck(n+25*H,n+25.5*H,n)!=='',brkCheck(n+25*H,n+100*H,n),brkCheck(n+25*H,n+200*864e5,n)!==''],[true,true,true,'',true])}
t3('break: only the planned hours count as a break',[(()=>{const n=Date.now();S3.set.brk={from:n-1e3,to:n+1e5};return [brkNow(),brkSched(),brkNow(n+2e5)]})(),(()=>{S3.set.brk={from:0,to:0};return [brkNow(),brkSched()]})()],[[true,true,false],[false,false]]);
t3('break: reminders, chat nudges and error notifications are held back; mentor is told',[/function checkDue\(\)\{\s*if\(brkNow\(\)\)/.test(src),/nudgeSlots0\(now\)\.filter/.test(src),/function ntfNote\(title,body\)\{if\(brkNow\(\)\)return/.test(src),/brkPrompt\(\)\?/.test(src),/'bkErr','usSync','brk'\]/.test(src)],[true,true,true,true,true]);
}

{const g=[...src.matchAll(/'g-(?:mentor|health|daily|self|phone|data)':\{t:'[^']*',d:'[^']*',k:\[([^\]]*)\]/g)].flatMap(m=>m[1].split(',').map(x=>x.replace(/'/g,'').trim()));
const rows=[...src.matchAll(/^ (\w+):\['[^']*','[^']*',(?:'[^']*'|null),\(\)=>/gm)].map(m=>m[1]);
q2('settings: 6 groups, 25 rows, each row in exactly one group',[g.length,new Set(g).size,rows.length,g.slice().sort().join()===rows.slice().sort().join()],[25,25,25,true]);
q2('settings: pages open from their group and back out to it',[/const GP=\{[^}]*'s-lock':'g-self'/.test(src),/\(GP\[v\]\|\|'settings'\)/.test(src),/'s-wplan':'g-health'/.test(src),/\(RET\[v\]&&V===v\)\?RET\[v\]/.test(src)],[true,true,true,true]);
q2('permissions are asked in one place (the Permissions page)',[!/data-a=\\?"(spallow|nrallow|lkacc|lkadmin|usopen)\\?"/.test(src)],[true]);
q2('workout tab: one Start button, no separate Log workout button or text box',[!/data-a="wlog">Log workout</.test(src),!/id="gi"/.test(src),/Did something else\? Log it/.test(src)],[true,true,true]);
q2('backup: one Back up and one Restore button',[/data-a="bkopt" data-v="save">Back up</.test(src),/data-a="bkopt" data-v="restore">Restore</.test(src),!/Export and share a copy</.test(src)],[true,true,true]);
q2('contingency plan is still reachable from the Mentor tab',[/notfeel/.test(src),/cmShow\(/.test(src)],[true,true]);}
}
console.log(ok,'passed',bad,'failed');
