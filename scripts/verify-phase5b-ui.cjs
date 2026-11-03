// Only the isolated Spring integration-test server and synthetic accounts supplied by TriageProposalIT.
const path=require('node:path'),assert=require('node:assert/strict'),fs=require('node:fs');
const {chromium}=require('playwright');
const [port,login,project,task,member,automaticRun]=process.argv.slice(2);
assert.match(login,/^p5b-/);assert.match(port,/^\d+$/);assert.notEqual(port,'18081');
async function openAnalysis(page) {
 const disclosure=page.locator('#task-analysis');
 await disclosure.waitFor({state:'attached'});
 if(await page.locator('#project-assistant').isVisible())await page.locator('#assistant-close').click();
 if(!await disclosure.evaluate(e=>e.open))await disclosure.locator(':scope > summary').click();
}
(async()=>{
 console.log('Browser acceptance: launching Chromium');
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 let page;const responses=[],requests=[],debug=[],errors=[];
 try{
  page=await browser.newPage({viewport:{width:1280,height:950}});page.setDefaultTimeout(90000);
  page.on('request',r=>requests.push({path:new URL(r.url()).pathname,method:r.method()}));
  page.on('console',m=>debug.push(m.text()));
  page.on('response',async response=>{const u=new URL(response.url());if(!u.pathname.includes('/agent/'))return;if(response.request().method()==='POST')console.log('Browser acceptance: agent POST response',u.pathname,response.status());let code,state;try{const body=await response.json();code=body.code;state=body.status;}catch{}responses.push({path:u.pathname,method:response.request().method(),status:response.status(),state,code});});page.on('pageerror',e=>errors.push(e.message));
  await page.goto(`http://127.0.0.1:${port}/#login`);
  await page.locator('#login [name=username]').fill(login);await page.locator('#login [name=password]').fill('Synthetic5b!');await page.locator('#login button').click();await page.waitForURL('**/#mine');console.log('Browser acceptance: signed in');
  await page.goto(`http://127.0.0.1:${port}/#task/${project}/${task}`);await openAnalysis(page);console.log('Browser acceptance: task analysis opened');
  const strategy=page.locator('[name="triage-strategy"]');
  await strategy.waitFor();
  assert.deepEqual(await strategy.locator('option').evaluateAll(xs=>xs.map(x=>x.value)),['WORKFLOW','GUIDED','AGENT']);
  let submitted=0;page.on('request',r=>{if(r.method()==='POST'&&r.url().includes(`/agent/triage/${task}/runs`))submitted++;});
  if(automaticRun) {
    assert.match(automaticRun,/^[0-9a-f-]{36}$/);
    const existing=page.locator(`[data-run="${automaticRun}"]`);await existing.waitFor();
    assert.match(await existing.innerText(),/自动分析/);
    // Deterministically hold the selected run reload: the prior proposal must cease
    // being actionable before its replacement response arrives.
    await page.locator('#proposal-edit').waitFor();
    let release;const held=new Promise(resolve=>release=resolve);
    let entered;const requested=new Promise(resolve=>entered=resolve);
    let completed;const delivered=new Promise(resolve=>completed=resolve);
    const reloadPattern=`**/agent/runs/${automaticRun}`;
    await page.route(reloadPattern,async route=>{const response=await route.fetch();entered();await held;await route.fulfill({status:response.status(),contentType:'application/json',body:await response.text()});completed();});
    try{await existing.click();await requested;assert.equal(await page.locator('#proposal-edit').count(),0);}
    finally{release();}
    await delivered;await page.locator('#proposal-edit').waitFor();await page.unroute(reloadPattern);
  } else {
  await strategy.selectOption('WORKFLOW');await page.locator('#triage-question').fill('重点核对取消名额约定');
  const request=page.waitForRequest(r=>r.method()==='POST'&&r.url().includes(`/agent/triage/${task}/runs`));
  await page.locator('#triage-analyze').click();
  const payload=(await request).postDataJSON();assert.equal(payload.strategy,'WORKFLOW');assert.match(payload.question,/重点核对取消名额约定/);
  }
  await page.locator('#proposal-edit').waitFor();
  assert.match(await page.locator('#triage-investigation').innerText(),/建议接纳/);
  assert.match(await page.locator('#triage-investigation').innerText(),/请补充影响范围/);
  assert.match(await page.locator('#triage-proposal').innerText(),/建议 · 未批准/);
  await page.locator('#proposal-preview').click();await page.locator('#proposal-approve').waitFor();assert.equal(await page.locator('#proposal-approve').isDisabled(),true);
  await page.locator('#proposal-edit [name=assigneeId]').selectOption(member);
  assert.equal(await page.locator('#proposal-approve').count(),0);assert.equal(await page.locator('#proposal-preview').isDisabled(),true);
  await page.locator('#proposal-edit [name=priority]').selectOption('2');
  await page.locator('#proposal-edit [name=rationale]').fill('合成页面验证：维护者选择陈成员，调整为 P2，并重新预览。');
  await page.locator('#proposal-edit button').click();await page.getByRole('heading',{name:/未批准 · 版本 2/}).waitFor();
  await page.locator('#proposal-preview').click();
  // The backend commits; deliberately discard only its response to the browser.
  let dropped=0;
  await page.route('**/agent/proposals/*/approve',async route=>{const response=await route.fetch();assert.equal(response.status(),200);dropped++;await route.abort('failed');});
  await page.locator('#proposal-approve').click();await page.locator('#triage-result').waitFor();
  assert.match(await page.locator('#triage-result').innerText(),/P2/);assert.equal(dropped,1);assert.equal(await page.locator('#notice.error').count(),0);
  await page.reload();await openAnalysis(page);await page.locator('#triage-result').waitFor();assert.match(await page.locator('#triage-proposal').innerText(),/执行成功/);
  await page.screenshot({path:'target/phase5b-ui-desktop.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});if(await page.locator('#project-assistant').isVisible())await page.locator('#assistant-close').click();assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth+2),false);
  assert.equal(await page.locator('#triage-proposal .table-scroll').evaluate(e=>e.scrollWidth>e.clientWidth+2),false);
  await page.screenshot({path:'target/phase5b-ui.png',fullPage:true});assert.deepEqual(errors,[]);
  assert.equal(submitted,automaticRun?0:1);
  fs.writeFileSync('target/phase5b-ui-result.json',JSON.stringify({status:'PASSED',synthetic:true,mode:automaticRun?'existing automatic result':'manual analysis',automaticRun:automaticRun||null,submittedRuns:submitted,checks:['three strategies selectable',automaticRun?'automatic result opened without creating another run':'selected strategy and focus sent to API','investigation decision and questions rendered','analysis without acceptance','missing owner disabled','unsaved edits remove approval','new version and fresh preview','exact edited priority','committed response deliberately dropped and recovered','receipt after reload','390px layout with both compared values visible','no page errors']},null,2));
  console.log('PASS: real page / real Java / isolated MySQL; synthetic model only');
 }catch(e){if(page){fs.writeFileSync('target/phase5b-ui-failure.json',JSON.stringify({responses,requests,debug,pageErrors:errors,body:await page.locator('body').innerText()},null,2));await page.screenshot({path:'target/phase5b-ui-failure.png',fullPage:true});}throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
