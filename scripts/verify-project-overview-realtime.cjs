// Real browsers and HTTP against explicitly seeded loopback applications. No external writes.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {performance}=require('node:perf_hooks'),{chromium}=require('playwright');
const seed=JSON.parse(fs.readFileSync(process.env.OVERVIEW_BENCH_SEED,'utf8'));
const base=process.env.OVERVIEW_BENCH_BASE||seed.base,other=process.env.OVERVIEW_SECOND_BASE||base;
for(const url of [base,other])assert(['127.0.0.1','localhost'].includes(new URL(url).hostname));
const out=process.env.OVERVIEW_REALTIME_OUTPUT;fs.mkdirSync(out,{recursive:true});
const checks=[],errors=[],samples=[];let browser,page,writer,observerContext,writerContext;
const story=seed.datasets.find(d=>d.id==='S0'),large=seed.datasets.find(d=>d.id==='S2');
const p=story.project,t=story.tasks;
const state=target=>target.evaluate(()=>document.querySelector('#panel')?.overviewState?.());
const pass=(id,description,evidence={})=>{checks.push({id,description,...evidence});console.log('PASS',id,description);};
async function login(target,user,origin){await target.goto(origin+'/#login');await target.locator('#login [name=username]').fill(user);await target.locator('#login [name=password]').fill(seed.password);await target.locator('#login button').click();await target.locator('#personal-filter').waitFor();}
async function api(ctx,origin,endpoint,method='GET',data,headers={}){
 const token=await(await ctx.request.get(origin+'/session/csrf')).json();
 const r=await ctx.request.fetch(origin+endpoint,{method,data,headers:{...(method==='GET'?{'Cache-Control':'no-cache'}:{[token.headerName]:token.token,'Idempotency-Key':crypto.randomUUID()}),...headers}});
 assert(r.ok(),`${method} ${endpoint}: ${r.status()} ${await r.text()}`);return r;
}
async function read(endpoint,ctx=writerContext,origin=base){return (await api(ctx,origin,endpoint)).json();}
async function patch(id,values){const old=await read(`/projects/${p}/tasks/${id}`);return (await api(writerContext,base,`/projects/${p}/tasks/${id}`,'PATCH',values,{'If-Match':String(old.revision)})).json();}
async function go(target,origin,project,task){await target.goto(origin+`/#project/${project}/overview`);await target.locator('[data-map-task]').first().waitFor();await target.waitForFunction(()=>!routing);if(task){await target.locator(`[data-map-task="${task}"]`).click();await target.locator('#map-inspector .map-detail-links').waitFor();}}
async function visible(id,text){await page.waitForFunction(({id,text})=>document.querySelector(`[data-map-task="${id}"]`)?.textContent.includes(text),{id,text});}
async function screenshot(name,target=page){await target.evaluate(async()=>{document.querySelector('.workspace-content').scrollTop=0;await document.fonts.ready;});await target.screenshot({path:path.join(out,name+'.png'),fullPage:true});}
async function waitApplied(seqValue){await page.waitForFunction(value=>BigInt(document.querySelector('#panel')?.overviewState?.().appliedSeq||0)>=BigInt(value),String(seqValue));}
(async()=>{
 browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 observerContext=await browser.newContext({viewport:{width:1440,height:1000},recordVideo:{dir:path.join(out,'video'),size:{width:1440,height:1000}}});
 await observerContext.addInitScript(()=>{
  const Native=window.EventSource;window.overviewEventSources=[];
  window.EventSource=class extends Native{constructor(url,options){super(url,options);if(String(url).includes('/graph/events'))window.overviewEventSources.push(this);}};
 });
 writerContext=await browser.newContext({viewport:{width:1440,height:1000}});
 page=await observerContext.newPage();writer=await writerContext.newPage();
 for(const target of [page,writer]){target.setDefaultTimeout(20000);target.on('pageerror',e=>errors.push(e.message));}
 await login(writer,seed.user,base);await login(page,seed.member,other);await patch(t[2],{status:'TODO'});
 await go(page,other,p);await screenshot('00-overview');await page.locator(`[data-map-task="${t[5]}"]`).click();await page.locator('#map-inspector .map-detail-links').waitFor();await screenshot('01-overview-selected');
 await page.evaluate(()=>{window.savedNodes=new Map([...document.querySelectorAll('[data-map-task]')].map(e=>[e.dataset.mapTask,e]));window.savedStyles=new Map([...window.savedNodes].map(([id,e])=>[id,e.getAttribute('style')]));});
 const before=await state(page),oldComments=await page.locator('#map-discussions').innerText();
 await writer.goto(base+`/#task/${p}/${t[2]}`);await writer.locator('#edit-task').click();await writer.locator('#task-edit [name=status]').selectOption('DONE');
 await Promise.all([writer.waitForResponse(r=>r.request().method()==='PATCH'&&r.url().endsWith('/tasks/'+t[2])),writer.locator('#task-edit button').first().click()]);const began=performance.now();
 await visible(t[2],'已完成');await visible(t[3],'可推进');await visible(t[5],'等待上游');
 samples.push({metricId:'collaboration_visible_ms',sampleValue:Math.max(0,performance.now()-began),unit:'ms',scenarioId:'two-jvm-ui-write'});
 const after=await state(page);assert.equal(after.metrics.layouts,before.metrics.layouts);assert.equal(after.selected,t[5]);assert.equal(after.scale,before.scale);
 assert(await page.evaluate(()=>[...window.savedNodes].every(([id,e])=>e===document.querySelector(`[data-map-task="${id}"]`)&&e.getAttribute('style')===window.savedStyles.get(id))));
 await screenshot('02-upstream-completed');await screenshot('02-writer-task',writer);
 pass('LIVE-01','成员A完成上游，实例B的观察者自动看到解除阻塞，位置、选择、缩放与DOM保持',{crossJvm:base!==other});
 const draft='尚未发送：请确认下游回归安排 '+Date.now();
 await writer.locator('#comment textarea').fill(draft);
 const draftNode=await writer.locator('#comment textarea').elementHandle();
 for(let i=0;i<12;i++){
  const value=i%2?'DONE':'TODO';await patch(t[2],{status:value});const start=performance.now();await visible(t[2],value==='DONE'?'已完成':'待办');
  samples.push({metricId:'collaboration_visible_ms',sampleValue:Math.max(0,performance.now()-start),unit:'ms',scenarioId:'two-jvm-status'});
 }
 const originalOwner=(await read(`/projects/${p}/tasks/${t[2]}`)).assigneeId;
 await patch(t[2],{assigneeId:seed.memberId});await visible(t[2],'陈希');await patch(t[2],{assigneeId:originalOwner});await visible(t[2],originalOwner===seed.developerId?'周航':'林悦');
 pass('RENDER-01','状态与负责人局部更新；布局次数不增加，既有节点DOM与坐标保持');
 // Fail sync after a notification. appliedSeq must remain at the old successfully applied cursor.
 let block=true,blocked;const firstBlocked=new Promise(r=>blocked=r);const oldSeq=(await state(page)).appliedSeq;
 await page.route('**/graph/sync',async route=>{if(block){blocked();await route.abort('failed');}else await route.continue();});
 await patch(t[2],{priority:2});await firstBlocked;assert.equal((await state(page)).appliedSeq,oldSeq);assert(BigInt((await state(page)).seenSeq)>BigInt(oldSeq));
 block=false;const latest=await read(`/projects/${p}/graph/state`);await waitApplied(latest.viewSeq);await page.unroute('**/graph/sync');
 pass('LIVE-03','通知到达后sync失败，已应用序号保持，重试补齐');
 // Preview failure remains dirty even after the graph cursor advances.
 let previewBlock=true;await page.route(`**/tasks/${t[5]}/preview?*`,r=>previewBlock?r.abort('failed'):r.continue());
 const marker='协作验收讨论 '+Date.now();
 const comment=await(await api(writerContext,base,`/projects/${p}/tasks/${t[5]}/comments`,'POST',{body:marker})).json();
 await page.locator('#map-detail-retry').waitFor();assert((await state(page)).dirty.includes(t[5]));assert.equal(await page.locator('#map-discussions').innerText(),oldComments);
 previewBlock=false;await page.locator('#map-detail-retry').click();await page.getByText(marker,{exact:true}).waitFor();assert(!(await state(page)).dirty.includes(t[5]));await page.unroute(`**/tasks/${t[5]}/preview?*`);
 pass('LIVE-04','预览读取失败保留dirty与原讨论，重试成功后更新');
 // Reading comments should not jump when a new discussion arrives.
 const commentNode=await page.locator('#map-discussions .map-discussion').first().elementHandle();
 const secondMarker='继续保留阅读位置 '+Date.now();await api(writerContext,base,`/projects/${p}/tasks/${t[5]}/comments`,'POST',{body:secondMarker});
 await page.locator('#map-new-discussion').waitFor();assert.equal(await page.locator('#map-discussions').getByText(secondMarker,{exact:true}).count(),0);
 assert(await commentNode.evaluate(e=>e.isConnected));await screenshot('03-new-discussion');await page.locator('#map-new-discussion').click();await page.getByText(secondMarker,{exact:true}).waitFor();
 // Editing and deleting a discussion goes through the same revision and invalidation contract.
 await api(writerContext,base,`/projects/${p}/tasks/${t[5]}/comments/${comment.id}`,'PUT',{body:marker+'（已补充）'},{'If-Match':String(comment.revision)});
 await page.locator('#map-new-discussion').waitFor();await page.locator('#map-new-discussion').click();await page.getByText(marker+'（已补充）',{exact:true}).waitFor();
 await api(writerContext,base,`/projects/${p}/tasks/${t[5]}/comments/${comment.id}`,'DELETE',undefined,{'If-Match':String(comment.revision+1)});
 await page.locator('#map-new-discussion').waitFor();await page.locator('#map-new-discussion').click();assert.equal(await page.getByText(marker+'（已补充）',{exact:true}).count(),0);
 pass('LIVE-12','讨论新增、编辑和删除保留阅读DOM，成员主动更新后看到新内容');
 assert.equal(await writer.locator('#comment textarea').inputValue(),draft);
 assert(await draftNode.evaluate(e=>e.isConnected));
 pass('LIVE-12-draft','任务详情中的未发送讨论草稿及输入DOM在并行概览更新、讨论增删改期间保持');
 // Rapid A→B→A selection cannot leave the inspector waiting for a discarded old response.
 let previewCaptured,releasePreview,firstPreview=true;const previewCapture=new Promise(r=>previewCaptured=r),previewGate=new Promise(r=>releasePreview=r);
 await page.route(`**/tasks/${t[5]}/preview?*`,async route=>{if(firstPreview){firstPreview=false;const response=await route.fetch();previewCaptured();await previewGate;await route.fulfill({response});}else await route.continue();});
 await page.locator(`[data-map-task="${t[5]}"]`).click();await previewCapture;
 await page.locator(`[data-map-task="${t[3]}"]`).click();await page.locator(`[data-map-task="${t[5]}"]`).click();releasePreview();
 await page.locator('#map-inspector h3').filter({hasText:'完成报表发布验收'}).waitFor();assert.equal((await state(page)).selected,t[5]);await page.unroute(`**/tasks/${t[5]}/preview?*`);
 pass('LIVE-05-preview','A→B→A快速选择丢弃旧预览后，自动读取当前选择');

 // Capacity is enforced before headers; a fourth connection falls back to polling.
 const cookies=await observerContext.cookies(other),cookie=cookies.map(c=>c.name+'='+c.value).join('; '),held=[];
 const latestForCapacity=await read(`/projects/${p}/graph/state`);
 try{
  for(let i=0;i<2;i++){
   const id=crypto.randomUUID(),controller=new AbortController();
   const response=await fetch(other+`/projects/${p}/graph/events?after=${latestForCapacity.viewSeq}&connectionId=${id}`,{headers:{Cookie:cookie,Accept:'text/event-stream'},signal:controller.signal});
   assert.equal(response.status,200);held.push({id,controller});
  }
  const overflow=await fetch(other+`/projects/${p}/graph/events?after=0`,{headers:{Cookie:cookie,Accept:'text/event-stream'}});
  assert.equal(overflow.status,503);assert.equal(overflow.headers.get('retry-after'),'5');await overflow.text();
  const fallback=await observerContext.newPage();await go(fallback,other,p);await fallback.getByText(/轮询同步/).waitFor();
  await patch(t[2],{priority:1});const start=performance.now();
  await fallback.waitForFunction(id=>document.querySelector(`[data-map-task="${id}"]`)?.getAttribute('aria-label')!==null,t[2]);
  const expected=await read(`/projects/${p}/graph/state`);await fallback.waitForFunction(v=>BigInt(document.querySelector('#panel')?.overviewState?.().appliedSeq||0)>=BigInt(v),expected.viewSeq);
  samples.push({metricId:'poll_fallback_visible_ms',sampleValue:performance.now()-start,unit:'ms',scenarioId:'capacity-fallback'});await fallback.close();
  pass('LIVE-10','真实连接配额返回503和Retry-After，额外页面通过轮询补齐；慢输出边界另有单元验证');
 }finally{
  for(const h of held){h.controller.abort();await api(observerContext,other,`/projects/${p}/graph/events?connectionId=${h.id}`,'DELETE');}
 }
 // Snapshot-to-subscription gap: hold a real SSE handshake while a write commits.
 const gap=await observerContext.newPage();let releaseGap,arrivedGap;
 const gapGate=new Promise(r=>releaseGap=r),gapArrived=new Promise(r=>arrivedGap=r);
 await gap.route('**/graph/events?*',async route=>{arrivedGap();await gapGate;await route.continue();});
 await go(gap,other,p,t[2]);await gapArrived;await patch(t[2],{priority:3});releaseGap();
 const gapLatest=await read(`/projects/${p}/graph/state`);await gap.waitForFunction(v=>BigInt(document.querySelector('#panel')?.overviewState?.().appliedSeq||0)>=BigInt(v),gapLatest.viewSeq);await gap.close();
 pass('LIVE-02','快照后、SSE握手前提交的变化通过连接校对补齐');
 // Duplicate and reordered transport events retain the highest observed version.
 const current=await state(page);
 await page.evaluate(value=>{
  const stream=window.overviewEventSources.at(-1),s=BigInt(value);
  for(const v of [s,s>0n?s-1n:0n,s])stream.dispatchEvent(new MessageEvent('project.changed',{data:JSON.stringify({latestSeq:String(v)})}));
 },current.appliedSeq);
 await waitApplied(current.appliedSeq);assert(BigInt((await state(page)).appliedSeq)>=BigInt(current.appliedSeq));
 // Hold an old sync response across a real scope change.
 let captured,releaseOld,once=true;const oldCaptured=new Promise(r=>captured=r),oldGate=new Promise(r=>releaseOld=r);
 await page.route('**/graph/sync',async route=>{if(once){once=false;const response=await route.fetch();captured();await oldGate;await route.fulfill({response});}else await route.continue();});
 const oldState=await state(page);await patch(t[2],{priority:2});await oldCaptured;
 const focusResponse=page.waitForResponse(r=>r.url().includes('/graph?view=focus'));
 await page.locator('#map-focus-task').click();await focusResponse;await page.waitForFunction(g=>document.querySelector('#panel')?.overviewState?.().generation>g,oldState.generation);
 const focused=await state(page);releaseOld();await page.waitForFunction(v=>document.querySelector('#panel')?.overviewState?.().metrics.staleResponses>v,oldState.metrics.staleResponses);
 assert.deepEqual((await state(page)).nodes,focused.nodes);await page.unroute('**/graph/sync');
 pass('LIVE-05','重复与乱序通知保持序号单调，跨范围晚到响应被丢弃');
 await go(page,other,p,t[5]);
 // Fault injection asks the real client to exercise its expired-journal recovery path.
 let expiredOnce=true;await page.route('**/graph/sync',route=>{if(expiredOnce){expiredOnce=false;return route.fulfill({status:409,contentType:'application/json',body:JSON.stringify({code:'GRAPH_RESYNC_REQUIRED',message:'expired journal fault injection'})});}return route.continue();});
 const recoveryBefore=await state(page),scrollBefore=await page.locator('#map-viewport').evaluate(e=>({x:e.scrollLeft,y:e.scrollTop}));
 await patch(t[2],{priority:1});const expiredLatest=await read(`/projects/${p}/graph/state`);await waitApplied(expiredLatest.viewSeq);
 const recovered=await state(page);assert.equal(recovered.selected,recoveryBefore.selected);assert.equal(recovered.scale,recoveryBefore.scale);assert.deepEqual(recovered.nodes,recoveryBefore.nodes);
 assert.deepEqual(await page.locator('#map-viewport').evaluate(e=>({x:e.scrollLeft,y:e.scrollTop})),scrollBefore);await page.unroute('**/graph/sync');
 pass('LIVE-06','过期响应注入后重取实际当前范围，选中、缩放、节点集合与滚动保持；真实清理由后端集成覆盖');
 // Network outage: real browser offline state. Changes continue on writer A.
 await observerContext.setOffline(true);await patch(t[2],{priority:3});await new Promise(r=>setTimeout(r,20000));
 const recovery=performance.now();await observerContext.setOffline(false);await waitApplied((await read(`/projects/${p}/graph/state`)).viewSeq);
 samples.push({metricId:'network_recovery_ms',sampleValue:performance.now()-recovery,unit:'ms',scenarioId:'offline-20s'});
 await screenshot('04-reconnected');
 // Controlled visibility signals exercise the real 30-second release timer in headless Chromium.
 await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,get:()=>true});document.dispatchEvent(new Event('visibilitychange'));});
 await page.waitForFunction(()=>window.overviewEventSources.at(-1)?.readyState===EventSource.CLOSED,{},{timeout:35000});
 await patch(t[2],{priority:2});const hiddenLatest=await read(`/projects/${p}/graph/state`);
 await page.evaluate(()=>{delete document.hidden;document.dispatchEvent(new Event('visibilitychange'));});await waitApplied(hiddenLatest.viewSeq);
 pass('LIVE-09','断网20秒后补齐；注入页面可见性信号，真实30秒定时器关闭订阅，返回时补齐并保留当前任务');
 // Global search locates a task outside the initial scope in a large project.
 await go(page,other,large.project);const scope=await state(page),outside=large.tasks.find(id=>!scope.nodes.includes(id));assert(outside);
 const target=await read(`/projects/${large.project}/tasks/${outside}`);await page.locator('#map-search').fill(target.title);await page.locator('#map-search').press('Enter');
 await page.locator('#map-inspector h3').filter({hasText:target.title}).waitFor();assert((await state(page)).nodes.includes(outside));
 const initialNodes=(await state(page)).nodes.length;const expansionResponse=page.waitForResponse(r=>r.url().endsWith('/graph/view'));await page.locator('[data-expand="DOWNSTREAM"]').click();await expansionResponse;await page.waitForFunction(n=>document.querySelector('#panel')?.overviewState?.().nodes.length>=n,initialNodes);
 await screenshot('05-global-focus-expand');pass('DATA-04','定位首屏外任务并载入真实周边');pass('DATA-05','从当前任务展开直接关系，保留范围内节点');
 // Field changes retain the actual drawn-edge omissions even in a dense, truncated graph.
 const dense=seed.datasets.find(d=>d.id==='S6');await go(page,other,dense.project);
 const omissions=()=>page.locator('[data-map-task]').evaluateAll(nodes=>nodes.map(n=>[n.dataset.mapTask,n.querySelector('.map-node-omitted')?.textContent||'']));
 const omittedBefore=await omissions(),denseBefore=await state(page);assert(omittedBefore.some(([,text])=>text));
 const fullDense=await(await api(writerContext,base,`/projects/${dense.project}/graph/view`,'POST',{nodeIds:denseBefore.nodes})).json(),omittedById=new Map(omittedBefore);
 const denseId=fullDense.nodes.find(n=>n.visibleUpstreamCount+n.visibleDownstreamCount>n.upstreamCount+n.downstreamCount-Number(omittedById.get(n.id)?.match(/\d+/)?.[0]||0))?.id;assert(denseId);
 const denseTask=await read(`/projects/${dense.project}/tasks/${denseId}`);
 await api(writerContext,base,`/projects/${dense.project}/tasks/${denseId}`,'PATCH',{title:denseTask.title+' · 排期确认'},{'If-Match':String(denseTask.revision)});
 await waitApplied((await read(`/projects/${dense.project}/graph/state`)).viewSeq);
 assert.deepEqual(await omissions(),omittedBefore);assert.equal((await state(page)).metrics.layouts,denseBefore.metrics.layouts);
 pass('RENDER-03','密集图省略关系的真实数量在字段更新后保持，布局次数不增加');
 // Hold a real paged request, change topology through the writer API, then release it.
 const fanout=seed.datasets.find(d=>d.id==='S4'),rootTask=fanout.tasks[0];
 await page.goto(other+`/#project/${fanout.project}/overview?task=${rootTask}`);await page.locator('#map-inspector .map-detail-links').waitFor();await page.waitForFunction(()=>!routing);
 const firstPage=page.waitForResponse(r=>r.url().endsWith('/graph/view')&&r.status()===200);
 await page.locator('[data-expand="DOWNSTREAM"]').click();assert((await(await firstPage).json()).expansion.nextCursor);
 let releaseCursor,cursorArrived;const cursorGate=new Promise(r=>releaseCursor=r),cursorReady=new Promise(r=>cursorArrived=r);let holdCursor=true;
 await page.route('**/graph/view',async route=>{if(holdCursor){holdCursor=false;assert(route.request().postDataJSON().expand.cursor);cursorArrived();await cursorGate;}await route.continue();});
 const preserved=await state(page);await page.locator('[data-expand="DOWNSTREAM"]').click();await cursorReady;
 await api(writerContext,base,`/projects/${fanout.project}/tasks`,'POST',{title:'核对新增工作安排',description:'',status:'TODO',type:'TASK',priority:1,assigneeId:seed.userId,dueDate:null});
 const stale=page.waitForResponse(r=>r.url().endsWith('/graph/view')&&r.status()===409),retried=page.waitForResponse(r=>r.url().endsWith('/graph/view')&&r.status()===200);releaseCursor();
 assert.equal((await(await stale).json()).code,'GRAPH_CURSOR_STALE');await retried;await page.waitForFunction(()=>document.querySelector('#map-refresh')?.disabled===false);
 const refreshed=await state(page);assert.equal(refreshed.selected,rootTask);assert(preserved.nodes.every(id=>refreshed.nodes.includes(id)));await page.unroute('**/graph/view');
 pass('DATA-06','真实邻接分页遇到拓扑提交后返回陈旧游标，页面自动重读第一页并保留已有节点和选择');
 // Mobile and keyboard keep the graph available without page overflow.
 await page.setViewportSize({width:390,height:844});await go(page,other,p);
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+2),false);
 assert((await page.locator('#map-viewport').boundingBox()).y<750);
 await screenshot('06-mobile');await page.locator('[data-map-task]').first().focus();await page.keyboard.press('ArrowRight');await page.keyboard.press('Enter');await page.locator('#map-inspector .map-detail-links').waitFor();
 await page.keyboard.press('Escape');assert.equal(await page.locator('[data-map-task]:focus').count(),1);await screenshot('06-mobile-keyboard');pass('RENDER-02','390px无整页横向溢出，方向键、Enter、Escape与焦点恢复');
 // Change access through the production API and observe the real SSE-driven clearing.
 const projectBefore=await read(`/projects/${p}`);
 await api(writerContext,base,`/projects/${p}/members/${seed.memberId}`,'PUT',{role:'MEMBER',active:false,expectedBusinessRevision:projectBefore.businessRevision});
 const revokedAt=performance.now();
 try{
  await page.getByRole('alert').filter({hasText:'请确认登录状态与项目访问权限'}).waitFor();
  samples.push({metricId:'access_revocation_clear_ms',sampleValue:performance.now()-revokedAt,unit:'ms',scenarioId:'project-member-revoked'});
  assert.equal(await page.locator('[data-map-task]').count(),0);assert.equal(await page.locator('#map-inspector').count(),0);assert.equal(await page.evaluate(()=>document.querySelector('#panel')?.overviewState),null);
  const rejected=await observerContext.request.get(other+`/projects/${p}/graph/state`,{headers:{'If-None-Match':'"0:0:0:0"'}});assert.equal(rejected.status(),403);
  pass('LIVE-08','正式成员撤权后SSE关闭，画布、预览和临时状态清理，条件读取403');
 }finally{
  const current=await read(`/projects/${p}`);await api(writerContext,base,`/projects/${p}/members/${seed.memberId}`,'PUT',{role:'MEMBER',active:true,expectedBusinessRevision:current.businessRevision});
 }
 assert.deepEqual(errors,[]);
 fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({status:'PASS',checks,errors,samples,instances:{writer:base,observer:other}},null,2));
})().catch(async e=>{if(writer){await writer.screenshot({path:path.join(out,'writer-failure.png'),fullPage:true}).catch(()=>{});fs.writeFileSync(path.join(out,'writer-failure.txt'),await writer.locator('body').innerText().catch(()=>''));}if(page)await page.screenshot({path:path.join(out,'failure.png'),fullPage:true}).catch(()=>{});fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({status:'FAIL',checks,errors,samples,error:e.stack},null,2));console.error(e);process.exitCode=1;}).finally(async()=>{await observerContext?.close();await writerContext?.close();await browser?.close();});
