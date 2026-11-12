// Browser journey against the real application and a dedicated MySQL fixture.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const seed = JSON.parse(fs.readFileSync(process.env.OVERVIEW_UI_SEED, 'utf8'));
const out = process.env.OVERVIEW_UI_OUTPUT;
const baseURL = new URL(seed.base);
assert.equal(baseURL.protocol, 'http:');
assert(['127.0.0.1', 'localhost'].includes(baseURL.hostname));
const base = baseURL.origin;
const checks = [], errors = [];
const pass = name => { checks.push(name); console.log('PASS', name); };

(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  const context = await browser.newContext({ viewport: { width: 1500, height: 1280 } });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  page.on('pageerror', e => errors.push(e.message));
  const p = seed.project;
  const nodes = '#map-canvas [data-map-task]';
  async function login(target, name) {
    await target.goto(base + '/#login');
    await target.locator('#login [name=username]').fill(name);
    await target.locator('#login [name=password]').fill(seed.password);
    await target.locator('#login button').click();
    await target.locator('#personal-filter').waitFor();
  }
  async function api(endpoint, method = 'GET', body, headers = {}, request = context.request) {
    const csrf = await (await request.get(base + '/session/csrf')).json();
    return request.fetch(base + endpoint, { method, data: body, headers: {
      ...(method === 'GET' ? {} : { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() }), ...headers
    } });
  }
  async function select(id) {
    await page.locator(`[data-map-task="${id}"]`).click();
    await page.locator('#map-inspector .map-detail-links').waitFor();
  }
  async function capture(name, keepScroll = false) {
    await page.evaluate(async keep => { if (!keep) document.querySelector('.workspace-content').scrollTop = 0; await document.fonts.ready; }, keepScroll);
    await page.screenshot({ path: path.join(out, name + '.png'), fullPage: true });
  }
  try {
    assert.equal((await context.request.get(base + `/projects/${p}/graph`)).status(), 403);
    assert.equal((await context.request.get(base + '/project-overview.js')).status(), 200);
    await login(page, seed.user);
    assert.equal((await api(`/projects/${seed.secret}/graph`)).status(), 403);
    const initialRepair = await (await api(`/projects/${p}/tasks/${seed.tasks[2]}`)).json();
    if (initialRepair.status !== 'TODO') {
      const reset = await api(`/projects/${p}/tasks/${seed.tasks[2]}`, 'PATCH', { status: 'TODO' }, { 'If-Match': String(initialRepair.revision) });
      assert(reset.ok(), await reset.text());
    }

    await page.goto(base + `/#project/${p}/overview`);
    await page.locator(nodes).first().waitFor();
    await page.waitForFunction(() => !routing);
    assert.equal(await page.locator(nodes).count(), 9);
    assert.equal(await page.locator('.map-edge').count(), 6);
    assert.equal(await page.locator('.map-legend i').count(), 4);
    const positions = await page.locator(nodes).evaluateAll(els => els.map(e => ({
      id: e.dataset.mapTask, x: parseFloat(e.style.left), y: parseFloat(e.style.top)
    })));
    for (const a of positions) for (const b of positions) if (a.id !== b.id)
      assert(a.x + 216 <= b.x || b.x + 216 <= a.x || a.y + 116 <= b.y || b.y + 116 <= a.y, 'overlapping nodes');
    await capture('overview-graph');
    pass('OVERVIEW-01 live project snapshot, branches, merge, isolated tasks and type colors');

    await select(seed.tasks[5]);
    assert.equal(await page.locator('#map-inspector h3').innerText(), '完成报表发布验收');
    assert.equal(await page.locator('#map-inspector [data-map-select]').count(), 3);
    assert.equal(await page.locator('#map-discussions .map-discussion').count(), 5);
    assert((await page.locator('#map-discussions').innerText()).includes('修复确认后由陈希开始回归'));
    assert(!(await page.locator('#map-discussions').innerText()).includes('验收讨论 1\n'));
    assert.equal(await page.locator('#map-discussions img').count(), 0);
    assert((await page.locator('#map-selection-status').innerText()).includes('上游 5 项，下游 1 项'));
    assert.equal(await page.locator('.map-node-muted').count(), 2);
    await capture('overview-task-discussion');
    await select(seed.tasks[8]);
    assert((await page.locator('#map-discussions').innerText()).includes('<img src=x onerror=alert(1)>'));
    assert.equal(await page.locator('#map-discussions img').count(), 0);
    await select(seed.tasks[5]);
    pass('OVERVIEW-02 select task, transitive path highlight, latest discussions and escaped content');

    await page.locator('#map-focus').check();
    assert.equal(await page.locator(nodes + ":visible").count(), 7);
    await page.locator('#map-focus').uncheck();
    await page.locator('#map-search').fill('导出遗漏');
    assert.equal(await page.locator('.map-search-match').count(), 1);
    await page.locator('#map-search').press('Enter');
    await page.locator('#map-inspector h3').filter({ hasText: '修复筛选后导出遗漏数据' }).waitFor();
    const before = await page.locator('#map-scale').innerText();
    await page.locator('#map-plus').click();
    assert.notEqual(await page.locator('#map-scale').innerText(), before);
    await page.locator('#map-fit').click();
    pass('OVERVIEW-03 focus dependency chain, locate by title and zoom controls');

    await select(seed.tasks[5]);
    await page.getByRole('link', { name: '打开完整任务 →', exact: true }).click();
    await page.locator('#task-summary').waitFor();
    await page.getByRole('link', { name: '← 返回项目概览', exact: true }).click();
    await page.locator('#map-inspector h3').filter({ hasText: '完成报表发布验收' }).waitFor();
    await page.locator('#map-discussions .map-discussion').first().getByRole('link').click();
    await page.locator('#comment-' + seed.lastComment + '.source-highlight').waitFor();
    await page.getByRole('link', { name: '← 返回项目概览', exact: true }).click();
    await page.locator('#map-inspector .map-detail-links').waitFor();
    await page.getByRole('link', { name: '全部讨论', exact: true }).click();
    await page.locator('#comments:focus').waitFor();
    await page.getByRole('link', { name: '← 返回项目概览', exact: true }).click();
    await page.locator('#map-inspector .map-detail-links').waitFor();
    pass('OVERVIEW-04 task and discussion deep links return to selected overview node');

    await page.locator('#map-close').click();
    assert(await page.locator('#map-inspector').isHidden());
    await page.locator(nodes).first().focus();
    await page.keyboard.press('ArrowRight');
    assert.equal(await page.locator(nodes + ':focus').count(), 1);
    await page.keyboard.press('Enter');
    await page.locator('#map-inspector .map-detail-links').waitFor();
    await page.keyboard.press('Escape');
    assert(await page.locator('#map-inspector').isHidden());
    assert.equal(await page.locator(nodes + ':focus').count(), 1);
    pass('OVERVIEW-05 keyboard node navigation, selection and Escape return focus');

    const repair = await (await api(`/projects/${p}/tasks/${seed.tasks[2]}`)).json();
    const updated = await api(`/projects/${p}/tasks/${seed.tasks[2]}`, 'PATCH', { status: 'DONE' }, { 'If-Match': String(repair.revision) });
    assert(updated.ok(), await updated.text());
    await page.locator('#map-refresh').click();
    await page.waitForFunction(id => document.querySelector(`[data-map-task="${id}"]`)?.textContent.includes('已完成'), seed.tasks[2]);
    assert((await page.locator(`[data-map-task="${seed.tasks[3]}"]`).innerText()).includes('可推进'));
    assert((await page.locator(`[data-map-task="${seed.tasks[5]}"]`).innerText()).includes('等待上游'));
    await capture('overview-unblocked');
    pass('OVERVIEW-06 real upstream completion updates ready task while downstream remains blocked');

    await page.route(base + `/projects/${p}/graph?view=overview`, route => route.abort('failed'));
    await page.locator('#map-refresh').click();
    await page.getByText('读取失败，当前保留上次读取的项目现状。请重新刷新或展开。').waitFor();
    assert.equal(await page.locator(nodes).count(), 9);
    await page.unroute(base + `/projects/${p}/graph?view=overview`);
    await page.locator('#map-refresh').click();
    await page.getByText('当前展示 9 / 9 项任务、6 / 6 条依赖。', { exact: true }).waitFor();
    await page.goto(base + `/#project/${seed.empty}/overview`);
    await page.getByText('从第一项工作开始', { exact: true }).waitFor();
    await page.goto(base + `/#project/${p}/overview`);
    await page.locator(nodes).first().waitFor();
    await page.route(base + `/projects/${p}/tasks/${seed.tasks[5]}/preview?commentLimit=5`, route => route.abort('failed'));
    await page.locator(`[data-map-task="${seed.tasks[5]}"]`).click();
    await page.locator('#map-detail-retry').waitFor();
    await page.unroute(base + `/projects/${p}/tasks/${seed.tasks[5]}/preview?commentLimit=5`);
    await page.locator('#map-detail-retry').click();
    await page.locator('#map-inspector .map-detail-links').waitFor();
    pass('OVERVIEW-07 refresh failure preserves graph, empty project and task retry');

    const memberContext = await browser.newContext();
    const member = await memberContext.newPage(); await login(member, seed.member);
    assert.equal((await memberContext.request.get(base + `/projects/${p}/graph`)).status(), 200);
    await member.goto(base + `/#project/${p}/overview?task=${seed.tasks[5]}`);
    await member.locator('#map-inspector .map-detail-links').waitFor();
    await memberContext.close();
    const outsideContext = await browser.newContext();
    const outside = await outsideContext.newPage(); await login(outside, seed.outsider);
    assert.equal((await outsideContext.request.get(base + `/projects/${p}/graph`)).status(), 403);
    await outsideContext.close();
    pass('OVERVIEW-08 members read graph and team admin requires project access');

    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(base + `/#project/${p}/overview`);
    await page.locator(nodes).first().waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 2), false);
    assert((await page.locator('#map-viewport').boundingBox()).y < 700, 'graph appears in the mobile first screen');
    await capture('overview-mobile');
    await select(seed.tasks[5]);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 2), false);
    await page.locator('#map-inspector').evaluate(el => el.scrollIntoView({block:'start'}));
    await capture('overview-mobile-task', true);
    assert.deepEqual(errors, []);
    pass('OVERVIEW-09 mobile graph scroll and task discussions without page overflow or browser errors');
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify({ status: 'PASS', checks, errors }, null, 2));
  } catch (error) {
    await page.screenshot({ path: path.join(out, 'failure.png'), fullPage: true }).catch(() => {});
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify({ status: 'FAIL', checks, errors, error: error.stack, body: await page.locator('body').innerText() }, null, 2));
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
