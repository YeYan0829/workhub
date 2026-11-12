"use strict";

// Model settings share the application's session, CSRF and route handling.
// Credentials live only in the current form and are cleared after a successful save.
function modelReason(code) {
  return ({
    AGENT_DISABLED: '部署者尚未开启文本生成功能。',
    MODEL_NOT_CONFIGURED: '团队尚未配置文本模型，请团队管理员在“模型服务”中配置。',
    MODEL_CONNECTION_DISABLED: '团队文本模型连接已停用，请团队管理员启用。',
    MODEL_POLICY_INVALID: '项目选用的模型已移出团队允许范围，请项目维护者更新模型策略。',
    MODEL_CONNECTION_CHANGED: '团队模型配置已更新，请重新发起分析以使用当前配置。',
    MODEL_POLICY_CHANGED: '项目模型策略已更新，请重新发起分析。',
    MODEL_CONTRACT_INVALID: '模型服务返回的模型身份与配置不一致，请团队管理员检查连接。',
    MODEL_CONFIG_CONFLICT: '配置已在其他窗口更新。当前输入保留，请重新载入并核对后保存。',
    MODEL_KEY_UNAVAILABLE: '部署端模型加密主密钥需要检查，请联系部署者。',
    MODEL_LEGACY_RUN: '这条历史分析使用旧版配置，可以发起新分析使用团队模型。',
    MODEL_MODE_CHANGED: '部署运行模式已更新，请重新发起分析。',
    MODEL_ADMISSION_UNINITIALIZED: '团队调用配额等待初始化，请部署者按架构说明初始化 Redis 配额域。',
    MODEL_ADMISSION_UNAVAILABLE: '共享调用服务暂时不可用，请联系部署者检查 Redis。',
    MODEL_ADMISSION_MISSING: '团队调用配额等待初始化或恢复，请联系部署者处理。',
    EMBEDDING_INDEX_NOT_ACTIVE: '项目正在使用关键词检索，维护者可在“模型与检索”中准备并切换向量空间。',
    EMBEDDING_INDEX_CHANGED: '向量空间刚刚切换，本次采用关键词检索，可重新查询。',
    MODEL_RESULT_UNKNOWN: '上次模型请求结果未知，已停止自动重发。可以明确重试本轮。',
    EMBEDDING_NOT_CONFIGURED: '团队尚未配置 Embedding，请团队管理员在“模型服务”中配置。',
    EMBEDDING_DISABLED: '部署者尚未开启 Embedding 与检索服务。',
    EMBEDDING_CONNECTION_DISABLED: '团队 Embedding 连接已停用，请团队管理员启用。',
    EMBEDDING_GENERATION_CONNECTION_INVALID: '复用凭据需要已启用的百炼文本模型连接，请团队管理员检查。',
    EMBEDDING_PROVIDER_WORKSPACE_REQUIRED: '请团队管理员补充百炼北京地域业务空间 ID。',
    EMBEDDING_CONFIG_CHANGED: 'Embedding 配置已更新，请刷新后继续。',
    EMBEDDING_POLICY_CHANGED: '项目检索策略已更新，请刷新后继续。',
    EMBEDDING_INDEX_NOT_READY: '目标空间正在追平项目资料，请刷新进度后切换。',
    FORBIDDEN: '当前操作权限已变化，请刷新页面确认团队或项目权限。',
    PROJECT_ARCHIVED: '项目已归档。项目维护者可在项目管理中恢复后修改配置。'
  })[code];
}

