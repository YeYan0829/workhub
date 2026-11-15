// Follow GETTING_STARTED through real forms on a disposable local installation.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {randomUUID} = require('node:crypto');
const {chromium} = require('playwright');

const mode = process.argv[2];
assert(['seed', 'check'].includes(mode), 'Usage: verify-first-run.cjs seed|check');
assert.equal(process.env.WORKHUB_FIRST_RUN_TEST, 'true', 'Use a dedicated test installation');
function local(value) {
  const u = new URL(value);
  assert.equal(u.protocol, 'http:');
  assert(['127.0.0.1', 'localhost', '[::1]'].includes(u.hostname));
  return u.origin;
}
const base = local(process.env.APP_URL || 'http://127.0.0.1:18080');
const mail = local(process.env.MAILPIT_URL || 'http://127.0.0.1:18025');
const out = path.resolve(process.env.FIRST_RUN_TEST_DIR || 'target/first-run');
fs.mkdirSync(out, {recursive:true});
const stateFile = path.join(out, 'state.json'), storageFile = path.join(out, 'session.json');
const titles = ['确定报表字段与接口', '完成报表页面与导出入口', '修复筛选后导出遗漏数据',
  '回归筛选与导出结果', '补齐报表使用说明', '完成报表发布验收', '发布报表功能公告', '整理功能截图素材'];
