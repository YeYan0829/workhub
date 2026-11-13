// Normal-load fault exercise against an explicit, disposable loopback fixture.
// The child supplies 99 subscriptions and 5 offered writes/s over 10 projects;
// this real Chromium member supplies the 100th subscription on the first project.
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {spawn}=require('node:child_process'),{performance}=require('node:perf_hooks');
const {chromium}=require('playwright');
const seed=JSON.parse(fs.readFileSync(process.env.OVERVIEW_BENCH_SEED,'utf8'));
const origins=(process.env.OVERVIEW_LOAD_ORIGINS||'').split(',');
assert.equal(origins.length,2,'Two application instances required');
for(const origin of origins){const u=new URL(origin);assert.equal(u.protocol,'http:');assert(['localhost','127.0.0.1'].includes(u.hostname));}
const out=process.env.OVERVIEW_REALTIME_OUTPUT;assert(out);fs.mkdirSync(out,{recursive:true});
const backgroundOut=path.join(out,'background'),p=seed.loadProjects[0].project;
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const samples=[],checks=[],errors=[],held=[];let browser,observer,admin,page,adminPage,child,backgroundExit,cdp;
let originalTask,taskId,memberInactive=false;
function evidence(name,value){fs.writeFileSync(path.join(out,name),JSON.stringify(value,null,2));}
const network=offline=>cdp.send('Network.emulateNetworkConditions',{offline,latency:50,downloadThroughput:10e6/8,uploadThroughput:5e6/8});
function checkpoint(id){checks.push(id);console.log('PASS',id);evidence('progress.json',{checks,samples,atUtc:new Date().toISOString()});}
function requireLoad(){assert.equal(backgroundExit,undefined,'Background workload ended before fault sample');const phase=JSON.parse(fs.readFileSync(path.join(backgroundOut,'phase.json'),'utf8'));assert.equal(phase.phase,'sample','Fault sample must be inside the steady load window');}
async function api(ctx,origin,endpoint,method='GET',data,headers={}){
 const csrf=method==='GET'?null:await(await ctx.request.get(origin+'/session/csrf')).json();
 return ctx.request.fetch(origin+endpoint,{method,data,headers:{'Cache-Control':'no-cache',...(csrf?{[csrf.headerName]:csrf.token,'Idempotency-Key':crypto.randomUUID()}:{}),...headers}});
}
async function read(endpoint){const r=await api(admin,origins[0],endpoint);assert(r.ok(),`${r.status()} ${await r.text()}`);return r.json();}
async function patch(values){const old=await read(`/projects/${p}/tasks/${taskId}`);const r=await api(admin,origins[0],`/projects/${p}/tasks/${taskId}`,'PATCH',values,{'If-Match':String(old.revision)});assert(r.ok(),`${r.status()} ${await r.text()}`);return r.json();}
async function membership(active){
 for(let attempt=0;attempt<20;attempt++){
  const project=await read(`/projects/${p}`);
  const r=await api(admin,origins[0],`/projects/${p}/members/${seed.memberId}`,'PUT',{role:'MEMBER',active,expectedBusinessRevision:project.businessRevision});
  if(r.ok()){memberInactive=!active;return;}
  const text=await r.text();assert.equal(r.status(),409,`${r.status()} ${text}`);errors.push({kind:'membership-optimistic-retry',attempt,body:text});
 }
 throw Error('Membership change did not win after 20 fresh-revision retries');
}
async function login(target,user,origin){await target.goto(origin+'/#login');await target.locator('#login [name=username]').fill(user);await target.locator('#login [name=password]').fill(seed.password);await target.locator('#login button').click();await target.locator('#personal-filter').waitFor();}
async function enter(){await page.goto(origins[1]+'/#mine');await page.locator('#personal-filter').waitFor();await page.goto(origins[1]+`/#project/${p}/overview`);await page.locator(`[data-map-task="${taskId}"]`).waitFor();await page.waitForFunction(()=>!routing);await page.locator(`[data-map-task="${taskId}"]`).click();await page.locator('#map-inspector .map-detail-links').waitFor();}
const state=()=>page.evaluate(()=>document.querySelector('#panel')?.overviewState?.());
async function frames(){await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));}
async function applied(seq){await page.waitForFunction(s=>BigInt(document.querySelector('#panel')?.overviewState?.().appliedSeq||0)>=BigInt(s),String(seq));await frames();}
function record(metricId,began,details={}){requireLoad();samples.push({metricId,sampleValue:performance.now()-began,unit:'ms',timestampUtc:new Date().toISOString(),...details});}
async function closeOwned(){
 const ids=await page.evaluate(()=>window.faultEventSources.filter(e=>e.readyState!==EventSource.CLOSED).map(e=>new URL(e.url).searchParams.get('connectionId')).filter(Boolean));
 await page.goto(origins[1]+'/#mine');await page.locator('#personal-filter').waitFor();
 for(const id of ids){const r=await api(observer,origins[1],`/projects/${p}/graph/events?connectionId=${id}`,'DELETE');assert(r.ok(),`close: ${r.status()}`);}
}
async function releaseHeld(){for(const h of held.splice(0)){h.controller.abort();await h.reader;const r=await api(observer,origins[1],`/projects/${p}/graph/events?connectionId=${h.id}`,'DELETE');assert(r.ok());}}
function quantiles(values){values.sort((a,b)=>a-b);return {n:values.length,p50:values[Math.ceil(values.length*.5)-1],p95:values[Math.ceil(values.length*.95)-1],p99:values[Math.ceil(values.length*.99)-1],maximum:values.at(-1)};}
(async()=>{
 const fd=fs.openSync(path.join(out,'background.log'),'a');
 child=spawn(process.execPath,[path.join(__dirname,'load-project-overview.cjs')],{env:{...process.env,OVERVIEW_BENCH_OUTPUT:backgroundOut,OVERVIEW_BENCH_VERSION:'B1',OVERVIEW_LOAD_SUBSCRIBERS:'99',OVERVIEW_LOAD_PROJECTS:'10',OVERVIEW_LOAD_WRITES:'5',OVERVIEW_LOAD_BROWSER:'false',OVERVIEW_LOAD_PROBE:'true',OVERVIEW_LOAD_WARMUP_SECONDS:process.env.OVERVIEW_FAULT_WARMUP_SECONDS||'120',OVERVIEW_LOAD_SECONDS:process.env.OVERVIEW_FAULT_SAMPLE_SECONDS||'600'},stdio:['ignore',fd,fd]});fs.closeSync(fd);
 const finished=new Promise(resolve=>{child.once('exit',(code,signal)=>{backgroundExit={code,signal};resolve(backgroundExit);});child.once('error',error=>{backgroundExit={error:error.message};resolve(backgroundExit);});});
 browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 observer=await browser.newContext({viewport:{width:1440,height:1000},recordVideo:{dir:path.join(out,'video'),size:{width:1440,height:1000}}});
 admin=await browser.newContext();
 await observer.addInitScript(()=>{const Native=EventSource;window.faultEventSources=[];window.EventSource=class extends Native{constructor(url,options){super(url,options);if(String(url).includes('/graph/events'))window.faultEventSources.push(this);}};});
 page=await observer.newPage();adminPage=await admin.newPage();page.setDefaultTimeout(20000);adminPage.setDefaultTimeout(20000);
 cdp=await observer.newCDPSession(page);await cdp.send('Network.enable');await network(false);
 page.on('pageerror',e=>errors.push({kind:'pageerror',error:e.message}));
 await login(adminPage,seed.user,origins[0]);await login(page,seed.member,origins[1]);
 while(!fs.existsSync(path.join(backgroundOut,'workload.json'))){assert.equal(backgroundExit,undefined);await sleep(250);}
 const primary=JSON.parse(fs.readFileSync(path.join(backgroundOut,'workload.json'),'utf8')).writerTargets.find(t=>t.project===p).taskId;
 const graph=await read(`/projects/${p}/graph?view=overview`);
 taskId=graph.nodes.find(n=>n.id!==primary&&['TODO','IN_PROGRESS'].includes(n.status))?.id;assert(taskId,'Independent, visible task required');originalTask=await read(`/projects/${p}/tasks/${taskId}`);
 evidence('fixture.json',{project:p,primaryWriterTask:primary,independentTask:taskId,backgroundSubscriptions:99,normalTotalSubscriptions:100,fallbackHeldConnections:3,fallbackTotalSubscriptions:102,projects:10,offeredWritesPerSecond:5,browserNetwork:{latencyMs:50,downloadMbps:10,uploadMbps:5},protocolNetwork:'unshaped loopback HTTP/SSE',warmupSeconds:+(process.env.OVERVIEW_FAULT_WARMUP_SECONDS||120),sampleSeconds:+(process.env.OVERVIEW_FAULT_SAMPLE_SECONDS||600)});
 await enter();
 while(JSON.parse(fs.readFileSync(path.join(backgroundOut,'phase.json'),'utf8')).phase!=='sample'){assert.equal(backgroundExit,undefined);await sleep(250);}
 requireLoad();checkpoint('normal-load-established');
 // A stale conditional ETag must still be denied immediately after real revocation.
 for(let i=0;i<20;i++){
  requireLoad();const old=await api(observer,origins[1],`/projects/${p}/graph/state`);assert(old.ok());const etag=old.headers().etag;assert(etag);
  await membership(false);const began=performance.now();
  await page.getByRole('alert').filter({hasText:'请确认登录状态与项目访问权限'}).waitFor();
  assert.equal(await page.locator('[data-map-task]').count(),0);assert.equal(await page.locator('#map-inspector').count(),0);await frames();record('access_revocation_clear_ms',began,{iteration:i+1});
  const denied=await api(observer,origins[1],`/projects/${p}/graph/state`,'GET',undefined,{'If-None-Match':etag});assert.equal(denied.status(),403);
  await membership(true);await enter();
 }
 checkpoint('revocation-20-under-load');await page.screenshot({path:path.join(out,'01-restored.png'),fullPage:true});
 await closeOwned();
 const cookie=(await observer.cookies(origins[1])).map(c=>c.name+'='+c.value).join('; ');
 for(let i=0;i<3;i++){
  const id=crypto.randomUUID(),controller=new AbortController();const r=await fetch(origins[1]+`/projects/${p}/graph/events?after=0&connectionId=${id}`,{headers:{Cookie:cookie,Accept:'text/event-stream'},signal:controller.signal});assert.equal(r.status,200);
  const reader=(async()=>{try{for await(const _ of r.body){}}catch(e){if(!controller.signal.aborted)throw e;}})();held.push({id,controller,reader});
 }
 await enter();await page.getByText(/轮询同步/).waitFor();
 for(let i=0;i<20;i++){
  requireLoad();const old=await read(`/projects/${p}/tasks/${taskId}`),next=old.status==='TODO'?'IN_PROGRESS':'TODO';await patch({status:next});const began=performance.now();
  const seq=(await read(`/projects/${p}/graph/state`)).viewSeq;await applied(seq);
  await page.waitForFunction(({id,text})=>document.querySelector(`[data-map-task="${id}"]`)?.textContent.includes(text),{id:taskId,text:next==='TODO'?'待办':'进行中'});await frames();record('poll_fallback_visible_ms',began,{iteration:i+1,expectedSeq:seq});
 }
 checkpoint('fallback-20-under-load');await page.screenshot({path:path.join(out,'02-fallback.png'),fullPage:true});await closeOwned();await releaseHeld();await enter();
 for(let i=0;i<5;i++){
  requireLoad();const before=await state();await network(true);await page.waitForFunction(()=>!navigator.onLine);await sleep(20000);requireLoad();const began=performance.now();await network(false);
  const seq=(await read(`/projects/${p}/graph/state`)).viewSeq;await applied(seq);record('network_recovery_ms',began,{iteration:i+1,outageSeconds:20,expectedSeq:seq});const after=await state();
  assert.equal(after.selected,before.selected);assert.equal(after.scale,before.scale);assert.equal(after.metrics.layouts,before.metrics.layouts);assert.deepEqual(after.nodes,before.nodes);
 }
 checkpoint('offline-5-under-load');await page.screenshot({path:path.join(out,'03-recovered.png'),fullPage:true});
 await patch({status:originalTask.status});await applied((await read(`/projects/${p}/graph/state`)).viewSeq);await closeOwned();
 const exit=await finished;assert.equal(exit.code,0,JSON.stringify(exit));const background=JSON.parse(fs.readFileSync(path.join(backgroundOut,'summary.json'),'utf8'));
 for(const key of ['lostCursors','factMismatches','counterMismatches'])assert.equal(background[key],0,key);assert.deepEqual(background.faults,[]);assert.equal(background.observationCoverage.observed,background.observationCoverage.expected);
 const thresholds={access_revocation_clear_ms:2000,poll_fallback_visible_ms:8000,network_recovery_ms:10000},groups={};
 for(const [metric,threshold]of Object.entries(thresholds)){const values=samples.filter(s=>s.metricId===metric).map(s=>s.sampleValue);groups[metric]={...quantiles(values),thresholdMs:threshold,exceedances:values.filter(v=>v>threshold).length};}
 assert.equal(errors.filter(e=>e.kind==='pageerror').length,0,JSON.stringify(errors));
 evidence('result.json',{status:'PASS',checks,samples,groups,errors,backgroundCondition:background.condition,backgroundObservationCoverage:background.observationCoverage,thresholdsMet:Object.values(groups).every(v=>v.p95<=v.thresholdMs),timing:'write or membership success response to DOM condition plus two animation frames; recovery starts when online is requested',scope:'99 protocol subscriptions plus one real browser over the same 10 projects; fallback temporarily uses 3 held connections, total 102; offline temporarily leaves 99 live subscriptions'});
 console.log('complete',JSON.stringify(groups));
})().catch(async error=>{await cdp?.send('Network.emulateNetworkConditions',{offline:false,latency:50,downloadThroughput:10e6/8,uploadThroughput:5e6/8}).catch(()=>{});await page?.screenshot({path:path.join(out,'failure.png'),fullPage:true}).catch(()=>{});evidence('result.json',{status:'FAIL',checks,samples,errors,error:error.stack});console.error(error);process.exitCode=1;}).finally(async()=>{
 if(memberInactive)await membership(true).catch(()=>{});
 if(originalTask&&admin)await patch({status:originalTask.status}).catch(()=>{});
 await releaseHeld().catch(()=>{});await observer?.close();await admin?.close();await browser?.close();
 if(child&&backgroundExit===undefined){child.kill('SIGTERM');await sleep(1000);if(backgroundExit===undefined)child.kill('SIGKILL');}
});