function modelError(error) {
  return modelReason(error.code || error.errorCode) || readableError(error.message || error.errorCode) || '加载失败，请重试。';
}
const modelNumber = value => value == null ? '未报告' : Number(value).toLocaleString('zh-CN');
const modelProvider = id => ({ZAI:'智谱 Z.AI', DASHSCOPE:'阿里云百炼', ARK:'火山方舟'})[id] || id;
const modelStatus = (enabled, configured=true) => `<span class="model-badge ${enabled?'is-on':''}">${configured?(enabled?'已启用':'已停用'):'待配置'}</span>`;
function modelNumeric(name, title, value, min, max, step=1) {
  return `<label>${esc(title)}<input type="number" name="${name}" value="${esc(value)}" min="${min}" max="${max}" step="${step}" required></label>`;
}
function modelSecret(required) {
  return `<label>供应商 API key<input name="apiKey" type="password" autocomplete="new-password" spellcheck="false" ${required?'required':''} placeholder="${required?'输入供应商 API key':'留空保留现有密钥'}"></label>`;
}
function modelFeedback(root, text, error=false) {
  if (!root.isConnected) return;
  const status=root.querySelector('[data-model-feedback]');
  if(status){status.textContent=text;status.className='model-feedback'+(error?' error':'');}
}
function modelShell(root, title, intro, tabs) {
  const section=new URLSearchParams(location.hash.split('?')[1]||'').get('section');
  const chosen=tabs.some(t=>t[0]===section)?section:tabs[0][0];
  root.innerHTML=`<div class="model-console"><div class="model-heading"><h2>${esc(title)}</h2><p>${esc(intro)}</p></div><div class="model-tabs" role="tablist" aria-label="${esc(title)}">${tabs.map(([id,text])=>`<button type="button" id="model-tab-${id}" role="tab" aria-selected="${id===chosen}" aria-controls="model-panel-${id}" tabindex="${id===chosen?'0':'-1'}" data-model-tab="${id}">${esc(text)}</button>`).join('')}</div>${tabs.map(([id])=>`<div id="model-panel-${id}" role="tabpanel" aria-labelledby="model-tab-${id}" ${id!==chosen?'hidden':''}><p role="status">正在加载…</p></div>`).join('')}</div>`;
  function choose(id, focus=false){
    root.querySelectorAll('[data-model-tab]').forEach(b=>{const active=b.dataset.modelTab===id;b.setAttribute('aria-selected',String(active));b.tabIndex=active?0:-1;if(active&&focus)b.focus();});
    tabs.forEach(([key])=>{root.querySelector('#model-panel-'+key).hidden=key!==id;});
    const [path,search='']=location.hash.split('?'),q=new URLSearchParams(search);q.set('section',id);history.replaceState(null,'',path+'?'+q);
  }
  root.querySelectorAll('[data-model-tab]').forEach((b,i)=>{
    b.onclick=()=>choose(b.dataset.modelTab);
    b.onkeydown=e=>{let next;if(e.key==='ArrowRight')next=(i+1)%tabs.length;if(e.key==='ArrowLeft')next=(i+tabs.length-1)%tabs.length;if(e.key==='Home')next=0;if(e.key==='End')next=tabs.length-1;if(next!==undefined){e.preventDefault();choose(tabs[next][0],true);}};
  });
  return id=>root.querySelector('#model-panel-'+id);
}
async function modelOptional(path, missingCode) {
  try{return await api(path);}catch(e){if(e.code===missingCode)return null;throw e;}
}
function modelLoadError(root, error, reload) {
  if(!root.isConnected)return;
  root.innerHTML=`<div class="model-card"><p class="error" role="alert">${esc(modelError(error))}</p><button type="button" class="secondary" data-reload>重试加载</button></div>`;
  root.querySelector('[data-reload]').onclick=reload;
}
function modelBind(root, submit, reload, success) {
  const f=root.querySelector('form');let saving=false;
  f.addEventListener('input',()=>{f.dataset.dirty='true';});
  f.addEventListener('change',()=>{f.dataset.dirty='true';});
  f.onsubmit=async e=>{
    e.preventDefault();if(saving)return;
    const data=new FormData(f), fields=f.querySelector('fieldset'),button=f.querySelector('[type=submit]');
    saving=true;fields.disabled=true;button.disabled=true;modelFeedback(root,'正在保存…');
    try{await submit(data);await reload();modelFeedback(root,success);}
    catch(error){modelFeedback(root,error.status?modelError(error):'保存结果待确认。请重新载入配置，核对当前版本后继续。',true);if(error.code==='MODEL_CONFIG_CONFLICT'||!error.status)f.dataset.stale='true';}
    finally{saving=false;if(f.isConnected){fields.disabled=false;button.disabled=f.dataset.stale==='true';}}
  };
  root.querySelector('[data-reload]').onclick=()=>{if(saving)return;if(f.dataset.dirty==='true'&&!confirm('重新载入会清空当前未保存输入，继续？'))return;void reload();};
}
function modelQuota(quota) {
  return quota?`<details class="model-ops"><summary>共享调用配额</summary><p>每秒 ${esc(quota.rate)} 次，突发 ${esc(quota.burst)} 次。</p><p>首次接入由部署者初始化配额域，步骤见仓库架构说明。</p><code>${esc(quota.scope)}</code><p class="muted">配置版本 ${esc(quota.version)}</p></details>`:'';
}