const edges = [[0,1], [1,3], [2,3], [3,5], [4,5], [5,6]];
const documentTitle = '报表发布验收清单';
const documentBody = '导出数据应与筛选页面一致；回归通过、使用说明补齐后，再确认发布验收。截图素材可并行整理。';
const discussion = '回归覆盖日期筛选、跨页导出和导出行数。';
const checks = [], errors = [];
function pass(name) { checks.push(name); console.log('PASS', name); }
async function verification(email) {
  const deadline = Date.now() + 120000;
  while (Date.now() < deadline) {
    const response = await fetch(mail + '/api/v1/messages', {signal:AbortSignal.timeout(5000)});
    assert(response.ok());
    for (const m of (await response.json()).messages || []) {
      if (!(m.To || []).some(to => to.Address === email)) continue;
      const r = await fetch(mail + '/api/v1/message/' + m.ID, {signal:AbortSignal.timeout(5000)});
      assert(r.ok());
      const link = (await r.json()).Text?.match(/https?:\/\/[^\s<>]+\/#verify\/[A-Za-z0-9_-]+/)?.[0];
      if (link) { assert.equal(new URL(link).origin, base); return link; }
    }
    await new Promise(r => setTimeout(r, 1000));
  }
  throw Error('Verification email did not arrive');
}
(async () => {
  const browser = await chromium.launch({headless:true, args:['--no-sandbox']});
  let page, writerContext;
  const context = await browser.newContext(mode === 'check' ? {storageState:storageFile} : {});
  context.on('page', p => p.on('pageerror', e => errors.push(e.message)));
  try {
    page = await context.newPage(); page.setDefaultTimeout(30000);
    let state;
    async function taskPage(index, target=page) {
      await target.goto(base + `/#task/${state.project}/${state.tasks[index]}`);
      await target.locator('#task-summary').waitFor();
    }
    async function status(index, value, target=page) {
      await taskPage(index, target);
      await target.locator('#edit-task').click();
      await target.locator('#task-edit [name=status]').selectOption(value);
      await target.locator('#task-edit button[type=submit], #task-edit > button:not([type=button])').first().click();
      await target.locator('#task-editor').waitFor({state:'hidden'});
    }
    async function overview() {
      await page.goto(base + `/#project/${state.project}/overview`);
      await page.locator(`[data-map-task="${state.tasks[5]}"]`).waitFor();
      await page.locator(`[data-map-task="${state.tasks[5]}"]`).click();
      await page.locator('#map-inspector .map-detail-links').waitFor();
    }
    if (mode === 'seed') {
      assert(!fs.existsSync(stateFile), 'Start with a new evidence directory');
      const id = randomUUID().replaceAll('-', '').slice(0,12);
      state = {base, login:'first_'+id, email:'first_'+id+'@example.test', displayName:'首次体验用户',
        password:'First-Run-'+randomUUID(), tasks:[]};
      await page.goto(base + '/#register');
      for (const name of ['login','email','displayName','password']) await page.locator(`#register [name=${name}]`).fill(state[name]);
      await page.locator('#register button').click();
      await page.waitForFunction(() => document.querySelector('#notice')?.textContent.includes('注册已受理'));
      await page.goto(await verification(state.email)); await page.locator('#verify').click();
      await page.waitForURL('**/#login');
      await page.locator('#login [name=username]').fill(state.login);
      await page.locator('#login [name=password]').fill(state.password);
      await page.locator('#login button').click(); await page.locator('#personal-filter').waitFor();
      pass('register, receive verification mail and log in');
      const me = await context.request.get(base + '/session/me'); assert(me.ok());
      state.userId = (await me.json()).userId;
      await page.goto(base + '/#home');
      await page.locator('#createWorkspace-disclosure > summary').click();
      await page.locator('#createWorkspace [name=name]').fill('星河产品团队');
      await page.locator('#createWorkspace button:not([type=button])').click();
      await page.waitForURL(/#workspace\/[a-f0-9-]{36}$/); state.workspace = page.url().split('/').pop();
      await page.locator('#newProject-disclosure > summary').click();
      await page.locator('#newProject [name=name]').fill('客户报表发布');
      await page.locator('#newProject button:not([type=button])').click();
      await page.waitForURL(/#project\/[a-f0-9-]{36}$/); state.project = page.url().split('/').pop();
      for (let i=0; i<titles.length; i++) {
        await page.goto(base + `/#project/${state.project}/tasks`);
        await page.locator('#newTask-disclosure > summary').click();
        await page.locator('#newTask [name=title]').fill(titles[i]);
        await page.locator('#newTask [name=description]').fill('按首次体验指南建立的报表发布工作。');
        await page.locator('#newTask [name=assigneeId]').selectOption(state.userId);
        await page.locator('#newTask button:not([type=button])').click();
        await page.waitForURL(/#task\/[a-f0-9-]{36}\/[a-f0-9-]{36}$/);
        state.tasks.push(page.url().split('/').pop());
      }
      await status(0,'DONE'); await status(1,'DONE'); await status(4,'IN_PROGRESS');
      pass('create a team, project and eight tasks using forms');
      for (const [up,down] of edges) {
        await taskPage(down);
        await page.locator('#dependency-editor > summary').click();
        await page.locator('#dependency [name=q]').fill(titles[up]);
        await page.locator('#dependency-options [role=option]').filter({hasText:titles[up]}).click();
        await page.locator('#dependency > button').click();
        await page.locator('#upstream').getByRole('link', {name:titles[up], exact:true}).waitFor();
      }
      pass('add all six dependencies using title suggestions');
      await page.goto(base + `/#project/${state.project}/documents`);
      await page.locator('#document-disclosure > summary').click();
      await page.locator('#document [name=title]').fill(documentTitle);
      await page.locator('#document [name=body]').fill(documentBody);
      await page.locator('#document button:not([type=button])').click();
      await page.waitForURL(/#document\/[a-f0-9-]{36}\/[a-f0-9-]{36}$/);
      state.document = page.url().split('/').pop();
      await taskPage(5);
      await page.locator('#links details > summary').click();
      await page.locator('#linkDocument [name=q]').fill(documentTitle);
      await page.locator('#document-options [role=option]').filter({hasText:documentTitle}).click();
      await page.locator('#linkDocument > button').click();
      await page.locator('#links').getByRole('link',{name:documentTitle, exact:true}).waitFor();
      await page.locator('#comment textarea').fill(discussion); await page.locator('#comment > button').click();
      await page.getByText(discussion,{exact:true}).waitFor();
      pass('create, link and read the release checklist and discussion');
      await overview();
      assert((await page.locator(`[data-map-task="${state.tasks[3]}"]`).innerText()).includes('等待上游'));
      assert((await page.locator(`[data-map-task="${state.tasks[5]}"]`).innerText()).includes('等待上游'));
      assert((await page.locator(`[data-map-task="${state.tasks[7]}"]`).innerText()).includes('可推进'));
      await page.screenshot({path:path.join(out,'before-update.png'),fullPage:true});
      pass('overview identifies regression and acceptance blockers and parallel work');
      const before = await page.evaluate(() => document.querySelector('#panel').overviewState());
      writerContext = await browser.newContext({storageState:await context.storageState()});
      const writer = await writerContext.newPage(); writer.setDefaultTimeout(30000);
      writer.on('pageerror',e=>errors.push(e.message));
      await status(2,'DONE',writer);
      await page.waitForFunction(id => document.querySelector(`[data-map-task="${id}"]`)?.textContent.includes('可推进'), state.tasks[3]);
      assert((await page.locator(`[data-map-task="${state.tasks[5]}"]`).innerText()).includes('等待上游'));
      const after = await page.evaluate(() => document.querySelector('#panel').overviewState());
      assert.equal(after.selected,before.selected); assert.equal(after.scale,before.scale);
      pass('second window completes repair; regression unblocks while selection and zoom remain');
      await page.screenshot({path:path.join(out,'after-update.png'),fullPage:true});
      await context.storageState({path:storageFile});
      fs.writeFileSync(stateFile,JSON.stringify(state,null,2),{mode:0o600});
    } else {
      state = JSON.parse(fs.readFileSync(stateFile,'utf8')); assert.equal(state.base,base);
      const me = await context.request.get(base + '/session/me'); assert(me.ok());
      assert.equal((await me.json()).userId,state.userId);
      await taskPage(5);
      await page.getByText(discussion,{exact:true}).waitFor();
      await page.locator('#links').getByRole('link',{name:documentTitle,exact:true}).waitFor();
      await page.goto(base + `/#document/${state.project}/${state.document}`);
      await page.getByText(documentBody,{exact:true}).waitFor();
      await overview();
      assert.equal(await page.locator('[data-map-task]').count(),8);
      assert((await page.locator(`[data-map-task="${state.tasks[3]}"]`).innerText()).includes('可推进'));
      assert((await page.locator(`[data-map-task="${state.tasks[5]}"]`).innerText()).includes('等待上游'));
      pass('saved session, eight tasks, dependencies, document and discussion survive recreation');
    }
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+2),false);
    assert.deepEqual(errors,[]);
    pass('390px viewport has no horizontal page overflow or browser errors');
    await page.screenshot({path:path.join(out,mode+'-mobile.png'),fullPage:true});
    fs.writeFileSync(path.join(out,`${mode}-result-${Date.now()}.json`),JSON.stringify({status:'PASS',mode,checks,errors},null,2));
  } catch (error) {
    await page?.screenshot({path:path.join(out,`failure-${Date.now()}.png`),fullPage:true}).catch(()=>{});
    fs.writeFileSync(path.join(out,`failure-${Date.now()}.json`),JSON.stringify({status:'FAIL',mode,checks,errors,error:error.stack},null,2));
    throw error;
  } finally { await writerContext?.close(); await context.close(); await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
