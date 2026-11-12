"use strict";
const $ = (s) => document.querySelector(s),
  app = $("#app"),
  notice = $("#notice");
const esc = (v) =>
  String(v ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const link = (href, text) => `<a href="#${esc(href)}">${esc(text)}</a>`;
const input = (name, label, type = "text", value = "", required = true) =>
  `<label>${esc(label)}<br><input name="${name}" type="${type}" value="${esc(value)}" ${required ? "required" : ""}></label>`;
const select = (name, label, values, current = "") =>
  `<label>${label?label+"<br>":""}<select name="${name}" ${label?"":'aria-label="切换团队"'}>${values.map(([v, l]) => `<option value="${esc(v)}" ${String(v) === String(current) ? "selected" : ""}>${esc(l)}</option>`).join("")}</select></label>`;
const form = (id, title, fields, button = "保存") =>
  `<form id="${id}" aria-label="${esc(title)}"><h2>${title}</h2>${fields}<button>${button}</button></form>`;
const table = (headers, rows, empty = "暂无记录。") =>
  rows.length
    ? `<div class="table-scroll" tabindex="0" role="region" aria-label="数据列表"><table><thead><tr>${headers.map((x) => `<th>${esc(x)}</th>`).join("")}</tr></thead><tbody>${rows.map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`
    : `<p class="empty">${esc(empty)}</p>`;

// Suggestions stay beside the field and share mouse and keyboard selection behavior.
function suggestionPicker(field, area, choose) {
  let items = [], active = -1;
  field.setAttribute("role", "combobox");
  field.setAttribute("aria-autocomplete", "list");
  field.setAttribute("aria-controls", area.id + "-list");
  field.setAttribute("aria-expanded", "false");
  function close() {
    area.hidden = true;
    field.setAttribute("aria-expanded", "false");
    field.removeAttribute("aria-activedescendant");
  }
  function highlight(index) {
    active = index;
    const options = area.querySelectorAll("[role=option]");
    options.forEach((el, i) => el.setAttribute("aria-selected", String(i === index)));
    if (options[index]) {
      field.setAttribute("aria-activedescendant", options[index].id);
      options[index].scrollIntoView({ block: "nearest" });
    }
  }
  function pick(index) {
    if (!items[index]) return;
    const item = items[index];
    close();
    choose(item);
  }
  function show(values, empty = "没有匹配结果。", footer = "") {
    items = values; active = -1;
    field.removeAttribute("aria-activedescendant");
    area.innerHTML = `<div id="${area.id}-list" role="listbox" aria-label="${esc(field.getAttribute('aria-label') || '匹配结果')}">${values.map((v, i) => `<button type="button" role="option" tabindex="-1" id="${area.id}-${i}" aria-selected="false" data-option="${i}"><strong>${esc(v.text)}</strong>${v.detail ? '<span>' + esc(v.detail) + '</span>' : ''}</button>`).join("")}</div>` + (values.length ? "" : `<p role="status">${esc(empty)}</p>`) + (footer ? `<p class="muted">${esc(footer)}</p>` : "");
    area.hidden = false;
    field.setAttribute("aria-expanded", "true");
  }
  area.addEventListener("mousedown", e => e.preventDefault());
  area.addEventListener("click", e => {
    const option = e.target.closest("[data-option]");
    if (option) pick(Number(option.dataset.option));
  });
  field.addEventListener("keydown", e => {
    if (area.hidden || e.isComposing) return;
    if (["ArrowDown", "ArrowUp"].includes(e.key) && items.length) {
      e.preventDefault();
      highlight(active < 0 ? (e.key === "ArrowDown" ? 0 : items.length - 1) : (active + (e.key === "ArrowDown" ? 1 : -1) + items.length) % items.length);
    } else if (e.key === "Enter" && active >= 0) {
      e.preventDefault(); e.stopImmediatePropagation(); pick(active);
    } else if (e.key === "Escape") { e.preventDefault(); close(); }
  });
  field.addEventListener("blur", close);
  return { show, close };
}
function attachMentions(field, members, id) {
  field.parentElement.classList.add("suggestion-field");
  const area = document.createElement("div");
  area.id = id; area.className = "suggestions mention-suggestions"; area.hidden = true;
  field.insertAdjacentElement("afterend", area);
  let token;
  const picker = suggestionPicker(field, area, member => {
    if (!token) return;
    const insertion = "@" + member.login + " ";
    field.setRangeText(insertion, token.start, token.end, "end");
    field.focus(); token = null;
    field.dispatchEvent(new Event("input", { bubbles: true }));
  });
  function update() {
    const end = field.selectionStart;
    if (field.selectionEnd !== end) { picker.close(); token = null; return; }
    const match = field.value.slice(0, end).match(/(?:^|[^\w@])@([^\s@]*)$/);
    if (!match) { picker.close(); token = null; return; }
    token = { start: end - match[1].length - 1, end };
    const query = match[1].toLocaleLowerCase();
    const matches = members.filter(m => m.active && (m.login.toLocaleLowerCase().includes(query) || m.displayName.toLocaleLowerCase().includes(query)));
    picker.show(matches.slice(0, 20).map(m => ({ ...m, text: m.displayName, detail: "@" + m.login })), "没有匹配的项目成员。", (matches.length > 20 ? "仅显示前20位，请输入名字缩小范围。" : "") + "仅列出当前有权访问此项目的成员；↑↓选择，Enter 插入，Esc 收起。");
  }
  for (const event of ["input", "click", "focus"]) field.addEventListener(event, update);
  field.addEventListener("keyup", e => { if (["ArrowLeft", "ArrowRight", "Home", "End"].includes(e.key)) update(); });
}

const states = ["TRIAGE", "TODO", "IN_PROGRESS", "DONE", "CANCELED"];
let csrf, me, contextProject, nextNotice;
const vocabulary = {
  ADMIN: "团队管理员", MEMBER: "成员", MAINTAINER: "项目维护者",
  DOCUMENT: "项目文档", TASK: "任务", BUG: "缺陷", FEATURE: "功能", QUESTION: "问题",
  PREVIEW: "待确认", RUNNING: "后台处理中", SUCCEEDED: "已完成", PARTIAL_FAILED: "部分成功",
  FAILED: "失败", STOPPED: "已中止", PENDING: "待处理", ACCEPTED: "已接受", REVOKED: "已撤回", EXPIRED: "已过期",
  QUEUED: "已受理，等待执行", PRECHECK: "发送前检查", RETRY_WAIT: "等待重试",
  SEND_STARTED: "已开始发送，等待确认", VERIFYING: "正在核对远端", UNKNOWN: "远端结果未知（仅观察）",
  APPLIED: "已读回并应用", DISCOVERED: "已发现，等待逐项确认", SATISFIED: "远端已满足请求", MEMBERSHIP_CHANGED: "变更成员权限", CONFIRMED: "远端已确认", SYNCED: "已确认同步", ERROR: "需要处理", CONFLICT: "远端冲突",
  TASK_SUBMITTED: "提交待分诊任务", TASK_ACCEPTED: "接纳任务", TASK_REJECTED: "拒绝申请", TASK_WITHDRAWN: "撤回申请", TASK_RETURNED_TO_TRIAGE: "退回分诊", REASSIGNMENT_REQUESTED: "申请更换负责人", REASSIGNMENT_APPROVED: "批准更换负责人", REASSIGNMENT_REJECTED: "拒绝更换负责人",
  TASK_CREATED: "创建任务", TASK_UPDATED: "更新任务", TASK_UNBLOCKED: "解除上游阻塞",
  COMMENT_CREATED: "发布评论", COMMENT_UPDATED: "编辑评论", COMMENT_DELETED: "删除评论",
  LINK_ADDED: "关联资料", LINK_REMOVED: "解除资料关联", DEPENDENCY_ADDED: "添加依赖", DEPENDENCY_REMOVED: "移除依赖", DOCUMENT_LINKED: "关联文档",
  URL_LINKED: "关联网页", INVITATION_CREATED: "发送团队邀请", INVITATION_ACCEPTED: "接受团队邀请",
  MEMBER_CHANGED: "变更成员权限", PROJECT_HANDOVER: "交接项目维护权", PROJECT_MEMBERSHIP_CHANGED: "变更项目成员权限", PROJECT_UPDATED: "更新项目",
  PROJECT_ARCHIVED: "归档项目", WORKSPACE_CREATED: "建立团队", WORKSPACE_RENAMED: "修改团队名称", MEMBER_INVITED: "邀请成员", INVITATION_REVOKED: "撤回邀请", MAINTAINER_HANDOVER: "交接项目维护权", WORKSPACE_UPDATED: "更新团队", SYNC_ERROR: "同步需要处理"
};
const label = (value) => vocabulary[value] || stateName(value) || "—";
const timeLabel = (value) => value ? new Date(/(?:Z|[+-]\d\d:\d\d)$/.test(String(value)) ? value : String(value) + "Z").toLocaleString("zh-CN", {hour12:false}) : "—";
function navigate(hash, text) { nextNotice = text; location.hash = hash; }
function archivedControls() {
  if (!contextProject?.archived) return;
  const page = location.hash.slice(1).split("?")[0];
  if (page.endsWith("/settings")) return;
  app.insertAdjacentHTML("afterbegin", '<p class="scope-note">项目已归档：历史内容仍可查看。需要继续工作时，请项目维护者在“项目管理”恢复项目。</p>');
  for (const id of ["newTask", "document", "upload", "comment", "url", "linkDocument", "connect", "intent"])
    $("#" + id)?.remove();
  for (const id of ["task", "doc"]) {
    $("#" + id)?.querySelectorAll("input,select,textarea").forEach(el => el.disabled = true);
    $("#" + id + " button")?.remove();
  }
  for (const id of ["triage", "subscribe", "unsubscribe", "confirm", "resume"]) $("#" + id)?.remove();
  document.querySelectorAll("[data-toggle],[data-reconcile]").forEach(el => el.remove());
}
function savedMessage(path) {
  if (path.endsWith("/confirm") || path.endsWith("/resume")) return "导入已受理，请以下方行结果为准。处理中会自动更新；成功行可直接打开任务。";
  if (path.endsWith("/github/intents")) return "远端修改已受理。任务只显示已确认内容；是否完成请查看下方 GitHub 确认状态。";
  if (path.endsWith("/reconcile")) return "已登记只读对账，请刷新仓库读取记录查看结果；受理不等于同步完成。";
  if (path.endsWith("/invitations")) return "邀请已登记，邮件在后台发送。对方接受后，还需在项目管理中授予项目权限。";
  if (path.endsWith("/handover")) return "维护权已授予新维护者。确认交接后，可在成员与邀请中移除原成员。";
  if (path.endsWith("/comments")) return "评论已发布，相关成员可在通知中查看。";
  if (path.endsWith("/triage-accept")) return "已接纳为待办，负责人和正式安排已确认。";
  return "操作已保存。";
}
async function api(path, method = "GET", body, extra = {}) {
  // Another tab may have switched the shared login session since the last request.
  if (method !== "GET") csrf = await api("/session/csrf");
  const headers = { ...extra };
  if (method !== "GET") {
    headers[csrf.headerName] = csrf.token;
    headers["Idempotency-Key"] ??= crypto.randomUUID();
  }
  let data = body;
  if (
    body &&
    !(body instanceof FormData) &&
    !(body instanceof URLSearchParams)
  ) {
    headers["Content-Type"] = "application/json";
    data = JSON.stringify(body);
  }
  const r = await fetch(path, {
    method,
    headers,
    body: data,
    credentials: "same-origin",
  });
  const text = await r.text();
  let value;
  try {
    value = text ? JSON.parse(text) : null;
  } catch {
    value = text;
  }
  if (!r.ok) {
    const error = new Error(value?.message || `请求失败 (${r.status})`);
    error.status = r.status;
    error.code = value?.code;
    throw error;
  }
  return value;
}
const readableError = (text) => String(text || "").replace(/(title|name|body|description) is required and must be at most (\d+) characters/g, (_, field, max) => `${({title:"标题",name:"名称",body:"正文",description:"描述"})[field]}不能为空，且不能超过${max}个字符`).replace("Assignee must be an active project member", "负责人必须是当前有效的项目成员");
const reason = (code) =>
  ({
    PROJECT_ARCHIVED: "项目已归档；先恢复项目，再由维护者恢复导入",
    FORBIDDEN: "原操作者已失去项目权限；请当前维护者接手恢复",
    IMPORT_EXPIRED: "文件已超过30天保留期；成功结果保留，继续导入需重新上传",
    LOCAL_INVARIANT_VIOLATION:
      "本行未提交，数据库约束校验失败；修复原因后可恢复该行",
    AUTHORIZATION_OR_ARCHIVE:
      "操作者权限已变化或项目已归档；恢复项目后由维护者重新对账",
    SCAN_FAILED_WATERMARK_UNCHANGED: "读取失败；检查 App 权限与网络后重新对账",
    REPOSITORY_IDENTITY_MISMATCH: "仓库身份不匹配；请部署者检查预授权配置",
    DISCONNECTED: "连接已停用；维护者可恢复",
    UNKNOWN_WRITE_OUTCOME: "远端写入结果仍不确定；只能观察确认，不可重发",
    DISPATCH_DISABLED: "部署者未开启远端写入",
    AUTHORIZATION_CHANGED: "权限已变化，尚未发送的写入已停止",
  })[code] ||
  readableError(code) ||
  "—";
function message(text, error = false) {
  notice.className = error ? "error" : "success";
  notice.textContent = text;
}
async function run(fn) {
  try {
    return await fn();
  } catch (e) {
    message(
      e.code?.includes("REVISION_CONFLICT")
        ? "版本冲突：服务器已有更新，本次未覆盖。你的输入仍保留；可在新标签页查看最新内容，复制草稿后刷新并合并。"
        : ({INVALID_CREDENTIALS: "登录名或密码错误，请重新输入。", FORBIDDEN: "无权执行此操作。请联系团队管理员或项目维护者确认权限。", DEPENDENCY_CYCLE: "这条依赖会形成循环，本次未保存。请调整上游任务。", DEPENDENCY_EXISTS: "这条依赖已存在，无需重复添加。", TRIAGE_ACCEPT_REQUIRED: "该任务仍需维护者接纳；请先回到待分诊，再使用接纳入口。", SYNC_IN_PROGRESS: "已有远端修改正在处理，请等待确认。", SYNC_OBSERVATION_REQUIRED: "远端结果尚未确认，只能观察，不能重新发送。", NOT_FOUND: "内容不存在或不在当前项目中。"}[e.code] || (e.status === 401 ? "登录已失效，请重新登录。" : e.message === "Failed to fetch" ? "网络连接失败。输入仍保留；请检查连接后重试。" : readableError(e.message))),
      true,
    );
    if (e.code?.includes("REVISION_CONFLICT")) notice.insertAdjacentHTML("beforeend", ` <a href="${esc(location.hash)}" target="_blank" rel="noopener">查看最新版本（新标签页）</a>`);
    return null;
  }
}
function bind(id, fn) {
  const el = $("#" + id);
  if (!el) return;
  el.addEventListener("submit", (e) => {
    e.preventDefault();
    const values = Object.fromEntries(new FormData(el));
    if (id === "settings" && values.archived === "true" && !contextProject?.archived && !confirm("归档后保留历史，停止新的任务、评论和导入写入。已经发出的 GitHub 请求仍需观察确认。确定归档？")) return;
    if (id === "membership" && values.active === "false" && !confirm("移除后，该成员的旧页面和会话也无法访问本项目。历史内容保留。确定移除？")) return;
    const button = el.querySelector("button[type=submit], button:not([type])");
    notice.textContent = "";
    button.disabled = true;
    run(() => fn(values, el)).finally(() => (button.disabled = false));
  });
}
// Persist only ordinary creation forms, never account credentials or model requests.
// A lost response keeps the original payload and key until that intent is confirmed.
function bindCreation(id, path, prepare, created) {
  const el = $("#" + id);
  if (!el) return;
  const actor = me.userId, storageKey = `creation:${actor}:${id}:${path}`;
  let pending, sending = false;
  try { pending = JSON.parse(sessionStorage.getItem(storageKey) || "null"); }
  catch { sessionStorage.removeItem(storageKey); }
  if (pending && (pending.actor !== actor || pending.path !== path || !pending.key || !pending.body || !pending.values)) {
    pending = null; sessionStorage.removeItem(storageKey);
  }
  const submit = el.querySelector("button:not([type]),button[type=submit]"), originalLabel = submit.textContent;
  const status = document.createElement("p");status.className = "form-status";status.setAttribute("role", "status");el.append(status);
  function sync() {
    for (const field of el.querySelectorAll("input,textarea,select")) {
      if (pending && field.name in pending.values) field.value = pending.values[field.name];
      field.disabled = !!pending;
    }
    submit.textContent = sending ? "正在提交…" : pending ? "确认上次提交" : originalLabel;
    status.textContent = sending ? "正在保存，请稍候。" : pending ? "上次提交结果尚未确认。再次提交会核对原请求，避免重复创建；确认后可继续新建。" : "";
  }
  sync();
  bind(id, async values => {
    const current = await api("/session/me");
    if (current.userId !== actor) throw new Error("登录账号已变化，请刷新页面后继续；原账号的待确认提交仍保留。");
    if (!pending) {
      pending = { actor, path, key: crypto.randomUUID(), values, body: prepare(values) };
      sessionStorage.setItem(storageKey, JSON.stringify(pending));
    }
    sending = true;sync();
    let value;
    try { value = await api(path, "POST", pending.body, {"Idempotency-Key": pending.key}); }
    catch (error) {
      if ([400, 409, 413, 415, 422].includes(error.status)) {
        pending = null;sessionStorage.removeItem(storageKey);
      }
      sending = false;sync();
      throw error;
    }
    pending = null;sending = false;sessionStorage.removeItem(storageKey);sync();
    await created(value);
  });
}

const contentCursor = (item, field = "updatedAt") => item ? `${item[field]}~${item.id}` : "";

function button(id, fn) {
  const el = $("#" + id);
  if (el) el.onclick = () => {
    if (el.disabled) return;
    notice.textContent = "";
    el.disabled = true;
    run(fn).finally(() => { if (el.isConnected) el.disabled = false; });
  };
}
async function mutation(path, method, body, headers) {
  await api(path, method, body, headers);
  await route();
  message(savedMessage(path));
}
function taskRows(rows, members = [], empty = "暂无任务。") {
  return table(
    ["任务", "状态", "负责人", "截止日期", "依赖"],
    rows.map((t) => [
      link(`task/${t.projectId}/${t.id}`, t.title),
      esc(stateName(t.status)),
      esc(
        t.assigneeId
          ? members.find((m) => m.id === t.assigneeId)?.displayName || "已分配"
          : "未分配",
      ),
      esc(t.dueDate || "—"),
      t.dependencyRisk ? "完成但依赖有风险" : t.isBlocked ? "被阻塞" : ["DONE","CANCELED"].includes(t.status) ? "—" : "可推进",
    ]),
    empty,
  );
}
// A page owns one stream. SSE carries metadata; final answers retain source validation.
let stopRunProgress = () => {};
let stopConversation = () => {};
let projectAssistant = null;
const assistantKeys = ['conversation', 'assistant', 'question', 'turn', 'source'];
function assistantKey(kind, projectId) { return `assistant-${kind}:${me?.userId}:${projectId}`; }
function projectRouteProject(hash) { const parts=hash.replace(/^#/, '').split('?')[0].split('/');return ['project','task','document','import'].includes(parts[0]) ? parts[1] : null; }
function assistantContentRoute(projectId) {
  const current=location.hash.slice(1), saved=sessionStorage.getItem(assistantKey('content',projectId));
  const valid=value=>value&&projectRouteProject(value)===projectId&&!value.split('?')[0].endsWith('/agent');
  return valid(current)?current:valid(saved)?saved:`project/${projectId}/tasks`;
}
function assistantRoute(projectId, conversation, extra={}) {
  const [path,query='']=assistantContentRoute(projectId).split('?'), params=new URLSearchParams(query);
  assistantKeys.forEach(k=>params.delete(k));params.set('assistant','open');
  if(conversation)params.set('conversation',conversation);
  Object.entries(extra).forEach(([k,v])=>params.set(k,v));return path+'?'+params;
}
function clearProjectAssistant() {
  stopConversation();projectAssistant=null;
  $('#project-assistant').hidden=true;$('#assistant-toggle').hidden=true;$('#assistant-backdrop').hidden=true;
  $('#assistant-body').replaceChildren();document.body.classList.remove('assistant-open','assistant-mobile-open');
  $('.workspace-content').inert=false;$('#sidebar').inert=false;$('body > header').inert=false;
}
function setAssistantOpen(open, focus=false) {
  if(!projectAssistant)return;
  projectAssistant.open=open;sessionStorage.setItem(assistantKey('open',projectAssistant.projectId),String(open));
  $('#project-assistant').hidden=!open;$('#assistant-toggle').hidden=open;
  document.body.classList.toggle('assistant-open',open);
  const modal=open&&matchMedia('(max-width: 1100px)').matches;
  $('#assistant-backdrop').hidden=!modal;document.body.classList.toggle('assistant-mobile-open',modal);
  $('.workspace-content').inert=modal;$('#sidebar').inert=modal;$('body > header').inert=modal;
  if(open)closeNavigation();
  $('#project-assistant').setAttribute('role',modal?'dialog':'complementary');
  if(modal)$('#project-assistant').setAttribute('aria-modal','true');else $('#project-assistant').removeAttribute('aria-modal');
  $('#assistant-toggle').setAttribute('aria-expanded',String(open));
  if(focus)(open?$('#assistant-close'):$('#assistant-toggle')).focus({preventScroll:true});
}
async function syncProjectAssistant(params) {
  if(!contextProject)return;
  const p=contextProject.id, actor=me.userId;
  if(projectAssistant&&(projectAssistant.projectId!==p||projectAssistant.actor!==actor))clearProjectAssistant();
  const requested=params.get('conversation');
  if(params.get('assistant')==='open'){$('#project-assistant').classList.remove('history-open');$('#assistant-history').setAttribute('aria-expanded','false');}
  if(!projectAssistant){
    projectAssistant={projectId:p,actor,controller:null,open:false};
    $('#assistant-project').textContent=contextProject.name;
    $('#assistant-close').onclick=()=>setAssistantOpen(false,true);
    $('#assistant-toggle').onclick=()=>setAssistantOpen(true,true);
    $('#assistant-backdrop').onclick=()=>setAssistantOpen(false,true);
    $('#assistant-history').onclick=()=>{const open=$('#project-assistant').classList.toggle('history-open');$('#assistant-history').setAttribute('aria-expanded',String(open));};
    const stored=sessionStorage.getItem(assistantKey('open',p));
    setAssistantOpen(params.get('assistant')==='open'||(stored===null?!$('#map-canvas')&&!matchMedia('(max-width: 1100px)').matches:stored==='true'));
  }
  if(!projectAssistant.controller||(requested&&requested!==projectAssistant.controller.selected())){
    const options=new URLSearchParams(params);
    if(!requested){const saved=sessionStorage.getItem(assistantKey('conversation',p));if(saved)options.set('conversation',saved);}
    $('#project-assistant').classList.remove('history-open');$('#assistant-history').setAttribute('aria-expanded','false');
    projectAssistant.controller=null;$('#assistant-new-slot').replaceChildren();
    try {
      projectAssistant.controller=await conversationPage($('#assistant-body'),p,options);
      $('#assistant-new-slot').replaceChildren($('#assistant-body #conversation-new'));
    } catch(error) {
      stopConversation();
      $('#assistant-body').innerHTML=`<div class="assistant-load-error"><p>助手暂时无法加载：${esc(readableError(error.message))}</p><button id="assistant-load-retry">重试加载助手</button></div>`;
      $('#assistant-load-retry').onclick=()=>run(()=>syncProjectAssistant(options));
    }
  } else await projectAssistant.controller.navigate(params);
  if(params.get('assistant')==='open')setAssistantOpen(true);
  const [path,query='']=location.hash.slice(1).split('?'), clean=new URLSearchParams(query);
  ['assistant','question'].forEach(k=>clean.delete(k));
  history.replaceState(null,'','#'+path+(clean.size?'?'+clean:''));
  const content=new URLSearchParams(clean);assistantKeys.forEach(k=>content.delete(k));
  sessionStorage.setItem(assistantKey('content',p),path+(content.size?'?'+content:''));
}
window.addEventListener('resize',()=>{if(projectAssistant)setAssistantOpen(projectAssistant.open);});
document.addEventListener('keydown',e=>{
  if(!projectAssistant?.open)return;
  if(e.key==='Escape'){e.preventDefault();setAssistantOpen(false,true);}
  if(e.key==='Tab'&&document.body.classList.contains('assistant-mobile-open')){
    const nodes=[...$('#project-assistant').querySelectorAll('button:not([disabled]),a[href],textarea:not([disabled]),input:not([disabled]),select,summary')].filter(n=>n.getClientRects().length&&getComputedStyle(n).visibility!=='hidden');
    const first=nodes[0],last=nodes.at(-1);
    if(e.shiftKey&&document.activeElement===first){e.preventDefault();last?.focus();}
    else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first?.focus();}
  }
});
function closeNavigation() {
  document.body.classList.remove('navigation-open');
  $('#navigation-toggle').setAttribute('aria-expanded','false');
  $('#navigation-backdrop').hidden=true;
  if(!document.body.classList.contains('assistant-mobile-open'))$('.workspace-content').inert=false;
}
$('#navigation-toggle').onclick=()=>{
  const open=!document.body.classList.contains('navigation-open');
  if(open&&projectAssistant?.open)setAssistantOpen(false);
  document.body.classList.toggle('navigation-open',open);
  $('#navigation-toggle').setAttribute('aria-expanded',String(open));$('#navigation-backdrop').hidden=!open;
  $('.workspace-content').inert=open;
};
$('#navigation-backdrop').onclick=closeNavigation;
document.addEventListener('keydown',e=>{if(e.key==='Escape'&&document.body.classList.contains('navigation-open')){closeNavigation();$('#navigation-toggle').focus();}});
function refinePageLayout() {
  // Move existing nodes, preserving validation and listeners. Optional actions open on demand.
  for(const [id,title] of [['newTask','新建任务'],['document','新建文档'],['newProject','新建项目'],['createWorkspace','新建团队'],['bindEmail','更换邮箱'],['password','修改密码']]) {
    const form=document.getElementById(id);if(!form||form.closest('details'))continue;
    const disclosure=document.createElement('details');disclosure.className='create-disclosure';disclosure.id=id+'-disclosure';
    const summary=document.createElement('summary');summary.textContent=title;disclosure.append(summary);
    form.before(disclosure);disclosure.append(form);
    if(['newTask','document','newProject','createWorkspace'].includes(id)){
      const host=id==='newTask'||id==='document'?$('#panel'):id==='newProject'?$('#workspace-projects'):app;
      const anchor=id==='createWorkspace'?host.querySelector('h1')?.nextSibling:host.firstChild;host.insertBefore(disclosure,anchor||null);
    }
    const cancel=document.createElement('button');cancel.type='button';cancel.className='secondary';cancel.textContent='取消';cancel.onclick=()=>{disclosure.open=false;summary.focus();};form.append(cancel);
    disclosure.addEventListener('toggle',()=>{if(disclosure.open)form.querySelector('input,textarea')?.focus({preventScroll:true});});
  }
  for(const id of ['filter','personal-filter','document-filter']){
    const form=document.getElementById(id);if(!form)continue;form.classList.add('filter-bar');form.setAttribute('aria-label','筛选');form.querySelector('h2')?.remove();
    if(id==='filter'){
      const advanced=document.createElement('details');advanced.className='advanced-filters';advanced.innerHTML='<summary>更多筛选</summary><div></div>';
      for(const name of ['blocked','due']){const field=form.querySelector(`[name=${name}]`);if(field){if(field.value)advanced.open=true;advanced.lastElementChild.append(field.closest('label'));}}
      form.insertBefore(advanced,form.querySelector('button'));
    }
  }
  const properties=$('#task-summary .task-properties');
  if(properties&&properties.children.length>3){
    const more=document.createElement('details');more.className='record-metadata';more.innerHTML='<summary>更多属性</summary><dl class="task-properties"></dl>';
    [...properties.children].slice(3).forEach(n=>more.lastElementChild.append(n));properties.after(more);
  }
  $('#task-summary > h2')?.remove();
  const reassignment=$('#reassignment');if(reassignment&&!reassignment.querySelector('.task-comment'))reassignment.classList.add('empty-reassignment');
  const handover=$('#workspace-handover');
  if(handover&&!handover.closest('details')){const detail=document.createElement('details');detail.className='settings-disclosure';detail.innerHTML='<summary>项目维护者交接</summary>';handover.before(detail);detail.append(handover);detail.hidden=handover.hidden;}
  const paging=$('#task-pagination');if(paging)paging.hidden=$('#prev').disabled&&$('#next').disabled;
}
function runContext(id) {
  const url = new URL(location.href), [path, query = ''] = url.hash.split('?');
  const params = new URLSearchParams(query);params.set('run', id);params.delete('proposal');
  url.hash = path + '?' + params;history.replaceState(null, '', url);
}
function progressText(s) {
  const errors = {
    AGENT_CAMPAIGN_LIMIT:'本轮模型请求次数已用尽，可以缩小问题范围后重新分析。',
    AGENT_ROUND_BUDGET:'本次分析已达到调用次数上限，尚未形成可核验的回答。请缩小问题范围后重新分析。',
    AGENT_NO_PROGRESS:'助手连续未完成有效查证，本次已停止。请稍后重试或先直接查看项目资料。',
    MODEL_COST_BUDGET:'这条历史分析的执行授权已结束，请使用团队模型重新发起。',
    GLM_COST_BUDGET:'这条历史分析的执行授权已结束，请使用团队模型重新发起。',
    MODEL_CIRCUIT_OPEN:'模型服务暂时不可用，已暂停调用。请等待恢复后重新分析。',
    GLM_RATE_LIMIT:'供应商触发限流，已共享冷却。请稍后重新分析。',
    MODEL_WAIT_TIMEOUT:'等待模型名额已到上限。请稍后重新分析。',
    MODEL_WAIT_BUDGET:'本次分析已达到等待时长上限。请稍后重新分析。',
    MODEL_ADMISSION_UNAVAILABLE:'共享限流服务不可用，已停止调用。请联系维护者恢复服务。',
    MODEL_ADMISSION_MISSING:'限流状态缺失，已停止调用。请联系维护者完成恢复。',
    AGENT_TIMEOUT:'分析已到执行期限。请缩小问题范围后重新分析。',
    FORBIDDEN:'当前访问权限已失效。请返回项目核对权限。',
  };
  if(modelReason(s.errorCode))return modelReason(s.errorCode);
  if(errors[s.errorCode])return errors[s.errorCode];
  if(s.stage==='DISCONNECTED')return '进度连接暂不可用，正在恢复当前状态；无需重复提交。';
  if(s.status === 'INCOMPLETE')return '证据不足，无法完成分析。请补充问题背景或项目资料后重试。';
  if(s.stage === 'WAITING')return s.reason === 'COOLDOWN' ? '等待供应商冷却结束；在本次等待期限内自动继续。' : '共享模型请求名额暂满；在本次等待期限内自动继续，无需重复提交。';
  return {QUEUED:'已受理，等待后台执行；可以离开后通过此链接恢复。',RUNNING:'后台分析已开始。',READING:'正在读取项目事实与证据。',MODEL_REQUEST:'模型请求已准备，正在等待响应。',CHECKING:'正在检查回答及来源。',RETRYING:'模型服务临时失败，正在有限重试。'}[s.stage] || triageRunLabel(s.status,s.errorCode);
}
function watchRun(p,id,alive,onProgress,onTerminal,standalone=false) {
  if(!standalone)stopRunProgress();let closed=false,last=-1,running=false,recovering=false,stream,timer;
  const close=()=>{closed=true;clearTimeout(timer);stream?.close();};if(!standalone)stopRunProgress=close;
  function accept(s) {
    if(closed || !alive()){close();return;}
    if(s.id!==id || s.sequence<last || (running && s.status==='QUEUED'))return;
    last=s.sequence;running ||= s.status==='RUNNING';
    onProgress(s);
    if(!['QUEUED','RUNNING'].includes(s.status)){close();void onTerminal();}
  }
  function connect() {
    if(closed||!alive()){close();return;}
    const source=stream=new EventSource(`/projects/${p}/agent/runs/${id}/events`);
    source.addEventListener('snapshot',event=>{try{accept(JSON.parse(event.data));}catch{close();}});
    source.onerror=async()=>{
      if(closed || !alive()){close();return;}
      if(recovering||source!==stream)return;recovering=true;
      try{accept(await api(`/projects/${p}/agent/runs/${id}/progress`));}
      catch(e){if(!closed&&alive())onProgress({stage:'DISCONNECTED',status:'RUNNING',errorCode:e.code});if(e.status===403||e.status===404)close();}
      finally{
        recovering=false;
        // Some HTTP failures permanently close EventSource. Retry only after the
        // authoritative read has checked current access; native reconnect owns CONNECTING.
        if(!closed&&source===stream&&source.readyState===EventSource.CLOSED)timer=setTimeout(connect,1000);
      }
    };
  }
  connect();return close;
}
let routing = false,
  routeAgain = false;
async function route() {
  overviewDispose?.();
  if (routing) {
    routeAgain = true;
    return;
  }
  routing = true;
  closeNavigation();
  stopRunProgress();
  if(!projectAssistant||projectRouteProject(location.hash)!==projectAssistant.projectId)clearProjectAssistant();
  try {
    await renderRoute();
    if(!routeAgain)await syncProjectAssistant(new URLSearchParams(location.hash.split('?')[1]||''));
    refinePageLayout();
    archivedControls();
    focusSource();
    document.querySelectorAll("input[name=password]").forEach(el => { if (!el.closest("#login,#bindEmail")) el.minLength = 12; });
    const registration = $("#register [name=login]");
    if (registration) { registration.pattern = "([A-Za-z0-9_]|-){3,60}"; registration.maxLength = 60; }
    if (nextNotice) { message(nextNotice); nextNotice = null; }
  } catch (error) {
    clearProjectAssistant();
    app.innerHTML =
      "<h1>无法打开此页面</h1><p>请确认登录与项目权限，或从个人工作台重新进入。</p>" +
      link("mine", "返回我的任务") + '<button id="retry-page" class="secondary">重试加载</button>';
    button("retry-page", route);
    throw error;
  } finally {
    routing = false;
    if (routeAgain) {
      routeAgain = false;
      await route();
    }
  }
}
function navLink(href, text, active) {
  return `<a href="#${href}" ${active ? 'aria-current="page"' : ""}>${esc(text)}</a>`;
}
function crumbs(tail = "") {
  if (!contextProject) return "";
  const workspace = me.workspaces.find(
    (w) => w.id === contextProject.workspaceId,
  );
  return `<nav class="breadcrumbs" aria-label="当前位置">${link("home", "全部团队")}<span> / </span>${link("workspace/" + contextProject.workspaceId, workspace?.name || "团队")}<span> / </span>${link("project/" + contextProject.id, contextProject.name)}${tail ? `<span> / ${esc(tail)}</span>` : ""}</nav>`;
}
async function unreadBadge() {
  const badge = $("#unread-count");
  if (!badge) return;
  try {
    const r = await api("/me/notifications/unread-count");
    badge.textContent = r.count;
    badge.setAttribute("aria-label", r.count + "条未读通知");
  } catch {
    badge.textContent = "—";
  }
}
async function renderRoute() {
  app.innerHTML = "<p>正在加载…</p>";
  $(".workspace-content").scrollTop=0;
  notice.textContent = "";
  let raw = location.hash.slice(1) || "mine";
  try {
    me = await api("/session/me");
  } catch (error) {
    if (error.status !== 401 && error.status !== 403) throw error;
    me = null;
  }
  if(projectAssistant&&projectAssistant.actor!==me?.userId)clearProjectAssistant();
  const legacy=raw.match(/^project\/([a-f0-9-]{36})\/agent(?:\?(.*))?$/);
  if(me&&legacy){const q=new URLSearchParams(legacy[2]||'');if(!q.has('run')){raw=assistantRoute(legacy[1],q.get('conversation'),Object.fromEntries([...q].filter(([k])=>k!=='conversation')));history.replaceState(null,'','#'+raw);}}
  const [path,search='']=raw.split('?'),[page,a,b,c]=path.split('/'),params=new URLSearchParams(search);
  document.body.dataset.page=page;
  contextProject = null;
  $('#sidebar').hidden = !me;
  $('#navigation-toggle').hidden=!me;
  $("#account-nav").innerHTML = me
    ? `${link("profile", "个人资料")}${link("logout", "退出")}`
    : `${link("login", "登录")}${link("register", "注册")}`;
  $("#nav").innerHTML = me
    ? `<p class="nav-label">工作台</p>${navLink("mine", "我的任务", page === "mine")}<a href="#inbox" ${page === "inbox" ? 'aria-current="page"' : ""}>通知 <span class="count" id="unread-count">…</span></a>${navLink("home", "团队与项目", page === "home")}${navLink("assistant", "会话历史", page === "assistant")}`
    : "";
  $("#scope-nav").innerHTML = "";
  if (me) {
    if (["project", "task", "document", "import"].includes(page))
      contextProject = await api("/projects/" + a);
    const workspaceId =
      contextProject?.workspaceId || (page === "workspace" ? a : "");
    $("#scope-nav").innerHTML =
      `<p class="nav-label">团队</p>${select("workspace-navigation", "", [["", "选择团队…"], ...me.workspaces.map((w) => [w.id, w.name])], workspaceId)}${workspaceId ? `<nav>${navLink("workspace/" + workspaceId, "项目列表", page === "workspace" && (!b || b === "projects"))}${navLink("workspace/" + workspaceId + "/members", "成员与邀请", page === "workspace" && b === "members")}${navLink("workspace/" + workspaceId + "/settings", "团队设置与交接", page === "workspace" && b === "settings")}</nav>` : ""}${contextProject ? `<p class="nav-label">当前项目</p><strong>${esc(contextProject.name)}</strong><nav>${navLink("project/" + a, "项目概览", page === "project" && (b === "overview" || (!b && !search)))}${navLink("project/" + a + "/tasks", "项目任务", page === "task" || b === "tasks" || (page === "project" && !b && !!search))}${navLink("project/" + a + "/github", "GitHub 来源", b === "github")}${navLink("project/" + a + "/documents", "项目文档", page === "document" || b === "documents")}${navLink("project/" + a + "/models", "模型与检索", b === "models")}</nav>` : ""}`;
    $("[name=workspace-navigation]").onchange = (e) => {
      if (e.target.value) location.hash = "workspace/" + e.target.value;
    };
    await unreadBadge();
  }
  if (page === "logout") {
    await api("/session/logout", "POST");
    csrf = null;
    location.hash = "login";
    return;
  }
  if (
    ["register", "login", "forgot", "verify", "reset", "invite"].includes(page)
  )
    return accountPage(page, a, params);
  if (!me) {
    location.hash = "login";
    return;
  }
  if (page === "assistant") return conversationPage(app,null,params);
  if (page === "home") {
    const [invitations, profile] = await Promise.all([api("/me/invitations"), api("/account/profile")]);
    const pending = invitations.length ? `<section id="pending-invitations"><h2>待接受的团队邀请</h2>${table(
      ["团队", "收件邮箱", "有效期至", "操作"],
      invitations.map(i => [esc(i.workspaceName), esc(i.email), esc(timeLabel(i.expiresAt)), `<button id="accept-invitation-${esc(i.id)}">接受邀请</button>`]),
    )}</section>` : "";
    app.innerHTML = `<h1>团队与项目</h1><p>当前账号：<strong>${esc(profile.login)}</strong> · ${esc(profile.email || "尚未绑定邮箱")}</p>${pending}<h2>已加入的团队</h2>${table(
      ["团队", "进入"],
      me.workspaces.map((w) => [
        esc(w.name),
        link("workspace/" + w.id, "打开"),
      ]),
    )}${form("createWorkspace", "建立团队", input("name", "团队名称"), "建立团队")}`;
    for (const invitation of invitations) {
      button("accept-invitation-" + invitation.id, async () => {
        const id = await api("/me/invitations/" + invitation.id + "/accept", "POST");
        sessionStorage.removeItem("invitation");
        navigate("workspace/" + id, "已加入团队。请联系项目维护者授予项目权限。");
      });
    }
    bind("createWorkspace", async (r) => {
      const id = await api("/workspaces", "POST", r);
      navigate("workspace/" + id, "团队已建立。下一步创建项目，并在成员与邀请中邀请同学。");
    });
    return;
  }
  if (page === "workspace") return workspacePage(a, b || "projects");
  if (page === "project") return projectPage(a, b || (search ? "tasks" : "overview"), params);
  if (page === "task") return taskPage(a, b);
  if (page === "document") return documentPage(a, b);
  if (page === "import") return importPage(a, b);
  if (page === "mine" || page === "inbox") return personalPage(page, params);
  if (page === "profile") {
    const r = await api("/account/profile");
    app.innerHTML = `<h1>个人资料</h1><p>登录名 ${esc(r.login)} ${r.email ? `· ${esc(r.email)} · ${r.emailVerified ? "已验证" : "待验证"}` : "· 未绑定邮箱"}</p>${form("profile", "显示名称", input("displayName", "显示名称", "text", r.displayName))}${form("bindEmail", "绑定或更换邮箱", input("email", "邮箱", "email") + input("password", "当前密码", "password"), "发送验证邮件")}${form("password", "修改密码", input("oldPassword", "原密码", "password") + input("password", "新密码（至少12个字符）", "password"), "修改并退出所有会话")}`;
    bind("bindEmail", async (r) => {
      await api("/account/email", "POST", r);
      message("验证邮件已发送，请在新邮箱中确认。");
    });
    bind("profile", (r) => mutation("/account/profile", "PATCH", r));
    bind("password", async (r) => {
      await api("/account/password", "POST", r);
      csrf = null;
      navigate("login", "密码已修改，请重新登录。");
    });
    return;
  }
  app.innerHTML = "<h1>页面不存在</h1>" + link("home", "返回团队");
}
function invitationRoute(page, token) {
  return token ? page + "?invitation=" + encodeURIComponent(token) : page;
}
function invitationIntro(invitation) {
  return `<p class="scope-note">受邀加入 <strong>${esc(invitation.workspaceName)}</strong> · 收件邮箱 <strong>${esc(invitation.email)}</strong></p>`;
}
async function loadInvitation(token) {
  let invitation;
  try {
    invitation = await api("/invitations/preview", "POST", { token });
  } catch (error) {
    if (![410, 422].includes(error.status)) throw error;
    sessionStorage.removeItem("invitation");
    app.innerHTML = `<h1>邀请不可用</h1><p>${esc(error.message)}</p>${link("mine", "返回登录")}`;
    return null;
  }
  if (invitation.status !== "PENDING") {
    sessionStorage.removeItem("invitation");
    app.innerHTML = `<h1>团队邀请</h1>${invitationIntro(invitation)}<p>${invitation.status === "ACCEPTED" ? "这份邀请已被收件人使用。请确认登录账号绑定的是上方收件邮箱，再到“团队与项目”查看成员关系；当前账号的其他待处理邀请也会显示在那里。若已离开团队，请管理员发送新邀请。" : "邀请已撤回或过期，请联系管理员重新邀请。"}</p>${link(me ? "home" : "mine", me ? "查看团队与待处理邀请" : "返回登录")}`;
    return null;
  }
  sessionStorage.setItem("invitation", token);
  return invitation;
}
async function invitationPage(token) {
  const invitation = await loadInvitation(token);
  if (!invitation) return;
  app.innerHTML = `<h1>团队邀请</h1>${invitationIntro(invitation)}<p>接受后加入团队；项目访问权限由维护者另行授予。</p>`;
  if (me) {
    const profile = await api("/account/profile");
    app.insertAdjacentHTML("beforeend", `<p>当前登录：<strong>${esc(profile.login)}</strong>（${esc(profile.email || "尚未绑定邮箱")}）</p>`);
    if (profile.email !== invitation.email) {
      const register = invitation.accountState === "REGISTER";
      app.insertAdjacentHTML("beforeend", `<p role="status">当前账号邮箱与邀请收件人不一致，无法接受这份邀请。${register ? "收件邮箱尚未注册，请先切换并创建账号。" : "请切换到收件邮箱对应的账号。"}</p><button id="switch-invitation-account">${register ? "退出当前账号并注册" : "切换账号"}</button><p>切换会同时影响此浏览器的其他 Work Hub 标签页。如需保留当前登录，请用另一浏览器配置或无痕窗口打开邀请链接。</p>`);
      button("switch-invitation-account", async () => {
        await api("/session/logout", "POST");
        csrf = null;
        me = null;
        if (invitation.accountState === "VERIFY") await route();
        else location.hash = invitationRoute(register ? "register" : "login", token);
      });
      return;
    }
    if (profile.emailVerified) {
      app.insertAdjacentHTML("beforeend", '<button id="accept">接受邀请</button>');
      button("accept", async () => {
        try {
          const id = await api("/invitations/accept", "POST", { token });
          sessionStorage.removeItem("invitation");
          navigate("workspace/" + id, "已加入团队。请联系项目维护者授予项目权限。");
        } catch (error) {
          if (["INVITATION_ACCOUNT_MISMATCH", "INVITATION_EMAIL_UNVERIFIED", "INVITATION_UNAVAILABLE", "INVITATION_MEMBERSHIP_ENDED"].includes(error.code)) await route();
          throw error;
        }
      });
      return;
    }
  }
  if (invitation.accountState === "REGISTER") {
    app.insertAdjacentHTML("beforeend", `<p>此邮箱尚未注册 Work Hub。请先注册并验证邮箱，再登录接受邀请。</p>${link(invitationRoute("register", token), "注册并继续加入团队")}`);
  } else if (invitation.accountState === "VERIFY") {
    app.insertAdjacentHTML("beforeend", `<p>账号已注册，邮箱尚未验证。请打开验证邮件完成验证，再登录接受邀请。邀请会在验证后继续保留。</p><button id="resend-invitation-verification">重发验证邮件</button><p>${link(invitationRoute("login", token), "已验证，去登录")}</p>`);
    button("resend-invitation-verification", async () => {
      await api("/account/verification", "POST", { email: invitation.email, invitationToken: token });
      message("验证邮件已发送，请检查收件邮箱；完成验证后可继续接受邀请。");
    });
  } else {
    app.insertAdjacentHTML("beforeend", `<p>收件邮箱已注册，请登录对应账号后接受邀请。</p>${link(invitationRoute("login", token), "登录并继续加入团队")}`);
  }
}
async function accountPage(page, token, params) {
  if (page === "invite") return invitationPage(token);
  const invitationToken = params.get("invitation") || sessionStorage.getItem("invitation");
  if (params.get("invitation")) sessionStorage.setItem("invitation", invitationToken);
  let invitation = null;
  if (invitationToken && ["register", "login", "forgot"].includes(page)) {
    invitation = await loadInvitation(invitationToken);
    if (!invitation) return;
    if (me) {
      location.hash = "invite/" + invitationToken;
      return;
    }
  }
  const intro = invitation ? invitationIntro(invitation) : "";
  if (page === "register") {
    app.innerHTML = intro + form(
      "register",
      "注册 Work Hub",
      input("login", "登录名（3–60位字母、数字、_ -）") +
        input("displayName", "显示名称") +
        input("email", "邮箱", "email", invitation?.email || "") +
        input("password", "密码（至少12个字符）", "password"),
      "注册并发送验证邮件",
    );
    if (invitation) $("#register [name=email]").readOnly = true;
    bind("register", async (r) => {
      await api("/account/register", "POST", { ...r, ...(invitation ? { invitationToken } : {}) });
      const text = "注册已受理，请在收件邮箱中打开验证链接。";
      if (invitation) navigate("invite/" + invitationToken, text);
      else message(text);
    });
    return;
  }
  if (page === "login") {
    app.innerHTML = intro + '<div class="login-heading"><h1>登录 WorkHub</h1></div>' +
      form(
        "login",
        "登录",
        input("username", "登录名") + input("password", "密码", "password"),
        "登录",
      ) + link(invitationRoute("forgot", invitationToken), "忘记密码 / 重发验证邮件");
    bind("login", async (r) => {
      await api("/session/login", "POST", new URLSearchParams(r));
      csrf = null;
      const invite = sessionStorage.getItem("invitation");
      location.hash = invite ? "invite/" + invite : "mine";
    });
    return;
  }
  if (page === "forgot") {
    app.innerHTML = intro + form(
      "forgot",
      "账号恢复",
      input("email", "邮箱", "email", invitation?.email || "") +
        select("purpose", "邮件类型", [
          ["forgot", "重置密码"],
          ["verification", "重发邮箱验证"],
        ]),
      "发送邮件",
    );
    if (invitation) $("#forgot [name=email]").readOnly = true;
    bind("forgot", async (r) => {
      await api("/account/" + r.purpose, "POST", { email: r.email, ...(invitation ? { invitationToken } : {}) });
      message("若账号符合条件，邮件将送达。请查看收件箱。");
    });
    return;
  }
  if (page === "verify") {
    if (invitationToken) {
      const context = await loadInvitation(invitationToken);
      if (!context) return;
      if (context.accountState === "LOGIN") {
        navigate("invite/" + invitationToken, "收件邮箱已经验证，请继续处理团队邀请。");
        return;
      }
    }
    app.innerHTML =
      '<h1>验证邮箱</h1><p>验证邮箱后还需登录并接受团队邀请，才会加入团队。</p><button id="verify">确认验证</button>' +
      `<p>${link(invitationToken ? "invite/" + invitationToken : "login", invitationToken ? "返回团队邀请" : "已经验证，去登录")}</p>`;
    button("verify", async () => {
      try {
        await api("/account/verify", "POST", { token });
        navigate(invitationRoute("login", invitationToken), "邮箱已验证，请登录后接受团队邀请。");
      } catch (error) {
        if (error.code !== "TOKEN_EXPIRED") throw error;
        message("验证链接已使用或过期。已验证的账号可以登录，在“团队与项目”中查看待接受邀请；尚未验证请重新申请验证邮件。");
      }
    });
    return;
  }
  if (page === "reset") {
    app.innerHTML = form(
      "reset",
      "重置密码",
      input("password", "新密码（至少12个字符）", "password"),
      "重置密码",
    );
    bind("reset", async (r) => {
      await api("/account/reset", "POST", { token, password: r.password });
      csrf = null;
      navigate(invitationRoute("login", invitationToken), "密码已重置，旧会话已失效。");
    });
    return;
  }
}
async function workspacePage(w, tab) {
  const [workspace, members, projects] = await Promise.all([
    api("/workspaces/" + w),
    api(`/workspaces/${w}/members`),
    api(`/workspaces/${w}/projects`),
  ]);
  const role = members.find((m) => m.id === me.userId)?.role,
    admin = role === "ADMIN";
  if (tab === "models") {
    app.innerHTML = `<nav class="breadcrumbs">${link("home", "全部团队")} / ${link("workspace/"+w, workspace.name)}</nav><h1>${esc(workspace.name)}</h1><nav class="tabs">${navLink(`workspace/${w}`, "项目", false)}${navLink(`workspace/${w}/settings`, "设置与交接", false)}${navLink(`workspace/${w}/models`, "模型服务", true)}</nav><div id="team-model-panel"></div>`;
    return teamModelPage($("#team-model-panel"), w, admin);
  }
  app.innerHTML = `<nav class="breadcrumbs">${link("home", "全部团队")} / ${esc(workspace.name)}</nav><h1>${esc(workspace.name)}</h1><nav class="tabs">${[
    ["projects", "项目"],
    ["members", "成员与邀请"],
    ["settings", "设置与交接"],
    ...(admin ? [["models", "模型服务"]] : []),
  ]
    .map(([key, label]) => navLink(`workspace/${w}/${key}`, label, tab === key))
    .join("")}</nav><section id="workspace-projects"><h2>我的项目</h2>${table(
    ["项目", "状态"],
    projects.map((p) => [
      link("project/" + p.id, p.name),
      p.archived ? "已归档" : "活动",
    ]),
  )}${form("newProject", "创建项目", input("name", "项目名称"), "创建项目")}</section><section id="workspace-members"><h2>团队成员</h2>${table(
    ["成员", "角色", "状态", "管理"],
    members.map((m) => [
      esc(m.displayName) + " (@" + esc(m.login) + ")",
      esc(label(m.role)),
      m.active ? "有效" : "已离开",
      admin && m.active
        ? `<button data-role="${m.id}">${m.role === "ADMIN" ? "降为成员" : "授予管理员"}</button><button class="danger" data-remove="${m.id}">移除</button>`
        : "",
    ]),
  )}<button class="danger" id="leave">退出团队</button></section>`;
  $("#workspace-projects").hidden = tab !== "projects";
  $("#workspace-members").hidden = tab !== "members";
  if (!admin && tab === "settings")
    app.insertAdjacentHTML(
      "beforeend",
      '<p class="empty">团队设置与维护者交接由团队管理员管理。请在“成员与邀请”中查看管理员。</p>',
    );
  bind("newProject", async (r) => {
    const p = await api(`/workspaces/${w}/projects`, "POST", r);
    navigate("project/" + p.id, "项目已创建");
  });
  button("leave", async () => {
    await api(`/workspaces/${w}/members/${me.userId}`, "PUT", {role:"MEMBER",active:false});
    navigate("home", "已退出团队。历史工作仍保留，重新加入需管理员发出新邀请。");
  });
  document.querySelectorAll("[data-role]").forEach(
    (el) =>
      (el.onclick = () =>
        run(() =>
          mutation(`/workspaces/${w}/members/${el.dataset.role}`, "PUT", {
            role:
              members.find((m) => m.id === el.dataset.role).role === "ADMIN"
                ? "MEMBER"
                : "ADMIN",
            active: true,
          }),
        )),
  );
  document.querySelectorAll("[data-remove]").forEach(
    (el) =>
      (el.onclick = () =>
        run(() =>
          mutation(`/workspaces/${w}/members/${el.dataset.remove}`, "PUT", {
            role: "MEMBER",
            active: false,
          }),
        )),
  );
  if (admin) {
    const [invitations, managed, audit] = await Promise.all([
      api(`/workspaces/${w}/invitations`),
      api(`/workspaces/${w}/management/projects`),
      api(`/workspaces/${w}/management/activities`),
    ]);
    app.insertAdjacentHTML(
      "beforeend",
      '<section id="workspace-settings">' +
        form(
          "rename",
          "团队设置",
          input("name", "团队名称", "text", workspace.name),
        ) +
        '</section><section id="workspace-invitations">' +
        form(
          "invite",
          "邀请成员",
          input("email", "成员邮箱", "email") + "<p>可邀请尚未注册的邮箱；对方需注册、验证邮箱并登录后接受邀请。</p>",
          "发送邀请",
        ) +
        table(
          ["邮箱", "状态", "操作"],
          invitations.map((i) => [
            esc(i.email),
            esc(label(i.status)),
            i.status === "PENDING"
              ? `<button data-revoke="${i.id}">撤回</button>`
              : "",
          ]),
        ) +
        '</section><section id="workspace-handover">' +
        form(
          "handover",
          "授予项目维护权",
          select(
            "project",
            "项目",
            managed.map((p) => [p.id, p.name]),
          ) +
            select(
              "userId",
              "新维护者",
              members
                .filter((m) => m.active)
                .map((m) => [m.id, m.displayName + " @" + m.login]),
            ),
          "授予维护者",
        ) +
        "<p>团队管理员可授予项目维护权；不会自动移除旧维护者。确认交接后再移除原成员。</p>" +
        table(
          ["管理记录", "时间"],
          audit.map((x) => [esc(label(x.kind)), esc(timeLabel(x.createdAt))]),
        ) +
        "</section>",
    );
    $("#workspace-settings").hidden = tab !== "settings";
    $("#workspace-handover").hidden = tab !== "settings";
    $("#workspace-invitations").hidden = tab !== "members";
    bind("rename", (r) =>
      mutation("/workspaces/" + w, "PATCH", {
        name: r.name,
        revision: workspace.revision,
      }),
    );
    bind("invite", (r) => mutation(`/workspaces/${w}/invitations`, "POST", r));
    bind("handover", (r) =>
      mutation(
        `/workspaces/${w}/management/projects/${r.project}/handover`,
        "POST",
        { userId: r.userId },
      ),
    );
    document
      .querySelectorAll("[data-revoke]")
      .forEach(
        (el) =>
          (el.onclick = () =>
            run(() =>
              mutation(
                `/workspaces/${w}/invitations/${el.dataset.revoke}`,
                "DELETE",
              ),
            )),
      );
  }
}
async function projectPage(p, tab, params = new URLSearchParams()) {
  if (tab === "notifications") {
    location.hash = "inbox?projectId=" + p;
    return;
  }
  const [project, members] = await Promise.all([
    tab === "overview" && contextProject?.id === p ? Promise.resolve(contextProject) : api("/projects/" + p),
    tab === "overview" ? Promise.resolve([]) : api(`/projects/${p}/members`),
  ]);
  const maintainer =
    members.find((m) => m.id === me.userId)?.role === "MAINTAINER";
  app.innerHTML = `${crumbs()}<h1>${esc(project.name)} ${project.archived ? '<span class="badge">已归档 · 只读</span>' : ""}</h1>${tab==="overview"&&project.description?`<p class="project-description">${esc(project.description)}</p>`:""}<nav class="tabs">${[
    ["overview", "概览"],
    ["tasks", "任务"],
    ["documents", "文档"],
    ["knowledge", "搜索资料"],
    ["imports", "CSV 导入"],
    ["github", "GitHub"],
    ["settings", "项目管理"],
    ["models", "模型与检索"],
  ]
    .map(([t, n]) => navLink(`project/${p}/${t}`, n, tab === t))
    .join(
      "",
    )}</nav><div id="panel"></div>`;
  const panel = $("#panel");
  if (tab === "models") return projectModelPage(panel, p, project, maintainer);
  if (tab === "overview") return projectOverviewPage(panel, p, members, params);
  if (tab === "agent" && !params.get("run")) return conversationPage(panel,p,params);
  if (tab === "agent") {
    panel.innerHTML = form("agent-question", "询问项目情况", `<label>问题<br><textarea name="question" required maxlength="2000" rows="3" placeholder="登录模块当前由谁负责？为什么阻塞发布任务？"></textarea></label>`, "开始分析") + `<p class="scope-note">助手只读取当前项目的任务、依赖、讨论、文档和已同步的 GitHub 资料。回答不会修改任务；点击引用编号可核对依据。</p><div class="question-suggestions"><button type="button" class="secondary" data-question="项目目前有哪些进行中的工作和待处理任务？请注明负责人与依据。">了解项目进展</button><button type="button" class="secondary" data-question="哪些任务被上游依赖阻塞？请列出直接原因和相关任务。">查清阻塞</button><button type="button" class="secondary" data-question="项目有哪些来自 GitHub 的任务？哪些仍待分诊？请给出来源。">查看外部问题</button></div><p id="answered-question" class="scope-note" hidden></p><div id="agent-answer" aria-live="polite"></div>`;
    panel.querySelector('#agent-question').insertAdjacentHTML('beforebegin','<button id="new-question" class="secondary" hidden>提出新问题</button>');
    button('new-question',async()=>{panel.querySelector('#agent-question').hidden=false;panel.querySelector('.question-suggestions').hidden=false;panel.querySelector('#new-question').hidden=true;questionField.focus();});
    panel.insertAdjacentHTML('afterbegin','<button id="legacy-conversation" class="secondary">将此历史问答转为会话</button>');
    button('legacy-conversation',async()=>{const c=await api(`/projects/${p}/agent/runs/${params.get('run')}/conversation`,'POST');location.hash=`project/${p}/agent?conversation=${c.id}`;});
    const questionField = panel.querySelector('[name="question"]');
    const draft = params.get("question");
    if(draft) questionField.value = draft.slice(0,2000);
    panel.querySelectorAll('[data-question]').forEach(button=>button.onclick=()=>{if(!questionField.disabled){questionField.value=button.dataset.question;questionField.focus();}});
    const labels = {FACT:"当前事实",EVIDENCE:"文本证据",INFERENCE:"推断",UNKNOWN:"尚不能确定"};
    const toolLabels = {read_content:"正文读取",find_documents:"文档查找",list_comments:"任务讨论",read_github_resource:"GitHub 同步原文",get_project_summary:"项目概况",get_task:"任务状态",list_tasks:"任务列表",get_direct_blockers:"直接阻塞",get_dependency_path:"依赖路径",get_linked_resources:"关联资料",get_sync_intent:"同步状态",search_project_evidence:"项目证据检索"};
    const errors = {AGENT_DISABLED:"此实例尚未启用项目助手。",GLM_BUDGET_NOT_APPROVED:"请团队管理员配置文本模型后重新发起分析。",GLM_COST_BUDGET:"这条历史分析已结束，请使用团队模型重新发起。",AGENT_TIMEOUT:"分析超时，请缩小问题范围后重试。",GLM_TIMEOUT:"模型响应超时，请稍后重新提问。",GLM_RATE_LIMIT:"模型服务限流，请稍后重新提问。",INVALID_AGENT_ANSWER:"模型回答或来源未通过校验，请重试。",AGENT_CONTEXT_CHANGED:"项目在分析期间变化，请重新提问。",KNOWLEDGE_CHANGED:"所用资料已更新或删除，请重新提问。",FORBIDDEN:"当前访问权限已失效。"};
    function sourceLink(s) {
      const title=s.kind==="FACT"?(s.data?.id?s.title:toolLabels[s.toolName]||"业务事实来源"):(s.evidence?.title||s.title||(s.evidence?.kind==="COMMENT"?"查看所属任务的讨论":"项目来源"));
      const path=(s.url||"").match(/^\/projects\/([0-9a-f-]{36})\/(tasks|documents)\/([0-9a-f-]{36})(?:\/comments\/[0-9a-f-]{36})?(?:#.*)?$/);
      if(path&&path[1]===p) {
        const q=new URLSearchParams({from:location.hash.slice(1)});
        const comment=(s.url||"").match(/comments\/([0-9a-f-]{36})/);
        if(comment)q.set("comment",comment[1]);
        if(s.evidence&&s.evidence.kind!=="GITHUB_COMMENT"){q.set("start",s.evidence.charStart);q.set("end",s.evidence.charEnd);}
        if(s.evidence?.kind==="GITHUB_COMMENT")q.set("section","github-status");
        return link(`${path[2]==="tasks"?"task":"document"}/${p}/${path[3]}?${q}`,title);
      }
      if(s.url===`/projects/${p}`)return link(`project/${p}`,title);
      if(/^https:\/\/github\.com\//.test(s.url||""))return `<a href="${esc(s.url)}" target="_blank" rel="noopener noreferrer">${esc(title)}</a>`;
      return `<span>${esc(title)}</span><p class="muted">此来源暂无可直接打开的业务链接，可核对下方片段。</p>`;
    }
    function currentFacts(s, shownTasks) {
      const d=s.data||{}, found=[];
      const factLink=(route,title)=>link(route+'?'+new URLSearchParams({from:location.hash.slice(1)}),title);
      function collect(v) {
        if(!v||typeof v!=="object")return;
        if(v.id&&v.title&&v.status&&!shownTasks.has(v.id)){found.push(v);shownTasks.add(v.id);}
        for(const value of Object.values(v))if(value&&typeof value==="object")Array.isArray(value)?value.forEach(collect):collect(value);
      }
      collect(d);
      let html=found.length?table(["任务","当前状态","优先级","负责人"],found.map(t=>[factLink(`task/${p}/${t.id}`,t.title),esc(stateName(t.status)),t.priority==null?"待确认":esc("P"+t.priority),t.assigneeId?esc(members.find(m=>m.id===t.assigneeId)?.displayName||"项目成员"):"未指派"])):"";
      if(d.statusCounts)html+=table(["当前状态","任务数"],d.statusCounts.map(r=>[esc(stateName(r.status)),esc(r.count)]));
      if(d.path)html+=`<p>已核验依赖路径：${d.path.length?d.path.map(id=>factLink(`task/${p}/${id}`,d.nodes?.find(t=>t.id===id)?.title||"查看任务")).join(" → "):"这两个任务之间没有依赖路径"}</p>`;
      if(d.status&&d.syncRevision!=null)html+=`<p>同步状态 ${esc(d.status)} · 最后确认 ${esc(d.confirmedAt)} · 待处理请求 ${esc(d.pending?.state||"无")}</p>`;
      if(d.links)html+=`<p>${(d.links.documents||[]).map(d=>factLink(`document/${p}/${typeof d==="string"?d:d.objectId}`,typeof d==="string"?"关联文档":d.title)).join(" · ")}</p>`;
      return html||`<p>已读取关联事实，请打开来源核对上下文。</p>`;
    }
    let selectedQa, qaVersion=0;
    async function show(id) {
      selectedQa=id;const version=++qaVersion;runContext(id);
      const alive=()=>panel.isConnected && selectedQa===id && version===qaVersion;
      if(location.hash.split("?")[0]!==`#project/${p}/agent`) return;
      let r;
      try { r=await api(`/projects/${p}/agent/runs/${id}`); }
      catch(e){if(alive())$("#agent-answer").textContent=errors[e.code]||e.message;return;}
      if(!alive())return;
      const reading = ["QUEUED","RUNNING"].includes(r.status) || !!r.result?.answer;
      panel.querySelector('#agent-question').hidden=reading;
      panel.querySelector('.question-suggestions').hidden=reading;
      panel.querySelector('#new-question').hidden=!r.result?.answer;
      $("#answered-question").hidden=!r.question;$("#answered-question").textContent="本次问题："+(r.question||"");
      if(["QUEUED","RUNNING"].includes(r.status)) {
        $("#agent-answer").textContent=progressText({status:r.status,stage:r.status});
        watchRun(p,id,alive,s=>{$("#agent-answer").textContent=s.stage==='DISCONNECTED'&&!s.errorCode?'连接中断，正在恢复当前状态；无需重复提交。':progressText(s);},()=>show(id));return;
      }
      const result=r.result;
      if(!result?.answer){$("#agent-answer").textContent=errors[r.errorCode]||progressText({status:r.status,errorCode:r.errorCode});return;}
      const sources=new Map(result.sources.map(s=>[s.id,s]));
      // Numbers label persisted selections only. They never manufacture a citation or support verdict.
      const selectedIds=[...new Set(result.answer.claims.flatMap(c=>c.citations).filter(id=>sources.has(id)))];
      const numbers=new Map(selectedIds.map((id,i)=>[id,i+1]));
      const factIds=[...new Set(result.answer.claims.flatMap(c=>c.citations).filter(id=>sources.get(id)?.kind==="FACT"))];
      const shownTasks=new Set();
      const readObjects=[...new Set(result.sources.filter(s=>s.evidence||s.toolName==="get_task").map(s=>s.data?.taskTitle?`${s.data.taskTitle}的评论`:s.title).filter(Boolean))];
      const sourceKinds={TASK:"任务描述",COMMENT:"历史评论",DOCUMENT:"文档",GITHUB_ISSUE:"Issue",GITHUB_COMMENT:"外部评论"};
      $("#agent-answer").innerHTML = `<h2>助手回答</h2>` +
        result.answer.claims.map(c=>`<article class="agent-claim">${c.kind==="ANSWER"?"":`<strong>${esc(c.kind==="FACT"?"当前情况":labels[c.kind]||c.kind)}</strong>`}<p>${esc(c.text)} ${[...new Set(c.citations)].map(id=>numbers.has(id)?`<button type="button" class="citation-marker" data-source-number="${numbers.get(id)}" aria-label="查看引用 ${numbers.get(id)}" aria-controls="agent-source-${numbers.get(id)}">[${numbers.get(id)}]</button>`:"").join(" ")}</p>${c.kind==="UNKNOWN"?`<p class="muted">此项证据不足，不能据此作出确定结论。</p>`:""}</article>`).join("") +
        (factIds.length?`<article class="agent-facts"><strong>当前事实</strong><p class="muted">以下是回答生成时读取的任务信息；打开任务可查看最新状态。</p>${factIds.map(id=>sources.has(id)?currentFacts(sources.get(id),shownTasks):"").join("")}</article>`:"") +
        (result.answer.limitations.length?`<p role="status">${result.answer.limitations.map(esc).join("；")}</p>`:"") +
        `<p class="muted">已读取对象：${readObjects.map(esc).join("、")||"暂无"}</p><p class="muted">已查询：${[...new Set(result.tools)].map(t=>esc(toolLabels[t]||"项目资料")).join("、")} · ${(result.elapsedMillis/1000).toFixed(1)}秒</p>` +
        (selectedIds.length?`<details class="agent-sources"><summary>核对来源</summary>${selectedIds.map(id=>{
          const s=sources.get(id),n=numbers.get(id);
          const type=s.toolName==="read_github_resource"?"GitHub 同步原文":s.evidence?(sourceKinds[s.evidence.kind]||s.evidence.kind):"实时业务查询";
          const snippet=s.evidence?s.evidence.text:s.toolName==="read_github_resource"?s.data.text:null;
          return `<article id="agent-source-${n}" tabindex="-1"><strong>[${n}] ${esc(type)}</strong><p>${sourceLink(s)}</p><p class="muted">读取于 ${esc(timeLabel(s.observedAt))} · ${s.evidence ? "已核对来源版本，可打开原文查看上下文" : "回答生成时的业务记录"}</p>${s.evidence?.kind==="COMMENT"?`<p class="muted">${esc(s.data?.taskTitle||"所属任务")} · ${esc(s.data?.authorDisplayName||"作者未提供")} · 创建于 ${esc(s.data?.createdAt||"时间未提供")}。评论片段是历史陈述；链接打开所属任务。</p>`:""}${s.toolName==="read_github_resource"?`<p class="muted">同步确认于 ${esc(s.data.confirmedAt||"未知")} · 字符 ${s.data.range.start}–${s.data.range.end} / ${s.data.range.totalCharacters}。这是已同步资料，不代表实时远端状态。</p>`:""}${snippet!==null?`<pre class="agent-evidence">${esc(snippet)}</pre>`:currentFacts(s,new Set())}</article>`;
        }).join("")}</details>`:`<p class="muted">本次回答没有选用可核对的来源。</p>`);
      $("#agent-answer").querySelectorAll(".citation-marker").forEach(button=>button.addEventListener("click",async()=>{
        button.disabled=true;
        try {
          // Reuse the authorized run read: deletion/revocation after initial rendering must invalidate the snapshot.
          const latest=await api(`/projects/${p}/agent/runs/${id}`);
          if(location.hash.split("?")[0]!==`#project/${p}/agent`)return;
          if(!latest.result?.answer){await show(id);return;}
          const details=$("#agent-answer .agent-sources");details.open=true;
          const card=document.getElementById(`agent-source-${button.dataset.sourceNumber}`);
          card.focus({preventScroll:true});card.scrollIntoView({block:"nearest",behavior:"smooth"});
        } catch(e) {
          if(location.hash.split("?")[0]===`#project/${p}/agent`)$("#agent-answer").textContent=errors[e.code]||e.message;
        } finally {button.disabled=false;}
      }));
    }
    const qaKey=`qa-run:${me.userId}:${p}`, intentKey=qaKey+':intent';
    function syncQaIntent() {
      const pending=JSON.parse(sessionStorage.getItem(intentKey)||'null');
      const field=panel.querySelector('[name="question"]');field.disabled=!!pending;
      if(pending)field.value=pending.body.question;
      panel.querySelector('#agent-question button').textContent=pending?'重试确认上次提问':'开始分析';
      panel.querySelectorAll('[data-question]').forEach(b=>b.disabled=!!pending);
    }
    syncQaIntent();
    bind("agent-question", async r=>{
      stopRunProgress();++qaVersion;$("#agent-answer").textContent="正在提交问题…";
      let intent=JSON.parse(sessionStorage.getItem(intentKey)||'null');
      if(!intent){intent={key:crypto.randomUUID(),body:r};sessionStorage.setItem(intentKey,JSON.stringify(intent));syncQaIntent();}
      let value;
      try{value=await api(`/projects/${p}/agent/runs`,"POST",intent.body,{"Idempotency-Key":intent.key});}
      catch(e){if(e.status>=400&&e.status<500 || ["AGENT_DISABLED","GLM_BUDGET_NOT_APPROVED","MODEL_BUDGET_NOT_APPROVED"].includes(e.code))sessionStorage.removeItem(intentKey);syncQaIntent();$("#agent-answer").textContent=modelReason(e.code)||errors[e.code]||readableError(e.message);throw e;}
      sessionStorage.setItem(qaKey,value.id);sessionStorage.removeItem(intentKey);syncQaIntent();
      if(panel.isConnected)await show(value.id);
    });
    const restore=params.get("run")||(!draft?sessionStorage.getItem(qaKey):null);
    if (/^[0-9a-f-]{36}$/.test(restore || "")) await show(restore);
    return;
  }
  if (tab === "knowledge") {
    let status;
    try { status = await api(`/projects/${p}/knowledge/status`); }
    catch (error) { panel.innerHTML = `<p>此实例尚未启用知识检索。${esc(error.message)}</p>`; return; }
    panel.innerHTML = `<h2>项目知识检索</h2><p>搜索任务、文档与评论。</p><details class="operational-details"><summary>索引状态</summary><p id="index-status">可用 ${status.eligible} · 已索引 ${status.published} · 待处理 ${status.pending} · 失败 ${status.failed}</p></details>` +
      form("knowledge-search", "搜索项目资料", input("q", "关键词或问题", "text", params.get("q") || "") + select("strategy", "检索方式", [["BM25","关键词"],["DENSE","语义"],["HYBRID","混合"]], "BM25"), "搜索") +
      `<div id="knowledge-results" aria-live="polite"></div>` +
      (maintainer && !project.archived ? `<h3>资料回填与恢复</h3><p>为存量资料或丢失的索引登记后台重建。资料较多时分批提交，可继续处理下一批。</p><button id="knowledge-rebuild">登记回填 / 重建</button><p id="rebuild-result" role="status"></p>` : "");
    bind("knowledge-search", async r => {
      const value = await api(`/projects/${p}/knowledge/search?` + new URLSearchParams({q:r.q,limit:10,strategy:r.strategy}));
      $("#knowledge-results").innerHTML = `<p>${value.results.length} 项结果 · 实际采用${({BM25:"关键词",DENSE:"语义",HYBRID:"混合"})[value.strategy]||"关键词"}检索${value.degradedReason?" · "+esc(modelReason(value.degradedReason)||"语义检索暂时不可用，已采用关键词检索。"):""}${value.retrievalComplete ? "" : " · 索引尚不完整，结果可能有遗漏"}，过滤失效候选 ${value.droppedStale}。</p>` +
        value.results.map(e => {
          const destination = e.kind === "DOCUMENT" ? `document/${p}/${e.ownerId}` : e.canonicalUrl.startsWith(`/projects/${p}/tasks/`) ? `task/${p}/${e.canonicalUrl.split('/')[4]}` : null;
          const source = destination ? link(destination,e.title || "查看任务讨论") : `<a href="${esc(e.canonicalUrl)}" target="_blank" rel="noopener noreferrer">查看来源</a>`;
          return `<article><h3>${source}</h3><p class="muted">来源版本 ${e.sourceRevision} · 字符 ${e.charStart}–${e.charEnd} · ${esc(e.kind)}</p><pre style="white-space:pre-wrap">${esc(e.text)}</pre></article>`;
        }).join("");
    });
    let cursor = null;
    button("knowledge-rebuild", async () => {
      const result = await api(`/projects/${p}/knowledge/rebuild`, "POST", {cursor,limit:50}, {"Idempotency-Key":crypto.randomUUID()});
      cursor = result.nextCursor;
      $("#rebuild-result").textContent = `已受理 ${result.jobIds.length} 项后台义务。${cursor ? "点击继续回填下一批。" : "本轮资料已遍历；可刷新查看实际索引进度。"}`;
      $("#knowledge-rebuild").textContent = cursor ? "继续回填下一批" : "再次登记重建";
    });
    return;
  }
  if (tab === "tasks") {
    panel.innerHTML =
      form(
        "filter",
        "查找任务",
        input("q", "标题包含", "text", "", false) +
          select("status", "状态", [
            ["", "全部"],
            ...states.map((s) => [s, stateName(s)]),
          ]) +
          select("assignee", "负责人", [
            ["", "全部"],
            ["unassigned", "未分配"],
            ["inactive", "负责人已失效"],
            ...members
              .filter((m) => m.active)
              .map((m) => [m.id, m.displayName]),
          ]) +
          select("blocked", "依赖", [
            ["", "全部"],
            ["true", "直接被阻塞"],
            ["false", "未阻塞"],
          ]) +
          input("due", "截止日期不晚于", "date", "", false),
        "查询",
      ) +
      '<div id="tasks"></div><div id="task-pagination" class="pagination"><button id="prev">上一页</button><button id="next">下一页</button></div>' +
      form(
        "newTask",
        "创建任务",
        input("title", "标题") +
          '<label>描述<textarea name="description"></textarea></label>' +
          input("dueDate", "截止日期", "date", "", false) +
          (maintainer ? select("assigneeId", "负责人", [["", "未分配"], ...members.filter(m => m.active).map(m => [m.id, m.displayName])]) +
            select("priority", "正式优先级", [[0, "P0 · 最高"], [1, "P1 · 高"], [2, "P2 · 普通"], [3, "P3 · 低"]], 2) +
            select("status", "初始状态", [["TODO", "待办"], ["TRIAGE", "待分诊"]]) :
            '<p class="scope-note">提交后进入待分诊。你可以完善标题、描述、截止日期和上游依赖；负责人及正式优先级由项目维护者接纳时确定。</p>'),
        "创建任务",
      );
    let offset = Number(params.get("offset") || 0),
      filters = Object.fromEntries(["q","status","assignee","blocked","due"].map(k => [k, params.get(k) || ""]));
    for (const [k,v] of Object.entries(filters)) $("#filter [name=" + k + "]").value = v;
    const load = async () => {
      const query = new URLSearchParams(
        Object.fromEntries(Object.entries(filters).filter(([, v]) => v !== "")),
      );
      query.set("offset", offset);
      query.set("limit", 25);
      const result = await api(`/projects/${p}/task-search?${query}`);
      $("#tasks").innerHTML =
        states
          .map((s) => {
            const rows = result.items.filter((t) => t.status === s);
            return rows.length
              ? `<h2>${esc(stateName(s))}</h2>${taskRows(rows, members)}`
              : "";
          })
          .join("") || '<p class="empty">没有匹配的任务。</p>';
      const from = encodeURIComponent(location.hash.slice(1));
      $("#tasks").querySelectorAll('a[href^="#task/"]').forEach(el => el.href += "?from=" + from);
      $("#prev").disabled = offset === 0;
      $("#next").disabled = !result.hasMore;
    };
    bind("filter", async (r) => {
      offset = 0;
      filters = r;
      location.hash = `project/${p}?` + new URLSearchParams(Object.entries(filters).filter(([,v]) => v !== ""));
    });
    button("prev", async () => {
      const q = new URLSearchParams(params); q.set("offset", Math.max(0, offset - 25)); location.hash = `project/${p}?` + q;
    });
    button("next", async () => {
      const q = new URLSearchParams(params); q.set("offset", offset + 25); location.hash = `project/${p}?` + q;
    });
    bindCreation("newTask", `/projects/${p}/tasks`, r => ({
      ...r, dueDate: r.dueDate || null,
      ...(maintainer ? { assigneeId: r.assigneeId || null, priority: Number(r.priority) } : { status: "TRIAGE" }),
    }), task => navigate(`task/${p}/${task.id}`, task.triageRequired ? "已提交，等待分诊" : "任务已创建"));
    await load();
  }
  if (tab === "documents") {
    const query = params.get("q") || "", initialCursor = params.get("cursor") || "";
    let cursor = initialCursor, shown = 0;
    panel.innerHTML = form("document-filter", "查找文档", input("q", "搜索标题或正文", "search", query, false), "搜索") +
      (initialCursor ? link(`project/${p}/documents?${new URLSearchParams({q:query})}`, "返回文档列表开头") : "") +
      '<div id="document-items" aria-live="polite"></div><div class="pagination"><button id="document-more" class="secondary" hidden>加载更多文档</button><p id="document-count" class="muted" role="status"></p></div>' +
      form("document", "新建项目文档", input("title", "标题") + '<label>正文<textarea name="body" required></textarea></label>', "创建文档");
    $("#document-filter [name=q]").maxLength = 200;
    bind("document-filter", r => {location.hash = `project/${p}/documents?${new URLSearchParams({q:r.q})}`;});
    async function loadDocuments() {
      const start = cursor, q = new URLSearchParams({q:query,limit:51});if (start) q.set("cursor", start);
      const page = await api(`/projects/${p}/documents?${q}`);if (!panel.isConnected) return;
      const docs = page.slice(0, 50);
      const from = `project/${p}/documents?${new URLSearchParams({q:query,...(start ? {cursor:start} : {})})}`;
      if (docs.length) {
        $("#document-items").insertAdjacentHTML("beforeend", table(["文档", "分类"], docs.map(d => [link(`document/${p}/${d.id}?${new URLSearchParams({from})}`, d.title), esc(label(d.kind))])));
        shown += docs.length;cursor = contentCursor(docs.at(-1));
      } else if (!shown) $("#document-items").innerHTML = '<p class="empty">没有匹配的文档。</p>';
      $("#document-more").hidden = page.length <= 50;
      $("#document-count").textContent = `已显示 ${shown} 篇文档`;
    }
    button("document-more", loadDocuments);
    bindCreation("document", `/projects/${p}/documents`, r => r, d => navigate(`document/${p}/${d.id}`, "文档已创建"));
    await loadDocuments();
  }
  if (tab === "imports") {
    const rows = await api(`/projects/${p}/imports`);
    panel.innerHTML =
      '<p>UTF-8 CSV，最大 1 MiB / 1000 行。预检查后确认导入；重复上传会创建新导入。</p><a href="/sample.csv" download>下载混合结果样例</a>' +
      (maintainer ? form("upload", "上传 CSV", input("file", "CSV 文件", "file"), "预检查") : '<p class="scope-note">CSV 导入包含正式优先级和负责人安排，由项目维护者操作。你可以在任务页提交待分诊申请。</p>') +
      table(
        ["文件", "状态", "结果"],
        rows.map((r) => [
          esc(r.filename),
          esc(label(r.state)),
          link(`import/${p}/${r.id}`, "查看"),
        ]),
      );
    bind("upload", async (r, el) => {
      const id = await api(`/projects/${p}/imports`, "POST", new FormData(el));
      location.hash = `import/${p}/${id}`;
    });
  }
  if (tab === "settings") {
    const workspaceMembers = await api(
      `/workspaces/${project.workspaceId}/members`,
    );
    panel.innerHTML =
      table(
        ["项目成员", "角色", "状态"],
        members.map((m) => [
          esc(m.displayName),
          esc(label(m.role)),
          m.active ? "有效" : "已失效",
        ]),
      ) +
      (maintainer
        ? form(
            "settings",
            "项目详情",
            input("name", "名称", "text", project.name) +
              '<label>描述<textarea name="description">' +
              esc(project.description) +
              "</textarea></label>" +
              select(
                "archived",
                "项目状态",
                [
                  ["false", "活动"],
                  ["true", "归档"],
                ],
                String(project.archived),
              ),
          ) +
          form(
            "membership",
            "项目成员管理",
            select(
              "userId",
              "团队成员",
              [["", "选择团队成员…"], ...workspaceMembers
                .filter((m) => m.active)
                .map((m) => [m.id, m.displayName + " @" + m.login])],
            ) +
              select("role", "角色", [
                ["MEMBER", "项目成员"],
                ["MAINTAINER", "项目维护者"],
              ]) +
              select("active", "访问状态", [
                ["true", "加入 / 更新"],
                ["false", "移除"],
              ]),
          )
        : "<p>成员维护与归档由项目维护者管理。</p>");
    bind("settings", (r) =>
      mutation("/projects/" + p, "PATCH", {
        ...r,
        archived: r.archived === "true",
        expectedBusinessRevision: project.businessRevision,
      }),
    );
    if (maintainer) {
      const auto = document.createElement('section');auto.id='automatic-triage-settings';panel.append(auto);
      const load = async () => {
        try {
          const config=await api(`/projects/${p}/agent/triage-settings`);
          if (!auto.isConnected) return;
          auto.innerHTML='<h2>自动分诊分析</h2><details><summary>触发条件与模型调用</summary><p>开启后，新建的待分诊任务使用团队文本模型自动分析；历史导入用于同步任务资料。关闭后停止后续调用，已发送的请求继续记录用量。分析建议由维护者确认接纳。</p></details>' +
            (project.archived ? '<p>项目已归档，恢复后可修改设置。</p>' : form('auto-triage','分析设置',select('enabled','新任务自动分析',[['false','关闭'],['true','开启']],String(config.enabled))+select('strategy','默认分析方式',[['WORKFLOW','固定资料分析'],['GUIDED','固定资料并补充查证'],['AGENT','自主调查']],config.strategy),'保存分析设置'));
          bind('auto-triage',async values=>{await api(`/projects/${p}/agent/triage-settings`,'PUT',{enabled:values.enabled==='true',strategy:values.strategy,expectedRevision:config.revision});await load();message('自动分析设置已保存，仅影响后续新建任务。');});
        } catch(e) {
          if (!auto.isConnected) return;
          auto.textContent=`自动分析设置暂不可用：${readableError(e.message)} `;
          const retry=document.createElement('button');retry.textContent='重试加载';retry.onclick=load;auto.append(retry);
        }
      };
      void load();
    }
    const memberSelect = $("#membership [name=userId]");
    if (memberSelect) {
      memberSelect.required = true;
      memberSelect.onchange = () => {
        const existing = members.find(m => m.id === memberSelect.value && m.active);
        $("#membership [name=role]").value = existing?.role || "MEMBER";
        $("#membership [name=active]").value = "true";
      };
    }
    bind("membership", (r) =>
      mutation(`/projects/${p}/members/${r.userId}`, "PUT", {
        role: r.role,
        active: r.active === "true",
        expectedBusinessRevision: project.businessRevision,
      }),
    );
  }
  if (tab === "github") {
    const [connections, operations] = await Promise.all([
      api(`/projects/${p}/github/connections`),
      api(`/projects/${p}/github/operations`),
    ]);
    panel.innerHTML =
      `<div class="section-heading"><h2>GitHub 仓库</h2>${link(`project/${p}/tasks?status=TRIAGE`,"待分诊任务")}</div>` +
      table(
        ["仓库", "连接", "已导入 / 错误", "最近成功同步", "操作"],
        connections.map((c) => [
          esc(c.repositoryName),
          c.enabled ? "启用" : "停用",
          `${c.imported} / ${c.errors}`,
          esc(c.lastSuccessfulScanAt ? timeLabel(c.lastSuccessfulScanAt) : "尚无成功同步"),
          maintainer
            ? `<button data-toggle="${c.id}">${c.enabled ? "停用" : "恢复"}</button><button data-reconcile="${c.id}">只读对账</button>`
            : "",
        ]),
      ) +
      "<h2>同步记录</h2>" +
      table(
        ["开始时间", "仓库", "处理进度", "结果", "原因"],
        operations.map((o) => [
          esc(timeLabel(o.createdAt)),
          esc(o.repositoryName),
          `发现 ${o.total} 项 · 已应用 ${o.succeeded} 项 · 失败 ${o.failed} 项 · 待处理 ${Math.max(0, o.total - o.succeeded - o.failed)} 项`,
          esc(({ SUCCEEDED: "本轮处理完成", RUNNING: "正在逐项读取", DISCOVERED: "等待处理", FAILED: "读取失败", PARTIAL_FAILED: "部分处理失败" })[o.state] || label(o.state)),
          esc(reason(o.error)),
        ]),
      ) +
      '<button id="refresh">刷新结果</button>';
    button("refresh", route);
    if (maintainer) {
      const [repos, setup] = await Promise.all([api(`/projects/${p}/github/repositories`), api(`/projects/${p}/github/setup`)]);
      const ready = setup.credentialsConfigured && setup.readEnabled && setup.authorizedRepositories > 0;
      panel.insertAdjacentHTML("afterbegin", `<details id="github-setup" ><summary>GitHub 接入配置 · ${ready ? '已有配置，选择仓库即可连接' : '首次接入 / 检查配置'}</summary><p>连接使用 Work Hub 服务器的 GitHub App 身份，无需把个人密码或令牌交给项目。第一次接入按以下三步进行：</p><ol class="setup-steps"><li><strong>部署者配置 Work Hub 的 GitHub App</strong><p>${setup.credentialsConfigured ? '服务器已填写 App 配置。' : '服务器尚未填写 App 配置，请部署者提供 App ID、私钥文件和同步配置。'}读取同步${setup.readEnabled ? '已启用' : '未启用'}；远端写回${setup.writeEnabled ? '已启用' : '未启用（仍可导入和本地分诊）'}。这里检查的是本地配置，实际读取结果以下方记录为准。</p></li><li><strong>仓库拥有者在 GitHub 授权这个 App</strong><p>在 GitHub 安装部署者提供的 App，只勾选要接入的仓库。已有安装换仓库时，到 GitHub → Settings → Applications → Installed GitHub Apps → Configure 调整可访问仓库。</p>${setup.installUrl ? `<a class="setup-link" href="${esc(setup.installUrl)}" target="_blank" rel="noopener">去 GitHub 安装并选择仓库</a>` : '<p class="muted">部署者尚未配置 App 安装链接，请向部署者获取这个 App 的安装地址。</p>'}</li><li><strong>部署者确认归属，维护者连接项目</strong><p>GitHub 的授权只决定 App 能访问什么；部署者还需将该安装和仓库授权给你的 Work Hub 团队，防止其他团队接入。当前团队已登记 ${setup.authorizedRepositories} 个授权仓库。登记后在下方选择仓库，点击“连接并开始导入”。</p></li></ol></details>`);

      panel.insertAdjacentHTML(
        "beforeend",
        repos.filter((r) => !r.connected).length
          ? form(
              "connect",
              "连接授权仓库",
              select(
                "repo",
                "仓库",
                repos
                  .filter((r) => !r.connected)
                  .map((r) => [r.repositoryId, r.repositoryName]),
              ),
              "连接并开始导入",
            )
          : '<p class="empty">没有可连接的仓库。请在接入配置中检查授权。</p>',
      );
      bind("connect", (r) => {
        const repo = repos.find((x) => String(x.repositoryId) === r.repo);
        return mutation(`/projects/${p}/github/connections`, "POST", {
          installationId: repo.installationId,
          repositoryId: repo.repositoryId,
          repositoryName: repo.repositoryName,
        });
      });
      document.querySelectorAll("[data-toggle]").forEach(
        (el) =>
          (el.onclick = () =>
            run(() =>
              mutation(
                `/projects/${p}/github/connections/${el.dataset.toggle}`,
                "PATCH",
                {
                  enabled: !connections.find((c) => c.id === el.dataset.toggle)
                    .enabled,
                },
              ),
            )),
      );
      document
        .querySelectorAll("[data-reconcile]")
        .forEach(
          (el) =>
            (el.onclick = () =>
              run(() =>
                mutation(
                  `/projects/${p}/github/connections/${el.dataset.reconcile}/reconcile`,
                  "POST",
                ),
              )),
        );
    }
  }
}
async function taskPage(p, t) {
  const d = await api(`/projects/${p}/tasks/${t}/detail`), task = d.task;
  const archived = contextProject.archived;
  const maintainer = d.members.some(m => m.id === me.userId && m.active && m.role === "MAINTAINER");
  const canEdit = !archived && d.canEditTask;
  const canManage = !archived && d.canManageTask;
  const canManageDependencies = !archived && d.canManageDependencies;
  const preparing = !archived && d.canPrepareTask;
  const canRequestReassignment = !archived && d.canRequestReassignment;
  const requests = d.reassignmentRequests || [];
  const pendingRequest = requests.find(r => r.status === "PENDING");
  const base = `/projects/${p}/tasks/${t}`, headers = { "If-Match": `"${task.revision}"` };
  const sourceParams = new URLSearchParams(location.hash.split("?")[1] || "");
  const focusedComment = sourceParams.get("comment");
  let focusUnavailable = false;
  if (focusedComment && /^[0-9a-f-]{36}$/.test(focusedComment)) {
    try {
      const page = await api(base + "/comments?" + new URLSearchParams({order:"DESC",focus:focusedComment,limit:51}));
      d.comments = page.slice(0, 50).reverse();d.commentsHasMore = page.length > 50;
    } catch (error) { if (error.status !== 404) throw error;focusUnavailable = true; }
  }
  const linkedDocs = d.links.documentDetails || [];
  const memberName = id => {
    if (!id) return "未分配";
    const member = d.members.find(m => m.id === id);
    return member ? member.displayName + (member.active ? "" : "（已离开）") : "历史成员";
  };
  const priorityNames = ["P0 · 最高", "P1 · 高", "P2 · 普通", "P3 · 低"];
  const dependencyState = task.dependencyRisk ? "已完成，但上游仍未完成" : task.isBlocked ? "等待上游任务" : "无阻塞";
  function commentHtml(c) {
    return `<article class="task-comment" id="comment-${c.id}" tabindex="-1"><div class="comment-meta"><strong>${esc(memberName(c.authorId))}</strong><time>${esc(timeLabel(c.createdAt))}</time></div><p class="comment-body">${esc(c.body)}</p>${!archived && c.authorId === me.userId ? '<details><summary>我的评论操作</summary>' + form("edit-comment-" + c.id, "编辑评论", '<label>评论内容<textarea name="body" required>' + esc(c.body) + '</textarea></label>') + (!maintainer ? `<button class="secondary" data-delete="${c.id}">删除我的评论</button>` : '') + '</details>' : ""}${!archived && maintainer ? `<div data-moderation hidden><button class="danger" data-delete="${c.id}">删除这条评论</button></div>` : ""}</article>`;
  }
  const latestParams = new URLSearchParams(sourceParams);for (const key of ["comment", "start", "end"]) latestParams.delete(key);
  app.innerHTML = crumbs("任务详情") + personalReturn() +
    `<div class="task-heading"><div><h1>${esc(task.title)}</h1><p class="task-badges"><span class="badge">${esc(stateName(task.status))}</span><span class="badge">${esc(dependencyState)}</span><span>${task.origin === "GITHUB" ? "来自 GitHub" : "本地任务"}</span></p></div><div class="task-heading-actions">${link(`project/${p}/agent?${new URLSearchParams({question:`任务「${task.title}」（ID：${task.id}）当前进展如何？有哪些上游阻塞和讨论依据？`})}`, "询问助手")}${canManage && task.status === "TRIAGE" ? '<button id="review-task" aria-expanded="false" aria-controls="triage-review">分诊与接纳</button>' : ""}${canEdit ? '<button id="edit-task" class="secondary" aria-expanded="false" aria-controls="task-editor">编辑任务</button>' : ""}</div></div>` +
    '<nav class="detail-jumps" aria-label="任务内容"><button data-scroll="task">任务信息</button><button data-scroll="upstream">依赖</button><button data-scroll="links">文档与链接</button><button data-scroll="comments">评论</button>' + (d.sync ? '<button data-scroll="github-status">GitHub 确认状态</button>' : '') + '</nav>' +
    `<section id="task"><div id="task-summary"><h2>任务信息</h2><dl class="task-properties"><div><dt>负责人</dt><dd>${esc(memberName(task.assigneeId))}</dd></div><div><dt>优先级</dt><dd>${esc(task.triageRequired ? "待分诊确认" : priorityNames[task.priority])}</dd></div><div><dt>截止日期</dt><dd>${esc(task.dueDate || "未设置")}</dd></div><div><dt>类型</dt><dd>${esc(label(task.type))}</dd></div><div><dt>创建者</dt><dd>${esc(memberName(d.createdBy))}</dd></div><div><dt>最近更新</dt><dd>${esc(timeLabel(task.updatedAt))}</dd></div></dl><h3>任务描述</h3><div class="task-description" data-source-prefix="${task.title.length+1}">${esc(task.description || "暂无任务描述。")}</div></div><div id="task-editor" hidden></div></section>` +
    (task.status === "TRIAGE" && !archived ? (canManage ? '<details id="triage-review"><summary>分诊并接纳任务</summary><p>确认任务范围，指定负责人和正式优先级后接纳为待办。</p>' +
      form("triage", "接纳安排", select("assigneeId", "任务负责人", [["", "请选择负责人"], ...d.members.filter(m => m.active).map(m => [m.id, m.displayName])], task.assigneeId || "") +
        select("priority", "正式优先级", priorityNames.map((name, i) => [i, name]), task.priority) + input("dueDate", "正式截止日期", "date", task.dueDate || "", false), "接纳为待办") +
      (task.origin === "GITHUB" ? '<p>正式取消 GitHub 任务请使用下方远端修改入口。</p>' : '<button id="reject-task" class="danger">拒绝申请</button>') + '</details>' : preparing ? '<button id="withdraw-task" class="secondary">撤回申请</button>' : "") : "") +
    '<section id="comments"><div class="section-heading"><h2>评论与讨论</h2>' + (!archived && maintainer ? '<button id="manage-comments" class="secondary" aria-expanded="false" aria-controls="comment-moderation">管理评论</button>' : '') + '</div><p id="comment-moderation" class="scope-note" hidden>评论管理已开启。删除后正文不再显示，操作会留下活动记录。</p>' +
    (focusedComment ? `<p class="scope-note">${focusUnavailable ? "这条评论已删除或不可访问，当前显示最新评论。" : "正在查看选中评论及之前的讨论。"}${link(`task/${p}/${t}?${latestParams}`, "返回最新评论")}</p>` : '') +
    `<div class="pagination"><button id="comments-older" class="secondary" ${d.commentsHasMore ? '' : 'hidden'}>加载较早评论</button><span id="comments-count" class="muted" role="status"></span></div>` +
    '<div id="comment-items">' + (d.comments.length ? d.comments.map(commentHtml).join("") : '<p class="muted">暂无评论</p>') + '</div>' +
    (!archived ? form("comment", "发表评论", '<label>评论内容<textarea name="body" required placeholder="讨论进展、提出问题。输入 @ 选择项目成员"></textarea></label>', "发布评论") : '<p class="muted">项目已归档，评论保留供查阅。</p>') +
    (d.subscribed ? '<p class="subscription-state">已订阅评论更新 <button id="unsubscribe" class="secondary">退订</button></p>' : '<p class="subscription-state">未订阅评论更新 <button id="subscribe" class="secondary">订阅</button></p>') + '</section>' +
    '<section id="upstream"><h2>上游依赖</h2>' +
    taskRows(d.blockers, d.members, "无上游依赖") +
    (canManageDependencies && d.blockers.length ? '<div class="dependency-actions">' + d.blockers.map(b => `<button class="secondary" data-remove-dependency="${b.id}">移除上游：${esc(b.title)}</button>`).join("") + '</div>' : "") +
    (canManageDependencies ? '<details id="dependency-editor"><summary>添加上游任务</summary>' +
      form("dependency", "添加上游任务", '<div class="suggestion-field"><label>按任务标题搜索<input name="q" type="search" autocomplete="off" aria-label="搜索上游任务" placeholder="输入标题，直接从建议中选择"></label><div id="dependency-options" class="suggestions" hidden></div></div><input type="hidden" name="blockerId"><p id="selected-dependency" class="muted" aria-live="polite">尚未选择上游任务。</p>', "添加上游依赖") + '</details>' : '') + '</section>' +
    '<section id="downstream"><h2>下游工作</h2>' +
    taskRows(d.downstream, d.members, "无下游任务") + '</section>' +
    '<section id="links"><h2>文档与链接</h2>' +
    table(canEdit ? ["文档", "操作"] : ["文档"], (d.links.documents || []).map(id => [link(`document/${p}/${id}?from=${encodeURIComponent(location.hash.slice(1))}`, linkedDocs.find(doc => doc.id === id)?.title || "项目文档"), ...(canEdit ? [`<button class="secondary" data-unlink-document="${id}">解除关联</button>`] : [])]), "尚未关联文档。") +
    table(canEdit ? ["网页", "操作"] : ["网页"], (d.links.resources || []).map(x => [/^https?:\/\//i.test(x.url) ? `<a href="${esc(x.url)}" target="_blank" rel="noopener noreferrer">${esc(x.url)}</a>` : esc(x.url), ...(canEdit ? [`<button class="secondary" data-unlink-resource="${x.id}">解除关联</button>`] : [])]), "尚未关联网页。") +
    (canEdit ? '<details id="link-editor"><summary>关联文档或网页</summary>' +
      form("url", "关联网页", input("url", "HTTP(S) 地址", "url"), "关联") +
      form("linkDocument", "关联已有文档", '<div class="suggestion-field"><label>搜索项目文档<input name="q" type="search" maxlength="200" autocomplete="off" placeholder="输入文档标题或关键词"></label><div id="document-options" class="suggestions" hidden></div></div><input type="hidden" name="id"><p id="selected-document" class="muted" role="status">尚未选择文档。</p>', "关联") + '</details>' : '') + '</section>' +
    '<details class="task-activity"><summary>活动记录</summary>' + table(["变化", "时间"], d.activities.map(a => [esc(label(a.kind)), esc(timeLabel(a.createdAt))])) + '</details>';

  if (maintainer) {
    const area = document.createElement("section"); area.id = "agent-triage";
    const disclosure=document.createElement('details');disclosure.id='task-analysis';
    disclosure.innerHTML='<summary>分诊分析与记录</summary>';disclosure.append(area);
    disclosure.open=new URLSearchParams(location.hash.split('?')[1]||'').has('run')||new URLSearchParams(location.hash.split('?')[1]||'').has('proposal');
    $('#task').after(disclosure);
    // Optional analysis must never delay binding task editing/comments or replace the route.
    void triageProposalPanel(area, p, task, d.members, canManage).catch(e => {
      if (area.isConnected) area.textContent = `分诊面板暂不可用：${readableError(e.message)}。刷新页面可重试。`;
    });
  }

  if (!maintainer && task.triageRequired) {
    const status = document.createElement("p"); status.id = "triage-status";
    $("#task-summary").append(status);
    const refresh = async () => {
      if (!status.isConnected) return;
      try {
        const value = await api(`/projects/${p}/agent/triage/${task.id}/status`);
        if (!status.isConnected) return;
        status.textContent = value.status ? `分诊分析：${triageRunLabel(value.status, value.errorCode)}。建议由维护者查看和确认。` : '暂无分析记录，维护者可发起分析。';
        if (['QUEUED','RUNNING'].includes(value.status)) setTimeout(refresh, 2000);
      } catch { if (status.isConnected) status.textContent = '分析状态暂不可用，刷新页面可重试。'; }
    };
    void refresh();
  }
  // Discussion follows the task body; optional dependencies and records follow it.

  function closeEditor() {
    $("#task-editor").replaceChildren();
    $("#task-editor").hidden = true;
    $("#task-summary").hidden = false;
    $("#edit-task").hidden = false;
    $("#edit-task").setAttribute("aria-expanded", "false");
    $("#edit-task").focus();
  }
  button("edit-task", () => {
    const fields = (task.origin !== "GITHUB" ? (canManage || preparing ? input("title", "标题", "text", task.title) : "") +
      '<label>描述<textarea name="description">' + esc(task.description) + '</textarea></label>' : '<p class="scope-note">描述及关闭/重开需通过下方 GitHub 修改入口处理；正式标题和取消由维护者管理。</p>') +
      (canManage ? select("status", "状态", states.filter(s => !task.triageRequired || ["TRIAGE", "CANCELED"].includes(s)).map(s => [s, stateName(s)]), task.status) +
        select("type", "类型", ["TASK", "BUG", "FEATURE", "QUESTION"].map(s => [s, label(s)]), task.type) :
        preparing ? '<p class="muted">当前为待分诊；接纳前不能推进执行状态。</p>' : select("status", "执行状态", ["TODO", "IN_PROGRESS", "DONE"].map(s => [s, stateName(s)]), task.status)) +
      (canManage || preparing ? input("dueDate", "截止日期", "date", task.dueDate || "", false) : "") +
      (canManage ? select("assigneeId", "负责人", [["", "未分配"], ...d.members.filter(m => m.active || m.id === task.assigneeId).map(m => [m.id, memberName(m.id)])], task.assigneeId || "") +
        select("priority", "优先级", priorityNames.map((name, i) => [i, name]), task.priority) : preparing ? '<p class="muted">负责人和正式优先级由项目维护者分诊确定。</p>' : '<p class="muted">正式安排的调整请联系维护者；需要交接工作时可申请更换负责人。</p>');
    $("#task-editor").innerHTML = form("task-edit", "编辑任务", fields, "保存修改");
    $("#task-edit").insertAdjacentHTML("beforeend", '<button type="button" id="cancel-task-edit" class="secondary">取消</button>');
    $("#task-summary").hidden = true;
    $("#task-editor").hidden = false;
    $("#edit-task").setAttribute("aria-expanded", "true");
    $("#edit-task").hidden = true;
    $("#task-edit input, #task-edit textarea, #task-edit select")?.focus();
    button("cancel-task-edit", closeEditor);
    bind("task-edit", async r => {
      const patch = { ...r };
      if ("dueDate" in r) patch.dueDate = r.dueDate || null;
      if (canManage) { patch.assigneeId = r.assigneeId || null; patch.priority = Number(r.priority); }
      if (task.origin === "GITHUB" && r.status !== task.status && (["DONE", "CANCELED"].includes(r.status) || ["DONE", "CANCELED"].includes(task.status)))
        throw new Error("GitHub 关闭/重开请使用下方远端修改入口");
      await mutation(base, "PATCH", patch, headers);
      $("#edit-task")?.focus();
    });
  });
  button("review-task", () => {
    const panel = $("#triage-review"); panel.open = !panel.open;
    $("#review-task").setAttribute("aria-expanded", String(panel.open));
    if (panel.open) { panel.scrollIntoView({ behavior: "smooth", block: "center" }); $("#triage [name=assigneeId]").focus({ preventScroll: true }); }
  });
  $("#triage-review")?.addEventListener("toggle", e => $("#review-task")?.setAttribute("aria-expanded", String(e.target.open)));
  button("manage-comments", () => {
    const managing = $("#manage-comments").getAttribute("aria-expanded") !== "true";
    $("#manage-comments").setAttribute("aria-expanded", String(managing));
    $("#manage-comments").textContent = managing ? "退出评论管理" : "管理评论";
    $("#comment-moderation").hidden = !managing;
    $("#comments").querySelectorAll("[data-moderation]").forEach(el => el.hidden = !managing);
  });
  async function showComment(id, text) {
    const q = new URLSearchParams(sourceParams);q.delete("start");q.delete("end");
    if (id) q.set("comment", id);else q.delete("comment");
    history.replaceState(null, "", `#task/${p}/${t}?${q}`);await route();message(text);
    if (id) document.getElementById("comment-" + id)?.scrollIntoView({block:"center"});
  }
  function bindComments(comments) {
    for (const c of comments) {
      bind("edit-comment-" + c.id, async r => {
        await api(base + "/comments/" + c.id, "PUT", r, {"If-Match": `"${c.revision}"`});
        await showComment(c.id, "评论已更新。");
      });
      const article = document.getElementById("comment-" + c.id);
      const textarea = article.querySelector("textarea");if (textarea) attachMentions(textarea, d.members, "mention-options-" + c.id);
      const remove = article.querySelector("[data-delete]");if (remove) remove.onclick = () => {
        if (remove.disabled || !confirm("删除这条评论？正文将不再显示。")) return;
        remove.disabled = true;
        run(async () => {await api(base + "/comments/" + c.id, "DELETE", undefined, {"If-Match": `"${c.revision}"`});await showComment(null, "评论已删除。");})
          .finally(() => {if (remove.isConnected) remove.disabled = false;});
      };
      if ($("#manage-comments")?.getAttribute("aria-expanded") === "true") article.querySelectorAll("[data-moderation]").forEach(el => el.hidden = false);
    }
    $("#comments-count").textContent = `已显示 ${d.comments.length} 条评论`;
  }
  bindComments(d.comments);
  if ($("#comment textarea")) attachMentions($("#comment textarea"), d.members, "mention-options-new");
  const commentItems = $("#comment-items"), olderComments = $("#comments-older");
  button("comments-older", async () => {
    const cursor = contentCursor(d.comments[0], "createdAt");
    const page = await api(base + "/comments?" + new URLSearchParams({order:"DESC",cursor,limit:51}));
    if (!commentItems.isConnected) return;
    const older = page.slice(0, 50).filter(c => !d.comments.some(old => old.id === c.id)).reverse();
    d.comments = [...older, ...d.comments];
    commentItems.insertAdjacentHTML("afterbegin", older.map(commentHtml).join(""));bindComments(older);
    olderComments.hidden = page.length <= 50;
  });
  if (canManage && task.status === "TRIAGE") $("#triage [name=assigneeId]").required = true;
  bind("triage", r => mutation(base + "/triage-accept", "POST", { assigneeId: r.assigneeId, priority: Number(r.priority), dueDate: r.dueDate || null }, headers));
  button("withdraw-task", () => { if (confirm("撤回后申请保留供查看，需要维护者重新开启才能继续完善。确定撤回？")) return mutation(base, "PATCH", { status: "CANCELED" }, headers); });
  button("reject-task", () => { if (confirm("拒绝后申请保留供查看，不会进入执行流程。确定拒绝？")) return mutation(base, "PATCH", { status: "CANCELED" }, headers); });
  bindCreation("comment", base + "/comments", r => r, c => showComment(c.id, "评论已发布。"));
  button("subscribe", () => mutation(base + "/subscription", "PUT", { active: true }));
  button("unsubscribe", () => mutation(base + "/subscription", "PUT", { active: false }));
  document.querySelectorAll("[data-scroll]").forEach(el => el.onclick = () => $("#" + el.dataset.scroll)?.scrollIntoView({ behavior: "smooth", block: "start" }));

  if (canManageDependencies) document.querySelectorAll("[data-remove-dependency]").forEach(el => el.onclick = () => {
    if (el.disabled) return;
    notice.textContent = "";
    el.disabled = true;
    run(async () => {
      const pr = await api("/projects/" + p);
      await mutation(`/projects/${p}/dependencies`, "DELETE", { blockerId: el.dataset.removeDependency, blockedId: t, expectedBusinessRevision: pr.businessRevision });
    }).finally(() => { if (el.isConnected) el.disabled = false; });
  });
  if (canRequestReassignment || requests.length) {
    const statusNames = { PENDING: "等待维护者处理", APPROVED: "已批准", REJECTED: "已拒绝", SUPERSEDED: "任务安排已变化，申请失效" };
    $("#comments").insertAdjacentHTML("beforebegin", '<section id="reassignment"><h2>负责人交接</h2>' +
      (requests.length ? requests.map(r => `<article class="task-comment" id="reassignment-${r.id}" tabindex="-1"><p><strong>${esc(statusNames[r.status])}</strong> · ${esc(memberName(r.requestedBy))} · ${esc(timeLabel(r.createdAt))}</p><p class="comment-body">${esc(r.reason)}</p>${r.decisionNote ? '<p>处理说明：' + esc(r.decisionNote) + '</p>' : ''}${r.reviewedBy ? '<p class="muted">处理人：' + esc(memberName(r.reviewedBy)) + '</p>' : ''}</article>`).join("") : "") +
      (canRequestReassignment && !pendingRequest ? '<details id="reassignment-editor"><summary>申请更换负责人</summary><p>维护者批准前，你仍是当前负责人，可继续更新进展。</p>' +
        form("request-reassignment", "说明交接原因", '<label>申请原因<textarea name="reason" required maxlength="2000"></textarea></label>', "提交申请") + '</details>' : '') +
      (pendingRequest && canManage ? '<details id="reassignment-review"><summary>处理更换负责人申请</summary>' + form("resolve-reassignment", "处理申请",
        select("decision", "处理决定", [["APPROVE", "批准并改派"], ["REJECT", "拒绝，保留当前负责人"]]) +
        select("assigneeId", "新的负责人", [["", "请选择另一位负责人"], ...d.members.filter(m => m.active && m.id !== task.assigneeId).map(m => [m.id, m.displayName])]) +
        '<label>处理说明<textarea name="note" maxlength="2000"></textarea></label>', "确认处理") + '</details>' : '') + '</section>');
    bind("request-reassignment", r => mutation(base + "/reassignment-requests", "POST", { reason: r.reason, expectedTaskRevision: task.revision }));
    if (pendingRequest && canManage) {
      const decision = $("#resolve-reassignment [name=decision]"), assignee = $("#resolve-reassignment [name=assigneeId]");
      const setRequired = () => { assignee.required = decision.value === "APPROVE"; assignee.disabled = decision.value !== "APPROVE"; };
      decision.onchange = setRequired; setRequired();
      bind("resolve-reassignment", r => mutation(base + `/reassignment-requests/${pendingRequest.id}/resolve`, "POST", { ...r, assigneeId: r.assigneeId || null, expectedTaskRevision: task.revision }, { "If-Match": `"${pendingRequest.revision}"` }));
    }
  }

  if (canManageDependencies) {
    const editor = $("#dependency-editor"), area = $("#dependency-options"), search = $("#dependency [name=q]"), add = $("#dependency > button");
    const selected = $("#dependency [name=blockerId]"), summary = $("#selected-dependency");
    let sequence = 0, timer;
    add.disabled = true;
    const picker = suggestionPicker(search, area, candidate => {
      clearTimeout(timer); sequence++;
      selected.value = candidate.id; search.value = candidate.text;
      summary.textContent = "已选择：" + candidate.text + " · " + candidate.detail;
      add.disabled = false; search.focus(); picker.close();
    });
    async function searchDependencies() {
      const current = ++sequence, query = search.value.trim();
      picker.show([], "正在查找…");
      try {
        const page = await api(`/projects/${p}/task-search?limit=100&q=${encodeURIComponent(query)}`);
        if (!area.isConnected || current !== sequence || !editor.open || document.activeElement !== search) return;
        const candidates = page.items.filter(x => x.id !== t && !d.blockers.some(b => b.id === x.id));
        picker.show(candidates.map(x => ({ id: x.id, text: x.title, detail: stateName(x.status) })),
          query ? "没有匹配的可添加任务，请更换标题。" : "没有可添加的任务。请先在本项目创建其他任务；自身和已添加的上游不显示。",
          page.hasMore ? "仅显示前100项，请输入更具体的标题。" : "↑↓选择，Enter 确认，Esc 收起；选择后点击添加。");
      } catch (error) {
        if (!area.isConnected || current !== sequence) return;
        picker.show([], "查找失败，请重新输入或再次聚焦重试。"); message(error.message, true);
      }
    }
    editor.addEventListener("toggle", () => {
      if (editor.open) search.focus();
      else { clearTimeout(timer); sequence++; picker.close(); }
    });
    search.addEventListener("focus", () => { if (!selected.value) searchDependencies(); });
    search.addEventListener("input", () => {
      clearTimeout(timer); sequence++; selected.value = ""; add.disabled = true;
      summary.textContent = "尚未选择上游任务。"; picker.show([], "正在查找…");
      timer = setTimeout(searchDependencies, 250);
    });
    search.addEventListener("keydown", e => {
      if (e.key === "Enter" && !e.isComposing) { e.preventDefault(); clearTimeout(timer); searchDependencies(); }
      if (e.key === "Escape") { clearTimeout(timer); sequence++; }
    });
    bind("dependency", async r => {
      if (!r.blockerId) throw new Error("请从建议列表中选择上游任务");
      const pr = await api("/projects/" + p);
      await mutation(`/projects/${p}/dependencies`, "POST", { blockerId: r.blockerId, blockedId: t, expectedBusinessRevision: pr.businessRevision });
    });
  }
  bind("url", r => mutation(base + "/url-links", "PUT", r, headers));
  bind("linkDocument", r => {
    if (!r.id) throw new Error("请从搜索建议中选择文档");
    return mutation(base + "/documents/" + r.id, "PUT", undefined, headers);
  });
  for (const [attribute, endpoint] of [["document", "documents"], ["resource", "url-links"]]) {
    document.querySelectorAll(`[data-unlink-${attribute}]`).forEach(el => el.onclick = () => {
      if (el.disabled || !confirm("解除此项关联？原资料仍然保留。")) return;
      el.disabled = true;
      run(() => mutation(base + "/" + endpoint + "/" + el.getAttribute("data-unlink-" + attribute), "DELETE", undefined, headers)).finally(() => {if (el.isConnected) el.disabled = false;});
    });
  }
  if (canEdit) {
    const field = $("#linkDocument [name=q]"), area = $("#document-options"), selected = $("#linkDocument [name=id]"), submit = $("#linkDocument > button");
    let sequence = 0, timer;submit.disabled = true;
    const picker = suggestionPicker(field, area, doc => {
      clearTimeout(timer);sequence++;selected.value = doc.id;field.value = doc.text;
      $("#selected-document").textContent = "已选择：" + doc.text;submit.disabled = false;field.focus();picker.close();
    });
    async function searchDocuments() {
      const version = ++sequence;picker.show([], "正在查找…");
      try {
        const docs = await api(`/projects/${p}/documents?` + new URLSearchParams({q:field.value.trim(),limit:21}));
        if (!field.isConnected || version !== sequence || document.activeElement !== field) return;
        picker.show(docs.slice(0,20).filter(doc => !d.links.documents.includes(doc.id)).map(doc => ({id:doc.id,text:doc.title,detail:"项目文档"})), "没有可关联的匹配文档，请更换关键词或先创建文档。", docs.length > 20 ? "结果较多，请输入更具体的标题。" : "↑↓选择，Enter 确认，Esc 收起。");
      } catch (error) {if (field.isConnected && version === sequence) {picker.show([], "查找失败，请重新输入或再次聚焦重试。");message(error.message, true);}}
    }
    field.addEventListener("focus", () => {if (!selected.value) void searchDocuments();});
    field.addEventListener("input", () => {clearTimeout(timer);sequence++;selected.value="";submit.disabled=true;$("#selected-document").textContent="尚未选择文档。";timer=setTimeout(searchDocuments,250);});
    field.addEventListener("keydown", e => {if (e.key === "Enter" && !e.isComposing) {e.preventDefault();clearTimeout(timer);void searchDocuments();}if(e.key === "Escape"){clearTimeout(timer);sequence++;}});
  }
  if (d.sync) {
    $("#comments").insertAdjacentHTML("beforebegin", `<section id="github-status"><h2>GitHub 确认状态</h2><p>${esc(label(d.sync.status))} · ${esc(d.sync.intent ? label(d.sync.intent.state) : "无待处理意图")} · ${esc(reason(d.sync.error || d.sync.intent?.error))}</p><p>最近确认：${esc(timeLabel(d.sync.confirmedAt))}。受理后等待远端确认；结果未知时仅观察。</p><button id="refreshSync" class="secondary">刷新确认状态</button>` +
      (canEdit ? '<details id="github-editor"><summary>请求修改 GitHub 任务</summary>' + form("intent", "请求修改 GitHub（确认后生效）", (canManage ? input("title", "标题", "text", task.title) : "") + '<label>描述<textarea name="body">' + esc(task.description) + '</textarea></label>' + (canManage || !preparing ? select("state", "远端状态", [["open", "打开"], ["completed", "关闭：完成"], ...(canManage ? [["not_planned", "关闭：取消"]] : [])], task.status === "DONE" ? "completed" : task.status === "CANCELED" ? "not_planned" : "open") : '<p class="muted">待分诊阶段仅完善描述，执行状态需在接纳后更新。</p>'), "提交 GitHub 修改请求") + '</details>' : '') +
      '<h3>外部评论（GitHub 作者）</h3>' + table(["作者", "正文"], (d.externalComments || []).map(c => [esc(c.remoteLogin), esc(c.body)])) + '</section>');
    if (canEdit && d.sync.intent && (["QUEUED", "PRECHECK", "RETRY_WAIT", "SEND_STARTED", "VERIFYING", "UNKNOWN"].includes(d.sync.intent.state) || (d.sync.intent.state === "ERROR" && d.sync.intent.sent))) $("#intent button").disabled = true;
    button("refreshSync", route);
    bind("intent", r => mutation(base + "/github/intents", "POST", { expectedTaskRevision: task.revision, expectedSyncRevision: d.sync.syncRevision, desired: { ...(canManage ? { title: r.title } : {}), body: r.body, ...(r.state ? { state: r.state === "open" ? "open" : "closed", ...(r.state === "open" ? {} : { state_reason: r.state }) } : {}) } }));
  }
}
async function documentPage(p, id) {
  const d = await api(`/projects/${p}/documents/${id}`);
  app.innerHTML = crumbs("文档") + personalReturn() + `<div class="task-heading"><h1>${esc(d.title)}</h1>${!contextProject.archived?'<button id="edit-document" class="secondary">编辑文档</button>':""}</div><p class="document-meta">版本 ${d.revision}</p>` + link(`project/${p}/documents`, "← 项目文档") + `<section id="document-reading"><div id="document-body" class="document-body" data-source-prefix="${d.title.length+1}">${esc(d.body)}</div></section><div id="document-editor" hidden></div>`;
  button("edit-document", async()=>{
    $("#document-reading").hidden=true;$("#document-editor").hidden=false;$("#edit-document").hidden=true;
    $("#document-editor").innerHTML=form("doc","编辑文档",input("title","标题","text",d.title)+'<label>正文<textarea name="body" required>'+esc(d.body)+'</textarea></label>')+'<button id="cancel-document" class="secondary">取消编辑</button>';
    bind("doc",r=>mutation(`/projects/${p}/documents/${id}`,"PUT",r,{"If-Match":`"${d.revision}"`}));
    button("cancel-document",route);
    const members=await api(`/projects/${p}/members`);
    if(members.some(m=>m.id===me.userId&&m.active&&m.role==="MAINTAINER")){
      $("#document-editor").insertAdjacentHTML("beforeend",'<button id="delete-document" class="danger">删除文档</button>');
      button("delete-document",async()=>{if(!confirm("删除后文档退出列表和知识检索，历史关联与审计保留。"))return;await api(`/projects/${p}/documents/${id}`,"DELETE",undefined,{"If-Match":`"${d.revision}"`});navigate(`project/${p}/documents`,"文档已删除。");});
    }
  });
}

let importTimer;
async function importPage(p, id) {
  clearTimeout(importTimer);
  const result = await api(`/projects/${p}/imports/${id}`), counts = result.counts;
  const preview = result.state === "PREVIEW", running = result.state === "RUNNING";
  const canManage = result.canManage && !contextProject.archived && !result.expired;
  app.innerHTML = crumbs("CSV 导入结果") + link(`project/${p}/imports`, "← 导入列表") +
    `<h1>${esc(result.filename)}</h1><p class="scope-note"><strong>${esc(label(result.state))}</strong><br>${preview ? "预检查完成，尚未创建任何任务。确认后仅处理通过检查的行。" : running ? "后台正在逐行提交。可离开此页，稍后从 CSV 导入查看结果。每2秒自动更新。" : "下方数量来自实际行结果，成功行可直接打开任务。"}</p>` +
    `<div class="metrics"><span>${preview ? "可导入" : "已成功"}<strong>${preview ? counts.PENDING : counts.SUCCEEDED}</strong></span><span>${preview ? "预检查错误" : "失败"}<strong>${counts.FAILED}</strong></span><span>${preview ? "已创建任务" : "未处理"}<strong>${preview ? counts.SUCCEEDED : counts.PENDING}</strong></span></div>` +
    (result.stop_reason ? `<p class="error">${esc(reason(result.stop_reason))}</p>` : "") +
    `<p>${preview ? "含错误的行会保留原因，不会阻止其他行导入。" : "成功行不会在恢复中重建。文件内容已冻结；格式错误需修改文件，仅上传更正后的行，避免重复导入成功工作。"}</p>` +
    `<button id="refresh" class="secondary">刷新结果</button>${preview && canManage ? '<button id="confirm">确认后台导入</button>' : ""}${["STOPPED","PARTIAL_FAILED","FAILED"].includes(result.state) && canManage ? '<button id="resume">重查并恢复失败 / 未处理行</button>' : ""}` +
    (result.expired ? '<p class="empty">文件和报告已过30天保留期；任务及摘要保留，不能下载或恢复。</p>' : `<a href="/projects/${p}/imports/${id}/source">下载源文件</a> · <a href="/projects/${p}/imports/${id}/report">下载结果报告</a><p><small>文件与报告保留30天。只有原操作者或当前项目维护者可确认、恢复；下载也会检查项目权限。</small></p>`) +
    table(["数据行", "任务标题", "结果", "原因 / 下一步"], result.rows.map(r => [r.rowNumber,esc(r.title || (result.expired ? "（源数据已过期）" : "（标题为空）")),esc(preview && r.state === "PENDING" ? "通过预检查" : preview && r.state === "FAILED" ? "预检查错误" : label(r.state)),r.taskId ? link(`task/${p}/${r.taskId}`, "打开已创建任务") : esc(reason(r.error))]));
  button("refresh", route);
  button("confirm", () => mutation(`/projects/${p}/imports/${id}/confirm`, "POST"));
  button("resume", () => mutation(`/projects/${p}/imports/${id}/resume`, "POST"));
  if (running) {
    const hash = location.hash;
    importTimer = setTimeout(() => { if (location.hash === hash && !document.hidden) run(route); }, 2000);
  }
}
window.addEventListener("hashchange", () => run(route));
document.addEventListener('click',e=>{
  const a=e.target.closest('a[href^="#"]');if(!a||e.ctrlKey||e.metaKey||e.shiftKey||e.altKey||e.button!==0)return;
  const hash=a.getAttribute('href').slice(1),[path,query='']=hash.split('?'),q=new URLSearchParams(query);
  const legacy=path.match(/^project\/([a-f0-9-]{36})\/agent$/);
  if(!projectAssistant||q.has('run'))return;
  if(a.closest('#assistant-body')&&!legacy&&q.get('assistant')!=='open'&&projectRouteProject(hash)===projectAssistant.projectId&&matchMedia('(max-width: 1100px)').matches)setAssistantOpen(false);
  if((legacy&&legacy[1]===projectAssistant.projectId)||(path===location.hash.slice(1).split('?')[0]&&q.get('assistant')==='open')){
    e.preventDefault();const destination=legacy?assistantRoute(legacy[1],q.get('conversation'),Object.fromEntries([...q].filter(([k])=>k!=='conversation'))):hash;
    history.replaceState(null,'','#'+destination);
    if(routing){routeAgain=true;return;}
    routing=true;run(async()=>{try{await syncProjectAssistant(new URLSearchParams(destination.split('?')[1]||''));}
      finally{routing=false;if(routeAgain){routeAgain=false;await route();}}});
  }
});
document.addEventListener("click", e => {
  const el = e.target.closest("#leave,[data-remove],[data-revoke],[data-delete]");
  if (!el) return;
  const text = el.id === "leave" ? "退出团队后将失去本团队所有项目访问权；历史内容保留。确定退出？" : el.hasAttribute("data-remove") ? "移除后，该成员立即失去本团队及项目访问权；历史内容保留。确定移除？" : el.hasAttribute("data-revoke") ? "撤回后，尚未接受的邀请链接将失效。确定撤回？" : "删除后正文不再展示，操作记录会保留。确定删除这条评论？";
  if (!confirm(text)) { e.preventDefault(); e.stopImmediatePropagation(); }
}, true);
run(route);

function stateName(value) {
  return (
    {
      TRIAGE: "待分诊",
      TODO: "待办",
      IN_PROGRESS: "进行中",
      DONE: "已完成",
      CANCELED: "已取消",
    }[value] || value
  );
}

function focusSource() {
  const q = new URLSearchParams(location.hash.split("?")[1] || "");
  if (q.get("section") === "comments" && $("#comments")) {
    $("#comments").setAttribute("tabindex", "-1");
    $("#comments").focus({preventScroll:true});
    $("#comments").scrollIntoView({block:"start"});
    return;
  }
  if(!q.get("from")?.startsWith(`project/${contextProject?.id}/agent`) && !/^[0-9a-f-]{36}$/.test(q.get("comment") || "")) return;
  const comment=q.get("comment");
  const target=q.get("section")==="github-status" ? $("#github-status") : comment && /^[0-9a-f-]{36}$/.test(comment) ? document.getElementById("comment-"+comment) : $("#document-body") || $(".task-description");
  if(!target){message("引用内容未出现在当前页，请核对最新资料或返回助手重新提问。",true);return;}
  const body=comment ? target.querySelector(".comment-body") : target;
  const prefix=Number(body.dataset.sourcePrefix||0),start=Math.max(0,Number(q.get("start"))-prefix),end=Number(q.get("end"))-prefix,text=body.textContent;
  if(q.has("start") && Number.isInteger(start)&&Number.isInteger(end)&&start>=0&&end>start&&end<=text.length) body.innerHTML=esc(text.slice(0,start))+"<mark>"+esc(text.slice(start,end))+"</mark>"+esc(text.slice(end));
  target.classList.add("source-highlight");target.setAttribute("tabindex","-1");target.focus({preventScroll:true});target.scrollIntoView({block:"center"});
}

function personalReturn() {
  const from = new URLSearchParams(location.hash.split("?")[1] || "").get(
    "from",
  );
  if (from && new RegExp(`^project/${contextProject?.id}/overview(?:\\?|$)`).test(from)) return `<p>${link(from,"← 返回项目概览")}</p>`;
  if (from && new RegExp(`^project/${contextProject?.id}/agent(?:\\?|$)`).test(from)) return `<p class="source-return">${link(from,"← 返回助手回答")}</p>`;
  if (from && new RegExp(`^task/${contextProject?.id}/[a-f0-9-]{36}(?:\\?|$)`).test(from)) return `<p>${link(from,"← 返回关联任务")}</p>`;
  if (from && new RegExp(`^project/${contextProject?.id}/documents(?:\\?|$)`).test(from)) return `<p>${link(from,"← 返回文档列表")}</p>`;
  return from && /^(mine|inbox|project\/[a-f0-9-]+(?:\/tasks)?)(\?|$)/.test(from)
    ? `<p>${link(from, from.startsWith("inbox") ? "← 返回通知" : from.startsWith("project/") ? "← 返回项目任务" : "← 返回我的任务")}</p>`
    : "";
}
function sourceLinks(p) {
  return `${link("workspace/" + p.workspaceId, p.workspaceName)} / ${link("project/" + (p.projectId || p.id), p.projectName || p.name)}${p.archived ? ' <span class="badge">已归档</span>' : ""}`;
}
async function personalPage(page, params) {
  const inbox = page === "inbox",
    options = await api("/me/projects");
  let workspaceId = params.get("workspaceId") || "",
    projectId = params.get("projectId") || "";
  const selected = options.find((p) => p.id === projectId);
  if (!workspaceId && selected) workspaceId = selected.workspaceId;
  const names = me.workspaces.find((w) => w.id === workspaceId)?.name;
  const query = new URLSearchParams(params);
  if (workspaceId) query.set("workspaceId", workspaceId);
  query.set("limit", "25");
  const result = await api(
    (inbox ? "/me/notifications" : "/me/tasks") + "?" + query,
  );
  const scope = projectId
    ? selected
      ? selected.workspaceName + " / " + selected.name
      : "指定项目（当前无访问权限）"
    : workspaceId
      ? names || "指定团队（当前无访问权限）"
      : "全部团队 / 全部项目";
  app.innerHTML =
    `<h1>${inbox ? "通知" : "我的任务"}</h1>` +
    form(
      "personal-filter",
      "筛选",
      select(
        "workspaceId",
        "团队",
        [["", "全部团队"], ...me.workspaces.map((w) => [w.id, w.name])],
        workspaceId,
      ) +
        select(
          "projectId",
          "项目",
          [
            ["", "全部项目"],
            ...options
              .filter((p) => !workspaceId || p.workspaceId === workspaceId)
              .map((p) => [
                p.id,
                p.workspaceName +
                  " / " +
                  p.name +
                  (p.archived ? "（已归档）" : ""),
              ]),
          ],
          projectId,
        ) +
        (inbox
          ? select(
              "unreadOnly",
              "通知状态",
              [
                ["false", "全部通知"],
                ["true", "仅未读"],
              ],
              params.get("unreadOnly") || "false",
            )
          : select(
              "status",
              "任务状态",
              [["", "全部状态"], ...states.map((x) => [x, stateName(x)])],
              params.get("status") || "",
            ) + input("q", "任务标题", "text", params.get("q") || "", false)),
      "应用筛选",
    ) +
    "<p>" +
    link(page, "清除筛选") +
    '</p><div id="personal-results"></div><nav class="pagination" id="personal-pagination"></nav>';
  const field = $("#personal-filter [name=workspaceId]");
  field.onchange = () => {
    $("#personal-filter [name=projectId]").innerHTML =
      '<option value="">全部项目</option>' +
      options
        .filter((p) => !field.value || p.workspaceId === field.value)
        .map(
          (p) =>
            `<option value="${p.id}">${esc(p.workspaceName + " / " + p.name)}</option>`,
        )
        .join("");
  };
  bind("personal-filter", (values) => {
    const q = new URLSearchParams(
      Object.fromEntries(Object.entries(values).filter(([, v]) => v !== "")),
    );
    location.hash = page + "?" + q;
  });
  const from = encodeURIComponent(page + "?" + params);
  if (inbox) {
    $("#personal-results").innerHTML = result.items.length
      ? table(
          ["通知 / 相关任务", "所属团队 / 项目", "时间", "状态与操作"],
          result.items.map((n) => [
            `${link("task/" + n.projectId + "/" + n.taskId + "?from=" + from, n.taskTitle)}<br><small>${esc({ TASK_UNBLOCKED: "上游依赖已解除", COMMENT_CREATED: "新评论", SYNC_ERROR: "GitHub 同步需要处理", TASK_CREATED: "任务分配给你", TASK_SUBMITTED: "新任务等待分诊", TASK_ACCEPTED: "任务已接纳", TASK_REJECTED: "任务申请被拒绝", TASK_WITHDRAWN: "申请已撤回", TASK_RETURNED_TO_TRIAGE: "任务退回分诊", REASSIGNMENT_REQUESTED: "负责人交接申请待处理", REASSIGNMENT_APPROVED: "负责人交接申请已批准", REASSIGNMENT_REJECTED: "负责人交接申请被拒绝", TASK_UPDATED: "任务已更新" }[n.kind] || "相关任务有更新")}</small>`,
            sourceLinks(n),
            esc(new Date(n.createdAt).toLocaleString()),
            `${n.readAt ? "已读" : "<strong>未读</strong>"} ${!n.readAt ? `<button data-inbox-read="${n.id}">标记已读</button>` : ""}`,
          ]),
        )
      : '<p class="empty">' +
        (params.get("unreadOnly") === "true"
          ? "当前范围没有未读通知。"
          : "当前范围没有通知。任务分配、评论或依赖解除后，相关通知会显示在这里。") +
        "</p>";
    document
      .querySelectorAll("[data-inbox-read]")
      .forEach(
        (el) =>
          (el.onclick = () =>
            run(() =>
              mutation(
                "/me/notifications/" + el.dataset.inboxRead + "/read",
                "POST",
              ),
            )),
      );
    const paging = $("#personal-pagination");
    paging.innerHTML = params.has("before")
      ? link(
          page +
            "?" +
            new URLSearchParams([...params].filter(([k]) => k !== "before")),
          "最新通知",
        )
      : "";
    if (result.hasMore) {
      const next = new URLSearchParams(params);
      next.set("before", result.nextBefore);
      paging.insertAdjacentHTML(
        "beforeend",
        link(page + "?" + next, "更早通知"),
      );
    }
  } else {
    $("#personal-results").innerHTML = result.items.length
      ? table(
          ["任务", "所属团队 / 项目", "状态", "截止日期", "依赖"],
          result.items.map((t) => [
            link("task/" + t.projectId + "/" + t.id + "?from=" + from, t.title),
            sourceLinks(result.projects[t.projectId]),
            esc(stateName(t.status)),
            esc(t.dueDate || "—"),
            t.dependencyRisk ? "完成但依赖有风险" : t.isBlocked ? "被阻塞" : ["DONE","CANCELED"].includes(t.status) ? "—" : "可推进",
          ]),
        )
      : '<p class="empty">暂无匹配任务</p>';
    const offset = Number(params.get("offset") || 0);
    const paging = $("#personal-pagination");
    for (const [enabled, next, label] of [
      [offset > 0, Math.max(0, offset - 25), "上一页"],
      [result.hasMore, offset + 25, "下一页"],
    ])
      if (enabled) {
        const q = new URLSearchParams(params);
        q.set("offset", next);
        paging.insertAdjacentHTML("beforeend", link(page + "?" + q, label));
      }
  }
}

function triageRunLabel(status,code) {
  const errors={AUTOMATIC_TRIAGE_DISABLED:'自动分诊已关闭，本次分析已停止，可手动重新发起',AGENT_QUEUE_TIMEOUT:'排队超过十分钟，未开始分析，请重新发起',PROJECT_ARCHIVED:'项目已归档，分析已停止',TRIAGE_NO_LONGER_REQUIRED:'任务已不再待分诊，分析已停止',AGENT_DISABLED:'分析服务未启用',GLM_BUDGET_EXHAUSTED:'这条历史分析已结束，请重新发起',MODEL_BUDGET_EXHAUSTED:'这条历史分析已结束，请重新发起',MODEL_COST_BUDGET:'历史执行授权已结束，请使用团队模型重新发起',GLM_BUDGET_NOT_APPROVED:'请团队管理员配置文本模型后重新发起',MODEL_BUDGET_NOT_APPROVED:'请团队管理员配置文本模型后重新发起',AGENT_CONTEXT_CHANGED:'依据已变化，结果已过期',GLM_CONTRACT_CHANGED:'模型配置已变化，请重新分析'};
  return modelReason(code) || errors[code] || ({QUEUED:'已排队，等待分析',RUNNING:'正在分析任务与资料',SUCCEEDED:'分析已完成',STALE:'分析已过期',FAILED:'分析失败',INCOMPLETE:'分析未完成'}[status] || status) + (code ? `（${code}）` : '');
}
async function triageProposalPanel(area, p, task, members, writable) {
  const base = `/projects/${p}/agent`, routeHash = location.hash.split("?")[0];
  const owner = id => id ? (members.find(m => m.id === id)?.displayName || "历史成员") : "待选择";
  const savedKey = `triage-run:${me.userId}:${p}:${task.id}`;
  const intentKey = `${savedKey}:intent`;
  const requested=new URLSearchParams(location.hash.split('?')[1]||'');
  const restoreProposal=requested.get('proposal'),restoreRun=requested.get('run')||sessionStorage.getItem(savedKey);
  let current, selectedRun, pollVersion = 0;
  const alive = () => location.hash.split("?")[0] === routeHash && area.isConnected;
  area.innerHTML = '<h2>分诊建议</h2>' +
    (task.origin === 'GITHUB' ? '<p>这项任务来自 GitHub。批准接纳只安排 WorkHub 中的工作；GitHub 的标题、描述和关闭状态仍通过远端修改入口处理。</p>' : '') +
    (writable && task.status === "TRIAGE" ? select('triage-strategy','调查方式', [['WORKFLOW','固定资料分析'],['GUIDED','固定资料并补充查证'],['AGENT','自主调查']], 'WORKFLOW') + '<label>补充要求（可选）<textarea id="triage-question" maxlength="1200" placeholder="例如：重点核对与历史故障的差异"></textarea></label><button id="triage-analyze">分析这项任务</button><p id="triage-intent" role="status"></p>' : '') +
    '<div id="triage-runs"></div><div id="triage-history"></div><div id="triage-proposal" aria-live="polite"></div>';
  const output = area.querySelector('#triage-proposal');
  function syncIntent() {
    const pending = sessionStorage.getItem(intentKey);
    const button = area.querySelector('#triage-analyze');
    if (!button) return;
    button.textContent = pending ? '重试确认上次分析请求' : '分析这项任务';
    area.querySelector('[name="triage-strategy"]').disabled = !!pending;
    area.querySelector('#triage-question').disabled = !!pending;
    area.querySelector('#triage-intent').textContent = pending ? '上次请求尚未确认。重试沿用原要求，避免重复创建分析。' : '';
  }
  async function history(kind = 'proposals', cursor = null, append = false) {
    const box = area.querySelector(kind === 'runs' ? '#triage-runs' : '#triage-history');
    const heading = kind === 'runs' ? '分析记录' : '已存提案';
    try {
      const endpoint = kind === 'runs' ? 'runs' : 'proposals/history';
      const page = await api(`${base}/triage/${task.id}/${endpoint}?limit=10${cursor ? '&cursor='+encodeURIComponent(cursor) : ''}`);
      if (!alive()) return;
      if (!append) box.innerHTML = `<h3>${heading}</h3>`;
      box.querySelector('[data-more]')?.remove();box.querySelector('[data-history-error]')?.remove();
      if (!page.items.length && !append) box.insertAdjacentHTML('beforeend','<p class="muted">暂无记录。</p>');
      for (const item of page.items) {
        const b = document.createElement('button'); b.className = 'secondary';
        if (kind === 'runs') {
          b.dataset.run = item.id;
          b.textContent = `${item.automatic ? '自动' : '手动'}分析 · ${timeLabel(item.createdAt)} · ${triageRunLabel(item.status,item.errorCode)}`;
          b.onclick = () => run(() => openRun(item.id));
        } else {
          b.dataset.proposal = item.id;b.textContent = `查看已存提案 · ${timeLabel(item.createdAt)}${item.executed ? ' · 已执行' : ''}`;
          b.onclick = () => run(async () => {const version=++pollVersion;selectedRun=null;stopRunProgress();current=null;output.textContent="正在读取提案…";const value=await api(`${base}/proposals/${item.id}`);if(version===pollVersion)show(value);});
        }
        box.append(b);
      }
      if (page.nextCursor) {
        const more=document.createElement('button');more.dataset.more='';more.textContent='加载更早记录';
        more.onclick=async()=>{more.disabled=true;await history(kind,page.nextCursor,true);if(more.isConnected)more.disabled=false;};box.append(more);
      }
      if (kind === 'proposals' && !append && page.items.length && !current && !selectedRun && !restoreProposal && !restoreRun) {const version=pollVersion;const value=await api(`${base}/proposals/${page.items[0].id}`);if(version===pollVersion && !current && !selectedRun)show(value);}
      return page.items;
    } catch(e) {
      if (!alive()) return;
      box.querySelector('[data-history-error]')?.remove();
      const error=document.createElement('p');error.dataset.historyError='';error.textContent=`${heading}暂不可用：${readableError(e.message)} `;
      const retry=document.createElement('button');retry.textContent='重试加载';retry.onclick=()=>history(kind,cursor,append);error.append(retry);box.append(error);
      return null;
    }
  }
  function show(v) {
    if(location.hash.split("?")[0] !== routeHash || !area.isConnected) return;
    if(v.content.taskId!==task.id){output.textContent='此提案不属于当前任务，请从本任务历史重新选择。';return;}
    const url=new URL(location.href),[path,query='']=url.hash.split('?');
    const params=new URLSearchParams(query);params.set('proposal',v.id);if(!selectedRun)params.delete('run');
    url.hash=path+'?'+params;window.history.replaceState(null,'',url);
    current = v;
    const c = v.content, f = c.fields, before = c.before;
    const status = {UNAPPROVED:'建议 · 未批准',STALE:'提案已过期，请重新分析',SUCCEEDED:'执行成功',FAILED:'执行失败 · 未提交业务变更'}[v.status] || v.status;
    const sources = new Map(c.sources.map(s => [s.id, s]));
    const investigation = c.analysis.investigation;
    const cited = [...new Set([...c.analysis.claims.flatMap(x => x.citations), ...(investigation?.relatedTasks || []).flatMap(x => x.citations)])];
    output.innerHTML = `<h3>${esc(status)} · 版本 ${v.version}</h3>` +
      (v.errorCode ? `<p class="error">${esc(v.errorCode)}</p>` : '') +
      (investigation ? `<section id="triage-investigation"><h4>处理建议：${esc({ACCEPT:'建议接纳',NEED_INFO:'先补充信息',POSSIBLE_DUPLICATE:'疑似重复，先核对'}[investigation.recommendation])}</h4>` +
        (investigation.recommendation !== 'ACCEPT' ? '<p>当前调查不建议直接接纳；请先核对问题。若决定接纳，请补齐安排并在调整说明中解释。</p>' : '') +
        investigation.relatedTasks.map(r=>`<p>${link(`task/${p}/${r.taskId}`,'查看相关任务')} · ${esc({POSSIBLE_DUPLICATE:'疑似重复',RELATED:'相关',DISTINCT:'存在关键差异'}[r.relationship])}：${esc(r.reason)} ${r.citations.map(id=>`[依据 ${cited.indexOf(id)+1}]`).join(' ')}</p>`).join('') +
        (investigation.questions.length ? '<h4>需要补充</h4><ul>' + investigation.questions.map(q=>`<li>${esc(q)}</li>`).join('') + '</ul>' : '') + '</section>' : '') +
      table(['字段','修改前','建议接纳后'],[
        ['状态',esc(stateName(before.status)),esc(stateName('TODO'))],
        ['类型',esc(label(before.type)),esc(f.type ? label(f.type) : '待补充')],
        ['优先级',esc(before.priority),esc(f.priority ?? '待补充')],
        ['负责人',esc(owner(before.assigneeId)),esc(owner(f.assigneeId))],
        ['截止日期',esc(before.dueDate || '无'),esc(f.dueDate || '无')]]) +
      '<h4>分析依据（模型建议，需核对）</h4>' + c.analysis.claims.map(x => `<p>${esc(x.text)} ${x.citations.map(id => `[依据 ${cited.indexOf(id)+1}]`).join(' ')}</p>`).join('') +
      cited.map((id,i) => {const s=sources.get(id);if(!s)return '';const match=(s.url||'').match(/^\/projects\/([0-9a-f-]{36})\/(tasks|documents)\/([0-9a-f-]{36})/);
        return `<details><summary>依据 ${i+1} · ${esc(s.title)}</summary>${match && match[1]===p ? link(`${match[2]==='tasks'?'task':'document'}/${p}/${match[3]}`,'打开来源') : ''}<p class="comment-body">${esc(s.evidence?.text || JSON.stringify(s.data))}</p></details>`;}).join('') +
      `<p>本版本说明：${esc(f.rationale)}</p>` +
      (v.result ? `<div id="triage-result"><h4>实际执行结果</h4><p>${esc(stateName(v.result.status))} · ${esc(label(v.result.type))} · P${v.result.priority} · ${esc(owner(v.result.assigneeId))} · 截止 ${esc(v.result.dueDate || '无')} · 任务版本 ${v.result.revision}</p>${link(`task/${p}/${task.id}`,'查看任务')}<button id="triage-refresh-task">刷新任务详情</button></div>` : '') +
      (writable && ['UNAPPROVED','FAILED'].includes(v.status) ?
        form('proposal-edit','修改建议',select('type','类型',[['','待补充'],...['TASK','BUG','FEATURE','QUESTION'].map(x=>[x,label(x)])],f.type||'') +
          select('priority','优先级',[['','待补充'],...[0,1,2,3].map(x=>[x,'P'+x])],f.priority==null?'':String(f.priority)) +
          select('assigneeId','负责人（请按实际安排选择）',[['','选择有效项目成员'],...members.filter(m=>m.active).map(m=>[m.id,m.displayName])],f.assigneeId||'') +
          input('dueDate','截止日期','date',f.dueDate||'',false) + `<label>调整说明<textarea name="rationale" required maxlength="4000">${esc(f.rationale)}</textarea></label>`,'保存为新版本') +
        '<button id="proposal-preview">校验并预览此版本</button>' +
        (v.previewToken ? `<p id="proposal-confirm">已校验版本 ${v.version}。批准将按上方安排接纳为待办。</p><button id="proposal-approve" ${f.type && f.priority!=null && f.assigneeId ? '' : 'disabled'}>明确批准版本 ${v.version} 并接纳</button>` : '<p>尚未批准。编辑后必须保存并重新预览。</p>') : '');
    button('triage-refresh-task',route);
    bind('proposal-edit',async values=>{
      const fields={type:values.type||null,priority:values.priority===''?null:Number(values.priority),assigneeId:values.assigneeId||null,dueDate:values.dueDate||null,rationale:values.rationale};
      show(await api(`${base}/proposals/${v.id}/versions`,'POST',{version:v.version,hash:v.hash,fields}));
    });
    // Unsaved form input must immediately remove the old approval affordance.
    output.querySelector('#proposal-edit')?.addEventListener('input',()=>{
      output.querySelector('#proposal-approve')?.remove();output.querySelector('#proposal-confirm')?.remove();
      output.querySelector('#proposal-preview').disabled=true;
    });
    button('proposal-preview',async()=>show(await api(`${base}/proposals/${v.id}/preview`,'POST',{version:v.version,hash:v.hash})));
    button('proposal-approve',async()=>{
      try { show(await api(`${base}/proposals/${v.id}/approve`,'POST',{version:v.version,hash:v.hash,previewToken:v.previewToken})); }
      catch(e) { const recovered=await api(`${base}/proposals/${v.id}`);show(recovered);if(recovered.status!=='SUCCEEDED')throw e; }
    });
  }
  async function openRun(id) {
    stopRunProgress();runContext(id);sessionStorage.setItem(savedKey,id);
    selectedRun=id;const version=++pollVersion;
    // A pending replacement must not leave the previous proposal actionable.
    // Otherwise its late response can erase a preview/edit completed in between.
    current=null;output.textContent='正在读取这次分析…';
    await poll(id,version);
  }
  async function poll(id,version) {
    if (!alive() || version!==pollVersion) return;
    try {
      const metadata=await api(`${base}/runs/${id}/progress`);
      if(metadata.taskId!==task.id)throw new Error("此分析不属于当前任务，请从本任务分析记录重新选择。");
      const r=await api(`${base}/runs/${id}`);
      if (!alive() || version!==pollVersion) return;
      if (['QUEUED','RUNNING'].includes(r.status)) {
        output.textContent=progressText({status:r.status,stage:r.status});
        watchRun(p,id,()=>alive()&&version===pollVersion,s=>{output.textContent=s.stage==='DISCONNECTED'&&!s.errorCode?'连接中断，正在恢复当前状态；无需重复提交。':progressText(s);},()=>poll(id,version));return;
      }
      sessionStorage.removeItem(savedKey);
      if (r.status!=='SUCCEEDED') {output.textContent=progressText({status:r.status,errorCode:r.errorCode});await history('runs');return;}
      if (writable) {
        const proposal=await api(`${base}/runs/${id}/proposal`,'POST',{});
        if (!alive() || version!==pollVersion) return;
        show(proposal);await history();
      } else output.textContent=(r.result?.answer?.claims || []).map(x=>x.text).join('\n') || '分析已完成。项目恢复后，维护者可查看并生成提案。';
      await history('runs');
    } catch(e) {
      if (!alive() || version!==pollVersion) return;
      output.textContent=`分析暂不可读取或转换为提案：${readableError(e.message)} `;
      const retry=document.createElement('button');retry.textContent='重新读取结果';retry.onclick=()=>openRun(id);output.append(retry);
    }
  }
  syncIntent();
  button('triage-analyze',async()=>{
    let intent=JSON.parse(sessionStorage.getItem(intentKey) || 'null');
    if (!intent) {
      intent={key:crypto.randomUUID(),body:{strategy:area.querySelector('[name="triage-strategy"]').value,question:'根据这项待分诊任务的描述、评论和有关资料，整理接纳方案，建议类型和优先级并解释依据；比较相关或疑似重复任务，资料不足请明确说明。'+area.querySelector('#triage-question').value}};
      sessionStorage.setItem(intentKey,JSON.stringify(intent));syncIntent();
    }
    let r;
    try {r=await api(`${base}/triage/${task.id}/runs`,'POST',intent.body,{'Idempotency-Key':intent.key});}
    catch(e) {
      // A definite client rejection did not create a run. Network/5xx outcomes stay unresolved.
      if(e.status>=400 && e.status<500)sessionStorage.removeItem(intentKey);
      syncIntent();throw e;
    }
    sessionStorage.setItem(savedKey,r.id);sessionStorage.removeItem(intentKey);syncIntent();
    await history('runs');await openRun(r.id);
  });
  // Both histories fail locally and independently; neither prevents ordinary task interaction.
  const [items] = await Promise.all([history('runs'),history()]);
  if(restoreProposal) {
    try{show(await api(`${base}/proposals/${restoreProposal}`));}
    catch(e){if(alive())output.textContent=`提案暂不可读取：${readableError(e.message)}`;}
  } else if (restoreRun) await openRun(restoreRun);
  else if (items?.length && !current && !selectedRun) await openRun(items[0].id);

}

// Private continuous assistant. Server history is authoritative; sessionStorage holds drafts/unsent intent only.
async function conversationPage(root, projectId, params) {
  stopConversation();
  const embedded=root.id==='assistant-body';
  let stopProgress=()=>{};
  const remember=()=>{if(embedded){if(selected)sessionStorage.setItem(assistantKey('conversation',projectId),selected);else sessionStorage.removeItem(assistantKey('conversation',projectId));}};
  const scrollToMessage=(node,block='nearest')=>{if(!node)return;if(!embedded){node.scrollIntoView({block});return;}const log=root.querySelector('#conversation-messages');log.scrollTo({top:log.scrollTop+node.getBoundingClientRect().top-log.getBoundingClientRect().top,behavior:'instant'});};
  let alive=true, timer=null, selected=params.get('conversation'), current=null, messages=[], nextBefore=null, cursor=null, messageBefore=null;
  const actor=me.userId, projects=await api('/me/projects');
  const availability=projectId?await api(`/projects/${projectId}/agent/availability`):{enabled:true};
  let filter=projectId||params.get('projectId')||'', archived=params.get('archived')==='true', query=params.get('q')||'';
  const draftKey=id=>`conversation-draft:${actor}:${id||filter||'new'}`;
  const intentKey=id=>`conversation-intent:${actor}:${id}`;
  const routeFor=(c,p=filter)=>embedded?assistantRoute(p,c):`project/${p}/agent?${new URLSearchParams({conversation:c})}`;
  const localError=e=>modelReason(e.code||e.errorCode)||({CONTEXT_SINGLE_TURN_LIMIT:'一轮历史内容超过整理容量，请新建会话并缩小内容；原文保留。',COMPACTION_INPUT_LIMIT:'待整理内容超过本轮容量，原文保留。',COMPACTION_LIMIT:'本轮对话整理已达到上限，请缩小问题或新建会话。',CONTEXT_SOURCE_UNAVAILABLE:'历史来源已不可访问，请重试以重新整理可用上下文。',CHECKPOINT_SOURCE_CHANGED:'恢复点的来源已变化，请重新分析本轮。',CONVERSATION_CONFLICT:'会话已在另一窗口更新。草稿已保留，请核对新消息后重新发送。',CONVERSATION_BUSY:'当前会话已有回答正在进行，草稿已保留。',CONVERSATION_ARCHIVED:'请先恢复会话再继续。',CONVERSATION_CANCELED:'本轮已停止，可以重试或继续提问。',AGENT_REQUEST_BUDGET:'上下文超过本轮容量，原始聊天记录已保留。请缩小问题或新建会话。',MODEL_RESULT_UNKNOWN:'上次模型请求结果未知，已停止自动重发。可明确重试本轮。',CHECKPOINT_RESPONSE_LOST:'上次响应未形成安全恢复点，本轮已停止。可明确重试。',AGENT_DISABLED:'此实例未启用模型；仍可管理历史会话。',GLM_BUDGET_NOT_APPROVED:'请团队管理员配置文本模型后重新发起。',MODEL_COST_BUDGET:'历史分析已结束，聊天记录已保留。可以使用团队模型重新发起。',AGENT_INTERNAL_ERROR:'本轮分析未完成，可以重试。',COMPACTION_INVALID:'较早对话整理失败，原始记录已保留。'}[e.code||e.errorCode]||readableError(e.message||e.errorCode||'本轮分析未完成，请重试。'));
  root.innerHTML=(!projectId?'<h1>会话历史</h1>':'')+`<section class="conversation-app ${!projectId?'history-only':''}"><aside class="conversation-list"><h2>我的会话</h2><form id="conversation-filter"><label>项目<select name="projectId"><option value="">全部项目</option>${projects.map(p=>`<option value="${esc(p.id)}" ${p.id===filter?'selected':''}>${esc(p.name||p.projectName)}</option>`).join('')}</select></label><label>查找标题<input name="q" value="${esc(query)}" maxlength="120" placeholder="会话标题"></label><label class="row"><input type="checkbox" name="archived" ${archived?'checked':''}>已归档</label><button>筛选</button></form><button id="conversation-new">新建会话</button><button id="conversation-import" class="secondary">导入旧问答</button><details><summary>不可访问会话</summary><div id="conversation-unavailable"></div></details><div id="conversation-items" aria-live="polite">正在加载会话…</div><button id="conversation-more" class="secondary" hidden>更多会话</button></aside><section class="conversation-main"><div id="conversation-heading"></div><p id="conversation-feedback" role="status" aria-live="polite"></p><button id="conversation-older" class="secondary" hidden>加载较早消息</button><button id="conversation-latest" class="secondary" hidden>返回最新消息</button><div id="conversation-messages" role="log" aria-label="对话记录"></div><form id="conversation-compose" class="conversation-compose"><label>继续对话<textarea name="text" maxlength="2000" rows="3" required placeholder="询问当前项目，或继续追问…"></textarea></label><div class="row"><button id="conversation-send">发送</button><button type="button" id="conversation-confirm" class="secondary" hidden>确认上次发送</button><button type="button" id="conversation-cancel" class="secondary" hidden>停止回答</button></div></form></section></section>`;
  const el=s=>root.querySelector(s), field=el('[name=text]'), feedback=(text,error=false)=>{if(!alive)return;el('#conversation-feedback').textContent=text;el('#conversation-feedback').className=error?'error':'';};
  field.value=params.get('question')||sessionStorage.getItem(draftKey(selected))||'';
  field.oninput=()=>sessionStorage.setItem(draftKey(selected),field.value);
  if(params.has('question'))field.oninput();
  stopConversation=()=>{alive=false;clearTimeout(timer);stopProgress();};
  if(embedded)el('#conversation-filter [name=projectId]').closest('label').hidden=true;
  const safe=fn=>async e=>{e?.preventDefault();try{await fn(e);}catch(error){if(alive)feedback(localError(error),true);}};
  function syncCompose(){
    if(!alive)return;
    const pending=selected&&sessionStorage.getItem(intentKey(selected));
    el('#conversation-send').disabled=!availability.enabled||!!current?.activeTurnId||!!current?.archived||!!pending;
    field.disabled=!availability.enabled;
    el('#conversation-confirm').hidden=!pending;
    el('#conversation-cancel').hidden=!current?.activeTurnId;
  }
  async function listing(more=false){
    const q=new URLSearchParams({archived,q:query,limit:15});if(filter)q.set('projectId',filter);if(more&&cursor)q.set('cursor',cursor);
    try{const data=await api('/me/agent/conversations?'+q);if(!alive)return;cursor=data.nextCursor;
      const html=data.items.map(c=>`<article class="conversation-item ${c.id===selected?'selected':''}">${link(routeFor(c.id,c.projectId),c.title)}<small>${esc(c.projectName)} · ${esc(timeLabel(c.lastActivityAt))}</small></article>`).join('');
      if(more)el('#conversation-items').insertAdjacentHTML('beforeend',html);else el('#conversation-items').innerHTML=html||'<p>还没有符合条件的会话。</p>';
      el('#conversation-more').hidden=!cursor;
    }catch(e){if(!alive)return;el('#conversation-items').innerHTML='<p>会话列表加载失败。</p><button id="conversation-list-retry">重试加载</button>';el('#conversation-list-retry').onclick=safe(()=>listing());throw e;}
  }
  function sourceLink(s,t,n){
    const from=`project/${current.projectId}/agent?${new URLSearchParams({conversation:selected,turn:t.seq,source:n})}`, q=new URLSearchParams({from});
    const match=(s.url||'').match(/^\/projects\/([0-9a-f-]{36})\/(tasks|documents)\/([0-9a-f-]{36})(?:\/comments\/([0-9a-f-]{36}))?/);
    if(match&&match[1]===current.projectId){if(match[4])q.set('comment',match[4]);if(s.evidence&&s.evidence.kind!=='GITHUB_COMMENT'){q.set('start',s.evidence.charStart);q.set('end',s.evidence.charEnd);}return link(`${match[2]==='tasks'?'task':'document'}/${current.projectId}/${match[3]}?${q}`,s.title||'打开原文');}
    if(s.kind==='HISTORY_MESSAGE')return link(routeFor(selected,current.projectId)+`&turn=${s.data.turn}`,'回看该轮历史');
    if(/^https:\/\/github\.com\//.test(s.url||''))return `<a href="${esc(s.url)}" target="_blank" rel="noopener noreferrer">${esc(s.title||'GitHub 来源')}</a>`;
    return esc(s.title||'项目资料');
  }
  function draw(){
    if(!alive)return;
    el('#conversation-heading').innerHTML=current?`<div class="conversation-title"><div><p class="scope-note">当前项目：${esc(current.projectName)}</p><h2>${esc(current.title)}</h2></div><div class="row"><button id="conversation-rename" class="secondary">重命名</button><button id="conversation-archive" class="secondary">${current.archived?'恢复会话':'归档'}</button><button id="conversation-delete" class="danger">删除</button></div></div>`:'<h2>今天想了解什么？</h2>';
    el('#conversation-messages').innerHTML=messages.length?messages.map(t=>{
      const result=t.result, sources=result?.sources||[], numbers=new Map(sources.map((s,i)=>[s.id,i+1]));
      const answer=result?.answer?.claims.map(c=>`<p class="chat-paragraph">${c.kind==='CLARIFICATION'?'<strong>需要澄清：</strong>':''}${esc(c.text)} ${c.citations.map(id=>numbers.has(id)?`<button class="citation-marker" data-turn="${t.seq}" data-citation="${numbers.get(id)}" aria-label="查看第 ${t.seq} 轮引用 ${numbers.get(id)}">[${numbers.get(id)}]</button>`:'').join(' ')}</p>`).join('');
      const live=current.activeTurnId===t.id;
      return `<article class="conversation-turn" id="turn-${t.seq}" tabindex="-1"><div class="chat-user"><strong>你</strong><p>${esc(t.text)}</p></div><div class="chat-assistant"><strong>项目助手</strong><p class="muted">${esc(timeLabel(t.createdAt))}${t.sourceState==='CHANGED'?' · 历史依据已变化，请以最新来源为准':''}</p>${live?'<p data-active-progress>正在查证项目资料…</p>':t.sourceState==='UNAVAILABLE'?'<p>来源已删除或不可访问，受影响的历史回答已隐藏。</p>':answer||`<p class="error">${esc(localError(t))}</p>`}${!live&&['FAILED','STALE','CANCELED'].includes(t.status)&&t.seq===current.lastSeq?`<button data-retry="${t.id}" class="secondary">重试本轮</button>`:''}${sources.length?`<details class="agent-sources"><summary>核对来源（${sources.length}）</summary>${sources.map((s,i)=>`<article id="turn-${t.seq}-source-${i+1}" tabindex="-1"><strong>[${i+1}] ${sourceLink(s,t,i+1)}</strong><p class="muted">${s.kind==='HISTORY_MESSAGE'?'历史对话，非当前业务依据':'读取于 '+esc(timeLabel(s.observedAt))}</p>${s.evidence?.text?`<pre class="agent-evidence">${esc(s.evidence.text)}</pre>`:''}</article>`).join('')}</details>`:''}</div></article>`;
    }).join(''):'<p class="conversation-empty">暂无消息</p>';
    el('#conversation-older').hidden=!nextBefore;el('#conversation-latest').hidden=!current||!messages.length||messages[messages.length-1].seq===current.lastSeq;syncCompose();
    if(current){
      el('#conversation-rename').onclick=safe(async()=>{const title=prompt('会话标题',current.title);if(title===null)return;current=await api(`/agent/conversations/${selected}`,'PATCH',{title,expectedHistoryRevision:current.historyRevision});draw();await listing();feedback('标题已保存。');});
      el('#conversation-archive').onclick=safe(async()=>{current=await api(`/agent/conversations/${selected}`,'PATCH',{archived:!current.archived,expectedHistoryRevision:current.historyRevision});draw();await listing();feedback(current.archived?'会话已归档；恢复后可继续提问。':'会话已恢复。');});
      el('#conversation-delete').onclick=safe(async()=>{if(!confirm('删除此会话及在线聊天正文？调用与用量记录保留，已有备份按保留政策过期。'))return;const response=await api(`/agent/conversations/${selected}`,'DELETE');sessionStorage.removeItem(draftKey(selected));sessionStorage.removeItem(intentKey(selected));selected=null;remember();current=null;messages=[];draw();await listing();feedback(response.backupNotice);history.replaceState(null,'','#'+(embedded?assistantRoute(filter,null):`project/${filter}/agent`));});
    }
    root.querySelectorAll('[data-retry]').forEach(b=>b.onclick=safe(()=>send(b.dataset.retry)));
    root.querySelectorAll('[data-citation]').forEach(b=>b.onclick=safe(async()=>{
      const before=location.hash;await refresh(false,Number(b.dataset.turn));
      if(!alive||location.hash!==before)return;
      const t=messages.find(t=>String(t.seq)===b.dataset.turn);if(!t?.result)return;
      const card=el(`#turn-${t.seq}-source-${b.dataset.citation}`);if(card){card.closest('details').open=true;card.focus({preventScroll:true});scrollToMessage(card);history.replaceState(null,'','#'+routeFor(selected,current.projectId)+`&turn=${t.seq}&source=${b.dataset.citation}`);}
    }));
  }
  function focusTurn(){const seq=params.get('turn'),source=params.get('source');if(!seq)return;const card=el(source?`#turn-${Number(seq)}-source-${Number(source)}`:`#turn-${Number(seq)}`);if(card){const d=card.closest('details');if(d)d.open=true;card.focus({preventScroll:true});scrollToMessage(card,'center');}}
  async function refresh(attach=true,targetSeq=null){
    if(!selected)return;try{
      const c=await api(`/agent/conversations/${selected}`);
      if(targetSeq)messageBefore=targetSeq<=c.lastSeq-20?targetSeq+1:null;
      const page=await api(`/agent/conversations/${selected}/messages?limit=20${messageBefore?'&before='+messageBefore:''}`);if(!alive)return;
      const previousActive=current?.activeTurnId;current=c;messages=page.items;nextBefore=page.nextBefore;draw();
      if(!targetSeq&&!messageBefore&&(attach||previousActive))scrollToMessage(el(`#turn-${c.lastSeq}`),'start');
      if(attach&&current.activeTurnId){const active=messages.find(t=>t.id===current.activeTurnId);if(active){stopProgress();stopProgress=watchRun(current.projectId,active.runId,()=>alive&&!!current?.activeTurnId,s=>{const node=el('[data-active-progress]');if(node)node.textContent=s.stage==='COMPACTION'?'正在整理较早对话…':s.stage==='DISCONNECTED'?'连接中断，正在恢复状态；无需重复发送。':progressText(s);},async()=>{if(alive){await refresh(false);await listing();}},true);}}
    }catch(e){if(!alive)return;if(e.status===403||e.status===404){current=null;messages=[];el('#conversation-heading').innerHTML='<h2>此会话已不可访问</h2>';el('#conversation-messages').innerHTML='';el('#conversation-items').innerHTML='';el('#conversation-compose').hidden=true;feedback('项目权限已失效或会话已删除。',true);stopProgress();}throw e;}
  }
  async function create(){const p=filter||projects[0]?.id;if(!p){feedback('请先加入一个项目。',true);return;}const c=await api(`/projects/${p}/agent/conversations`,'POST');if(field.value)sessionStorage.setItem(draftKey(c.id),field.value);location.hash=routeFor(c.id,p);}
  async function send(retry=null,confirmPending=false){
    if(!availability.enabled){feedback((modelReason(availability.reason)||'项目助手待配置。')+' 可查看和管理已保存会话。');return;}
    if(!selected){const p=filter||projects[0]?.id;if(!p){feedback('请先加入项目。',true);return;}current=await api(`/projects/${p}/agent/conversations`,'POST');selected=current.id;remember();history.replaceState(null,'','#'+routeFor(selected,p));}
    let intent=JSON.parse(sessionStorage.getItem(intentKey(selected))||'null');
    if(intent&&!confirmPending){feedback('上次提交回执尚未确认，请点“确认上次发送”；草稿可继续编辑。',true);return;}
    if(!intent){intent={key:crypto.randomUUID(),body:{text:retry?null:field.value,expectedHistoryRevision:current.historyRevision},retry};sessionStorage.setItem(intentKey(selected),JSON.stringify(intent));}
    syncCompose();feedback('正在提交…');
    try{await api(`/agent/conversations/${selected}/turns${intent.retry?'/'+intent.retry+'/retry':''}`,'POST',intent.body,{'Idempotency-Key':intent.key});sessionStorage.removeItem(intentKey(selected));if(!intent.retry&&field.value===intent.body.text){field.value='';sessionStorage.removeItem(draftKey(selected));}messageBefore=null;await refresh();await listing();feedback('');}
    catch(e){if(e.status>=400&&e.status<500||['AGENT_DISABLED','GLM_BUDGET_NOT_APPROVED'].includes(e.code)){sessionStorage.removeItem(intentKey(selected));await refresh();}feedback(localError(e),true);}finally{syncCompose();}
  }
  el('#conversation-compose').onsubmit=safe(()=>send());el('#conversation-confirm').onclick=safe(()=>send(null,true));
  el('#conversation-cancel').onclick=safe(async()=>{await api(`/agent/conversations/${selected}/turns/${current.activeTurnId}/cancel`,'POST');stopProgress();await refresh(false);feedback('已停止本轮回答；草稿保留。');});
  el('#conversation-import').onclick=safe(async()=>{const result=await api('/me/agent/conversations/import-legacy','POST');await listing();feedback(`已导入 ${result.imported} 条旧问答；每次最多50条，不产生模型调用。`);});
  const unavailable=await api('/me/agent/conversations/unavailable');
  el('#conversation-unavailable').innerHTML=unavailable.length?unavailable.map(id=>`<p>不可访问会话 <button data-delete-unavailable="${id}" class="secondary">删除记录</button></p>`).join(''):'<p>暂无</p>';
  root.querySelectorAll('[data-delete-unavailable]').forEach(b=>b.onclick=safe(async()=>{if(!confirm('删除这个不可访问会话的在线正文？'))return;await api(`/agent/conversations/${b.dataset.deleteUnavailable}`,'DELETE');b.parentElement.remove();feedback('不可访问会话已清理。');}));
  el('#conversation-new').onclick=safe(create);el('#conversation-more').onclick=safe(()=>listing(true));
  el('#conversation-filter').onsubmit=safe(async e=>{const data=new FormData(e.target);filter=embedded?projectId:data.get('projectId');query=data.get('q');archived=data.has('archived');await listing();});
  el('#conversation-older').onclick=safe(async()=>{const page=await api(`/agent/conversations/${selected}/messages?limit=20&before=${nextBefore}`);messages=[...page.items,...messages];nextBefore=page.nextBefore;draw();});
  el('#conversation-latest').onclick=safe(async()=>{messageBefore=null;await refresh();if(embedded)el('#conversation-messages').scrollTop=el('#conversation-messages').scrollHeight;else el('#conversation-compose').scrollIntoView({block:'end'});});
  draw();await listing();if(selected){await refresh(true,Number(params.get('turn'))||null);remember();focusTurn();}
  if(!availability.enabled)feedback((modelReason(availability.reason)||'项目助手待配置。')+' 可查看和管理已保存会话。');
  async function reauthorize(){if(!alive)return;if(selected)try{await api(`/agent/conversations/${selected}`);}catch(e){await refresh(false).catch(()=>{});}timer=setTimeout(reauthorize,4000);}
  timer=setTimeout(reauthorize,4000);
  return {selected:()=>selected,async navigate(next){
    params=next;
    if(next.has('question')){field.value=next.get('question').slice(0,2000);field.oninput();field.focus({preventScroll:true});}
    if(next.has('turn')){await refresh(false,Number(next.get('turn'))||null);focusTurn();}
  }};
}
