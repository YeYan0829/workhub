// Loaded entry/selection measurements. 99 established protocol viewers plus
// the joining real browser make 100; every measurement stays in the sample window.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {spawn}=require('node:child_process'),{performance}=require('node:perf_hooks');
const {chromium}=require('playwright');
const seed=JSON.parse(fs.readFileSync(process.env.OVERVIEW_BENCH_SEED,'utf8'));
const origins=(process.env.OVERVIEW_LOAD_ORIGINS||'').split(',');
assert(origins.length===1||origins.length===2);assert.equal(new Set(origins).size,origins.length);
for(const origin of origins){const u=new URL(origin);assert.equal(u.protocol,'http:');assert(['127.0.0.1','localhost'].includes(u.hostname));}
const origin=origins.at(-1),out=process.env.OVERVIEW_REALTIME_OUTPUT;assert(out);fs.mkdirSync(out,{recursive:true});
const backgroundOut=path.join(out,'background'),samples=[],errors=[];
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const datasets=seed.datasets.filter(d=>['S1','S2','S3'].includes(d.id));assert.equal(datasets.length,3);
const combinations=[false,true].flatMap(mobile=>datasets.map(dataset=>({mobile,dataset})));
let child,childExit,browser,context,page,cdp;
function evidence(name,value){fs.writeFileSync(path.join(out,name),JSON.stringify(value,null,2));}
function requireSampling(){assert.equal(childExit,undefined,'Background finished during a browser sample');assert.equal(JSON.parse(fs.readFileSync(path.join(backgroundOut,'phase.json'),'utf8')).phase,'sample');}
async function closeOwned(){
 const opened=await page.evaluate(()=>window.entrySources.filter(s=>s.readyState!==EventSource.CLOSED).map(s=>{const url=new URL(s.url);return {project:url.pathname.split('/')[2],id:url.searchParams.get('connectionId')};}));
 await page.goto(origin+'/#mine');await page.locator('#personal-filter').waitFor();await page.waitForFunction(()=>!routing);
 for(const connection of opened){
  const csrf=await(await context.request.get(origin+'/session/csrf')).json();
  const closed=await context.request.delete(origin+`/projects/${connection.project}/graph/events?connectionId=${connection.id}`,{headers:{[csrf.headerName]:csrf.token}});assert(closed.ok());
 }
 await page.evaluate(()=>{window.entrySources=window.entrySources.filter(source=>source.readyState!==EventSource.CLOSED);});
}
async function measure(combination,index,record){
 const {mobile,dataset}=combination;
 if(record)requireSampling();
 await closeOwned();await page.setViewportSize(mobile?{width:390,height:844}:{width:1440,height:1000});
 await cdp.send('Emulation.setCPUThrottlingRate',{rate:mobile?4:1});
 // Same clock and first-frame definition as the B0/B1 sequential comparison.
 await page.evaluate(route=>{
  window.graphEnter=performance.now();window.graphReady=null;window.selectionFrame=null;
  const observer=new MutationObserver(()=>{if(document.querySelector('[data-map-task]')){observer.disconnect();requestAnimationFrame(()=>{window.graphReady=performance.now()-window.graphEnter;});}});
  observer.observe(document.querySelector('#app')||document.body,{subtree:true,childList:true});location.hash=route;
 },'project/'+dataset.project+'/overview');
 await page.waitForFunction(()=>window.graphReady!==null);
 const interactive=await page.evaluate(()=>window.graphReady);
 await page.evaluate(()=>{document.querySelector('[data-map-task]').addEventListener('click',()=>{const start=performance.now();requestAnimationFrame(()=>{window.selectionFrame=performance.now()-start;});},{capture:true,once:true});});
 await page.locator('[data-map-task]').first().focus();await page.keyboard.press('Enter');await page.waitForFunction(()=>window.selectionFrame!==null);
 const selection=await page.evaluate(()=>window.selectionFrame);
 const view=await page.evaluate(()=>document.querySelector('#panel').overviewState());assert(view.nodes.length<=50);assert(view.selected);
 if(mobile)assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+2),false);
 // Finish outside the timed entry measurement, with a registered stream, so
 // the following route can explicitly release exactly its own connection.
 await page.waitForFunction(()=>window.entrySources.at(-1)?.readyState===EventSource.OPEN);
 if(record){
  requireSampling();const prefix=mobile?'mobile':'desktop';
  for(const [metric,value]of [['graph_interactive_ms',interactive],['selection_frame_ms',selection]])samples.push({datasetId:dataset.id,metricId:prefix+'_'+metric,sampleValue:value,unit:'ms',sampleIndex:index,project:dataset.project,appliedSeq:view.appliedSeq,timestampUtc:new Date().toISOString()});
  evidence('progress.json',{samples,errors,atUtc:new Date().toISOString()});
  if(index===0)await page.screenshot({path:path.join(out,dataset.id+'-'+prefix+'.png'),fullPage:true});
 }
 await closeOwned();
}
function quantiles(values){values.sort((a,b)=>a-b);return {n:values.length,minimum:values[0],p50:values[Math.ceil(values.length*.5)-1],p95:values[Math.ceil(values.length*.95)-1],p99:values[Math.ceil(values.length*.99)-1],maximum:values.at(-1)};}
(async()=>{
 const fd=fs.openSync(path.join(out,'background.log'),'a');
 child=spawn(process.execPath,[path.join(__dirname,'load-project-overview.cjs')],{env:{...process.env,OVERVIEW_BENCH_OUTPUT:backgroundOut,OVERVIEW_BENCH_VERSION:'B1',OVERVIEW_LOAD_SUBSCRIBERS:'99',OVERVIEW_LOAD_PROJECTS:'10',OVERVIEW_LOAD_WRITES:'5',OVERVIEW_LOAD_BROWSER:'false',OVERVIEW_LOAD_PROBE:'true',OVERVIEW_LOAD_WARMUP_SECONDS:'120',OVERVIEW_LOAD_SECONDS:'600'},stdio:['ignore',fd,fd]});fs.closeSync(fd);
 const finished=new Promise(resolve=>{child.once('exit',(code,signal)=>{childExit={code,signal};resolve(childExit);});child.once('error',error=>{childExit={error:error.message};resolve(childExit);});});
 browser=await chromium.launch({headless:true,args:['--no-sandbox']});context=await browser.newContext({viewport:{width:1440,height:1000}});
 await context.addInitScript(()=>{const Native=EventSource;window.entrySources=[];window.EventSource=class extends Native{constructor(url,options){super(url,options);if(String(url).includes('/graph/events'))window.entrySources.push(this);}};});
 page=await context.newPage();page.setDefaultTimeout(20000);page.on('pageerror',error=>errors.push(error.message));
 cdp=await context.newCDPSession(page);await cdp.send('Network.enable');await cdp.send('Network.emulateNetworkConditions',{offline:false,latency:50,downloadThroughput:10e6/8,uploadThroughput:5e6/8});
 await page.goto(origin+'/#login');await page.locator('#login [name=username]').fill(seed.member);await page.locator('#login [name=password]').fill(seed.password);await page.locator('#login button').click();await page.locator('#personal-filter').waitFor();
 while(!fs.existsSync(path.join(backgroundOut,'workload.json'))){assert.equal(childExit,undefined);await sleep(100);}
 for(let warmup=0;warmup<2;warmup++)for(const combination of combinations)await measure(combination,warmup,false);
 while(JSON.parse(fs.readFileSync(path.join(backgroundOut,'phase.json'),'utf8')).phase!=='sample'){assert.equal(childExit,undefined);await sleep(100);}
 const began=performance.now();
 for(let index=0;index<90;index++){
  await sleep(Math.max(0,began+index*6000-performance.now()));
  await measure(combinations[index%6],Math.floor(index/6),true);
 }
 assert.deepEqual(errors,[]);await closeOwned();await context.close();context=undefined;await browser.close();browser=undefined;
 const exit=await finished;assert.equal(exit.code,0,JSON.stringify(exit));const background=JSON.parse(fs.readFileSync(path.join(backgroundOut,'summary.json'),'utf8'));
 for(const key of ['lostCursors','factMismatches','counterMismatches'])assert.equal(background[key],0,key);assert.deepEqual(background.faults,[]);assert.equal(background.observationCoverage.observed,background.observationCoverage.expected);
 const grouped={};for(const sample of samples)(grouped[sample.datasetId+':'+sample.metricId]??=[]).push(sample.sampleValue);
 const groups=Object.fromEntries(Object.entries(grouped).map(([key,values])=>[key,quantiles(values)]));
 assert.equal(Object.keys(groups).length,12);assert(Object.values(groups).every(group=>group.n===15));
 const limits={desktop_graph_interactive_ms:1500,mobile_graph_interactive_ms:2500,desktop_selection_frame_ms:50,mobile_selection_frame_ms:150};
 const breaches=Object.entries(groups).filter(([key,value])=>value.p95>limits[key.split(':')[1]]).map(([key,value])=>({metric:key,p95:value.p95,limit:limits[key.split(':')[1]]}));
 evidence('result.json',{status:'PASS',condition:{establishedProtocolViewers:99,joiningBrowser:1,totalWithBrowser:100,projects:10,writesPerSecond:5,jvms:origins.length,warmupSeconds:120,sampleSeconds:600,entrySamplesPerDatasetViewport:15,browserWarmupPerDatasetViewport:2,browserNetwork:{latencyMs:50,downloadMbps:10,uploadMbps:5},mobileCpuSlowdown:4,entryCadenceSeconds:6,selection:'capture-phase activation event to next animation frame',entry:'authenticated route change to first graph frame'},groups,samples,errors,breaches,thresholdsMet:breaches.length===0,backgroundCondition:background.condition,backgroundObservationCoverage:background.observationCoverage});
 console.log('complete',JSON.stringify({groups,breaches}));if(breaches.length)process.exitCode=1;
})().catch(async error=>{await page?.screenshot({path:path.join(out,'failure.png'),fullPage:true}).catch(()=>{});evidence('result.json',{status:'FAIL',samples,errors,error:error.stack});console.error(error);process.exitCode=1;}).finally(async()=>{await context?.close();await browser?.close();if(child&&childExit===undefined){child.kill('SIGTERM');await sleep(1000);if(childExit===undefined)child.kill('SIGKILL');}});
