// Synthetic accounts against this turn's isolated Spring/MySQL test server only.
const path=require('node:path'),assert=require('node:assert/strict'),fs=require('node:fs');
const {chromium}=require('playwright');
const [port,login,project,task,member]=process.argv.slice(2);
assert.match(login,/^p5b-/);assert.match(port,/^\d+$/);assert(!['18080','18081'].includes(port));
async function openAnalysis(page) {
 const disclosure=page.locator('#task-analysis');
 await disclosure.waitFor({state:'attached'});
 if(await page.locator('#project-assistant').isVisible())await page.locator('#assistant-close').click();
 if(!await disclosure.evaluate(e=>e.open))await disclosure.locator(':scope > summary').click();
}
(async()=>{
 console.log('Browser acceptance: launching Chromium');
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 try {
  const page=await browser.newPage({viewport:{width:1280,height:950}}),errors=[];page.setDefaultTimeout(90000);page.on('pageerror',e=>errors.push(e.message));
  await page.goto(`http://127.0.0.1:${port}/#login`);await page.locator('#login [name=username]').fill(login);await page.locator('#login [name=password]').fill('Synthetic5b!');await page.locator('#login button').click();await page.waitForURL('**/#mine');console.log('Browser acceptance: signed in');
  const historyPattern='**/agent/triage/*/proposals/history*';
  await page.route(historyPattern,r=>r.fulfill({status:503,contentType:'application/json',body:JSON.stringify({message:'合成历史故障'})}));
  await page.goto(`http://127.0.0.1:${port}/#task/${project}/${task}`);await openAnalysis(page);console.log('Browser acceptance: task analysis opened');
  await page.locator('#triage-history [data-history-error]').waitFor();
  await page.locator('#edit-task').click();await page.locator('#task-edit').waitFor();await page.locator('#cancel-task-edit').click();assert.equal(await page.locator('#comment textarea').isEnabled(),true);
  assert.equal(await page.getByText('无法打开此页面',{exact:true}).count(),0);
  await page.unroute(historyPattern);await page.locator('#triage-history').getByRole('button',{name:'重试加载'}).click();await page.waitForFunction(()=>!document.querySelector('#triage-history [data-history-error]'));
  // Commit the first run, lose its response, reload, and retry the same user intent.
  const seen=[];let dropped=false;
  await page.route('**/agent/triage/*/runs',async route=>{
    if(route.request().method()!=='POST')return route.continue();
    seen.push({key:route.request().headers()['idempotency-key'],body:route.request().postDataJSON()});
    if(!dropped){dropped=true;const response=await route.fetch();assert.equal(response.status(),202);await route.abort('failed');}else await route.continue();
  });
  await page.locator('#triage-question').fill('合成响应丢失测试');await page.locator('#triage-analyze').click();
  await page.waitForFunction(()=>document.querySelector('#triage-analyze')?.textContent.includes('重试确认') && !document.querySelector('#triage-analyze').disabled);
  await page.reload();await openAnalysis(page);await page.getByRole('button',{name:'重试确认上次分析请求'}).waitFor();
  assert.equal(await page.locator('#triage-question').isDisabled(),true);
  await page.getByRole('button',{name:'重试确认上次分析请求'}).click();await page.locator('#proposal-edit').waitFor();
  assert.equal(seen.length,2);assert.deepEqual(seen[0],seen[1]);
  // Remove tab-local memory: the project history alone must rediscover the result.
  await page.evaluate(()=>sessionStorage.clear());await page.reload();await openAnalysis(page);await page.locator('#proposal-edit').waitFor();assert.equal(await page.locator('[data-run]').count(),1);
  await page.goto(`http://127.0.0.1:${port}/#project/${project}/settings`);await page.locator('#auto-triage').waitFor();
  assert.equal(await page.locator('#auto-triage [name=enabled]').inputValue(),'false');
  await page.locator('#auto-triage [name=enabled]').selectOption('true');await page.locator('#auto-triage button').click();
  await page.waitForFunction(()=>document.querySelector('#notice')?.textContent.includes('自动分析设置已保存'));
  await page.reload();await page.locator('#auto-triage').waitFor();assert.equal(await page.locator('#auto-triage [name=enabled]').inputValue(),'true');
  await page.screenshot({path:'target/automatic-triage-settings.png',fullPage:true});
  await page.goto(`http://127.0.0.1:${port}/#task/${project}/${task}`);await openAnalysis(page);await page.locator('#proposal-edit').waitFor();
  await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+2),false);
  await page.screenshot({path:'target/automatic-triage-mobile.png',fullPage:true});assert.deepEqual(errors,[]);
  // Full product flow: member submits through the UI, no analysis button is clicked.
  const memberPage=await browser.newPage();memberPage.on('pageerror',e=>errors.push(e.message));
  await memberPage.goto(`http://127.0.0.1:${port}/#login`);
  await memberPage.locator('#login [name=username]').fill(`p5b-${member}`);await memberPage.locator('#login [name=password]').fill('Synthetic5b!');await memberPage.locator('#login button').click();await memberPage.waitForURL('**/#mine');
  await memberPage.goto(`http://127.0.0.1:${port}/#project/${project}/tasks`);
  await memberPage.locator('#newTask-disclosure > summary').click();await memberPage.locator('#newTask [name=title]').fill('自动分析页面闭环');await memberPage.locator('#newTask [name=description]').fill('取消报名后名额未恢复，请核对影响范围。');await memberPage.locator('#newTask button:not([type=button])').click();await memberPage.waitForURL(`**/#task/${project}/*`);
  const submitted=memberPage.url().split('/').pop();
  await memberPage.locator('#triage-status').waitFor();assert.equal(await memberPage.locator('#triage-analyze').count(),0);
  await page.goto(`http://127.0.0.1:${port}/#task/${project}/${submitted}`);await openAnalysis(page);await page.locator('#proposal-edit').waitFor();
  assert.match(await page.locator('#triage-runs').innerText(),/自动分析/);assert.match(await page.locator('#triage-proposal').innerText(),/未批准/);
  await page.locator('#proposal-edit [name=assigneeId]').selectOption(member);await page.locator('#proposal-edit button').click();await page.getByRole('heading',{name:/未批准 · 版本 2/}).waitFor();
  await page.locator('#proposal-preview').click();await page.locator('#proposal-approve').click();await page.locator('#triage-result').waitFor();
  await page.screenshot({path:'target/automatic-triage-approved.png',fullPage:true});
  await memberPage.reload();await memberPage.locator('#task-summary').waitFor();assert.equal(await memberPage.locator('.task-badges .badge').first().innerText(),'待办');
  await memberPage.close();assert.deepEqual(errors,[]);
  fs.writeFileSync('target/automatic-triage-ui-result.json',JSON.stringify({status:'PASSED',synthetic:true,checks:['503 history isolated','task editing and comment remain usable','history retry','POST committed response lost','same key and body across reload','project history without sessionStorage','project opt-in saved and read back','390px layout','member submits through UI','automatic result without clicking analyze','maintainer edits previews and approves','member reads accepted task','no page errors']},null,2));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
