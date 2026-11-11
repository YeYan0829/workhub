// Real-browser content regressions using a dedicated local Docker smoke-test account.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { chromium } = require('playwright');

assert.equal(process.env.WORKHUB_FRONTEND_TEST, 'true', 'Use WORKHUB_FRONTEND_TEST=true with an isolated instance');
const seed = path.resolve(process.env.FRONTEND_SEED_DIR || 'target/docker-test');
const state = JSON.parse(fs.readFileSync(path.join(seed, 'state.json')));
const url = new URL(process.env.APP_URL || state.base);
assert.equal(url.protocol, 'http:');
assert(['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname));
assert.equal(url.origin, state.base);
const base = url.origin;
const out = path.resolve(process.env.FRONTEND_TEST_DIR || 'target/frontend-test');
fs.mkdirSync(out, { recursive: true });
const checks = [];
const save = value => fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(value, null, 2) + '\n');
const pass = name => { checks.push(name); console.log('PASS ' + name); };

(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  const context = await browser.newContext();
  const page = await context.newPage();
  page.setDefaultTimeout(30000);
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', dialog => dialog.accept());
  let projectId;
  try {
    await page.goto(base + '/#login');
    await page.locator('#login [name=username]').fill(state.login);
    await page.locator('#login [name=password]').fill(state.password);
    await page.locator('#login button').click();
    await page.waitForSelector('#personal-filter');
    const request = context.request;
    const csrf = await (await request.get(base + '/session/csrf')).json();
    async function api(endpoint, method = 'GET', body, extra = {}) {
      const response = await request.fetch(base + endpoint, { method, data: body,
        headers: method === 'GET' ? extra : { [csrf.headerName]: csrf.token, 'Idempotency-Key': randomUUID(), ...extra } });
      assert(response.ok(), `${method} ${endpoint}: ${response.status()} ${await response.text()}`);
      const text = await response.text(); return text ? JSON.parse(text) : null;
    }
    projectId = (await api(`/workspaces/${state.workspaceId}/projects`, 'POST', { name: 'Content regression ' + randomUUID().slice(0, 8) })).id;
    const p = projectId;
    const taskTitle = 'Recover creation ' + randomUUID();
    await page.goto(base + `/#project/${p}/tasks`);
    await page.locator('#newTask-disclosure > summary').click();
    await page.locator('#newTask [name=title]').fill(taskTitle);
    const creationKeys = [];
    let dropCreation = true;
    await page.route(base + `/projects/${p}/tasks`, async route => {
      if (route.request().method() !== 'POST') return route.continue();
      creationKeys.push(route.request().headers()['idempotency-key']);
      if (dropCreation) { dropCreation = false; assert((await route.fetch()).ok()); await route.abort('failed'); }
      else await route.continue();
    });
    await page.locator('#newTask button:not([type=button])').click();
    await page.waitForFunction(() => document.querySelector('#notice')?.textContent.includes('网络连接失败'));
    assert(await page.locator('#newTask [name=title]').isDisabled());
    await page.reload();
    await page.locator('#newTask-disclosure > summary').click();
    assert.equal(await page.locator('#newTask [name=title]').inputValue(), taskTitle);
    await page.locator('#newTask').getByRole('button', { name: '确认上次提交', exact: true }).click();
    await page.waitForURL(/#task\//);
    const found = await api(`/projects/${p}/task-search?` + new URLSearchParams({ q: taskTitle }));
    assert.equal(found.items.length, 1);
    assert.equal(creationKeys.length, 2);
    assert.equal(creationKeys[0], creationKeys[1]);
    const taskId = found.items[0].id, taskBase = `/projects/${p}/tasks/${taskId}`;
    await page.unroute(base + `/projects/${p}/tasks`);
    pass('BOUND-01 lost creation response and page reload recover one task');

    const comments = [];
    for (let i = 1; i <= 52; i++) comments.push(await api(taskBase + '/comments', 'POST', { body: 'Discussion ' + String(i).padStart(3, '0') }));
    const documents = [];
    for (let i = 1; i <= 101; i++) documents.push(await api(`/projects/${p}/documents`, 'POST', { title: 'Release notes ' + String(i).padStart(3, '0'), body: 'Background ' + i }));
    fs.writeFileSync(path.join(out, 'fixture.json'), JSON.stringify({ projectId: p, taskId, comments: comments.map(c => c.id), documents: documents.map(d => d.id) }, null, 2));

    await page.goto(base + `/#task/${p}/${taskId}`);
    await page.reload();
    await page.waitForFunction(() => document.querySelectorAll('#comment-items .task-comment').length === 50);
    assert.equal(await page.locator('#comment-items .task-comment').count(), 50);
    assert.equal(await page.locator('#comment-' + comments.at(-1).id).count(), 1);
    assert.equal(await page.locator('#comment-' + comments[0].id).count(), 0);
    await page.locator('#comment textarea').fill('Draft survives older discussion');
    await page.locator('#comments-older').click();
    await page.waitForFunction(() => document.querySelectorAll('#comment-items .task-comment').length === 52);
    assert.equal(await page.locator('#comment textarea').inputValue(), 'Draft survives older discussion');
    assert(await page.locator('#comments-older').isHidden());
    pass('CONTENT-06 latest fifty and older discussion preserve the draft');

    await page.goto(base + `/#task/${p}/${taskId}?` + new URLSearchParams({ comment: comments[0].id, from: `project/${p}/agent?conversation=${randomUUID()}`, start: 0, end: 5 }));
    await page.waitForSelector('#comment-' + comments[0].id + '.source-highlight');
    assert.equal(await page.locator('#comment-' + comments[0].id + ' mark').innerText(), 'Discu');
    await page.locator('#comment-' + comments[0].id + ' details > summary').click();
    await page.locator('#edit-comment-' + comments[0].id + ' textarea').fill('Updated historical source');
    await page.locator('#edit-comment-' + comments[0].id + ' button').click();
    await page.waitForFunction(() => document.querySelector('#notice')?.textContent.includes('评论已更新'));
    assert.equal(await page.locator('#comment-' + comments[0].id + ' .comment-body').innerText(), 'Updated historical source');
    pass('CONTENT-06 and QA-04 old comment deep link highlights and remains readable after editing');

    await page.goto(base + `/#task/${p}/${taskId}`);
    await page.locator('#comment textarea').fill('Visible new discussion');
    await page.locator('#comment button:not([type=button])').click();
    await page.waitForFunction(() => document.querySelector('#notice')?.textContent.includes('评论已发布'));
    assert((await page.locator('#comment-items').innerText()).includes('Visible new discussion'));
    pass('CONTENT-06 newly posted discussion is immediately visible');

    await page.goto(base + `/#project/${p}/documents`);
    await page.waitForSelector('#document-items table tbody tr');
    assert.equal(await page.locator('#document-items tbody tr').count(), 50);
    await page.locator('#document-more').click();
    await page.waitForFunction(() => document.querySelectorAll('#document-items tbody tr').length === 100);
    await page.locator('#document-more').click();
    await page.waitForFunction(() => document.querySelectorAll('#document-items tbody tr').length === 101);
    assert(await page.locator('#document-more').isHidden());
    const oldTitle = documents[0].title;
    await page.locator('#document-items').getByRole('link', { name: oldTitle, exact: true }).click();
    await page.waitForSelector('#document-body');
    await page.getByRole('link', { name: '← 返回文档列表', exact: true }).click();
    await page.locator('#document-items').getByRole('link', { name: oldTitle, exact: true }).waitFor();
    await page.locator('#document-filter [name=q]').fill(oldTitle);
    await page.locator('#document-filter button').click();
    await page.waitForFunction(() => document.querySelectorAll('#document-items tbody tr').length === 1);
    assert.equal(await page.locator('#document-items tbody tr td:first-child').innerText(), oldTitle);
    pass('CONTENT-07 document pagination, return path, and literal full-title search');

    await page.goto(base + `/#task/${p}/${taskId}`);
    await page.locator('#link-editor > summary').click();
    await page.locator('#linkDocument [name=q]').fill(oldTitle);
    await page.locator('#document-options [data-option]').first().click();
    assert.equal(await page.locator('#linkDocument [name=id]').inputValue(), documents[0].id);
    await page.locator('#linkDocument > button').click();
    await page.locator('#links').getByRole('link', { name: oldTitle, exact: true }).waitFor();
    await page.locator('#link-editor > summary').click();
    await page.locator('#url [name=url]').fill('https://example.test/release-notes');
    await page.locator('#url button').click();
    await page.locator('[data-unlink-resource]').waitFor();
    pass('CONTENT-08 document beyond the first hundred can be selected and its title is preserved');

    await page.locator('[data-unlink-document]').click();
    await page.waitForFunction(() => !document.querySelector('[data-unlink-document]'));
    await page.locator('[data-unlink-resource]').click();
    await page.waitForFunction(() => !document.querySelector('[data-unlink-resource]'));
    const links = await api(taskBase + '/links');
    assert.equal(links.documents.length, 0); assert.equal(links.resources.length, 0);
    assert.equal((await api(`/projects/${p}/documents/${documents[0].id}`)).body, 'Background 1');
    pass('CONTENT-05 unlink both resource types while retaining the original document');

    await page.goto(base + `/#project/${p}?q=` + encodeURIComponent(taskTitle));
    await page.waitForFunction(() => !routing);
    await page.locator('#tasks').getByRole('link', {name: taskTitle, exact: true}).waitFor();
    assert.equal(await page.locator('#scope-nav a[aria-current="page"]').last().innerText(), '项目任务');
    pass('TASK-07 filtered task routes keep the task navigation selected');

    const duplicates = await page.evaluate(() => { const ids = [...document.querySelectorAll('[id]')].map(n => n.id); return ids.filter((id, i) => ids.indexOf(id) !== i); });
    assert.deepEqual(duplicates, []); assert.deepEqual(errors, []);
    await page.screenshot({ path: path.join(out, 'content.png'), fullPage: true });
    save({ status: 'PASSED', checks, projectId, pageErrors: errors });
  } catch (error) {
    await page.screenshot({ path: path.join(out, 'failed.png'), fullPage: true }).catch(() => {});
    save({ status: 'FAILED', checks, projectId, error: error.stack, pageErrors: errors });
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
