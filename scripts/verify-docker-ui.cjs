// Browser smoke checks against a dedicated, local Compose environment.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { chromium } = require('playwright');

const mode = process.argv[2];
assert(['seed', 'queue', 'check'].includes(mode), 'Usage: node scripts/verify-docker-ui.cjs seed|queue|check');
function localUrl(value) {
  const url = new URL(value);
  assert.equal(url.protocol, 'http:');
  assert(['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname), 'Use a dedicated local test instance');
  return url.origin;
}
const base = localUrl(process.env.APP_URL || 'http://127.0.0.1:18080');
const mail = localUrl(process.env.MAILPIT_URL || 'http://127.0.0.1:18025');
assert.equal(process.env.WORKHUB_DOCKER_TEST, 'true', 'Set WORKHUB_DOCKER_TEST=true for an isolated test environment');
const out = path.resolve(process.env.DOCKER_TEST_DIR || 'target/docker-test');
fs.mkdirSync(out, { recursive: true });
const stateFile = path.join(out, 'state.json');
const cookieFile = path.join(out, 'session.json');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function json(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(5000) });
  assert(response.ok, `HTTP ${response.status}: ${url}`);
  return response.json();
}
async function verificationLink(email) {
  const deadline = Date.now() + 120000;
  while (Date.now() < deadline) {
    const list = await json(mail + '/api/v1/messages');
    for (const message of list.messages || []) {
      if (!(message.To || []).some(to => to.Address === email)) continue;
      const detail = await json(mail + '/api/v1/message/' + message.ID);
      const link = detail.Text?.match(/https?:\/\/[^\s<>]+\/#verify\/[A-Za-z0-9_-]+/)?.[0];
      if (link) {
        assert.equal(new URL(link).origin, base, 'Email URL must use the browser-facing application address');
        return link;
      }
    }
    await pause(1000);
  }
  throw new Error('Verification email did not arrive: ' + email);
}
async function register(page, account) {
  await page.goto(base + '/#register');
  for (const name of ['login', 'email', 'displayName', 'password'])
    await page.locator(`#register [name=${name}]`).fill(account[name]);
  await page.locator('#register button').click();
  await page.waitForFunction(() => document.querySelector('#notice')?.textContent.includes('注册已受理'));
}
async function verify(page, email) {
  await page.goto(await verificationLink(email));
  await page.locator('#verify').click();
  await page.waitForURL('**/#login');
}

(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  let context, page;
  const errors = [];
  try {
    let state;
    let pendingVerifiedNow = false;
    if (mode === 'seed') {
      assert(!fs.existsSync(stateFile), 'Use a fresh DOCKER_TEST_DIR for a new run');
      const login = 'docker_' + randomUUID().replaceAll('-', '').slice(0, 12);
      state = { base, login, email: login + '@example.test', displayName: 'Docker 验证用户',
        password: 'Docker-Smoke-' + randomUUID(), title: '容器重建后保留的任务',
        comment: '通过容器中的正式业务入口保存。' };
    } else {
      state = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
      assert.equal(state.base, base, 'Use the same isolated application');
    }
    context = await browser.newContext(mode === 'check' ? { storageState: cookieFile } : {});
    page = await context.newPage();
    page.setDefaultTimeout(90000);
    page.on('pageerror', error => errors.push(error.message));
    if (mode === 'seed') {
      await register(page, state);
      console.log('Registered synthetic account');
      await verify(page, state.email);
      console.log('SMTP delivery and email verification passed');
      await page.locator('#login [name=username]').fill(state.login);
      await page.locator('#login [name=password]').fill(state.password);
      await page.locator('#login button').click();
      await page.waitForURL('**/#mine');
      const meResponse = await page.request.get(base + '/session/me');
      assert(meResponse.ok());
      state.userId = (await meResponse.json()).userId;
      await page.goto(base + '/#home');
      await page.locator('#createWorkspace-disclosure > summary').click();
      await page.locator('#createWorkspace [name=name]').fill('Docker 本地验收');
      await page.locator('#createWorkspace button:not([type=button])').click();
      await page.waitForURL(/#workspace\/[a-f0-9-]{36}$/);
      state.workspaceId = page.url().split('/').pop();
      await page.locator('#newProject-disclosure > summary').click();
      await page.locator('#newProject [name=name]').fill('容器启动与数据保留');
      await page.locator('#newProject button:not([type=button])').click();
      await page.waitForURL(/#project\/[a-f0-9-]{36}$/);
      state.projectId = page.url().split('/').pop();
      await page.goto(base + '/#project/' + state.projectId + '/tasks');
      await page.locator('#newTask-disclosure > summary').click();
      await page.locator('#newTask [name=title]').fill(state.title);
      await page.locator('#newTask [name=description]').fill('验证源码镜像、数据库和持久会话。');
      await page.locator('#newTask [name=assigneeId]').selectOption(state.userId);
      await page.locator('#newTask button:not([type=button])').click();
      await page.waitForURL(/#task\/[a-f0-9-]{36}\/[a-f0-9-]{36}$/);
      state.taskId = page.url().split('/').pop();
      console.log('Workspace, project and task created through browser');
      await page.locator('#comment textarea').fill(state.comment);
      await page.locator('#comment button').click();
      await page.getByText(state.comment, { exact: true }).first().waitFor();
      await context.storageState({ path: cookieFile });
    } else if (mode === 'queue') {
      // Stop only this test environment's Mailpit before this phase.
      state.pending = { login: state.login + '_p_' + randomUUID().slice(0, 6), email: state.login + '_p_' + randomUUID().slice(0, 6) + '@example.test',
        displayName: '待投递邮件验证用户', password: 'Docker-Pending-' + randomUUID() };
      await register(page, state.pending);
    } else {
      const meResponse = await context.request.get(base + '/session/me');
      assert(meResponse.ok(), 'Database session must survive application recreation');
      assert.equal((await meResponse.json()).userId, state.userId);
      await page.goto(base + '/#task/' + state.projectId + '/' + state.taskId);
      await page.locator('#task-summary').waitFor();
      await page.getByRole('heading', { name: state.title, exact: true }).waitFor();
      await page.getByText(state.comment, { exact: true }).first().waitFor();
      const response = await context.request.get(base + '/projects/' + state.projectId + '/tasks/' + state.taskId);
      assert(response.ok());
      const task = await response.json();
      assert.equal(task.title, state.title);
      assert.equal(task.assigneeId, state.userId);
      assert.equal(task.status, 'TODO');
      if (state.pending && !state.pending.verified) {
        const pendingContext = await browser.newContext();
        try {
          await verify(await pendingContext.newPage(), state.pending.email);
          state.pending.verified = true;
          pendingVerifiedNow = true;
        }
        finally { await pendingContext.close(); }
      }
    }
    assert.deepEqual(errors, []);
    await page.screenshot({ path: path.join(out, mode + '.png'), fullPage: true });
    fs.writeFileSync(stateFile, JSON.stringify(state, null, 2), { mode: 0o600 });
    let resultFile = path.join(out, mode + '-result.json');
    if (fs.existsSync(resultFile)) resultFile = path.join(out, mode + '-result-' + Date.now() + '.json');
    fs.writeFileSync(resultFile, JSON.stringify({ status: 'PASSED', mode,
      checks: mode === 'seed' ? ['browser registration', 'RabbitMQ worker SMTP delivery', 'public verification URL',
        'email verification', 'login', 'workspace/project/task/comment creation', 'no page errors'] :
        mode === 'queue' ? ['pending encrypted email registered while SMTP unavailable'] :
        ['persisted session', 'persisted task/assignee/status/comment',
          ...(pendingVerifiedNow ? ['old encrypted email delivered and verified after recreation'] : []), 'no page errors'] }, null, 2));
    console.log(JSON.stringify({ status: 'PASSED', mode, output: out }));
  } catch (error) {
    if (page) await page.screenshot({ path: path.join(out, mode + '-failed-' + Date.now() + '.png'), fullPage: true }).catch(() => {});
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
