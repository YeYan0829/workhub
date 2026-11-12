// Invoked by ModelSettingsUIIT against an isolated application and local fake suppliers.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require('playwright');
assert.equal(process.env.WORKHUB_MODEL_UI_TEST, 'true');
const seed=JSON.parse(fs.readFileSync(process.env.MODEL_UI_SEED_FILE,'utf8'));
const base=new URL(seed.base);assert.equal(base.protocol,'http:');assert(['127.0.0.1','localhost'].includes(base.hostname));
const out=path.resolve(process.env.MODEL_UI_TEST_DIR);fs.mkdirSync(out,{recursive:true});
const checks=[],errors=[];
function pass(id,description){checks.push({id,description});console.log('PASS '+id+' '+description);}
const w=seed.workspaceId,p=seed.projectId;
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
  const context=await browser.newContext({viewport:{width:1440,height:1050}});
  const page=await context.newPage();page.setDefaultTimeout(45000);page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
  async function login(target,user){await target.goto(seed.base+'/#login');await target.locator('#login [name=username]').fill(user);await target.locator('#login [name=password]').fill(seed.password);await target.locator('#login button').click();await target.locator('#personal-filter').waitFor();}
  async function api(endpoint,method='GET',body,request=context.request,extraHeaders={}){const csrf=await(await request.get(seed.base+'/session/csrf')).json();return request.fetch(seed.base+endpoint,{method,data:body,headers:method==='GET'?extraHeaders:{[csrf.headerName]:csrf.token,'Idempotency-Key':crypto.randomUUID(),...extraHeaders}});}
  async function data(endpoint){const r=await api(endpoint);assert(r.ok(),endpoint+' '+r.status());return r.json();}
  async function go(route,selector){await page.goto(seed.base+'/#'+route);await page.locator(selector).waitFor();await page.waitForFunction(()=>!routing);if(route.includes('/models')&&await page.locator('#assistant-close').isVisible())await page.locator('#assistant-close').click();}
  async function saved(form,message){await page.locator(form+' button[type=submit]').click();await page.waitForFunction(text=>[...document.querySelectorAll('[data-model-feedback]')].some(n=>n.textContent.includes(text)),message);}
  async function capture(name){await page.evaluate(async()=>{document.querySelector('.workspace-content').scrollTop=0;await document.fonts.ready;});await page.screenshot({path:path.join(out,name+'.png'),fullPage:true});}
  async function ready(){await page.locator('[data-vector-activate]:not([disabled])').waitFor();}
  async function activate(){await page.locator('[data-vector-activate]').click();await page.waitForFunction(()=>document.querySelector('#model-panel-vectors [data-model-feedback]')?.textContent.includes('项目已切换'));}
  try{
    await login(page,seed.admin);
    await go(`workspace/${w}/models`,'#team-text-model');
    assert.equal(await page.locator('#team-text-model [name=apiKey]').inputValue(),'');
    assert((await data(`/workspaces/${w}/model-catalog`)).some(m=>m.model==='glm-5.3'));
    assert.deepEqual((await data(`/workspaces/${w}/embedding-catalog`)).dimensions,[64,128,256,512,768,1024,1536,2048]);
    pass('MODEL-01','首次配置与服务端模型目录');

    const text='#team-text-model';
    await page.locator(text+' [name=provider]').selectOption('ZAI');await page.locator(text+' [name=apiKey]').fill('synthetic-browser-text-key');await page.locator(text+' [name=requestsPerSecond]').fill('1000');await page.locator(text+' [name=burst]').fill('2');
    await saved(text,'文本模型已保存');assert.equal(await page.locator(text+' [name=apiKey]').inputValue(),'');
    const first=await data(`/workspaces/${w}/model-connection`);assert(first.hasKey);assert.equal(first.version,1);assert(!JSON.stringify(first).includes('synthetic-browser'));
    await saved(text,'文本模型已保存');assert.equal((await data(`/workspaces/${w}/model-connection`)).version,2);
    await page.locator(text+' [name=apiKey]').fill('synthetic-browser-text-rotated');await saved(text,'文本模型已保存');
    assert.equal((await data(`/workspaces/${w}/model-connection`)).version,3);
    pass('MODEL-02','保存、留空保留密钥与密钥轮换');

    const stale=await context.newPage();stale.on('dialog',d=>d.accept());await stale.goto(seed.base+`/#workspace/${w}/models`);await stale.locator(text).waitFor();
    await page.locator(text+' [name=burst]').fill('3');await saved(text,'文本模型已保存');
    await stale.locator(text+' [name=burst]').fill('4');await stale.locator(text+' button[type=submit]').click();await stale.getByText('配置已在其他窗口更新。当前输入保留，请重新载入并核对后保存。').waitFor();
    assert.equal(await stale.locator(text+' [name=burst]').inputValue(),'4');assert(await stale.locator(text+' button[type=submit]').isDisabled());
    assert.equal((await data(`/workspaces/${w}/model-connection`)).burst,3);await stale.close();
    pass('MODEL-03','多窗口版本冲突保留输入并阻止覆盖');

    const beforeLost=(await data(`/workspaces/${w}/model-connection`)).version;
    let lost=false;
    await page.route(seed.base+`/workspaces/${w}/model-connection`,async route=>{
      if(route.request().method()==='PUT'&&!lost){lost=true;assert((await route.fetch()).ok());await route.abort('failed');}else await route.continue();
    });
    await page.locator(text+' [name=burst]').fill('2');await page.locator(text+' button[type=submit]').click();await page.getByText('保存结果待确认。请重新载入配置，核对当前版本后继续。').waitFor();assert(await page.locator(text+' button[type=submit]').isDisabled());
    await page.unroute(seed.base+`/workspaces/${w}/model-connection`);await page.locator(text+' [data-reload]').click();await page.locator(text+' [name=burst]').waitFor();assert.equal((await data(`/workspaces/${w}/model-connection`)).version,beforeLost+1);
    pass('MODEL-14','保存响应丢失后重新读取当前配置，避免重复覆盖');


    await go(`project/${p}/models`,'#project-model-policy');
    await page.locator('#project-model-policy [name=model]').selectOption('glm-5.3');await page.locator('#project-model-policy [name=maxCalls]').fill('8');await page.locator('#project-model-policy [name=seconds]').fill('120');await saved('#project-model-policy','项目策略已保存');
    let policy=await data(`/projects/${p}/model-policy`);assert.equal(policy.model,'glm-5.3');assert.equal(policy.maxMillis,120000);assert.equal(policy.inherited,false);
    pass('MODEL-04','项目指定模型与分析次数、时间边界');

    await go(`workspace/${w}/models`,text);await page.locator(text+' [name=provider]').selectOption('DASHSCOPE');assert(await page.locator(text+' [name=apiKey]').getAttribute('required')!==null);await page.locator(text+' [name=apiKey]').fill('synthetic-browser-bailian-key');await saved(text,'文本模型已保存');
    await go(`project/${p}/models`,'#project-model-policy');await page.getByText('当前模型已移出团队允许范围，请重新选择。').waitFor();await page.locator('#project-model-policy [name=model]').selectOption('');await saved('#project-model-policy','项目策略已保存');
    policy=await data(`/projects/${p}/model-policy`);assert.equal(policy.model,'qwen3.7-plus');assert.equal(policy.inherited,true);
    await capture('project-model-policy');pass('MODEL-05','供应商切换与失效项目策略修复');

    await go(`workspace/${w}/models?section=embedding`,'#team-embedding-model');
    const embed='#team-embedding-model';await page.locator(embed+' [name=dimensions]').selectOption('64');await page.locator(embed+' [name=providerWorkspaceId]').fill('fixture-space');await page.locator(embed+' [name=apiKey]').fill('synthetic-browser-embedding-key');await page.locator(embed+' [name=requestsPerSecond]').fill('1000');await page.locator(embed+' [name=burst]').fill('2');await saved(embed,'Embedding 已保存');
    const independent=await data(`/workspaces/${w}/embedding-connection`);assert.equal(independent.useGenerationKey,false);assert(independent.quota.scope.startsWith('team-embedding-'));assert.equal(await page.locator(embed+' [name=apiKey]').inputValue(),'');
    await page.locator(embed+' [name=useGenerationKey]').selectOption('true');assert(await page.locator(embed+' [name=apiKey]').isHidden());await saved(embed,'Embedding 已保存');
    const shared=await data(`/workspaces/${w}/embedding-connection`);assert.equal(shared.useGenerationKey,true);assert.equal(shared.defaultSpace.configId,independent.defaultSpace.configId);assert.equal(shared.quota.scope,(await data(`/workspaces/${w}/model-connection`)).quotaScope);
    await capture('team-embedding');pass('MODEL-06','Embedding 独立配置与显式复用文本生成凭据');

    await go(`project/${p}/models?section=vectors`,'#project-embedding-policy');await page.locator('#project-embedding-policy [name=enabled]').selectOption('true');await saved('#project-embedding-policy','项目检索设置已保存');await ready();await activate();
    const active=(await data(`/projects/${p}/embedding-policy`)).activeConfigId;assert(active);
    await page.locator('[data-vector-rebuild]').click();await page.waitForFunction(()=>document.querySelector('#model-panel-vectors [data-model-feedback]')?.textContent.includes('重建任务已登记'));
    await capture('project-vector-active');pass('MODEL-07','首次启用、持久重建与切换到就绪空间');

    await go(`workspace/${w}/models?section=embedding`,embed);await page.locator(embed+' [name=dimensions]').selectOption('128');await saved(embed,'Embedding 已保存');const changed=await data(`/workspaces/${w}/embedding-connection`);assert.notEqual(changed.defaultSpace.configId,active);
    await go(`project/${p}/models?section=vectors`,'#project-embedding-policy');await page.locator('#project-embedding-policy [name=configId]').selectOption('');await saved('#project-embedding-policy','项目检索设置已保存');
    let vectorPolicy=await data(`/projects/${p}/embedding-policy`);assert.equal(vectorPolicy.activeConfigId,active);assert.equal(vectorPolicy.buildingConfigId,changed.defaultSpace.configId);await capture('project-vector-building');await ready();await activate();
    await page.locator('#project-embedding-policy [name=configId]').selectOption(String(active));await saved('#project-embedding-policy','项目检索设置已保存');await ready();await activate();assert.equal((await data(`/projects/${p}/embedding-policy`)).activeConfigId,active);
    pass('MODEL-08','新空间并行准备、原空间继续使用及显式切回');

    await go(`project/${p}/knowledge`,'#knowledge-search');await page.locator('#knowledge-search [name=q]').fill('交接');await page.locator('#knowledge-search [name=strategy]').selectOption('DENSE');await page.locator('#knowledge-search button').click();await page.getByText(/实际采用语义检索/).waitFor();await page.locator('#knowledge-search [name=strategy]').selectOption('HYBRID');await page.locator('#knowledge-search button').click();await page.getByText(/实际采用混合检索/).waitFor();await page.locator('#knowledge-search [name=strategy]').selectOption('BM25');await page.locator('#knowledge-search button').click();await page.getByText(/实际采用关键词检索/).waitFor();
    await go(`project/${p}/overview`,'.project-stats');if(await page.locator('#assistant-toggle').getAttribute('aria-expanded')!=='true')await page.locator('#assistant-toggle').click();await page.locator('#conversation-compose textarea').fill('交接资料有哪些约定？');await page.locator('#conversation-send').click();await page.getByText('已查阅项目资料。',{exact:false}).waitFor();
    pass('MODEL-09','三种检索策略与助手调用走团队配置并产生用量');

    // Three real overview SSE connections share the browser with the assistant event stream.
    const collaborationTaskResponse=await api(`/projects/${p}/tasks`,'POST',{title:'多标签协作核对',description:'助手运行期间保持任务协作。',status:'TODO',type:'TASK'});
    assert(collaborationTaskResponse.ok(),await collaborationTaskResponse.text());const collaborationTask=await collaborationTaskResponse.json();
    await page.locator('#map-refresh').click();await page.locator('[data-map-task]').first().waitFor();
    const graphTabs=[await context.newPage(),await context.newPage()];
    try{
      for(const target of graphTabs){await target.goto(seed.base+`/#project/${p}/overview`);await target.locator('[data-map-task]').first().waitFor();await target.locator('#map-sync-status').filter({hasText:'已同步'}).waitFor();}
      await page.bringToFront();const replies=(await page.locator('body').innerText()).split('已查阅项目资料。').length-1;
      await page.locator('#conversation-compose textarea').fill('多标签协作期间再次核对交接资料。');
      const update=await api(`/projects/${p}/tasks/${collaborationTask.id}`,'PATCH',{status:'IN_PROGRESS'},context.request,{'If-Match':String(collaborationTask.revision)});assert(update.ok(),await update.text());
      for(const target of [page,...graphTabs])await target.waitForFunction(id=>document.querySelector(`[data-map-task="${id}"]`)?.textContent.includes('进行中'),collaborationTask.id);
      assert.equal(await page.locator('#conversation-compose textarea').inputValue(),'多标签协作期间再次核对交接资料。');await page.locator('#conversation-send').click();
      await page.waitForFunction(n=>document.body.innerText.split('已查阅项目资料。').length-1>n,replies);
      pass('LIVE-11','同一身份三个概览标签与真实助手事件流并存，任务与助手回复持续更新');
    }finally{for(const target of graphTabs)await target.close();}


    await go(`project/${p}/models?section=usage`,'#model-usage-filter');await page.locator('[data-usage-results] table').waitFor();const usage=await data(`/projects/${p}/model-usage`);assert(usage.some(r=>r.capability==='EMBEDDING'));assert(usage.some(r=>r.capability==='TEXT_GENERATION'));assert(usage.some(r=>r.unknownUsageCalls>0));assert((await page.locator('[data-usage-results]').innerText()).includes('不适用'));assert((await page.locator('[data-usage-results]').innerText()).includes('输入 未报告'));
    await page.locator('#model-usage-filter [name=capability]').selectOption('TEXT_GENERATION');assert(!(await page.locator('[data-usage-results] tbody').innerText()).includes('Embedding'));
    await capture('project-usage');await go(`workspace/${w}/models?section=usage`,'#model-usage-filter');await page.locator('[data-usage-results] table').waitFor();await capture('team-usage');pass('MODEL-10','团队与项目用量、类型筛选及缺失用量展示');

    const memberContext=await browser.newContext();const member=await memberContext.newPage();await login(member,seed.member);await member.goto(seed.base+`/#project/${p}/models`);await member.locator('#project-model-policy').waitFor();assert(await member.locator('#project-model-policy [name=model]').isDisabled());assert.equal(await member.locator('#project-model-policy button[type=submit]').count(),0);
    for(const suffix of ['model-connection','model-catalog','embedding-connection','embedding-catalog'])assert.equal((await api(`/workspaces/${w}/`+suffix,'GET',undefined,memberContext.request)).status(),403);
    assert.equal((await api(`/projects/${p}/model-policy`,'PUT',{expectedVersion:policy.version,model:null,maxCalls:1,maxMillis:1000},memberContext.request)).status(),403);
    await member.goto(seed.base+`/#workspace/${w}`);assert.equal(await member.getByRole('link',{name:'模型服务',exact:true}).count(),0);await memberContext.close();
    const otherContext=await browser.newContext();const other=await otherContext.newPage();await login(other,seed.otherAdmin);assert.equal((await api(`/workspaces/${w}/model-connection`,'GET',undefined,otherContext.request)).status(),403);assert.equal((await api(`/projects/${p}/model-usage`,'GET',undefined,otherContext.request)).status(),403);await otherContext.close();
    const maintainerContext=await browser.newContext();const maintainer=await maintainerContext.newPage();await login(maintainer,seed.maintainer);await maintainer.goto(seed.base+`/#project/${p}/models`);await maintainer.locator('#project-model-policy').waitFor();assert(await maintainer.locator('#project-model-policy button[type=submit]').isVisible());assert.equal((await api(`/workspaces/${w}/model-connection`,'GET',undefined,maintainerContext.request)).status(),403);await maintainerContext.close();
    pass('MODEL-11','成员只读、项目维护者与团队管理员分权、跨团队隔离');

    await go(`workspace/${w}/models`,text);await page.locator(text+' [name=enabled]').selectOption('false');await saved(text,'文本模型已保存');const availability=await data(`/projects/${p}/agent/availability`);assert.equal(availability.reason,'MODEL_CONNECTION_DISABLED');assert.equal(availability.enabled,false);
    await go(`project/${p}/overview`,'.project-stats');if(await page.locator('#assistant-toggle').getAttribute('aria-expanded')!=='true')await page.locator('#assistant-toggle').click();await page.getByText(/团队文本模型连接已停用/).waitFor();assert(await page.locator('#conversation-send').isDisabled());
    await go(`workspace/${w}/models`,text);await page.locator(text+' [name=enabled]').selectOption('true');await saved(text,'文本模型已保存');await capture('team-text');
    pass('MODEL-12','禁用与恢复、助手展示具体配置原因');

    await go(`project/${p}/models?section=vectors`,'#project-embedding-policy');await page.setViewportSize({width:390,height:844});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));await capture('mobile-vector');
    const tabs=page.getByRole('tab');await tabs.first().focus();await page.keyboard.press('End');assert.equal(await tabs.last().getAttribute('aria-selected'),'true');assert.equal(await tabs.last().getAttribute('tabindex'),'0');
    const stored=await page.evaluate(()=>JSON.stringify({...localStorage,...sessionStorage}));assert(!stored.includes('synthetic-browser-'));assert.deepEqual(errors,[]);
    pass('MODEL-13','移动端布局、键盘页签与凭据存储检查');
    fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({status:'PASS',checks,pageErrors:errors,supplier:'local deterministic fixtures'},null,2));
  }catch(error){await page.screenshot({path:path.join(out,'failure.png'),fullPage:true}).catch(()=>{});fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({status:'FAIL',checks,error:error.stack,pageErrors:errors},null,2));throw error;}
  finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
