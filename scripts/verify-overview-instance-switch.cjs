// Real browser, streaming loopback proxy and two independently running application JVMs.
const assert=require('node:assert/strict'),fs=require('node:fs'),http=require('node:http'),crypto=require('node:crypto');
const {performance}=require('node:perf_hooks'),{chromium}=require('playwright');
const seed=JSON.parse(fs.readFileSync(process.env.OVERVIEW_BENCH_SEED,'utf8'));
const first=process.env.OVERVIEW_BENCH_BASE,second=process.env.OVERVIEW_SECOND_BASE,out=process.env.OVERVIEW_REALTIME_OUTPUT;
for(const origin of [first,second])assert(new URL(origin).protocol==='http:'&&['127.0.0.1','localhost'].includes(new URL(origin).hostname));
assert.notEqual(first,second);fs.mkdirSync(out,{recursive:true});
const story=seed.datasets.find(d=>d.id==='S0'),project=story.project,task=story.tasks[2],selected=story.tasks[5];
let destination=second,browser;const streams=new Set(),connections=[],results=[],errors=[];
const proxy=http.createServer((request,response)=>{
 const streaming=request.method==='GET'&&new URL(request.url,second).pathname.endsWith('/graph/events');
 const target=streaming?destination:second;
 const upstream=http.request(target+request.url,{method:request.method,headers:request.headers},reply=>{response.writeHead(reply.statusCode,reply.headers);reply.pipe(response);});
 const stream={upstream,response};
 if(streaming){streams.add(stream);connections.push({backend:target,after:new URL(request.url,second).searchParams.get('after'),atUtc:new Date().toISOString()});}
 response.on('close',()=>{streams.delete(stream);if(!response.writableEnded)upstream.destroy();});
 upstream.on('error',error=>{if(response.destroyed)return;if(!response.headersSent)response.writeHead(502);response.end(error.message);});
 request.pipe(upstream);
});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function login(page,origin,user){await page.goto(origin+'/#login');await page.locator('#login [name=username]').fill(user);await page.locator('#login [name=password]').fill(seed.password);await page.locator('#login button').click();await page.locator('#personal-filter').waitFor();}
async function api(context,origin,endpoint,method='GET',data,headers={}){
 const token=await(await context.request.get(origin+'/session/csrf')).json();
 const response=await context.request.fetch(origin+endpoint,{method,data,headers:{...(method==='GET'?{'Cache-Control':'no-cache'}:{[token.headerName]:token.token,'Idempotency-Key':crypto.randomUUID()}),...headers}});
 assert(response.ok(),`${method} ${endpoint}: ${response.status()} ${await response.text()}`);return response.json();
}
const state=page=>page.evaluate(()=>document.querySelector('#panel')?.overviewState?.());
(async()=>{
 await new Promise(resolve=>proxy.listen(0,'127.0.0.1',resolve));const origin='http://127.0.0.1:'+proxy.address().port;
 browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 for(let repetition=1;repetition<=3;repetition++){
  destination=second;
  const writer=await browser.newContext(),observer=await browser.newContext({viewport:{width:1440,height:1000}});
  const w=await writer.newPage(),page=await observer.newPage();page.on('pageerror',e=>errors.push(e.message));
  try{
   await login(w,first,seed.user);await login(page,origin,seed.member);
   const original=await api(writer,first,`/projects/${project}/tasks/${task}`);
   await page.goto(origin+`/#project/${project}/overview`);await page.locator(`[data-map-task="${selected}"]`).click();await page.locator('#map-inspector .map-detail-links').waitFor();await page.waitForFunction(()=>!routing);
   await page.getByText(/^已同步/).waitFor();await page.locator('#map-plus').click();
   const before=await state(page),priorConnections=connections.length;
   destination=first;const switchedAt=performance.now();for(const stream of [...streams]){stream.response.destroy();stream.upstream.destroy();}
   let current=await api(writer,second,`/projects/${project}/tasks/${task}`);
   current=await api(writer,second,`/projects/${project}/tasks/${task}`,'PATCH',{priority:current.priority===1?2:1},{'If-Match':String(current.revision)});
   const deadline=performance.now()+10000;
   while(!connections.slice(priorConnections).some(c=>c.backend===first)){assert(performance.now()<deadline,'SSE did not switch to first JVM');await sleep(50);}
   current=await api(writer,second,`/projects/${project}/tasks/${task}`,'PATCH',{status:current.status==='DONE'?'TODO':'DONE'},{'If-Match':String(current.revision)});
   const latest=await api(writer,second,`/projects/${project}/graph/state`);
   await page.waitForFunction(seq=>BigInt(document.querySelector('#panel')?.overviewState?.().appliedSeq||0)>=BigInt(seq),latest.viewSeq,{timeout:10000});
   await page.waitForFunction(({task,status})=>document.querySelector(`[data-map-task="${task}"] .map-node-status`)?.textContent===stateName(status),{task,status:current.status});
   await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
   const after=await state(page);assert.equal(after.selected,before.selected);assert.equal(after.scale,before.scale);assert.equal(after.metrics.layouts,before.metrics.layouts);assert.deepEqual(after.nodes,before.nodes);
   assert(BigInt(after.appliedSeq)>=BigInt(before.appliedSeq));
   results.push({repetition,metricId:'instance_switch_visible_ms',sampleValue:performance.now()-switchedAt,unit:'ms',before,after,connections:connections.slice(priorConnections)});
   await page.screenshot({path:out+`/switched-${repetition}.png`,fullPage:true});
   current=await api(writer,first,`/projects/${project}/tasks/${task}`);if(current.status!==original.status||current.priority!==original.priority)await api(writer,first,`/projects/${project}/tasks/${task}`,'PATCH',{status:original.status,priority:original.priority},{'If-Match':String(current.revision)});
  }finally{await observer.close();await writer.close();}
 }
 assert.deepEqual(errors,[]);fs.writeFileSync(out+'/result.json',JSON.stringify({status:'PASS',checks:['LIVE-07-instance-switch'],results,errors},null,2));console.log('PASS LIVE-07 actual SSE upstream switch, three repetitions');
})().catch(e=>{fs.writeFileSync(out+'/result.json',JSON.stringify({status:'FAIL',error:e.stack,results,errors},null,2));console.error(e);process.exitCode=1;}).finally(async()=>{await browser?.close();for(const stream of streams){stream.response.destroy();stream.upstream.destroy();}proxy.closeAllConnections();await new Promise(resolve=>proxy.close(resolve));http.globalAgent.destroy();});
