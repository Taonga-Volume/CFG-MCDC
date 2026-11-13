// analyser.js — full logic for the analyser page

// ── State ────────────────────────────────────────────────────────────────────
let state = { file: null, scale: 1, tx: 0, ty: 0, dragging: false, mx: 0, my: 0, highlighted: null, analysed: false };

// ── File handling ─────────────────────────────────────────────────────────────
function onDragOver(e) { e.preventDefault(); document.getElementById('dropzone').classList.add('drag'); }
function onDrop(e) { e.preventDefault(); document.getElementById('dropzone').classList.remove('drag'); const f = e.dataTransfer.files[0]; if (f) loadFile(f); }
function onFileSelect(e) { const f = e.target.files[0]; if (f) loadFile(f); }

function loadFile(file) {
  if (!file.name.endsWith('.java')) { showError('Only .java files are accepted.'); return; }
  if (file.size > 1048576) { showError('File too large. Maximum size is 1 MB.'); return; }
  state.file = file;
  const reader = new FileReader();
  reader.onload = (e) => {
    document.getElementById('codeInput').value = e.target.result;
    updateLineNumbers();
    showFileBadge(file.name, (file.size / 1024).toFixed(1) + ' KB');
    hideError();
  };
  reader.readAsText(file);
}

function showFileBadge(name, meta) {
  document.getElementById('fileBadge').style.display = 'flex';
  document.getElementById('fileName').textContent = name;
  document.getElementById('fileMeta').textContent = meta + ' · ready to analyse';
  document.getElementById('dropzone').style.display = 'none';
  setStatus('Ready', 'tag-purple');
}

function clearFile() {
  state.file = null;
  document.getElementById('fileBadge').style.display = 'none';
  document.getElementById('dropzone').style.display = 'block';
  document.getElementById('fileIn').value = '';
  setStatus('Ready', 'tag-purple');
}

// ── Analysis ──────────────────────────────────────────────────────────────────
function runAnalysis() {
  const code = document.getElementById('codeInput').value.trim();
  if (!code) { showError('Please upload a .java file or paste Java code first.'); return; }
  hideError();
  setLoading(true);
  setStatus('Analysing...', 'tag-amber');

  fetch('/api/analyse', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sourceCode: code })
  })
    .then(r => {
      console.log('Response status:', r.status);
      return r.json();
    })
    .then(data => {
      console.log('Backend data:', JSON.stringify(data));
      setLoading(false);
      if (data.error) {
        showError(data.error);
        setStatus('Error', 'tag-red');
        return;
      }
      if (!data.testCases || !data.cfg) {
        showError('Unexpected response from backend.');
        setStatus('Error', 'tag-red');
        return;
      }
      state.analysed = true;
      state._lastData = data;
      renderCFG(data.cfg);
      renderResults(data);
      setStatus('Done', 'tag-green');
    })
    .catch((err) => {
      console.error('Fetch error:', err);
      setLoading(false);
      showError('Could not connect to the backend. Make sure Spring Boot is running on port 8080.');
      setStatus('Error', 'tag-red');
    });
}

function setLoading(on) {
  const btn = document.getElementById('analyseBtn');
  btn.classList.toggle('loading', on);
  btn.disabled = on;
}

function setStatus(text, cls) {
  const t = document.getElementById('statusTag');
  t.textContent = text;
  t.className = 'tag ' + cls;
}

function resetAll() {
  clearFile();
  document.getElementById('codeInput').value = '';
  updateLineNumbers();
  hideError();
  state = { ...state, scale: 1, tx: 0, ty: 0, analysed: false, highlighted: null };
  state._lastData = null;
  state._lastCfg  = null;
  document.getElementById('cfgEmptyState').style.display = 'flex';
  document.getElementById('cfgSvg').style.display = 'none';
  document.getElementById('cfgSvg').innerHTML = '';
  document.getElementById('cfgInfo').textContent = 'No file loaded';
  document.getElementById('resultsBody').innerHTML = `
    <div class="results-empty">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.2" width="28" height="28">
        <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
        <polyline points="14 2 14 8 20 8"/>
      </svg>
      <div style="font-size:13px;font-weight:500;color:var(--text-secondary)">No results yet</div>
      <div style="font-size:12px">Generate to see MC/DC test cases</div>
    </div>`;
  document.getElementById('tcCount').style.display = 'none';
  setStatus('Ready', 'tag-purple');
}