async function teamModelPage(root, w, admin) {
  if(!admin){root.innerHTML='<p class="scope-note">团队模型服务由团队管理员管理。项目成员可以在项目的“模型与检索”中查看策略和用量。</p>';return;}
  const panel=modelShell(root,'团队模型服务','为团队项目配置文本生成与 Embedding。团队内各项目共享对应的调用速率。', [['text','文本生成'],['embedding','Embedding'],['usage','团队用量']]);
  async function text(){
    const area=panel('text');
    try{
      const [catalog,c]=await Promise.all([api(`/workspaces/${w}/model-catalog`),modelOptional(`/workspaces/${w}/model-connection`,'MODEL_NOT_CONFIGURED')]);if(!area.isConnected)return;
      const providers=[...new Set(catalog.map(x=>x.provider))], provider=c?.provider||providers[0];
      area.innerHTML=`<form id="team-text-model" class="model-card"><div class="section-heading"><h3>文本生成连接</h3>${modelStatus(c?.enabled,!!c)}</div><p class="muted">用于项目问答、任务分析和会话整理。</p><fieldset><legend class="sr-only">文本模型配置</legend><div class="model-grid">${select('provider','供应商',providers.map(x=>[x,modelProvider(x)]),provider)}<div data-model-select></div></div><fieldset class="model-choices"><legend>项目可用模型</legend><div data-model-options></div></fieldset><div class="model-grid">${select('enabled','连接状态',[['true','启用'],['false','停用']],String(c?.enabled??true))}${modelSecret(!c)}${modelNumeric('requestsPerSecond','团队每秒请求数',c?.requestsPerSecond||4,.001,10000,.001)}${modelNumeric('burst','突发请求数',c?.burst||2,1,10000)}</div><p class="model-hint">保存后采用新的连接版本，等待中的旧分析会结束；可以重新发起分析使用新配置。</p><button type="submit">保存文本模型</button></fieldset><button type="button" class="secondary" data-reload>重新载入配置</button><p data-model-feedback role="status"></p>${modelQuota(c?{scope:c.quotaScope,version:c.version,rate:c.requestsPerSecond,burst:c.burst}:null)}</form>`;
      const f=area.querySelector('form');
      function choices(){const selected=f.elements.provider.value,models=catalog.filter(x=>x.provider===selected).map(x=>x.model),same=selected===c?.provider;
        area.querySelector('[data-model-select]').innerHTML=select('model','团队默认模型',models.map(x=>[x,x]),same?c.model:models[0]);
        area.querySelector('[data-model-options]').innerHTML=models.map(m=>`<label class="model-check"><input type="checkbox" name="allowedModels" value="${esc(m)}" ${!same||c.allowedModels.includes(m)?'checked':''}>${esc(m)}</label>`).join('');
        f.elements.apiKey.required=!c||!same;
      }
      choices();f.elements.provider.onchange=choices;
      modelBind(area,async d=>{const allowed=d.getAll('allowedModels');if(!allowed.includes(d.get('model')))throw Object.assign(new Error('请将团队默认模型加入项目可用模型。'),{status:400});await api(`/workspaces/${w}/model-connection`,'PUT',{expectedVersion:c?.version||0,provider:d.get('provider'),model:d.get('model'),allowedModels:allowed,enabled:d.get('enabled')==='true',requestsPerSecond:Number(d.get('requestsPerSecond')),burst:Number(d.get('burst')),apiKey:d.get('apiKey')||null});},text,'文本模型已保存，密钥输入已清空。');
    }catch(e){modelLoadError(area,e,text);}
  }
  async function embedding(){
    const area=panel('embedding');
    try{
      const [catalog,c]=await Promise.all([api(`/workspaces/${w}/embedding-catalog`),modelOptional(`/workspaces/${w}/embedding-connection`,'EMBEDDING_NOT_CONFIGURED')]);if(!area.isConnected)return;
      area.innerHTML=`<form id="team-embedding-model" class="model-card"><div class="section-heading"><h3>Embedding 连接</h3>${modelStatus(c?.enabled,!!c)}</div><p class="muted">将项目资料转为向量，用于语义与混合检索。供应商：阿里云百炼 · ${esc(catalog.region)}。</p>${c?.configurationIssue?`<p class="error">${esc(modelReason(c.configurationIssue)||c.configurationIssue)}</p>`:''}<fieldset><legend class="sr-only">Embedding 配置</legend><div class="model-grid">${select('model','模型',[[catalog.model,catalog.model]],catalog.model)}${select('dimensions','向量维度',catalog.dimensions.map(x=>[x,x]),c?.defaultSpace.dimensions||1024)}${input('providerWorkspaceId','百炼业务空间 ID','text',c?.providerWorkspaceId||'')}${select('enabled','连接状态',[['true','启用'],['false','停用']],String(c?.enabled??true))}${select('useGenerationKey','凭据来源',[['false','独立 Embedding 密钥'],['true','复用百炼文本模型凭据']],String(c?.useGenerationKey||false))}<div data-independent-key>${modelSecret(!c||c.useGenerationKey)}</div></div><p class="model-hint">业务空间 ID 使用百炼北京地域的空间标识，可在百炼控制台查看。</p><p data-reuse-hint class="model-hint" hidden>Embedding 与文本生成共享凭据及调用速率。文本生成连接需选择百炼并启用。</p><div class="model-grid" data-independent-rate>${modelNumeric('requestsPerSecond','Embedding 每秒请求数',c?.quota?.rate||4,.001,10000,.001)}${modelNumeric('burst','突发请求数',c?.quota?.burst||2,1,10000)}</div><label>查询指令<textarea name="queryInstruction" maxlength="1000" rows="2">${esc(c?.defaultSpace.queryInstruction||'')}</textarea></label><p class="model-hint">模型、维度或查询指令变更后，项目维护者可在“模型与检索”中选择新空间，重建并切换。</p><button type="submit">保存 Embedding</button></fieldset><button type="button" class="secondary" data-reload>重新载入配置</button><p data-model-feedback role="status"></p>${c?`<p class="muted">团队默认空间 #${c.defaultSpace.configId} · ${esc(c.defaultSpace.model)} · ${c.defaultSpace.dimensions} 维</p>`:''}${modelQuota(c?.quota)}</form>`;
      const f=area.querySelector('form');
      function credentials(){const reuse=f.elements.useGenerationKey.value==='true';area.querySelector('[data-independent-key]').hidden=reuse;area.querySelector('[data-independent-rate]').hidden=reuse;area.querySelector('[data-reuse-hint]').hidden=!reuse;f.elements.apiKey.disabled=reuse;f.elements.apiKey.required=!reuse&&(!c||c.useGenerationKey);if(reuse)f.elements.apiKey.value='';}
      f.elements.useGenerationKey.onchange=credentials;credentials();
      modelBind(area,d=>api(`/workspaces/${w}/embedding-connection`,'PUT',{expectedVersion:c?.version||0,model:d.get('model'),dimensions:Number(d.get('dimensions')),providerWorkspaceId:d.get('providerWorkspaceId').trim(),queryInstruction:d.get('queryInstruction'),enabled:d.get('enabled')==='true',useGenerationKey:d.get('useGenerationKey')==='true',requestsPerSecond:Number(d.get('requestsPerSecond')),burst:Number(d.get('burst')),apiKey:d.get('useGenerationKey')==='true'?null:(d.get('apiKey')||null)}),embedding,'Embedding 已保存，项目维护者可选择目标空间并重建。');
    }catch(e){modelLoadError(area,e,embedding);}
  }
  await Promise.allSettled([text(),embedding(),modelUsagePage(panel('usage'),{workspace:w})]);
}

