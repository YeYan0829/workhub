/* The overview is a live projection of project tasks and their dependency edges. */
const overviewTypes = { BUG: "缺陷", FEATURE: "功能", QUESTION: "问题", TASK: "任务" };

function overviewLayout(nodes, edges) {
  const byId = new Map(nodes.map(n => [n.id, n]));
  const incoming = new Map(nodes.map(n => [n.id, []]));
  const outgoing = new Map(nodes.map(n => [n.id, []]));
  for (const e of edges) if (byId.has(e.blockerId) && byId.has(e.blockedId)) {
    incoming.get(e.blockedId).push(e.blockerId);
    outgoing.get(e.blockerId).push(e.blockedId);
  }
  const rank = new Map(nodes.map(n => [n.id, 0]));
  const pending = new Map(nodes.map(n => [n.id, incoming.get(n.id).length]));
  const queue = nodes.filter(n => !pending.get(n.id)).map(n => n.id);
  for (let i = 0; i < queue.length; i++) for (const next of outgoing.get(queue[i])) {
    rank.set(next, Math.max(rank.get(next), rank.get(queue[i]) + 1));
    pending.set(next, pending.get(next) - 1);
    if (!pending.get(next)) queue.push(next);
  }
  const lanes = [];
  const independent = [];
  for (const n of nodes) {
    if (!n.upstreamCount && !n.downstreamCount) independent.push(n);
    else (lanes[rank.get(n.id)] ||= []).push(n);
  }
  // Cache each lane's ordering once, so parent centering traverses each edge once.
  const order = new Map();
  for (let i = 0; i < lanes.length; i++) if (lanes[i]) {
    const centers = new Map(lanes[i].map(n => { const parents = incoming.get(n.id); return [n.id,parents.length ? parents.reduce((sum,id) => sum+(order.get(id)||0),0)/parents.length : 0]; }));
    lanes[i].sort((a,b) => centers.get(a.id)-centers.get(b.id));
    lanes[i].forEach((n,j) => order.set(n.id,j));
  }
  const positions = new Map();
  const stepX = 268, stepY = 148;
  const rows = Math.max(1, ...lanes.map(l => l?.length || 0));
  lanes.forEach((lane, i) => lane?.forEach((n, j) => positions.set(n.id, {
    x: 24 + i * stepX, y: 44 + (rows - lane.length) * stepY / 2 + j * stepY
  })));
  const cols = Math.max(1, lanes.length);
  const mainHeight = lanes.length ? rows * stepY + 44 : 0;
  independent.forEach((n, i) => positions.set(n.id, {
    x: 24 + (i % Math.max(2, Math.min(cols, 4))) * stepX,
    y: mainHeight + 52 + Math.floor(i / Math.max(2, Math.min(cols, 4))) * stepY
  }));
  const independentCols = independent.length ? Math.min(independent.length, Math.max(2, Math.min(cols, 4))) : 0;
  return { positions, incoming, outgoing,
    width: Math.max(cols, independentCols) * stepX + 16,
    height: Math.max(220, mainHeight + (independent.length ? 52 + Math.ceil(independent.length / Math.max(2, Math.min(cols, 4))) * stepY : 0)),
    independentY: independent.length ? mainHeight + 22 : null,
    hasDependencies: lanes.length > 0
  };
}

let overviewDispose = null;