// ── CFG Rendering ─────────────────────────────────────────────────────────────
function nodeStyle(type) {
  const m = {
    entry:    { fill: '#00d4a8', text: '#003d30', rx: 999 },
    exit:     { fill: '#00d4a8', text: '#003d30', rx: 999 },
    decision: { fill: '#ffb347', text: '#3d2800', rx: 6 },
    stmt:     { fill: '#3a3938', text: '#c0bebb', rx: 6 },
    merge:    { fill: '#6c63ff', text: '#1a1640', rx: 8 },
  };
  return m[type] || m.stmt;
}

function renderCFG(cfg, highlightNode) {
  const canvas = document.getElementById('cfgCanvas');
  const W = canvas.offsetWidth || 560;
  const H = canvas.offsetHeight || 480;
  const svg = document.getElementById('cfgSvg');
  svg.style.display = 'block';
  document.getElementById('cfgEmptyState').style.display = 'none';

  const nodeMap = {};
  cfg.nodes.forEach(n => nodeMap[n.id] = n);

  const cx = W / 2 + state.tx;
  const cy = H / 2 + state.ty;

  let markup = `<defs>
    <marker id="arrT" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
      <path d="M2 1L8 5L2 9" fill="none" stroke="#00d4a8" stroke-width="1.5"/>
    </marker>
    <marker id="arrF" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
      <path d="M2 1L8 5L2 9" fill="none" stroke="#ff5f5f" stroke-width="1.5"/>
    </marker>
    <marker id="arrN" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
      <path d="M2 1L8 5L2 9" fill="none" stroke="#4a4947" stroke-width="1.5"/>
    </marker>
  </defs>
  <g transform="translate(${cx},${cy}) scale(${state.scale})">`;

  // Edges
  cfg.edges.forEach(edge => {
    const s = nodeMap[edge.from], t = nodeMap[edge.to];
    if (!s || !t) return;
    const isT = edge.label === 'true', isF = edge.label === 'false';
    const color  = isT ? '#00d4a8' : isF ? '#ff5f5f' : '#4a4947';
    const marker = isT ? 'arrT'    : isF ? 'arrF'    : 'arrN';
    const sy  = (s.type === 'entry' || s.type === 'exit') ? s.y + 16 : s.y + 17;
    const ty2 = (t.type === 'entry' || t.type === 'exit') ? t.y - 16 : t.y - 17;
    markup += `<line x1="${s.x}" y1="${sy}" x2="${t.x}" y2="${ty2}" stroke="${color}" stroke-width="1.2" marker-end="url(#${marker})"/>`;
    if (edge.label) {
      const mx = (s.x + t.x) / 2 + 6, my = (s.y + t.y) / 2;
      markup += `<text x="${mx}" y="${my}" font-size="10" fill="${color}" font-family="Space Grotesk,sans-serif" font-weight="600">${edge.label}</text>`;
    }
  });

  // Nodes
  cfg.nodes.forEach(node => {
    const ns  = nodeStyle(node.type);
    const isHL = highlightNode && node.id === highlightNode;
    const fill   = isHL ? '#6c63ff' : ns.fill;
    const textC  = isHL ? '#fff'    : ns.text;
    const w      = Math.max(node.label.length * 7.8 + 24, 84);
    const h      = 34;
    const stroke = isHL ? '#9d96ff' : 'none';
    const sw     = isHL ? 2 : 0;

    if (node.type === 'entry' || node.type === 'exit') {
      markup += `<g style="cursor:pointer" onclick="onNodeClick('${node.id}')">
        <ellipse cx="${node.x}" cy="${node.y}" rx="${w/2}" ry="16" fill="${fill}" stroke="${stroke}" stroke-width="${sw}"/>
        <text x="${node.x}" y="${node.y}" text-anchor="middle" dominant-baseline="central" font-size="11" font-weight="600" fill="${textC}" font-family="Space Grotesk,sans-serif">${node.label}</text>
      </g>`;
    } else {
      markup += `<g style="cursor:pointer" onclick="onNodeClick('${node.id}')">
        <rect x="${node.x - w/2}" y="${node.y - h/2}" width="${w}" height="${h}" rx="${ns.rx}" fill="${fill}" stroke="${stroke}" stroke-width="${sw}"/>
        <text x="${node.x}" y="${node.y}" text-anchor="middle" dominant-baseline="central" font-size="11" font-weight="600" fill="${textC}" font-family="Space Grotesk,sans-serif">${node.label}</text>
      </g>`;
    }
  });

  markup += `</g>`;
  svg.innerHTML = markup;
  document.getElementById('cfgInfo').textContent = `${cfg.nodes.length} nodes · ${cfg.edges.length} edges`;

  // Pan and zoom
  svg.onmousedown  = e => { state.dragging = true; state.mx = e.clientX - state.tx; state.my = e.clientY - state.ty; svg.style.cursor = 'grabbing'; };
  svg.onmousemove  = e => { if (!state.dragging) return; state.tx = e.clientX - state.mx; state.ty = e.clientY - state.my; renderCFG(cfg, highlightNode); };
  svg.onmouseup    = () => { state.dragging = false; svg.style.cursor = 'grab'; };
  svg.onmouseleave = () => { state.dragging = false; svg.style.cursor = 'grab'; };
  svg.onwheel      = e => { e.preventDefault(); state.scale = Math.min(2.5, Math.max(0.3, state.scale + (e.deltaY > 0 ? -0.1 : 0.1))); renderCFG(cfg, highlightNode); };

  state._lastCfg = cfg;
}

