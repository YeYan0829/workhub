// Fixed-rate loopback load. Explicit seed required; every subscription is closed in finally.
const fs=require('node:fs'),http=require('node:http'),crypto=require('node:crypto'),{performance,monitorEventLoopDelay}=require('node:perf_hooks');
const seed=JSON.parse(fs.readFileSync(process.env.OVERVIEW_BENCH_SEED,'utf8'));
const origins=(process.env.OVERVIEW_LOAD_ORIGINS||seed.base).split(',');
for(const url of origins)if(!['127.0.0.1','localhost'].includes(new URL(url).hostname)||new URL(url).protocol!=='http:')throw Error('Loopback HTTP fixture required');
const loginIpMode=process.env.OVERVIEW_LOAD_LOGIN_IP_MODE||'distinct';if(!['distinct','single'].includes(loginIpMode))throw Error('Invalid login IP mode');
const version=process.env.OVERVIEW_BENCH_VERSION||'B1',subscribers=+(process.env.OVERVIEW_LOAD_SUBSCRIBERS||100),projectCount=+(process.env.OVERVIEW_LOAD_PROJECTS||10),wps=+(process.env.OVERVIEW_LOAD_WRITES||5);
const warmup=+(process.env.OVERVIEW_LOAD_WARMUP_SECONDS||120),seconds=+(process.env.OVERVIEW_LOAD_SECONDS||600),out=process.env.OVERVIEW_BENCH_OUTPUT;
const reconcileMillis=+(process.env.OVERVIEW_LOAD_RECONCILE_MS||30000);
const users=seed.loadUsers,projects=seed.loadProjects?.slice(0,projectCount);
if(!Number.isInteger(subscribers)||subscribers<1||subscribers>200||!Number.isInteger(projectCount)||projectCount<1||projectCount>subscribers||!Number.isFinite(wps)||wps<0||!Number.isFinite(warmup)||warmup<0||!Number.isFinite(seconds)||seconds<=0||!Number.isFinite(reconcileMillis)||reconcileMillis<250)throw Error('Invalid load condition');
if(!users?.length||projects?.length!==projectCount||Math.ceil(subscribers/3)>users.length)throw Error('Prepare the optional load fixture first');
const browserCount=process.env.OVERVIEW_LOAD_BROWSER==='true'?Math.min(3,projectCount):0;
fs.mkdirSync(out,{recursive:true});const log=fs.createWriteStream(out+'/samples.jsonl');
const sleep=ms=>new Promise(r=>setTimeout(r,ms)),metricValues={},faults=[],transportEvents=[],watchers=[],sessions=[],browserObservers=[];let phase='warmup',running=true,writerTimer,probeTimer,resourceTimer,browser;
function phaseEvidence(name){fs.writeFileSync(out+'/phase.json',JSON.stringify({phase:name,atUtc:new Date().toISOString(),monotonicMs:performance.now()}));}
phaseEvidence(phase);
// Stream full records to disk; retain only numbers required for exact percentiles.
function sample(metricId,value,fields={},cohort=phase){if(cohort!=='sample')return;const s={version,metricId,sampleValue:value,unit:metricId.includes('bytes')?'bytes':'ms',timestampUtc:new Date().toISOString(),...fields};(metricValues[metricId]??=[]).push(value);log.write(JSON.stringify(s)+'\n');}
const driverLag=monitorEventLoopDelay({resolution:20});driverLag.enable();
let driverCpu=process.cpuUsage(),driverAt=performance.now();
resourceTimer=setInterval(()=>{
 const now=performance.now(),cpu=process.cpuUsage(),elapsed=now-driverAt;
 sample('driver_rss_bytes',process.memoryUsage().rss);
 sample('driver_cpu_percent',((cpu.user-driverCpu.user)+(cpu.system-driverCpu.system))/(elapsed*10),{unit:'percent'});
 sample('driver_event_loop_p95_ms',driverLag.percentile(95)/1e6);
 sample('driver_event_loop_p99_ms',driverLag.percentile(99)/1e6);
 driverCpu=cpu;driverAt=now;driverLag.reset();
},10000);
class Session{
 constructor(user,origin){this.user=user;this.origin=origin;this.cookie='';}
 async call(endpoint,method='GET',data,headers={}){
  const requestId=headers['X-Request-Id']||crypto.randomUUID();
  const r=await fetch(this.origin+endpoint,{method,body:data===undefined?undefined:typeof data==='string'?data:JSON.stringify(data),headers:{Cookie:this.cookie,'X-Request-Id':requestId,...(method==='GET'?{}:{'Content-Type':'application/json',[this.csrf?.headerName||'X-CSRF-TOKEN']:this.csrf?.token||'','Idempotency-Key':crypto.randomUUID()}),...headers},signal:AbortSignal.timeout(20000)});
  const set=r.headers.getSetCookie();if(set.length)this.cookie=set.map(v=>v.split(';')[0]).join('; ');
  const text=await r.text();if(!r.ok&&r.status!==304){const error=Error(r.status+' '+endpoint+' '+text.slice(0,180));error.status=r.status;try{error.code=JSON.parse(text).code;}catch{}throw error;}return {data:text?JSON.parse(text):null,bytes:Buffer.byteLength(text),requestId,etag:r.headers.get('ETag')};
 }
 async login(){
  this.csrf=(await this.call('/session/csrf')).data;
  const identity=crypto.createHash('sha256').update(this.user).digest().readUInt16BE(),localAddress=loginIpMode==='single'?'127.0.0.1':`127.88.${identity>>8}.${identity&255}`;
  const body=new URLSearchParams({username:this.user,password:seed.password}).toString(),requestId=crypto.randomUUID();
  // Distinct loopback sources represent distinct users while preserving the production
  // per-IP login limit. Sessions and CSRF still come from the real login endpoint.
  await new Promise((resolve,reject)=>{
   const request=http.request(new URL(this.origin+'/session/login'),{method:'POST',localAddress,headers:{Cookie:this.cookie,'Content-Type':'application/x-www-form-urlencoded','Content-Length':Buffer.byteLength(body),'X-Request-Id':requestId,[this.csrf.headerName]:this.csrf.token}},response=>{
    let text='';response.setEncoding('utf8');response.on('data',part=>text+=part);response.on('error',reject);response.on('end',()=>{
     if(response.statusCode<200||response.statusCode>=300)return reject(Error(response.statusCode+' /session/login '+text.slice(0,180)));
     const cookies=response.headers['set-cookie'];if(cookies?.length)this.cookie=cookies.map(v=>v.split(';')[0]).join('; ');resolve();
    });
   });request.on('error',reject);request.setTimeout(20000,()=>request.destroy(Error('Login timed out')));request.end(body);
  });
  this.csrf=(await this.call('/session/csrf')).data;return this;
 }
}
const acknowledgments=new Map(),observers=new Map();
function recordObserved(observer){
 const entries=acknowledgments.get(observer.project)||[];
 while(observer.next<entries.length&&entries[observer.next].seq<=observer.seq){
  const ack=entries[observer.next++];
  sample(observer.kind,Math.max(0,observer.at-ack.time),{projectSeq:String(ack.seq),observedSeq:String(observer.seq),datasetId:ack.dataset,clientId:String(observer.client),requestId:ack.id},ack.phase);
 }
}
function applied(project,seq,at,kind,client){
 const key=project+':'+kind+':'+client,value=BigInt(seq);let observer=observers.get(key);
 if(!observer){observer={project,seq:value,at,kind,client,next:0};observers.set(key,observer);}
 else if(value>observer.seq){observer.seq=value;observer.at=at;}
 recordObserved(observer);
}
class Watcher{
 constructor(session,project,snapshot,index,datasetId){this.session=session;this.project=project;this.datasetId=datasetId;this.ids=snapshot.nodes.map(n=>n.id);this.seq=snapshot.viewSeq;this.seen=BigInt(this.seq||0);this.nodes=new Map(snapshot.nodes.map(n=>[n.id,n]));this.counts=snapshot.counts;this.index=index;this.connectionId=crypto.randomUUID();this.abort=new AbortController();this.busy=false;this.last=0;}
 async open(){
  if(version==='B0')return;
  const r=await fetch(this.session.origin+`/projects/${this.project}/graph/events?after=${this.seq}&connectionId=${this.connectionId}`,{headers:{Cookie:this.session.cookie,Accept:'text/event-stream'},signal:this.abort.signal});
  if(r.status!==200)throw Error('SSE status '+r.status);
  this.streaming=true;this.reconnectAttempt=0;this.nextCheck=performance.now()+reconcileMillis;
  if(this.disconnectedAt!==undefined){sample('stream_reconnect_ms',performance.now()-this.disconnectedAt,{clientId:String(this.index),datasetId:this.datasetId});this.disconnectedAt=undefined;}
  if(!this.stateTimer)this.stateTimer=setInterval(()=>{if(performance.now()>=this.nextCheck)this.reconcile();},Math.min(1000,reconcileMillis));
  this.reader=(async()=>{let buffer='',error;const decoder=new TextDecoder();try{
   for await(const chunk of r.body){buffer+=decoder.decode(chunk,{stream:true});let end;
    while((end=buffer.indexOf('\n\n'))>=0){const frame=buffer.slice(0,end);buffer=buffer.slice(end+2);sample('sse_frame_bytes',Buffer.byteLength(frame)+2,{clientId:String(this.index),datasetId:this.datasetId});const data=frame.split('\n').find(v=>v.startsWith('data: '));
     if(frame.includes('access.revoked')){faults.push({kind:'unexpected_revocation',client:this.index});throw Error('unexpected revoked');}
     if(data&&(frame.includes('project.changed')||frame.includes('project.reset'))){const value=JSON.parse(data.slice(6));this.seen=this.seen>BigInt(value.latestSeq)?this.seen:BigInt(value.latestSeq);this.schedule();}
    }
   }
  }catch(e){error=e.message;}finally{
   if(!this.abort.signal.aborted&&running&&!this.closed){this.streaming=false;this.disconnectedAt??=performance.now();transportEvents.push({kind:error?'stream_error':'stream_eof',error,client:this.index,atUtc:new Date().toISOString()});this.nextCheck=0;this.reconcile();this.retryTimer=setTimeout(()=>this.reconnect(),1000);}
  }})();
 }
 reconcile(){
  if(!running||this.closed||this.checking)return;this.checking=true;
  this.checkFlight=this.session.call(`/projects/${this.project}/graph/state`,'GET',undefined,this.etag?{'If-None-Match':this.etag}:{}).then(r=>{this.etag=r.etag||this.etag;if(r.data)this.seen=this.seen>BigInt(r.data.viewSeq)?this.seen:BigInt(r.data.viewSeq);this.schedule();}).catch(e=>faults.push({kind:'state',error:e.message,client:this.index})).finally(()=>{this.checking=false;this.nextCheck=performance.now()+(this.streaming?reconcileMillis:5000);});
 }
 async reconnect(){
  if(!running||this.closed)return;
  try{await this.open();}catch(e){if(!running||this.closed)return;transportEvents.push({kind:'reconnect_failed',error:e.message,client:this.index,atUtc:new Date().toISOString()});this.retryTimer=setTimeout(()=>this.reconnect(),Math.min(5000,1000*2**Math.min(++this.reconnectAttempt,3)));}
 }
 schedule(){if(!running||this.timer||this.busy||BigInt(this.seq)>=this.seen)return;this.timer=setTimeout(()=>{this.timer=null;this.sync();},Math.max(200,500-(performance.now()-this.last)));}
 async sync(){
  if(!running||this.busy)return;this.busy=true;this.last=performance.now();
  try{this.session.csrf=(await this.session.call('/session/csrf')).data;const cohort=phase,began=performance.now(),r=await this.session.call(`/projects/${this.project}/graph/sync`,'POST',{afterSeq:this.seq,nodeIds:this.ids});
   sample('sync_api_ms',performance.now()-began,{clientId:String(this.index),datasetId:this.datasetId,requestId:r.requestId},cohort);sample('normal_sync_json_bytes',r.bytes,{clientId:String(this.index),datasetId:this.datasetId,requestId:r.requestId},cohort);
   const d=r.data;if(BigInt(d.toSeq)<BigInt(this.seq))throw Error('Applied sequence regressed');for(const n of d.nodesUpsert)this.nodes.set(n.id,n);for(const id of d.nodesRemove)this.nodes.delete(id);this.counts=d.counts;this.seq=d.toSeq;applied(this.project,this.seq,performance.now(),'sync_client_applied_ms',this.index);
  }catch(e){
   if(e.code==='GRAPH_RESYNC_REQUIRED'){
    try{const began=performance.now(),r=await this.session.call(`/projects/${this.project}/graph/view`,'POST',{nodeIds:this.ids});sample('recovery_graph_api_ms',performance.now()-began,{clientId:String(this.index),datasetId:this.datasetId,requestId:r.requestId});this.nodes=new Map(r.data.nodes.map(n=>[n.id,n]));this.counts=r.data.counts;this.seq=r.data.viewSeq;applied(this.project,this.seq,performance.now(),'sync_client_applied_ms',this.index);transportEvents.push({kind:'scope_resync',client:this.index,atUtc:new Date().toISOString()});}
    catch(recovery){faults.push({kind:'scope_recovery',error:recovery.message,client:this.index});}
   }else faults.push({kind:'sync',error:e.message,client:this.index});
  }finally{this.busy=false;this.schedule();}
 }
 async close(){if(this.closed)return;this.closed=true;clearTimeout(this.timer);clearTimeout(this.retryTimer);clearInterval(this.stateTimer);this.abort.abort();await this.checkFlight;await this.reader;if(version==='B1')await this.session.call(`/projects/${this.project}/graph/events?connectionId=${this.connectionId}`,'DELETE').catch(e=>faults.push({kind:'close',error:e.message}));}
}
async function pool(values,limit,fn){let next=0;await Promise.all(Array.from({length:Math.min(limit,values.length)},async()=>{while(next<values.length){const i=next++;await fn(values[i],i);}}));}
(async()=>{
 const writer=await new Session(seed.user,origins[0]).login(),snapshots=new Map();
 for(const p of projects)snapshots.set(p.project,(await writer.call(`/projects/${p.project}/graph${version==='B1'?'?view=overview':''}`)).data);
 const userCount=Math.ceil(subscribers/3);for(let i=0;i<userCount;i++)sessions.push(await new Session(users[i].user,origins[0]).login());
 await pool(Array.from({length:subscribers},(_,i)=>i),20,async i=>{
  const original=sessions[Math.floor(i/3)],session=Object.assign(new Session(original.user,origins[i%origins.length]),{cookie:original.cookie,csrf:original.csrf});
  if(i<browserCount)return;const project=projects[i%projectCount];const w=new Watcher(session,project.project,snapshots.get(project.project),i,project.id);watchers.push(w);await w.open();
 });
 const tasks=new Map();for(const p of projects){const n=snapshots.get(p.project).nodes.find(n=>['TODO','IN_PROGRESS'].includes(n.status))||snapshots.get(p.project).nodes[0];tasks.set(p.project,(await writer.call(`/projects/${p.project}/tasks/${n.id}`)).data);}
 // Setup metadata lets fault exercises choose a different task from the serial
 // writer, preserving its revision contract while both workloads run together.
 fs.writeFileSync(out+'/workload.json',JSON.stringify({writerTargets:projects.map(p=>({project:p.project,taskId:tasks.get(p.project).id,datasetId:p.id}))},null,2));
 if(process.env.OVERVIEW_LOAD_BROWSER==='true'){
  const {chromium}=require('playwright');browser=await chromium.launch({headless:true,args:['--no-sandbox']});
  const observer=await new Session(seed.member,origins.at(-1)).login(),cookie=observer.cookie.split(';')[0],split=cookie.indexOf('=');
  for(let i=0;i<browserCount;i++){
   const context=await browser.newContext({viewport:{width:1440,height:1000}}),project=projects[i];
   await context.addCookies([{name:cookie.slice(0,split),value:cookie.slice(split+1),url:origins.at(-1)}]);const page=await context.newPage();
   const cdp=await context.newCDPSession(page);await cdp.send('Network.enable');
   await cdp.send('Network.emulateNetworkConditions',{offline:false,latency:50,downloadThroughput:10e6/8,uploadThroughput:5e6/8});
   await page.exposeFunction('loadPaint',d=>applied(d.project,d.seq,performance.now(),'collaboration_visible_ms','chromium-'+i));
   await page.addInitScript(()=>document.addEventListener('overview.applied',e=>{const detail=e.detail;requestAnimationFrame(()=>requestAnimationFrame(()=>window.loadPaint(detail)));}));
   await page.goto(origins.at(-1)+`/#project/${project.project}/overview`);await page.locator('[data-map-task]').first().waitFor();await page.waitForFunction(()=>!routing);
   if(await page.evaluate(()=>document.hidden))throw Error('Chromium observer must be visible');
   browserObservers.push({page,project:project.project});
  }
 }
 const queues=new Map();let totalWrites=0,completedWrites=0,failedWrites=0,droppedWrites=0,probe=0;
 if(wps>0)writerTimer=setInterval(()=>{
  const project=projects[totalWrites++%projects.length],wanted=performance.now();
  const chain=(queues.get(project.project)||Promise.resolve()).then(async()=>{
   if(!running||phase==='drain'){droppedWrites++;return;}
   sample('write_schedule_lag_ms',performance.now()-wanted,{datasetId:project.id});
   writer.csrf=(await writer.call('/session/csrf')).data;if(phase==='drain'){droppedWrites++;return;}const old=tasks.get(project.project),cohort=phase,start=performance.now(),id=crypto.randomUUID();
   const r=await writer.call(`/projects/${project.project}/tasks/${old.id}`,'PATCH',{status:old.status==='IN_PROGRESS'?'TODO':'IN_PROGRESS'},{'If-Match':String(old.revision),'X-Request-Id':id});
   const confirmed=performance.now(),confirmedPhase=phase;completedWrites++;tasks.set(project.project,r.data);sample('write_api_ms',confirmed-start,{datasetId:project.id,requestId:id},cohort);
   if(version==='B1'){
    const s=(await writer.call(`/projects/${project.project}/graph/state`,'GET',undefined,{'Cache-Control':'no-cache'})).data;
    if(!acknowledgments.has(project.project))acknowledgments.set(project.project,[]);
    acknowledgments.get(project.project).push({seq:BigInt(s.viewSeq),time:confirmed,id,dataset:project.id,phase:confirmedPhase});
    for(const observer of observers.values())if(observer.project===project.project)recordObserved(observer);
   }
  }).catch(e=>{failedWrites++;faults.push({kind:'write',error:e.message,project:project.project});});queues.set(project.project,chain);
 },1000/wps);
 let probing=false,probeFlight=Promise.resolve();
 if(process.env.OVERVIEW_LOAD_PROBE!=='false')probeTimer=setInterval(()=>{
  if(probing||phase==='drain')return;probing=true;const p=projects[probe++%projectCount];probeFlight=(async()=>{try{
   for(const [kind,endpoint]of [['graph',`/projects/${p.project}/graph${version==='B1'?'?view=overview':''}`],['preview',`/projects/${p.project}/tasks/${p.tasks[5]}/${version==='B1'?'preview':'detail'}`],['search',`/projects/${p.project}/task-search?q=${encodeURIComponent('任务')}&limit=20${version==='B1'?'&projection=overview':''}`],['search_miss',`/projects/${p.project}/task-search?q=workhub-no-such-fixture-title&limit=20${version==='B1'?'&projection=overview':''}`]]){
    const cohort=phase,began=performance.now(),r=await writer.call(endpoint);sample(kind+'_api_ms',performance.now()-began,{datasetId:p.id,requestId:r.requestId},cohort);if(kind==='graph')sample('initial_json_bytes',r.bytes,{datasetId:p.id,requestId:r.requestId},cohort);
   }
  }catch(e){faults.push({kind:'probe',error:e.message});}finally{probing=false;}})();
 },1000);
 await sleep(warmup*1000);phase='sample';phaseEvidence(phase);const sampleStart=performance.now(),writesAtStart=totalWrites,completedAtStart=completedWrites,failedAtStart=failedWrites;console.log('sampling',JSON.stringify({subscribers,projectCount,wps,seconds,origins}));
 await sleep(seconds*1000);clearInterval(writerTimer);clearInterval(probeTimer);
 const elapsed=(performance.now()-sampleStart)/1000,scheduledWrites=totalWrites-writesAtStart,actualWrites=completedWrites-completedAtStart,windowFailedWrites=failedWrites-failedAtStart;
 phase='drain';phaseEvidence(phase);const drainStart=performance.now();await Promise.all([...queues.values(),probeFlight]);
 let lost=0,factMismatches=0,counterMismatches=0;const mismatchDetails=[];
 if(version==='B1'){
  const canonical=new Map();writer.csrf=(await writer.call('/session/csrf')).data;for(const p of projects)canonical.set(p.project,(await writer.call(`/projects/${p.project}/graph/view`,'POST',{nodeIds:snapshots.get(p.project).nodes.map(n=>n.id)})).data);
 const paintTargets=new Map([...acknowledgments].map(([project,entries])=>[project,entries.filter(a=>a.phase==='sample').at(-1)?.seq]));
 const deadline=performance.now()+10000;let pendingPaint=0;
  do{
   lost=watchers.filter(w=>w.seq!==canonical.get(w.project).viewSeq).length;
   for(const observer of browserObservers){const value=await observer.page.evaluate(()=>document.querySelector('#panel')?.overviewState?.().appliedSeq);if(value!==canonical.get(observer.project).viewSeq)lost++;}
   // appliedSeq advances before the two-frame visibility callback. Keep the page
   // alive until acknowledged sample writes have actual visibility reports too.
   pendingPaint=browserObservers.filter((o,i)=>{const target=paintTargets.get(o.project),observed=observers.get(o.project+':collaboration_visible_ms:chromium-'+i);return target!==undefined&&(!observed||observed.seq<target);}).length;
   if(!lost&&!pendingPaint||performance.now()>=deadline)break;await sleep(100);
  }while(running);
 fs.writeFileSync(out+'/observation-drain.json',JSON.stringify({lostCursors:lost,pendingPaint,deadlineExceeded:performance.now()>=deadline,secondsAfterWritesStopped:(performance.now()-drainStart)/1000},null,2));
  const nodeFacts=n=>{const {visibleUpstreamCount,visibleDownstreamCount,...facts}=n;return JSON.stringify(facts);};
  for(const w of watchers){const value=canonical.get(w.project),expected=new Map(value.nodes.map(n=>[n.id,n]));if(w.seq!==value.viewSeq)mismatchDetails.push({kind:'cursor',client:w.index,project:w.project,applied:w.seq,seen:String(w.seen),expected:value.viewSeq,streaming:w.streaming});if(JSON.stringify(w.counts)!==JSON.stringify(value.counts)){counterMismatches++;mismatchDetails.push({kind:'counts',client:w.index,project:w.project,actual:w.counts,expected:value.counts});}for(const id of w.ids)if(!w.nodes.has(id)||!expected.has(id)||nodeFacts(w.nodes.get(id))!==nodeFacts(expected.get(id))){factMismatches++;mismatchDetails.push({kind:'node',client:w.index,project:w.project,id,actual:w.nodes.get(id),expected:expected.get(id)});}}
  for(const observer of browserObservers){
   const value=canonical.get(observer.project);
   const errors=await observer.page.evaluate(nodes=>nodes.filter(n=>document.querySelector(`[data-map-task="${n.id}"] .map-node-status`)?.textContent!==stateName(n.status)).length,value.nodes);
   factMismatches+=errors;
  }
 }
 running=false;
 await browser?.close();browser=undefined;await pool(watchers,20,w=>w.close());
 let expectedObservations=0;for(const [project,entries]of acknowledgments){const count=watchers.filter(w=>w.project===project).length+browserObservers.filter(o=>o.project===project).length;expectedObservations+=entries.filter(a=>a.phase==='sample').length*count;}
 const observedSamples=(metricValues.sync_client_applied_ms?.length||0)+(metricValues.collaboration_visible_ms?.length||0);
 const summary={version,condition:{subscribers,projectCount,browserObservers:browserCount,browserNetwork:browserCount?{latencyMs:50,downloadMbps:10,uploadMbps:5}:null,protocolNetwork:'unshaped loopback HTTP/SSE',loginIpMode,writeWorkflow:'one serial task chain per project: fresh CSRF, PATCH, fresh state acknowledgment',writesPerSecond:wps,scheduledWrites,actualWrites,actualWritesPerSecond:actualWrites/elapsed,writeWorkflowErrors:windowFailedWrites,droppedQueuedWrites:droppedWrites,drainSeconds:(performance.now()-drainStart)/1000,warmupSeconds:warmup,sampleSeconds:seconds,elapsedSampleSeconds:elapsed,reconcileMillis,jvms:origins.length,apiCohort:'request started during sampling',visibleCohort:'write acknowledged during sampling until observer reaches that sequence or newer; includes drain'},observationCoverage:{expected:expectedObservations,observed:observedSamples},transportEvents,mismatchDetails,faults,lostCursors:lost,factMismatches,counterMismatches,groups:{}};
 for(const [metric,a]of Object.entries(metricValues)){a.sort((x,y)=>x-y);const p=n=>a[Math.min(a.length-1,Math.ceil(a.length*n)-1)];summary.groups[metric]={n:a.length,p50:p(.5),p95:p(.95),p99:p(.99)};}
 fs.writeFileSync(out+'/summary.json',JSON.stringify(summary,null,2));phaseEvidence('complete');console.log('complete',JSON.stringify(summary.condition),'faults',faults.length,'lost',lost);
 if(faults.length||lost||factMismatches||counterMismatches||observedSamples!==expectedObservations)process.exitCode=1;
})().catch(e=>{faults.push({kind:'driver',error:e.stack});console.error(e);fs.writeFileSync(out+'/failure.json',JSON.stringify({faults},null,2));process.exitCode=1;}).finally(async()=>{running=false;clearInterval(writerTimer);clearInterval(probeTimer);clearInterval(resourceTimer);driverLag.disable();await browser?.close();await pool(watchers,20,w=>w.close());log.end();});