async function projectOverviewPage(root, p, members, params) {
  overviewDispose?.();
  const entered = performance.now(), connectionId = crypto.randomUUID();
  root.innerHTML = '<section class="overview-loading" role="status">正在整理项目任务与依赖…</section>';
  const dirtyVersion = new Map(), previewReadVersion = new Map();
  const nodeById = new Map(), nodeDom = new Map(), edgeDom = new Map(), dirty = new Set(), cursors = new Map();
  let closeToken=null;
  let graph, layout, selected = params.get('task'), scale = 1, focusOnly = false, stopped = false;
  let viewGeneration = 0, previewGeneration = 0, searchGeneration = 0, appliedSeq = 0n, seenSeq = 0n;
  let busy = false, scopeBusy = false, syncTimer, searchTimer, retryTimer, hiddenTimer, reconcileTimer;
  let source, reconnectAttempt = 0, lastSync = '', preview, pendingPreview, previewBusy = false, streamOpen = false, lastSyncStart = 0;
  let viewportObserver, disposeObserver, searchMatches = [], selectedSearch = 0, forceRecovery = false;
  const metrics = { layouts: 0, syncs: 0, staleResponses: 0, selections: [], snapshots: 0 };
  const $m = selector => root.querySelector(selector);
  const alive = () => !stopped && root.isConnected;
  const seq = v => { try { return BigInt(v); } catch { return 0n; } };
  const base = `/projects/${p}`;
  const taskUrl = id => `task/${p}/${id}?from=${encodeURIComponent(`project/${p}/overview?task=${id}`)}`;
  const memberName = id => nodeById.get(id)?.assignee?.displayName || members.find(m => m.id === id)?.displayName || (id ? '项目成员' : '未分配');
  const ownerName = n => (n.assignee?.displayName ? n.assignee.displayName+(n.assignee.active?'':'（已离开）') : '') || (n.assigneeId ? memberName(n.assigneeId) : '未分配');
  const relation = (id, map) => {
    const found = new Set(), queue = [...(map.get(id) || [])];
    for (let i = 0; i < queue.length; i++) if (!found.has(queue[i]) && queue[i] !== id) { found.add(queue[i]); queue.push(...(map.get(queue[i]) || [])); }
    return found;
  };
  const nodeLabel = n => [n.title, overviewTypes[n.type] || '任务', stateName(n.status), ownerName(n), n.dependencyRisk ? '已完成，上游仍有未完成任务' : n.isBlocked ? '等待上游' : '无上游阻塞'].join('，');
  function status(value) { if ($m('#map-sync-status')) $m('#map-sync-status').textContent = value + (lastSync ? ` · ${lastSync}` : ''); }
  function dispose() {
    if (stopped) return; stopped = true; source?.close();
    const token=csrf||closeToken;
    if (token?.headerName) fetch(base+'/graph/events?connectionId='+connectionId,{method:'DELETE',credentials:'same-origin',headers:{[token.headerName]:token.token},keepalive:true}).catch(()=>{});
    for (const t of [syncTimer,searchTimer,retryTimer,hiddenTimer]) clearTimeout(t);
    clearInterval(reconcileTimer); viewportObserver?.disconnect(); disposeObserver?.disconnect();
    document.removeEventListener('visibilitychange', visibility); window.removeEventListener('online', online); window.removeEventListener('offline', offline); window.removeEventListener('pagehide',dispose);
    root.overviewState = null;
  }
  overviewDispose = dispose;
  function deny() {
    dispose(); nodeById.clear(); nodeDom.clear(); edgeDom.clear(); dirty.clear(); cursors.clear(); preview = pendingPreview = graph = null;
    root.innerHTML = '<section><p role="alert">请确认登录状态与项目访问权限，再重新进入项目。</p></section>';
  }
  function failed(error) { if (error.status === 401 || error.status === 403) { deny(); return true; } return false; }
  function mount() {
    root.innerHTML =
      `<div class="project-stats overview-stats">${states.map(s => `<a data-count="${s}" href="#project/${p}/tasks?status=${s}"><strong>0</strong><span>${stateName(s)}</span></a>`).join('')}</div>` +
      `<section class="project-map" aria-labelledby="project-map-title"><div class="map-heading"><div><h2 id="project-map-title">任务与依赖</h2><p class="muted">从重点工作开始，沿箭头查看后续安排。</p><span id="map-sync-status" class="map-sync-status" role="status">正在同步</span></div><button type="button" class="secondary" id="map-refresh">更新重点工作</button></div>` +
      `<div class="map-toolbar"><label class="map-search">定位项目内任务<input id="map-search" type="search" maxlength="200" placeholder="输入任务标题" autocomplete="off" aria-controls="map-search-results" aria-expanded="false"></label><label class="map-focus"><input id="map-focus" type="checkbox" disabled>聚焦所选依赖链</label><div class="map-zoom" role="group" aria-label="依赖图缩放"><button type="button" class="secondary" id="map-minus" aria-label="缩小依赖图">−</button><output id="map-scale" aria-live="polite">100%</output><button type="button" class="secondary" id="map-plus" aria-label="放大依赖图">＋</button><button type="button" class="secondary" id="map-fit">适应画布</button></div></div>` +
      `<div id="map-search-results" class="map-search-results" hidden></div><div class="map-legend">${Object.entries(overviewTypes).map(([type,name]) => `<span><i class="map-type-${type.toLowerCase()}"></i>${name}</span>`).join('')}<span>实线 → 上游未完成</span><span>虚线 ⇢ 上游已完成</span><span>高亮 → 所选任务的上下游路径</span></div>` +
      `<p class="map-scope" role="status" id="map-scope"></p><p id="map-discovery" class="map-discovery" hidden>项目有新工作，可更新重点工作查看。</p>` +
      `<div class="map-workspace"><div class="map-viewport" id="map-viewport" role="region" tabindex="0" aria-label="任务依赖图，可滚动浏览，方向键切换任务，Enter 查看任务"><div class="map-surface" id="map-surface"><div class="map-canvas" id="map-canvas"></div></div></div><aside class="map-inspector" id="map-inspector" aria-label="所选任务详情" hidden></aside></div>` +
      `<div class="map-footer"><span id="map-selection-status" role="status">选择任务后，高亮其上游依赖与下游影响。</span>${link(`project/${p}/tasks`,'打开任务列表 →')}</div></section>` +
      `<section class="overview-documents"><div class="section-heading"><h2>项目背景与约定</h2>${link(`project/${p}/documents`,'全部文档')}</div><div id="overview-documents">正在读取资料…</div></section>`;
    $m('#map-plus').onclick = () => zoom(scale + .15); $m('#map-minus').onclick = () => zoom(scale - .15); $m('#map-fit').onclick = fit;
    $m('#map-refresh').onclick = () => changeScope('initial');
    $m('#map-focus').onchange = e => { focusOnly = e.target.checked; highlight(); };
    const field = $m('#map-search');
    field.oninput = () => { searchMatches=[];const q=field.value.trim().toLocaleLowerCase();for(const [id,el] of nodeDom)el.classList.toggle('map-search-match',!!q&&nodeById.get(id).title.toLocaleLowerCase().includes(q));clearTimeout(searchTimer); searchGeneration++; searchTimer = setTimeout(search,300); };
    field.onkeydown = e => {
      if (e.key === 'Escape') { e.stopPropagation(); $m('#map-search-results').hidden = true; field.setAttribute('aria-expanded','false'); return; }
      if (e.key === 'Enter' && !e.isComposing) { e.preventDefault(); if (searchMatches.length) focusTask(searchMatches[selectedSearch].id); else { clearTimeout(searchTimer); search(true); } }
      if (['ArrowDown','ArrowUp'].includes(e.key) && searchMatches.length) { e.preventDefault(); selectedSearch = (selectedSearch + (e.key === 'ArrowDown' ? 1 : -1) + searchMatches.length) % searchMatches.length; markSearch(); }
    };
    let width = $m('#map-viewport').clientWidth;
    viewportObserver = new ResizeObserver(() => { if (!alive()) return; const next = $m('#map-viewport').clientWidth; if (next && next !== width) { width = next; zoom(scale); } });
    viewportObserver.observe($m('#map-viewport'));
    root.addEventListener('keydown',e => { if (e.key === 'Escape' && selected) { e.preventDefault(); e.stopPropagation(); closeInspector(); } });
    root.overviewState = () => ({ appliedSeq: String(appliedSeq), seenSeq: String(seenSeq), dirty: [...dirty], generation: viewGeneration, selected, scale, metrics: structuredClone(metrics), nodes: [...nodeById.keys()] });
    disposeObserver = new MutationObserver(() => { if (!root.isConnected) dispose(); }); disposeObserver.observe(root.parentNode,{childList:true});
    loadDocuments();
  }
  async function loadDocuments() {
    const target = $m('#overview-documents');
    try { const docs = await api(base + '/documents?limit=5'); if (alive() && target.isConnected) target.innerHTML = table(['文档'],docs.map(d => [link(`document/${p}/${d.id}`,d.title)]),'在文档页记录项目背景与协作约定。'); }
    catch (e) { if (failed(e)) return; if (alive()) target.innerHTML = `<p>资料暂未加载。${link(`project/${p}/documents`,'打开文档页')}</p>`; }
  }
  async function refreshProject() {
    try { const value=await api(base);if(!alive())return;
      const heading=app.querySelector('h1');if(heading)heading.innerHTML=esc(value.name)+(value.archived?' <span class="badge">已归档 · 只读</span>':'');
      if(contextProject?.id===p){contextProject.name=value.name;contextProject.archived=value.archived;}
      const description=app.querySelector('.project-description');if(description)description.textContent=value.description||'';
    }catch(e){failed(e);}
  }
  function counters() {
    for (const s of states) $m(`[data-count="${s}"] strong`).textContent = graph.counts[s] || 0;
    $m('#map-scope').textContent = `当前展示 ${nodeById.size} / ${graph.totalTasks} 项任务、${graph.edges.length} / ${graph.totalEdges} 条依赖。` +
      (graph.scope?.edgePartial ? '当前节点间仍有关系省略，可聚焦任务查看。' : '') + (nodeById.size < graph.totalTasks ? '可搜索全项目，或从所选任务展开图外关系。' : '');
  }
  function reindex() {
    layout.incoming = new Map([...nodeById.keys()].map(id => [id,[]])); layout.outgoing = new Map([...nodeById.keys()].map(id => [id,[]]));
    for (const e of graph.edges) if (nodeById.has(e.blockerId) && nodeById.has(e.blockedId)) { layout.incoming.get(e.blockedId).push(e.blockerId); layout.outgoing.get(e.blockerId).push(e.blockedId); }
  }
  function patchNode(n) {
    let el = nodeDom.get(n.id);
    if (!el) {
      el = document.createElement('button'); el.type = 'button'; el.dataset.mapTask = n.id; nodeDom.set(n.id,el); $m('#map-canvas').append(el);
      el.onclick = () => selectNode(n.id,true);
      el.onkeydown = e => {
        if (!['ArrowRight','ArrowDown','ArrowLeft','ArrowUp','Home','End'].includes(e.key)) return;
        e.preventDefault(); const buttons = [...nodeDom.values()].filter(b => !b.hidden), i = buttons.indexOf(el);
        const next = e.key === 'Home' ? 0 : e.key === 'End' ? buttons.length - 1 : (i + (['ArrowRight','ArrowDown'].includes(e.key) ? 1 : -1) + buttons.length) % buttons.length;
        buttons.forEach(b => b.tabIndex = -1); buttons[next].tabIndex = 0; buttons[next].focus();
      };
    }
    const omitted = Math.max(0,n.upstreamCount - (layout.incoming.get(n.id)?.length || 0)) + Math.max(0,n.downstreamCount - (layout.outgoing.get(n.id)?.length || 0));
    const html = `<span class="map-node-top"><span class="map-node-type">${esc(overviewTypes[n.type] || '任务')}</span><span class="map-node-status">${esc(stateName(n.status))}</span></span><strong>${esc(n.title)}</strong><span class="map-node-bottom"><span>${esc(ownerName(n))}</span><span>${n.origin === 'GITHUB' ? 'GitHub · ' : ''}${n.dependencyRisk ? '依赖有风险' : n.isBlocked ? '等待上游' : ['DONE','CANCELED'].includes(n.status) ? '已结束' : n.triageRequired ? '待确认安排' : '可推进'}</span></span>${omitted ? `<span class="map-node-omitted">另有 ${omitted} 条关系在图外</span>` : ''}`;
    if (el.innerHTML !== html) el.innerHTML = html;
    el.classList.add('map-node'); for (const type of Object.keys(overviewTypes)) el.classList.toggle('map-type-' + type.toLowerCase(),n.type === type);
    el.classList.toggle('map-node-finished',['DONE','CANCELED'].includes(n.status)); el.setAttribute('aria-label',nodeLabel(n));
  }
  function drawTopology(preserve) {
    const canvas = $m('#map-canvas'); metrics.layouts++;
    const next = overviewLayout([...nodeById.values()],graph.edges);
    if (preserve && layout) {
      const occupied = [];
      for (const [id,pos] of layout.positions) if (nodeById.has(id)) { next.positions.set(id,pos); occupied.push(pos); }
      for (const [id,pos] of next.positions) if (!layout.positions.has(id)) {
        while (occupied.some(v => Math.abs(v.x-pos.x) < 240 && Math.abs(v.y-pos.y) < 135)) pos.y += 148;
        occupied.push(pos);
      }
      next.width = Math.max(next.width,...[...next.positions.values()].map(pos => pos.x+244));
      next.height = Math.max(next.height,...[...next.positions.values()].map(pos => pos.y+148));
    }
    layout = next; reindex();
    for (const [id,el] of nodeDom) if (!nodeById.has(id)) { el.remove(); nodeDom.delete(id); }
    canvas.querySelector('.map-empty')?.remove();
    if (!nodeById.size) {
      canvas.innerHTML = `<div class="map-empty"><strong>从第一项工作开始</strong><p>创建任务并关联上游工作，这里会呈现项目的依赖关系。</p>${link(`project/${p}/tasks`,'创建任务 →')}</div>`;
      canvas.style.width = '100%'; canvas.style.height = '260px'; $m('#map-surface').style.cssText = 'width:100%;height:260px'; $m('#map-viewport').style.height = '260px';
      for (const id of ['map-minus','map-plus','map-fit']) $m('#'+id).disabled = true; return;
    }
    let svg = canvas.querySelector('svg');
    if (!svg) { canvas.insertAdjacentHTML('afterbegin','<svg class="map-edges" aria-hidden="true"><defs><marker id="map-arrow" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto" markerUnits="strokeWidth"><path d="M0,0 L0,6 L6,3 z" fill="context-stroke"/></marker></defs></svg>'); svg = canvas.querySelector('svg'); }
    svg.setAttribute('width',layout.width); svg.setAttribute('height',layout.height); canvas.style.width = layout.width+'px'; canvas.style.height = layout.height+'px';
    const keys = new Set();
    graph.edges.forEach((e,i) => {
      const key = e.blockerId+':'+e.blockedId; keys.add(key); let path = edgeDom.get(key);
      if (!path) { path = document.createElementNS('http://www.w3.org/2000/svg','path'); path.classList.add('map-edge'); path.setAttribute('marker-end','url(#map-arrow)'); edgeDom.set(key,path); svg.append(path); }
      const a = layout.positions.get(e.blockerId), b = layout.positions.get(e.blockedId); if (!a || !b) return;
      const x1=a.x+216,y1=a.y+58,x2=b.x-5,y2=b.y+58,routeY=25+(i%4)*4;
      path.setAttribute('d',b.x-a.x>280 ? `M ${x1} ${y1} H ${x1+16} V ${routeY} H ${x2-14} V ${y2} H ${x2}` : `M ${x1} ${y1} C ${x1+26} ${y1},${x2-26} ${y2},${x2} ${y2}`);
    });
    for (const [key,path] of edgeDom) if (!keys.has(key)) { path.remove(); edgeDom.delete(key); }
    for (const n of nodeById.values()) { patchNode(n); const pos = layout.positions.get(n.id); nodeDom.get(n.id).style.cssText = `left:${pos.x}px;top:${pos.y}px`; }
    highlight(); zoom(scale);
  }
  function highlight() {
    if (!layout) return;
    const before = selected ? relation(selected,layout.incoming) : new Set(), after = selected ? relation(selected,layout.outgoing) : new Set();
    const active = selected ? new Set([selected,...before,...after]) : null;
    for (const [id,el] of nodeDom) { el.classList.toggle('map-node-muted',!!active && !active.has(id)); el.hidden = focusOnly && !active?.has(id); el.setAttribute('aria-pressed',String(id === selected)); el.tabIndex = id === (selected || nodeById.keys().next().value) ? 0 : -1; }
    for (const e of graph.edges) {
      const path = edgeDom.get(e.blockerId+':'+e.blockedId); if (!path) continue;
      const lit = active?.has(e.blockerId) && active?.has(e.blockedId);
      path.classList.toggle('map-edge-active',!!lit); path.classList.toggle('map-edge-muted',!!active && !lit); path.classList.toggle('map-edge-complete',nodeById.get(e.blockerId)?.status === 'DONE'); path.style.display = focusOnly && !lit ? 'none' : '';
    }
    if (selected) $m('#map-selection-status').textContent = `已选中：${nodeById.get(selected)?.title || '任务'}。上游 ${before.size} 项，下游 ${after.size} 项。`;
  }
  function zoom(value) {
    scale=Math.max(.45,Math.min(1.6,value)); if (!nodeById.size) return;
    const canvas=$m('#map-canvas'), surface=$m('#map-surface'); canvas.style.transform=`scale(${scale})`;
    surface.style.width=parseFloat(canvas.style.width)*scale+'px'; surface.style.height=parseFloat(canvas.style.height)*scale+'px';
    const h=window.innerWidth<600 ? 400 : window.innerWidth<900 ? 440 : Math.max(420,Math.min(720,parseFloat(canvas.style.height)*scale+16));
    $m('#map-viewport').style.height=h+'px'; $m('.map-workspace').style.setProperty('--map-height',h+'px'); $m('#map-scale').value=Math.round(scale*100)+'%';
    $m('#map-minus').disabled=scale<=.45; $m('#map-plus').disabled=scale>=1.6;
  }
  function fit() { if (nodeById.size) zoom(Math.max(window.innerWidth<700?.85:.7,Math.min(1,($m('#map-viewport').clientWidth-20)/parseFloat($m('#map-canvas').style.width)))); }
  function applySnapshot(data,preserve) {
    graph=data; nodeById.clear(); for (const n of data.nodes) nodeById.set(n.id,n); metrics.snapshots++;
    if (selected && !nodeById.has(selected)) closeInspector();
    drawTopology(preserve); counters(); appliedSeq=seq(data.viewSeq); seenSeq=seenSeq>appliedSeq?seenSeq:appliedSeq;
    if (selected) { dirty.add(selected); loadPreview(false); }
    lastSync=new Date().toLocaleTimeString('zh-CN',{hour12:false}); status(streamOpen?'已同步':'轮询同步');
    root.dispatchEvent(new CustomEvent('overview.applied',{bubbles:true,detail:{project:p,seq:String(appliedSeq)}}));
  }
  async function changeScope(kind,expand) {
    if (scopeBusy || !alive()) return;
    scopeBusy=true;let retryExpansion=null; const token=++viewGeneration; status('正在同步'); $m('#map-refresh').disabled=true;
    try {
      const data=kind==='initial' ? await api(base+'/graph?view=overview') : kind==='focus' ? await api(base+'/graph?view=focus&focusTaskId='+expand) : await api(base+'/graph/view','POST',{nodeIds:[...nodeById.keys()],...(expand?{expand}:{})});
      if (!alive() || token!==viewGeneration) { metrics.staleResponses++; return; }
      if(kind==='initial'||kind==='focus')cursors.clear();
      applySnapshot(data,kind!=='initial'&&kind!=='focus');
      if (expand && kind!=='focus') cursors.set(expand.taskId+':'+expand.direction,data.expansion?.nextCursor || null);
      if (kind==='initial') { $m('#map-discovery').hidden=true; fit(); }
      if (kind==='focus') { selected=expand; selectNode(expand,false); fit(); }
    } catch(e) {
      if (failed(e)) return;
      if (e.code==='GRAPH_CURSOR_STALE' && expand) { cursors.delete(expand.taskId+':'+expand.direction);retryExpansion={...expand,cursor:null}; $m('#map-scope').textContent='依赖已变化，正在从这一方向的第一页补齐。'; }
      else $m('#map-scope').textContent='读取失败，当前保留上次读取的项目现状。请重新刷新或展开。';
    } finally { scopeBusy=false; if (alive()) { $m('#map-refresh').disabled=false; updateRelations(); if(retryExpansion)changeScope('view',retryExpansion);else scheduleSync(0); } }
  }
  async function search(focusFirst=false) {
    const field=$m('#map-search'), token=++searchGeneration, q=field.value.trim(), area=$m('#map-search-results');
    if (!q) { area.hidden=true; field.setAttribute('aria-expanded','false'); return; }
    area.hidden=false; area.textContent='正在查找全项目任务…'; field.setAttribute('aria-expanded','true');
    try {
      const result=await api(base+'/task-search?projection=overview&limit=20&q='+encodeURIComponent(q));
      if (!alive() || token!==searchGeneration) return;
      searchMatches=result.items; selectedSearch=0;
      area.innerHTML=searchMatches.length ? searchMatches.map((n,i)=>`<button type="button" class="secondary" data-search-index="${i}">${esc(n.title)} · ${esc(stateName(n.status))}</button>`).join('') : '<p>没有找到匹配任务，请调整标题关键词。</p>';
      area.querySelectorAll('button').forEach(b=>{b.onclick=()=>focusTask(searchMatches[+b.dataset.searchIndex].id);}); markSearch();if(focusFirst&&searchMatches.length)focusTask(searchMatches[0].id);
    } catch(e) { if (!failed(e) && alive() && token===searchGeneration) area.textContent='查找失败，请重新输入后重试。'; }
  }
  function markSearch() { $m('#map-search-results').querySelectorAll('button').forEach((b,i)=>b.classList.toggle('map-search-current',i===selectedSearch)); }
  async function focusTask(id) {
    $m('#map-search-results').hidden=true; $m('#map-search').setAttribute('aria-expanded','false');
    if (nodeById.has(id)) { selectNode(id,false); nodeDom.get(id)?.scrollIntoView({block:'nearest',inline:'center'}); }
    else await changeScope('focus',id);
  }
  function selectNode(id,fromClick) {
    if (!nodeById.has(id)) return;
    const start=performance.now(); if (projectAssistant?.open) setAssistantOpen(false);
    if (selected!==id) { preview=null; pendingPreview=null; previewGeneration++; }
    selected=id; $m('#map-focus').disabled=false; $m('#map-inspector').hidden=false;
    history.replaceState(null,'',`#project/${p}/overview?task=${id}`); highlight();
    if (fromClick) { nodeDom.get(id)?.focus({preventScroll:true}); if(innerWidth>=900)nodeDom.get(id)?.scrollIntoView({block:'nearest',inline:'nearest'}); }
    requestAnimationFrame(()=>{ metrics.selections.push(performance.now()-start); root.dispatchEvent(new CustomEvent('overview.selected',{bubbles:true,detail:{id,ms:performance.now()-start}})); });
    if (!preview) $m('#map-inspector').innerHTML='<p role="status">正在读取任务与讨论…</p>';
    loadPreview(true);
    if (fromClick && innerWidth<900) $m('#map-inspector').scrollIntoView({block:'start',behavior:'smooth'});
  }
  function updateRelations() {
    const area=$m('#map-relations'); if (!area || !selected) return;
    const n=nodeById.get(selected);if(!n)return;
    const signature=JSON.stringify([selected,n.upstreamCount,n.downstreamCount,scopeBusy,nodeById.size,[...layout.incoming.get(selected)||[],...layout.outgoing.get(selected)||[]].map(id=>[id,nodeById.get(id)?.title]),cursors.get(selected+':UPSTREAM'),cursors.get(selected+':DOWNSTREAM')]);
    if(area.overviewSignature===signature)return;area.overviewSignature=signature;
    const active=document.activeElement;const restore=area.contains(active)?active.dataset.expand?'[data-expand="'+active.dataset.expand+'"]':active.dataset.mapSelect?'[data-map-select="'+active.dataset.mapSelect+'"]':active.id?'#'+active.id:null:null;
    area.innerHTML=['UPSTREAM','DOWNSTREAM'].map(direction=>{
      const up=direction==='UPSTREAM', ids=(up?layout.incoming:layout.outgoing).get(selected)||[],total=up?n.upstreamCount:n.downstreamCount;
      return `<h4>${up?'上游依赖':'下游工作'} <span>${total}</span></h4><ul class="map-related">${ids.slice(0,20).map(id=>`<li><button type="button" class="map-related-task" data-map-select="${id}">${esc(nodeById.get(id)?.title)}</button></li>`).join('')}</ul><button type="button" class="secondary" data-expand="${direction}">${cursors.get(selected+':'+direction)?'继续展开':'展开'}${up?'上游':'下游'} · 已显示 ${ids.length}/${total}</button>`;
    }).join('') + (nodeById.size>=200?'<p class="muted">当前范围已达200项，可定位所选任务以收起其他分支。</p>':'') + '<button type="button" class="secondary" id="map-focus-task">只看这一任务周边</button>';
    area.querySelectorAll('[data-expand]').forEach(b=>{b.disabled=scopeBusy||nodeById.size>=200;b.onclick=()=>changeScope('view',{taskId:selected,direction:b.dataset.expand,cursor:cursors.get(selected+':'+b.dataset.expand)||null,limit:20});});
    area.querySelectorAll('[data-map-select]').forEach(b=>b.onclick=()=>selectNode(b.dataset.mapSelect,false));
    area.querySelector('#map-focus-task').onclick=()=>changeScope('focus',selected);if(restore)area.querySelector(restore)?.focus({preventScroll:true});
  }
  function renderPreview(data,keepReading) {
    const panel=$m('#map-inspector'),t=data.task,scroll=panel.scrollTop;
    if (!panel.querySelector('#map-preview-main')) {
      panel.innerHTML='<div class="map-inspector-heading"><span class="map-eyebrow">任务详情</span><button type="button" class="secondary" id="map-close" aria-label="收起任务详情">×</button></div><div id="map-preview-main"></div><div class="section-heading"><h4>最近讨论</h4><span id="map-comments-link"></span></div><div id="map-discussion-notice" role="status"></div><div id="map-discussions"></div><div id="map-relations"></div>';
      panel.querySelector('#map-close').onclick=closeInspector;
    }
    const main=panel.querySelector('#map-preview-main');const markup=`<h3>${esc(t.title)}</h3><div class="map-inspector-tags"><span class="badge">${esc(stateName(t.status))}</span><span class="badge">${t.isBlocked?'等待上游':'无上游阻塞'}</span></div><dl class="map-task-meta"><div><dt>负责人</dt><dd>${esc(ownerName(t))}</dd></div><div><dt>优先级</dt><dd>${t.triageRequired?'待确认':'P'+t.priority}</dd></div><div><dt>截止日期</dt><dd>${esc(t.dueDate||'未设置')}</dd></div></dl><p class="map-description">${esc(t.description||'在任务详情中补充工作范围与完成条件。')}${t.descriptionTruncated?'…':''}</p><div class="map-detail-links">${link(taskUrl(t.id),'打开完整任务 →')}${link(`project/${p}/agent?${new URLSearchParams({question:`任务「${t.title}」（ID：${t.id}）有哪些上游阻塞和下游影响？请结合讨论给出下一步建议。`})}`,'询问项目助手')}</div>`;
    if(main.overviewMarkup!==markup){main.innerHTML=markup;main.overviewMarkup=markup;}
    panel.querySelector('#map-comments-link').innerHTML=link(taskUrl(t.id)+'&section=comments','全部讨论');
    if (!keepReading) renderComments(data);
    updateRelations(); panel.scrollTop=scroll;
  }
  function renderComments(data) {
    $m('#map-discussions').innerHTML=data.comments.length ? data.comments.map(c=>`<article class="map-discussion" tabindex="-1"><p><strong>${esc(c.authorDisplayName||'项目成员')}</strong><time>${esc(timeLabel(c.createdAt))}</time></p><p>${esc(c.body)}${c.truncated?'…':''}</p>${link(taskUrl(data.task.id)+'&comment='+encodeURIComponent(c.id),'查看这条讨论')}</article>`).join('') : '<p class="muted">还没有讨论。进入任务详情记录进展或提出问题。</p>';
    $m('#map-discussion-notice').innerHTML=''; preview=data; pendingPreview=null; if(previewReadVersion.get(data.task.id)===dirtyVersion.get(data.task.id))dirty.delete(data.task.id);
  }
  async function loadPreview(explicit=false) {
    if (!selected || !alive() || previewBusy) return;
    if (!explicit && pendingPreview && previewReadVersion.get(selected) === dirtyVersion.get(selected)) return;
    const id=selected,token=++previewGeneration,range=viewGeneration,obligation=dirtyVersion.get(selected);previewBusy=true;
    try {
      const data=await api(base+`/tasks/${id}/preview?commentLimit=5`);
      if (!alive() || selected!==id || token!==previewGeneration || range!==viewGeneration) {metrics.staleResponses++;return;}
      if (preview && (data.task.revision<preview.task.revision || data.task.discussionRevision<preview.task.discussionRevision)) return;
      if(data.task.assignee)for(const n of nodeById.values())if(n.assigneeId===data.task.assignee.id&&(n.assignee?.displayName!==data.task.assignee.displayName||n.assignee?.active!==data.task.assignee.active)){n.assignee=data.task.assignee;patchNode(n);}
      previewReadVersion.set(id,obligation);
      const changed=preview && (data.task.discussionRevision!==preview.task.discussionRevision || JSON.stringify(data.comments)!==JSON.stringify(preview.comments));
      const keep=!!changed && !explicit;
      renderPreview(data,keep);
      if (keep) { pendingPreview=data; $m('#map-discussion-notice').innerHTML='<button type="button" class="secondary" id="map-new-discussion">有新讨论，更新最近讨论</button>'; $m('#map-new-discussion').onclick=()=>{renderComments(pendingPreview);const first=$m('#map-discussions .map-discussion');first?.focus({preventScroll:true});first?.scrollIntoView({block:'nearest'});}; }
      else { preview=data; if(dirtyVersion.get(id)===obligation)dirty.delete(id); }
    } catch(e) {
      if(failed(e))return;
      if (!alive() || selected!==id || token!==previewGeneration) return;
      dirty.add(id);const panel=$m('#map-inspector');
      if (!preview) panel.innerHTML='<p role="alert">任务详情暂未加载，请重试。</p><button type="button" id="map-detail-retry">重新读取</button><button type="button" class="secondary" id="map-close">收起详情</button>';
      else $m('#map-discussion-notice').innerHTML='<p role="status">新进展暂未读取，已保留当前内容。</p><button type="button" id="map-detail-retry">重新读取</button>';
      panel.querySelector('#map-detail-retry').onclick=()=>loadPreview(true); if(panel.querySelector('#map-close'))panel.querySelector('#map-close').onclick=closeInspector;
    } finally { previewBusy=false;if(alive()&&selected){if(selected!==id||token!==previewGeneration)loadPreview(true);else if(range!==viewGeneration||dirtyVersion.get(id)!==obligation)loadPreview(false);} }
  }
  function closeInspector() {
    const previous=selected;selected=null;preview=pendingPreview=null;previewGeneration++;focusOnly=false;
    if(!$m('#map-inspector'))return;$m('#map-inspector').hidden=true;$m('#map-inspector').innerHTML='';$m('#map-focus').checked=false;$m('#map-focus').disabled=true;
    history.replaceState(null,'',`#project/${p}/overview`);highlight();$m('#map-selection-status').textContent='选择任务后，高亮其上游依赖与下游影响。';nodeDom.get(previous)?.focus({preventScroll:true});
  }
  function scheduleSync(delay=200) { if (!alive() || syncTimer) return; syncTimer=setTimeout(()=>{syncTimer=null;sync();},Math.max(delay,lastSyncStart+500-performance.now())); }
  async function sync() {
    if (!alive() || busy || scopeBusy || (seenSeq<=appliedSeq && !forceRecovery)) return;
    const token=viewGeneration,ids=[...nodeById.keys()],from=appliedSeq;busy=true;lastSyncStart=performance.now();metrics.syncs++;status('正在同步');
    try {
      if(forceRecovery){forceRecovery=false;await changeScope('view');return;}
      const d=await api(base+'/graph/sync','POST',{afterSeq:String(from),nodeIds:ids,selectedTaskId:selected});
      if(!alive()||token!==viewGeneration||from!==appliedSeq){metrics.staleResponses++;return;}
      let topology=false;
      for(const id of d.nodesRemove){nodeById.delete(id);topology=true;}
      for(const n of d.nodesUpsert){if(!nodeById.has(n.id))topology=true;nodeById.set(n.id,n);}
      if(d.edgesReplace!==null){topology ||= JSON.stringify(graph.edges)!==JSON.stringify(d.edgesReplace);graph.edges=d.edgesReplace;}
      graph.counts=d.counts;graph.totalTasks=d.totalTasks;graph.totalEdges=d.totalEdges;
      if(d.edgesReplace!==null)graph.scope.edgePartial=d.edgePartial;
      if(topology)drawTopology(true);else{for(const n of d.nodesUpsert)patchNode(n);highlight();}
      for(const id of d.previewDirtyTaskIds){dirty.add(id);dirtyVersion.set(id,d.toSeq);}
      if(selected&&!nodeById.has(selected))closeInspector();
      counters();if(d.discoveryChanged)$m('#map-discovery').hidden=false;if(d.documentsDirty)loadDocuments();if(d.projectDirty)refreshProject();
      appliedSeq=seq(d.toSeq);lastSync=new Date().toLocaleTimeString('zh-CN',{hour12:false});status(streamOpen?'已同步':'轮询同步');
      if(selected&&dirty.has(selected))loadPreview(false);updateRelations();
      root.dispatchEvent(new CustomEvent('overview.applied',{bubbles:true,detail:{project:p,seq:String(appliedSeq)}}));
    }catch(e){if(failed(e))return;if(e.code==='GRAPH_RESYNC_REQUIRED')forceRecovery=true;status('连接中断，正在补齐');}
    finally{busy=false;if(alive()&&(seenSeq>appliedSeq||forceRecovery))scheduleSync(500);}
  }
  async function reconcile(fresh=false) {
    if(!alive()||document.hidden||scopeBusy)return;
    try{const state=await api(base+'/graph/state','GET',undefined,fresh?{'Cache-Control':'no-cache'}:{});if(!alive())return;seenSeq=seenSeq>seq(state.viewSeq)?seenSeq:seq(state.viewSeq);if(appliedSeq<seq(state.retainedAfterSeq)||appliedSeq>seq(state.viewSeq))forceRecovery=true;scheduleSync(0);if(selected&&dirty.has(selected)&&!pendingPreview)loadPreview(false);}
    catch(e){if(!failed(e))status('连接中断');}
  }
  function connect() {
    if(!alive()||document.hidden)return;source?.close();streamOpen=false;status('连接中');
    source=new EventSource(base+'/graph/events?after='+String(appliedSeq)+'&connectionId='+connectionId);
    const changed=e=>{try{const d=JSON.parse(e.data);seenSeq=seenSeq>seq(d.latestSeq)?seenSeq:seq(d.latestSeq);scheduleSync();}catch{reconcile();}};
    source.addEventListener('project.changed',changed);source.addEventListener('project.reset',e=>{changed(e);forceRecovery=true;scheduleSync(0);});
    source.addEventListener('access.revoked',deny);
    source.onopen=()=>{streamOpen=true;reconnectAttempt=0;status(seenSeq>appliedSeq?'正在同步':'已同步');reconcile(true);};
    source.onerror=()=>{source?.close();streamOpen=false;if(!alive())return;status(navigator.onLine?'轮询同步':'连接中断');clearTimeout(retryTimer);retryTimer=setTimeout(connect,Math.min(5000,1000*2**Math.min(reconnectAttempt++,3))*(1+Math.random()*.1));reconcile();};
  }
  function visibility(){clearTimeout(hiddenTimer);if(document.hidden)hiddenTimer=setTimeout(()=>{source?.close();streamOpen=false;},30000);else{reconcile(true);connect();}}
  function online(){reconcile(true);connect();}function offline(){source?.close();streamOpen=false;status('连接中断');}
  try {
    const focus=selected?'&view=focus&focusTaskId='+selected:'&view=overview';
    const data=await api(base+'/graph?'+focus.slice(1));if(!alive())return;
    mount();api('/session/csrf').then(token=>{closeToken=token;}).catch(()=>{});applySnapshot(data,false);fit();if(selected)selectNode(selected,false);
    requestAnimationFrame(()=>{if(alive()){metrics.interactiveMs=performance.now()-entered;root.dispatchEvent(new CustomEvent('overview.interactive',{bubbles:true,detail:{ms:metrics.interactiveMs}}));}});
    document.addEventListener('visibilitychange',visibility);window.addEventListener('online',online);window.addEventListener('offline',offline);window.addEventListener('pagehide',dispose);
    let nextReconcile=Date.now()+30000;
    reconcileTimer=setInterval(()=>{if(!alive()){dispose();return;}if(!streamOpen||Date.now()>=nextReconcile){nextReconcile=Date.now()+30000;reconcile();}},5000);
    connect();
  }catch(e){if(failed(e))return;root.innerHTML='<section><p role="alert">项目现状暂未加载，请重试。</p><button type="button" id="map-retry">重新加载</button></section>';$m('#map-retry').onclick=()=>projectOverviewPage(root,p,members,params);}
}