function onNodeClick(nodeId) {
  if (!state._lastData) return;
  const tc = state._lastData.testCases.find(t => t.nodeId === nodeId);
  if (tc) selectTestCase(tc.id);
}

function cfgZoom(factor)  { if (state._lastCfg) { state.scale = Math.min(2.5, Math.max(0.3, state.scale * factor)); renderCFG(state._lastCfg, state.highlighted); } }
function cfgResetView()   { state.scale = 1; state.tx = 0; state.ty = 0; if (state._lastCfg) renderCFG(state._lastCfg, state.highlighted); }
function cfgFit()         { state.scale = 1; state.tx = 0; state.ty = 0; if (state._lastCfg) renderCFG(state._lastCfg, state.highlighted); }

// ── Results ───────────────────────────────────────────────────────────────────
function renderResults(data, selectedId) {
  const { testCases, coverage, methodName, conditionLabels } = data;

  if (!testCases || testCases.length === 0) {
    document.getElementById('resultsBody').innerHTML = `
      <div class="results-empty">
        <div style="font-size:13px;font-weight:500;color:var(--text-secondary)">No test cases found</div>
        <div style="font-size:12px">No decisions with multiple conditions were detected</div>
      </div>`;
    return;
  }

  const numInputs = testCases[0].inputs.length;
  const labels = (conditionLabels && conditionLabels.length === numInputs)
    ? conditionLabels
    : Array.from({ length: numInputs }, (_, i) => 'C' + (i + 1));

  const body    = document.getElementById('resultsBody');
  const tcCount = document.getElementById('tcCount');
  tcCount.style.display = 'inline-flex';
  tcCount.textContent   = testCases.length + ' cases';

  const rows = testCases.map(tc => {
    const sel        = selectedId === tc.id ? 'highlight' : '';
    const inputCells = tc.inputs.map(v =>
      `<td class="${v ? 'val-t' : 'val-f'}">${v ? 'T' : 'F'}</td>`
    ).join('');
    return `<tr class="${sel}" style="cursor:pointer" onclick="selectTestCase('${tc.id}')">
      <td class="tc-id">${tc.id}</td>
      ${inputCells}
      <td class="${tc.output ? 'val-t' : 'val-f'}">${tc.output ? 'true' : 'false'}</td>
    </tr>`;
  }).join('');

  const selTc  = selectedId ? testCases.find(t => t.id === selectedId) : null;
  const expHtml = selTc && selTc.explanation
    ? `<div class="exp-card">
        <div class="exp-card-title">
          <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
          ${selTc.id} — condition: ${selTc.conditionTested}
        </div>
        <div class="exp-card-body">${selTc.explanation}</div>
       </div>`
    : `<div class="exp-card">
        <div class="exp-card-title">
          <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
          How to read this table
        </div>
        <div class="exp-card-body">Click any test case row to see the MC/DC independence explanation for that condition. The corresponding decision node in the CFG will also be highlighted.</div>
       </div>`;

  const pct = Math.round(coverage.percentage);

  body.innerHTML = `
    <div style="display:flex;align-items:flex-start;justify-content:space-between;gap:8px">
      <div>
        <div style="font-size:13px;font-weight:600;color:var(--text-primary)">${methodName}</div>
        <div style="font-size:11px;color:var(--text-secondary);margin-top:2px">${coverage.total} condition${coverage.total !== 1 ? 's' : ''} · ${testCases.length} test cases</div>
      </div>
      <span class="tag tag-green">MC/DC ✓</span>
    </div>

    <div style="overflow-x:auto">
      <table class="data-table">
        <thead>
          <tr>
            <th style="width:40px">TC</th>
            ${labels.map(l => `<th style="max-width:60px;overflow:hidden;text-overflow:ellipsis" title="${l}">${l.length > 7 ? l.slice(0,6)+'…' : l}</th>`).join('')}
            <th>result</th>
          </tr>
        </thead>
        <tbody>${rows}</tbody>
      </table>
    </div>

    ${expHtml}

    <div class="cov-section">
      <div class="cov-header">
        <div class="cov-title">MC/DC coverage</div>
        <div class="cov-pct">${pct}%</div>
      </div>
      <div class="progress-track"><div class="progress-fill green" id="covBar" style="width:0%"></div></div>
      <div class="metrics-grid">
        <div class="metric-card"><div class="metric-val">${coverage.covered}</div><div class="metric-lbl">conditions covered</div></div>
        <div class="metric-card"><div class="metric-val">${coverage.total}</div><div class="metric-lbl">total conditions</div></div>
      </div>
    </div>

    <div style="font-size:10px;font-weight:600;color:var(--text-tertiary);text-transform:uppercase;letter-spacing:.08em">Export results</div>
    <div class="exp-btns">
      <button class="exp-btn" onclick="doExport('json')">JSON</button>
      <button class="exp-btn" onclick="doExport('html')">HTML report</button>
      <button class="exp-btn" onclick="doExport('text')">Plain text</button>
    </div>
  `;

  state._lastData = data;
  setTimeout(() => { const b = document.getElementById('covBar'); if (b) b.style.width = pct + '%'; }, 120);
}

