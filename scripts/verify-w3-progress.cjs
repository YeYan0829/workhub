// Isolated Spring test server only. Synthetic DB stage driver is NOT a model-quality test.
const path=require('node:path'),fs=require('node:fs'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const [port,login,project,initialRun,out,round,member]=process.argv.slice(2);assert.match(login,/^w3-/);assert.notEqual(port,'18081');
let base,failEvents=0,transient503=0;const events=[],samples=[],recoveries=[],sockets=new Set();
const http=require('node:http');
const proxy=http.createServer((req,res)=>{
 if(req.url.endsWith('/events')&&failEvents>0){failEvents--;transient503++;res.writeHead(503);res.end();return;}
 const upstream=http.request({hostname:'127.0.0.1',port,path:req.url,method:req.method,headers:req.headers},remote=>{res.writeHead(remote.statusCode,remote.headers);remote.pipe(res);});
 const stream={upstream,res};if(req.url.endsWith('/events'))sockets.add(stream);
 res.on('close',()=>{sockets.delete(stream);upstream.destroy();});
 upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502);res.end();});req.pipe(upstream);
});
(async()=>{
 await new Promise(resolve=>proxy.listen(0,'127.0.0.1',resolve));base=`http://127.0.0.1:${proxy.address().port}`;
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});let page;
 try{
  const context=await browser.newContext({viewport:{width:1280,height:950}});page=await context.newPage();
  await page.exposeFunction('captureSnapshot',s=>events.push(s));
  await page.addInitScript(()=>{
   const Native=window.EventSource;
   window.EventSource=class extends Native {constructor(...args){super(...args);(window.fixtureStreams ||= []).push(this);this.addEventListener('snapshot',e=>{const s=JSON.parse(e.data);requestAnimationFrame(()=>{window.lastProgress={...s,appliedAt:Date.now(),text:document.querySelector('#agent-answer')?.textContent};window.captureSnapshot(window.lastProgress);});});}};
  });
  await page.goto(base+'/#login');await page.locator('#login [name=username]').fill(login);await page.locator('#login [name=password]').fill('SyntheticW3!');await page.locator('#login button').click();await page.waitForURL('**/#mine');
  async function post(url,body={}){const token=await (await context.request.get(base+'/session/csrf')).json();const response=await context.request.post(base+url,{data:body,headers:{[token.headerName]:token.token}});assert.equal(response.status(),200,await response.text());return response.json();}
  async function stage(id,body){return post(`/projects/${project}/w3-fixture/${id}/stage`,body);}
  const other=await browser.newContext();
  const otherCsrf=await (await other.request.get(base+'/session/csrf')).json();
  assert.equal((await other.request.post(base+'/session/login',{form:{username:'w3-'+member,password:'SyntheticW3!'},headers:{[otherCsrf.headerName]:otherCsrf.token}})).status(),204);
  const denied=await other.request.get(`${base}/projects/${project}/agent/runs/${initialRun}/events`,{headers:{Accept:'text/event-stream'}});
  assert.equal(denied.status(),404);assert.equal(await denied.text(),'');await other.close();
  const missingScope=await context.request.get(`${base}/projects/00000000-0000-0000-0000-000000000001/agent/runs/${initialRun}/events`,{headers:{Accept:'text/event-stream'}});
  assert.equal(missingScope.status(),403);assert.equal(await missingScope.text(),'');
  await page.goto(`${base}/#project/${project}/agent?run=${initialRun}`);
  const count=round==='1'?100:5;
  for(let i=0;i<count;i++){
   const reason=i%2?'COOLDOWN':'RATE',record=await stage(initialRun,{kind:'MODEL_WAIT',reason});
   await page.waitForFunction(seq=>window.lastProgress?.sequence===seq,record.sequence,{timeout:3000});
   const applied=await page.evaluate(()=>window.lastProgress);assert.match(applied.text,reason==='RATE'?/共享模型请求名额暂满/:/供应商冷却/);
   assert.ok(!JSON.stringify(applied).includes('DO_NOT_EXPOSE'));
   samples.push({...record,appliedAt:applied.appliedAt,latencyMillis:applied.appliedAt-record.committedAt});
  }
  const beforeOld=await page.locator('#agent-answer').innerText();
  await page.evaluate(id=>window.fixtureStreams.at(-1).dispatchEvent(new MessageEvent('snapshot',{data:JSON.stringify({id,sequence:0,status:'QUEUED',stage:'QUEUED'})})),initialRun);
  assert.equal(await page.locator('#agent-answer').innerText(),beforeOld);
  // Each independent browser repetition includes a 5 second outage and an authoritative refresh.
  await context.setOffline(true);
  assert.ok(sockets.size>0,'a live SSE transport exists before the outage');
  for(const s of sockets){s.res.destroy();s.upstream.destroy();}sockets.clear();
  await new Promise(r=>setTimeout(r,5000));
  const changed=await stage(initialRun,{kind:'TOOL_ATTEMPT'});
  assert.notEqual(await page.evaluate(()=>window.lastProgress?.sequence),changed.sequence,'new state cannot arrive on a closed transport');
  failEvents=1;const onlineAt=Date.now();await context.setOffline(false);
  await page.waitForFunction(seq=>window.lastProgress?.sequence===seq,changed.sequence,{timeout:3000});
  recoveries.push({kind:'offline5s',millis:Date.now()-onlineAt});
  const refreshedAt=Date.now();await page.reload();await page.waitForFunction(seq=>window.lastProgress?.sequence===seq,changed.sequence,{timeout:3000});recoveries.push({kind:'refresh',millis:Date.now()-refreshedAt});
  assert.ok(page.url().includes('run='+initialRun));
  // Lost POST response: persist both key and body through a reload, then recover the same run.
  let createdId,keys=[],bodies=[];
  const pattern=`**/projects/${project}/agent/runs`;
  await page.route(pattern,async route=>{keys.push(route.request().headers()['idempotency-key']);bodies.push(route.request().postDataJSON());const r=await route.fetch();assert.equal(r.status(),202);const value=await r.json();createdId=value.id;await route.abort('failed');});
  await page.locator('#agent-question [name=question]').fill('合成丢响应验证问题');await page.locator('#agent-question button').click();
  await page.waitForFunction(()=>document.querySelector('#agent-question button')?.textContent==='重试确认上次提问');
  // Wait for the intercepted response before detaching the fault injector.
  await page.waitForTimeout(500);assert.ok(createdId);await page.unroute(pattern);
  await page.reload();await page.getByRole('button',{name:'重试确认上次提问'}).waitFor();
  page.on('request',r=>{if(r.url()===base+`/projects/${project}/agent/runs`&&r.method()==='POST'){keys.push(r.headers()['idempotency-key']);bodies.push(r.postDataJSON());}});
  await page.getByRole('button',{name:'重试确认上次提问'}).click();await page.waitForFunction(id=>new URLSearchParams(location.hash.split('?')[1]).get('run')===id,createdId);
  assert.equal(keys.length,2);assert.equal(keys[0],keys[1]);assert.deepEqual(bodies[0],bodies[1]);
  // UI reason mappings are driven by synthetic committed states, not fresh Redis/model faults.
  const reasons=[];
  for(const [status,errorCode,expected] of [['FAILED','MODEL_COST_BUDGET','费用额度不足'],['FAILED','MODEL_CIRCUIT_OPEN','已暂停调用'],['INCOMPLETE',null,'证据不足']]){
   await stage(createdId,{status,...(errorCode?{errorCode}:{}),kind:'ERROR'});await page.reload();await page.waitForFunction(expected=>document.querySelector('#agent-answer')?.textContent.includes(expected),expected);
   reasons.push({status,errorCode,text:await page.locator('#agent-answer').innerText()});
  }
  await stage(initialRun,{kind:'MODEL_WAIT',reason:'RATE'});await page.goto(`${base}/#project/${project}/agent?run=${initialRun}`);await page.waitForFunction(id=>window.lastProgress?.id===id,initialRun);
  await page.screenshot({path:path.join(out,'waiting-desktop.png'),fullPage:true});await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+2),false);await page.screenshot({path:path.join(out,'waiting-mobile.png'),fullPage:true});
  const revoked=await post(`/projects/${project}/w3-fixture/revoke`);await page.waitForTimeout(1800);
  const after=events.filter(x=>x.appliedAt>revoked.at+100);assert.equal(after.length,0,'no content after revoked permission observed');
  const sorted=samples.map(x=>x.latencyMillis).sort((a,b)=>a-b);const p95=sorted[Math.ceil(sorted.length*.95)-1];assert.ok(p95<=2000);assert.ok(recoveries.every(x=>x.millis<=3000));
  fs.writeFileSync(path.join(out,'browser.json'),JSON.stringify({round:Number(round),modelCalls:0,transient503,visibility:{nonOwnerQaStatus:denied.status(),otherProjectStatus:missingScope.status()},stageDriver:'synthetic committed metadata; no quality or Redis integration claim',samples,p50:sorted[Math.ceil(sorted.length*.5)-1],p95,max:sorted.at(-1),recoveries,reasons,createRequests:keys.length,uniqueKeys:new Set(keys).size,revocation:{at:revoked.at,eventsAfter100ms:after.length},events},null,2));
  console.log(JSON.stringify({ok:true,n:samples.length,p95,recoveries}));
 }catch(e){if(page){await page.screenshot({path:path.join(out,'failed.png'),fullPage:true});fs.writeFileSync(path.join(out,'failed.json'),JSON.stringify({error:e.stack,url:page.url(),body:await page.locator('body').innerText(),events},null,2));}throw e;}
 finally{await browser.close();proxy.closeAllConnections();await new Promise(resolve=>proxy.close(resolve));}
})().catch(e=>{console.error(e);process.exitCode=1;});