async function projectModelPage(root, p, project, maintainer) {
  const editable=maintainer&&!project.archived;
  const panel=modelShell(root,'项目模型与检索','选择团队提供的模型，管理分析边界与项目检索空间。', [['policy','文本模型策略'],['vectors','向量空间'],['usage','项目用量']]);
  async function policy(){
    const area=panel('policy');
    try{const c=await api(`/projects/${p}/model-policy`);if(!area.isConnected)return;
      area.innerHTML=`<form id="project-model-policy" class="model-card"><div class="section-heading"><h3>文本模型策略</h3><span class="model-badge">${c.inherited?'继承团队默认':'项目指定'}</span></div><p>当前模型：<strong>${esc(c.model)}</strong></p>${!c.modelAllowed?'<p class="error">当前模型已移出团队允许范围，请重新选择。</p>':''}<fieldset ${editable?'':'disabled'}><legend class="sr-only">项目分析边界</legend>${select('model','使用模型',[['','继承团队默认'],...c.allowedModels.map(x=>[x,x])],c.inherited?'':c.model)}<div class="model-grid">${modelNumeric('maxCalls','每次分析最多生成请求数',c.maxCalls,1,20)}${modelNumeric('seconds','每次分析最长时间（秒）',c.maxMillis/1000,1,300)}</div><p class="model-hint">回答生成、会话整理和生成重试共用请求次数；整次分析受执行时长限制。</p>${editable?'<button type="submit">保存项目策略</button>':''}</fieldset>${editable?'<button type="button" class="secondary" data-reload>重新载入配置</button>':`<p class="scope-note">${project.archived?'项目已归档，当前展示已保存策略。':'项目维护者可修改模型策略。'}</p>`}<p data-model-feedback role="status"></p></form>`;
      if(editable)modelBind(area,d=>api(`/projects/${p}/model-policy`,'PUT',{expectedVersion:c.version,model:d.get('model')||null,maxCalls:Number(d.get('maxCalls')),maxMillis:Number(d.get('seconds'))*1000}),policy,'项目策略已保存，后续分析使用新的执行边界。');
    }catch(e){modelLoadError(area,e,policy);}
  }
  async function vectors(){
    const area=panel('vectors');let uncertain=false, timer;
    try{const c=await api(`/projects/${p}/embedding-policy`);if(!area.isConnected)return;
      area.innerHTML=`<div class="model-card"><h3>项目向量空间</h3><p class="muted">选择目标空间后，后台逐步处理项目资料。覆盖当前资料后，由项目维护者切换检索空间。</p><div data-vector-status aria-live="polite"></div><form id="project-embedding-policy"><fieldset ${editable?'':'disabled'}><legend>检索设置</legend>${select('enabled','语义检索',[['false','关闭'],['true','启用']],String(c.enabled))}${select('configId','目标向量空间',[['','团队默认空间'],...c.availableSpaces.map(s=>[s.configId,`#${s.configId} · ${s.model} · ${s.dimensions} 维`])],c.buildingConfigId??c.activeConfigId??'')}${editable?'<button type="submit">保存检索设置</button>':''}</fieldset></form>${editable?'':'<p class="scope-note">'+(project.archived?'项目已归档，当前展示已保存策略。':'项目维护者可管理重建与切换。')+'</p>'}<button type="button" class="secondary" data-reload>刷新配置与进度</button><p data-model-feedback role="status"></p></div>`;
      const statusNode=area.querySelector('[data-vector-status]');
      function status(v){if(!statusNode.isConnected)return;
        const target=v.buildingConfigId??v.activeConfigId, space=v.availableSpaces.find(s=>s.configId===target);
        const activeSpace=v.availableSpaces.find(s=>s.configId===v.activeConfigId), buildingSpace=v.availableSpaces.find(s=>s.configId===v.buildingConfigId);
        area.querySelector('[data-vector-status]').innerHTML=`<div class="model-metrics"><div><span>当前检索空间</span><strong>${!v.enabled?'关键词':activeSpace?activeSpace.dimensions+' 维':'待切换'}</strong>${activeSpace?'<small>空间 #'+activeSpace.configId+'</small>':''}</div><div><span>待切换空间</span><strong>${buildingSpace?buildingSpace.dimensions+' 维':'—'}</strong>${buildingSpace?'<small>空间 #'+buildingSpace.configId+'</small>':''}</div><div><span>目标资料覆盖</span><strong>${v.readySources} / ${v.eligibleSources}</strong></div></div>${space?`<p>${esc(space.model)} · ${space.dimensions} 维${space.queryInstruction?' · 查询指令：'+esc(space.queryInstruction):''}</p>`:''}<progress aria-label="目标空间资料覆盖" value="${v.readySources}" max="${Math.max(v.eligibleSources,1)}"></progress><p class="muted">项目检索待处理任务 ${v.pendingJobs} · 累计失败任务 ${v.failedJobs}</p><p>${!v.enabled?'当前使用关键词检索。':v.buildingConfigId?'后台正在准备目标空间，现有检索空间继续服务。':v.activeConfigId?'项目已启用当前向量空间。':'选择团队空间并启用后，可开始重建。'}</p>${editable?`<div class="model-actions"><button type="button" data-vector-rebuild class="secondary" ${!v.enabled||uncertain?'disabled':''}>重建目标空间</button><button type="button" data-vector-activate ${!v.enabled||!v.buildingConfigId||v.readySources!==v.eligibleSources||uncertain?'disabled':''}>切换到目标空间</button></div>`:''}`;
        if(editable)for(const [action,label]of [['rebuild','重建任务已登记，后台正在处理项目资料。'],['activate','项目已切换到目标向量空间。']]){
          area.querySelector('[data-vector-'+action+']').onclick=async()=>{
            if(action==='activate'&&!confirm(`将项目检索切换到空间 #${v.buildingConfigId}？`))return;
            uncertain=true;status(v);modelFeedback(area,'正在提交…');
            try{await api(`/projects/${p}/knowledge/embedding-${action}`,'POST',{expectedVersion:v.version});clearTimeout(timer);await vectors();modelFeedback(area,label);}
            catch(e){if(e.status){uncertain=false;status(v);}modelFeedback(area,e.status?modelError(e):'提交结果待确认。请刷新配置与进度，查看后台任务状态。',true);}
          };
        }
      }
      status(c);
      if(editable)modelBind(area,d=>api(`/projects/${p}/embedding-policy`,'PUT',{expectedVersion:c.version,enabled:d.get('enabled')==='true',configId:d.get('configId')?Number(d.get('configId')):null}),vectors,'项目检索设置已保存，可查看覆盖进度并重建目标空间。');
      else area.querySelector('[data-reload]').onclick=vectors;
      async function poll(){if(!statusNode.isConnected||uncertain)return;try{const v=await api(`/projects/${p}/embedding-policy`);if(statusNode.isConnected)status(v);if(statusNode.isConnected&&(v.buildingConfigId||v.pendingJobs))timer=setTimeout(poll,4000);}catch(e){modelFeedback(area,modelError(e),true);}}
      if(c.buildingConfigId||c.pendingJobs)timer=setTimeout(poll,4000);
    }catch(e){modelLoadError(area,e,vectors);}
  }
  await Promise.allSettled([policy(),vectors(),modelUsagePage(panel('usage'),{project:p})]);
}