function selectTestCase(id) {
  state.highlighted = id;
  const testCases = state._lastData ? state._lastData.testCases : [];
  const tc = testCases.find(t => t.id === id);
  if (tc && tc.nodeId && state._lastCfg) renderCFG(state._lastCfg, tc.nodeId);
  if (state._lastData) renderResults(state._lastData, id);
}

// ── Export ────────────────────────────────────────────────────────────────────
function doExport(fmt) {
  if (!state._lastData) { alert('No results to export yet. Generate an analysis first.'); return; }

  if (fmt === 'json') {
    const blob = new Blob([JSON.stringify(state._lastData, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'mcdc-results.json';
    a.click();
    return;
  }

  if (fmt === 'text') {
    const lines = ['MC/DC Analysis — ' + state._lastData.methodName, ''];
    state._lastData.testCases.forEach(tc => {
      lines.push(tc.id + ': inputs=[' + tc.inputs.join(', ') + '] output=' + tc.output);
      if (tc.explanation) lines.push('  → ' + tc.explanation);
    });
    lines.push('');
    lines.push('Coverage: ' + Math.round(state._lastData.coverage.percentage) + '%');
    lines.push('Conditions covered: ' + state._lastData.coverage.covered + ' / ' + state._lastData.coverage.total);
    const blob = new Blob([lines.join('\n')], { type: 'text/plain' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'mcdc-results.txt';
    a.click();
    return;
  }

  if (fmt === 'html') {
    fetch('/api/export?format=html', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(state._lastData)
    })
    .then(r => r.text())
    .then(html => {
      const blob = new Blob([html], { type: 'text/html' });
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = 'mcdc-report.html';
      a.click();
    })
    .catch(() => alert('HTML export failed. Make sure Spring Boot is running.'));
    return;
  }
}

// ── Error helpers ─────────────────────────────────────────────────────────────
function showError(msg) { const b = document.getElementById('errorBox'); document.getElementById('errorMsg').textContent = msg; b.style.display = 'flex'; }
function hideError() { document.getElementById('errorBox').style.display = 'none'; }
// updateLineNumbers — called on every keystroke in the code textarea.
// HOW IT WORKS:
//   1. Count how many newlines are in the text (= number of lines).
//   2. Build a string "1\n2\n3\n..." with that many numbers.
//   3. Write it into the line-numbers gutter div.
// The gutter and textarea share the same font + line-height, so the numbers
// stay perfectly aligned with the code rows.
function updateLineNumbers() {
  const code    = document.getElementById('codeInput').value;
  const count   = code === '' ? 1 : code.split('\n').length;
  const nums    = Array.from({ length: count }, (_, i) => i + 1).join('\n');
  const gutter  = document.getElementById('lineNumbers');
  if (gutter) gutter.textContent = nums;
  syncScroll();
}

// syncScroll — called when the textarea scrolls vertically.
// We mirror the textarea's scrollTop onto the gutter so the numbers
// stay in sync as the user scrolls through long files.
function syncScroll() {
  const ta     = document.getElementById('codeInput');
  const gutter = document.getElementById('lineNumbers');
  if (ta && gutter) gutter.scrollTop = ta.scrollTop;
}

// Keep old name as alias so any other callers don't break
function updateLineCounter() { updateLineNumbers(); }