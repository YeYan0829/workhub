const fs=require('fs'),{performance}=require('perf_hooks'),zlib=require('zlib');
// Operates on an explicitly supplied isolated fixture; never provisions or deletes data.
const seed=JSON.parse(fs.readFileSync(process.env.OVERVIEW_BENCH_SEED,'utf8'));
const out=process.env.OVERVIEW_BENCH_OUTPUT,version=process.env.OVERVIEW_BENCH_VERSION||'B0',base=process.env.OVERVIEW_BENCH_BASE||seed.base;
if(!['127.0.0.1','localhost'].includes(new URL(base).hostname))throw Error('Loopback benchmark fixture required');
const sampleCount=+(process.env.OVERVIEW_BENCH_SAMPLES||40);
const samples=[];let cookie='',benchmarkBrowser;
const writeSamples=process.env.OVERVIEW_BENCH_WRITE==='true';
async function req(path,method='GET',body,headers={}){
 const requestId=headers['X-Request-Id']||crypto.randomUUID();
 const r=await fetch(base+path,{method,headers:{Cookie:cookie,'X-Request-Id':requestId,...headers},body,redirect:'manual',signal:AbortSignal.timeout(20000)});
 const c=r.headers.getSetCookie();if(c.length)cookie=c.map(v=>v.split(';')[0]).join('; ');
 const text=await r.text();if(!r.ok)throw Error(r.status+' '+path+' '+text.slice(0,300));return {text,status:r.status,headers:r.headers,requestId};
}
(async()=>{
 const csrf=JSON.parse((await req('/session/csrf')).text);
 await req('/session/login','POST',new URLSearchParams({username:seed.user,password:seed.password}).toString(),{'Content-Type':'application/x-www-form-urlencoded',[csrf.headerName]:csrf.token});
 fs.mkdirSync(out,{recursive:true});
 const mutationCsrf=JSON.parse((await req('/session/csrf')).text);
 const mutate=(endpoint,method,body,headers={})=>req(endpoint,method,JSON.stringify(body),{'Content-Type':'application/json',[mutationCsrf.headerName]:mutationCsrf.token,'Idempotency-Key':crypto.randomUUID(),...headers});
 for(const d of seed.datasets){
  for(const kind of ((process.env.OVERVIEW_BENCH_ONLY_WRITES==='true'||process.env.OVERVIEW_BENCH_ONLY_BROWSER==='true')?[]:['graph','preview','search','search_miss'])){
   const path=kind==='graph'?`/projects/${d.project}/graph${version==='B1'?'?view=overview':''}`:kind==='preview'?`/projects/${d.project}/tasks/${d.tasks[5]}/${version==='B1'?'preview':'detail'}`:`/projects/${d.project}/task-search?q=${encodeURIComponent(kind==='search_miss'?'workhub-no-such-fixture-title':'任务')}&limit=20${version==='B1'?'&projection=overview':''}`;
   for(let i=-5;i<sampleCount;i++){
    const t=performance.now(),r=await req(path),ms=performance.now()-t;
    if(i===-5)samples.push({version,datasetId:d.id,scenarioId:kind+'-first-hit',metricId:kind+'_first_hit_ms',sampleValue:ms,unit:'ms',requestId:r.requestId,timestampUtc:new Date().toISOString()});
    if(i>=0){samples.push({version,datasetId:d.id,scenarioId:kind,metricId:kind+'_api_ms',sampleValue:ms,unit:'ms',requestId:r.requestId,timestampUtc:new Date().toISOString()});
     if(kind==='graph')samples.push({version,datasetId:d.id,scenarioId:kind,metricId:'initial_json_bytes',sampleValue:Buffer.byteLength(r.text),unit:'bytes',gzipBytes:zlib.gzipSync(r.text).length,timestampUtc:new Date().toISOString()});
    }
    if(i===0)fs.writeFileSync(`${out}/${d.id}-${kind}.json`,r.text);
   }
  }
  if(writeSamples&&['S1','S2','S3'].includes(d.id)){
   const snapshot=JSON.parse((await req(`/projects/${d.project}/graph${version==='B1'?'?view=overview':''}`)).text);
   const id=snapshot.nodes[0].id,nodeIds=snapshot.nodes.map(n=>n.id);let cursor=snapshot.viewSeq;
   let task=JSON.parse((await req(`/projects/${d.project}/tasks/${id}`)).text);
   for(let i=-5;i<sampleCount;i++){
    const start=performance.now(),write=await mutate(`/projects/${d.project}/tasks/${id}`,'PATCH',{priority:task.priority===0?1:0},{'If-Match':String(task.revision)});task=JSON.parse(write.text);
    if(i>=0)samples.push({version,datasetId:d.id,scenarioId:'write',metricId:'write_api_ms',sampleValue:performance.now()-start,unit:'ms',requestId:write.requestId,timestampUtc:new Date().toISOString()});
    if(version==='B1'){
     const began=performance.now(),delta=await mutate(`/projects/${d.project}/graph/sync`,'POST',{afterSeq:cursor,nodeIds,selectedTaskId:id});cursor=JSON.parse(delta.text).toSeq;
     if(i>=0){samples.push({version,datasetId:d.id,scenarioId:'sync',metricId:'sync_api_ms',sampleValue:performance.now()-began,unit:'ms',requestId:delta.requestId,timestampUtc:new Date().toISOString()});samples.push({version,datasetId:d.id,scenarioId:'sync',metricId:'normal_sync_json_bytes',sampleValue:Buffer.byteLength(delta.text),unit:'bytes',timestampUtc:new Date().toISOString()});}
    }
   }
  }
  fs.writeFileSync(out+'/samples.jsonl',samples.map(s=>JSON.stringify(s)).join('\n')+'\n');process.stdout.write(d.id+' sampled\n');
 }
 if(process.env.OVERVIEW_BENCH_BROWSER==='true'){
  const {chromium}=require('playwright');const browser=benchmarkBrowser=await chromium.launch({headless:true,args:['--no-sandbox']});
  for(const mobile of [false,true]){
   const context=await browser.newContext({viewport:mobile?{width:390,height:844}:{width:1440,height:1000}});
   const first=cookie.split(';')[0],split=first.indexOf('=');await context.addCookies([{name:first.slice(0,split),value:first.slice(split+1),url:base}]);
   const page=await context.newPage();const cdp=await context.newCDPSession(page);
   await cdp.send('Network.enable');await cdp.send('Network.emulateNetworkConditions',{offline:false,latency:50,downloadThroughput:10e6/8,uploadThroughput:5e6/8});
   if(mobile)await cdp.send('Emulation.setCPUThrottlingRate',{rate:4});
   for(const d of seed.datasets.filter(d=>['S1','S2','S3'].includes(d.id))){
    for(let i=-2;i<+(process.env.OVERVIEW_BENCH_BROWSER_SAMPLES||15);i++){
     await page.goto(base+'/#mine');await page.locator('#personal-filter').waitFor();await page.waitForFunction(()=>!routing);
     await page.evaluate(route=>{
      window.graphEnter=performance.now();window.graphReady=null;window.selectionFrame=null;
      const obs=new MutationObserver(()=>{if(document.querySelector('[data-map-task]')){obs.disconnect();requestAnimationFrame(()=>{window.graphReady=performance.now()-window.graphEnter;});}});
      obs.observe(document.querySelector('#app')||document.body,{subtree:true,childList:true});
      location.hash=route;
     },'project/'+d.project+'/overview');
     await page.waitForFunction(()=>window.graphReady!==null);
     const interactive=await page.evaluate(()=>window.graphReady);
     await page.evaluate(()=>{document.querySelector('[data-map-task]').addEventListener('click',()=>{const t=performance.now();requestAnimationFrame(()=>{window.selectionFrame=performance.now()-t;});},{capture:true,once:true});});
     await page.locator('[data-map-task]').first().focus();await page.keyboard.press('Enter');await page.waitForFunction(()=>window.selectionFrame!==null);
     const click=await page.evaluate(()=>window.selectionFrame);
     if(i>=0){for(const [metricId,sampleValue]of [[(mobile?'mobile':'desktop')+'_graph_interactive_ms',interactive],[(mobile?'mobile':'desktop')+'_selection_frame_ms',click]])samples.push({version,datasetId:d.id,scenarioId:'browser-'+(mobile?'mobile':'desktop'),metricId,sampleValue,unit:'ms',timestampUtc:new Date().toISOString()});}
    }
    fs.writeFileSync(out+'/samples.jsonl',samples.map(s=>JSON.stringify(s)).join('\n')+'\n');console.log(d.id,mobile?'mobile':'desktop','browser sampled');
   }
   await context.close();
  }
  await browser.close();fs.writeFileSync(out+'/samples.jsonl',samples.map(s=>JSON.stringify(s)).join('\n')+'\n');
 }
 const groups={};for(const s of samples)(groups[s.datasetId+':'+s.metricId]??=[]).push(s.sampleValue);
 const summary={version,condition:{sampleCount,warmupRequests:5,transport:'loopback HTTP, sequential smoke',targetLoad:false},groups:{}};
 for(const [key,a] of Object.entries(groups)){a.sort((x,y)=>x-y);const p=n=>a[Math.min(a.length-1,Math.ceil(a.length*n)-1)];summary.groups[key]={n:a.length,p50:p(.5),p95:p(.95),p99:p(.99)};}
 fs.writeFileSync(out+'/summary.json',JSON.stringify(summary,null,2));
})().catch(e=>{fs.mkdirSync(out,{recursive:true});fs.writeFileSync(out+'/samples.jsonl',samples.map(s=>JSON.stringify(s)).join('\n')+'\n');fs.writeFileSync(out+'/failure.json',JSON.stringify({error:e.stack,version},null,2));console.error(e);process.exitCode=1}).finally(async()=>{await benchmarkBrowser?.close();});