async function modelUsagePage(root, scope) {
  const month=new Date().toISOString().slice(0,7);
  let projects=[];
  try{
    if(scope.workspace)projects=await api(`/workspaces/${scope.workspace}/management/projects`);if(!root.isConnected)return;
    root.innerHTML=`<div class="model-card"><h3>${scope.workspace?'团队':'项目'}模型用量</h3><p class="muted">按 UTC 月份查看生成与 Embedding 的调用记录，以及供应商报告的 token 用量。</p><form id="model-usage-filter" class="model-filters"><label>月份（UTC）<input type="month" name="period" value="${month}" required></label>${scope.workspace?select('project','项目',[['','全部项目'],...projects.map(p=>[p.id,p.name])]):''}${select('capability','调用类型',[['','全部类型'],['TEXT_GENERATION','文本生成'],['EMBEDDING','Embedding']])}<button type="submit">查询用量</button></form><p data-model-feedback role="status"></p><div data-usage-results aria-live="polite"></div></div>`;
    let loading=false, rows=[];
    const f=root.querySelector('form'),results=root.querySelector('[data-usage-results]');
    function render(){
      const capability=f.elements.capability.value,visible=rows.filter(r=>!capability||r.capability===capability);
      results.innerHTML=`<p class="model-hint">返回 ${rows.length} 组调用记录，当前展示 ${visible.length} 组。每次查询最多返回 100 组。</p>`+table(['执行与模型','请求结果','供应商报告用量','累计耗时'],visible.map(r=>{
        const source=({MANUAL:'手动发起',AUTOMATIC:'自动分诊',INDEX:'资料建索引',QUERY:'检索查询',AUTOMATIC_QUERY:'后台检索'})[r.triggerSource]||r.triggerSource;
        return [`<strong>${r.capability==='EMBEDDING'?'Embedding':'文本生成'}</strong><br>${esc(r.model)}<br><small>${esc(source)} · 连接版本 ${esc(r.connectionVersion)}${scope.workspace?"<br>项目 "+esc(projects.find(p=>p.id===r.projectId)?.name||r.projectId):""}</small><details><summary>执行标识</summary><small>${esc(r.executionId)}</small></details>${scope.project&&r.runId&&r.actorId===me.userId?link(`project/${r.projectId}/agent?run=${r.runId}`,"查看分析"):""}`,`尝试 ${modelNumber(r.attempts)}<br>成功 ${modelNumber(r.successfulCalls)} · 失败 ${modelNumber(r.failedCalls)}<br>待完成 ${modelNumber(r.pendingCalls)} · 结果未知 ${modelNumber(r.unknownCalls)}<br>未发送 ${modelNumber(r.notSentCalls)}`,`输入 ${modelNumber(r.inputTokens)}<br>输出 ${r.capability==='EMBEDDING'?'不适用':modelNumber(r.outputTokens)}<br><small>用量完整 ${modelNumber(r.knownUsageCalls)} · 部分 ${modelNumber(r.partialUsageCalls)} · 未报告 ${modelNumber(r.unknownUsageCalls)}</small>`,r.elapsedMillis==null?'未报告':esc((Number(r.elapsedMillis)/1000).toLocaleString('zh-CN',{maximumFractionDigits:2})+' 秒')];
      }),'该月份暂无符合条件的调用记录。');
    }
    async function load(e){e?.preventDefault();if(loading||!f.reportValidity())return;loading=true;const submit=f.querySelector('button');submit.disabled=true;modelFeedback(root,'正在查询…');
      try{const q=new URLSearchParams({period:f.elements.period.value+'-01',limit:100});if(scope.workspace&&f.elements.project.value)q.set('project',f.elements.project.value);const data=await api((scope.workspace?`/workspaces/${scope.workspace}`:`/projects/${scope.project}`)+'/model-usage?'+q);if(!root.isConnected)return;rows=data;render();modelFeedback(root,'');}
      catch(error){if(root.isConnected)results.innerHTML='';modelFeedback(root,modelError(error),true);}finally{loading=false;if(submit.isConnected)submit.disabled=false;}
    }
    f.onsubmit=load;f.elements.capability.onchange=render;await load();
  }catch(e){modelLoadError(root,e,()=>modelUsagePage(root,scope));}
}
