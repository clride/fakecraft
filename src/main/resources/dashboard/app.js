/* FakeMCPlayer operator console — vanilla JS, no build step. */
(() => {
'use strict';

const $ = (s) => document.querySelector(s);
const qs = new URLSearchParams(location.search);
if (qs.get('token')) sessionStorage.setItem('fmp.token', qs.get('token'));
const TOKEN = sessionStorage.getItem('fmp.token') || '';

const state = { mode: 'auto', paused: false, left: false, botName: 'bot', playersOnline: 0 };
let feed = [];                   // ALL console events, ordered (single channel)
let activity = [];               // mirrors feed + operator meta (mode/pause/mute)
let lastId = 0;
let activityFilter = 'all';
let atBottom = true;
let renderQueued = false;

// Types shown in the transcript. mode/pause/mute stay in the Activity panel.
const FEED_TYPES = new Set([
  'player_message', 'bot_message', 'judge', 'trigger', 'skip', 'held',
  'suppressed', 'draft_created', 'draft_resolved', 'error'
]);

// ------------------------------------------------------------------ utils

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}
function timeStr(at) {
  if (!at) return '';
  return new Date(at).toTimeString().slice(0, 8);
}
function banner(text) {
  const b = $('#banner');
  b.textContent = text;
  b.hidden = false;
}
async function api(path, opts = {}) {
  const res = await fetch(path, {
    method: opts.method || 'GET',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer ' + TOKEN
    },
    body: opts.body ? JSON.stringify(opts.body) : undefined
  });
  if (res.status === 401) {
    banner('Missing or wrong token — open the exact URL printed by the server (/bot dashboard).');
    throw new Error('unauthorized');
  }
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return res.json();
}

// ----------------------------------------------------------------- header

function statusLine() {
  return `${state.botName} · ${state.mode}${state.paused ? ' · held' : ''}`
    + (state.left ? ' · LEFT' : ` · ${state.playersOnline} online`);
}
function setStatus(on, text) {
  $('#statusDot').className = 'dot ' + (on ? 'on' : 'off');
  $('#statusText').textContent = text;
}
function renderModes() {
  for (const b of document.querySelectorAll('#modes button')) {
    b.classList.toggle('active', b.dataset.mode === state.mode);
  }
  const left = !!state.left;
  const leaveBtn = $('#leaveBtn');
  leaveBtn.textContent = left ? 'Rejoin' : 'Leave';
  leaveBtn.classList.toggle('away', left);
  // A bot that has left cannot write — lock the composer.
  $('#input').disabled = left;
  $('#sendBtn').disabled = left;
  $('#suggestBtn').disabled = left;
  const p = $('#pauseBtn');
  p.classList.toggle('held', state.paused);
  p.textContent = state.paused ? 'Held' : 'Hold';
  const hints = {
    auto: '',
    approve: 'approve mode — AI drafts, nothing sends until you press Send',
    manual: 'manual mode — bot is quiet; you write the replies'
  };
  $('#composerHint').textContent = left
    ? 'bot left — press Rejoin to bring it back'
    : (hints[state.mode] || '');
  $('#botName').textContent = state.botName || '';
  $('#presence').textContent = left ? ' · left the game' : ` · ${state.playersOnline} online`;
  setStatus(true, statusLine());
}

// ------------------------------------------------------------ the one feed

function rowHtml(ev) {
  switch (ev.type) {
    case 'player_message':
      return ev.system
        ? `<div class="sys sysline">✦ ${esc(ev.text)}</div>`
        : msgHtml(ev, false);
    case 'bot_message': return msgHtml(ev, true);
    case 'judge': {
      const v = String(ev.verdict || '').toUpperCase();
      const extra = ev.probability != null
        ? ` p=${Number(ev.probability).toFixed(2)} (thr ${Number(ev.threshold).toFixed(2)})` : '';
      return `<div class="sys judge">judge ${esc(ev.engine || 'llm')}${ev.model ? ' ' + esc(ev.model) : ''}:`
        + ` <b class="${v === 'YES' ? 'yes' : 'no'}">${esc(v)}</b>${extra}`
        + `${ev.ms ? ' · ' + ev.ms + 'ms' : ''}</div>`;
    }
    case 'trigger': return `<div class="sys trigger">trigger: ${esc(ev.reason)}</div>`;
    case 'held': return `<div class="sys held">held — ${esc(ev.reason)} (waiting for you)</div>`;
    case 'suppressed': return `<div class="sys muted">suppressed ${esc(ev.kind || '')} — ${esc(ev.reason || '')}</div>`;
    case 'skip': return `<div class="sys skip">skipped: ${esc(ev.reason)}</div>`;
    case 'draft_created': return `<div class="sys draft">draft generated${ev.replaced ? ' (regenerated)' : ''}</div>`;
    case 'draft_resolved': return `<div class="sys draft">draft ${esc(ev.action)}</div>`;
    case 'error': return `<div class="sys error">error (${esc(ev.where)}): ${esc(ev.message)}</div>`;
    default: return '';
  }
}
function msgHtml(ev, bot) {
  const meta = bot
    ? `${esc(state.botName)} · ${timeStr(ev.at)}${ev.source && ev.source !== 'reply' ? ' · ' + esc(ev.source) : ''}`
    : `${esc(ev.player)} · ${timeStr(ev.at)}`;
  return `<div class="msg ${bot ? 'bot' : 'player'}">
    <div class="meta">${meta}</div>
    <div class="text">${esc(ev.text)}</div>
  </div>`;
}
function draftCardHtml(d) {
  return `<div class="draft" data-id="${d.id}">
    <div class="draft-head"><span>AI draft for ${esc(d.player)} — edit and Send, or discard</span><span>${timeStr(d.at)}</span></div>
    <textarea class="draft-text" rows="2">${esc(d.text)}</textarea>
    <div class="draft-actions">
      <button class="ghost act-regen">Regenerate</button>
      <button class="ghost act-discard">Discard</button>
      <button class="primary act-send">Send</button>
    </div>
  </div>`;
}
/** Live drafts from the feed itself: recent draft_created not yet resolved. */
function liveDrafts() {
  const live = new Map(); // draftId -> {player, text, at}
  for (const ev of feed) {
    if (ev.type === 'draft_created' && ev.player) {
      live.set(ev.draftId, { id: ev.draftId, player: ev.player, text: ev.text, at: ev.at });
    } else if (ev.type === 'draft_resolved' && ev.draftId != null) {
      live.delete(ev.draftId);
    }
  }
  return live;
}
function renderFeed() {
  const box = $('#transcript');
  const live = liveDrafts();
  // Preserve a draft textarea the operator is actively typing in: the feed
  // re-renders on every event, and a rebuild from server text would wipe it.
  const editing = new Map();
  for (const el of box.querySelectorAll('.draft[data-id]')) {
    const ta = el.querySelector('.draft-text');
    if (ta && document.activeElement === ta) {
      editing.set(String(el.dataset.id), { value: ta.value, s: ta.selectionStart, e: ta.selectionEnd });
    }
  }
  const rows = [];
  for (const ev of feed) {
    if (!FEED_TYPES.has(ev.type)) continue;
    if (ev.type === 'draft_created' && live.has(ev.draftId)) {
      rows.push(draftCardHtml(live.get(ev.draftId)));
    } else {
      rows.push(rowHtml(ev));
    }
  }
  box.innerHTML = rows.join('');
  for (const [id, saved] of editing) {
    const fresh = box.querySelector(`.draft[data-id="${CSS.escape(id)}"] .draft-text`);
    if (!fresh) continue;
    fresh.value = saved.value;
    try { fresh.setSelectionRange(saved.s, saved.e); fresh.focus(); } catch (e) { /* detached */ }
  }
  if (atBottom) box.scrollTop = box.scrollHeight;
}
function scheduleFeed() {
  if (renderQueued) return;
  renderQueued = true;
  setTimeout(() => {
    renderQueued = false;
    renderFeed();
  }, 120);
}

// ---------------------------------------------------------------- activity

function activityText(ev) {
  switch (ev.type) {
    case 'player_message': return esc(ev.text)
      + (ev.system ? '<span class="tag">system</span>' : '');
    case 'bot_message': return `<b>${esc(state.botName)}</b>: ${esc(ev.text)}`
      + (ev.source ? `<span class="tag">${esc(ev.source)}</span>` : '');
    case 'judge': {
      const v = String(ev.verdict || '').toUpperCase();
      return `judge ${esc(ev.engine || '')}${ev.model ? ' ' + esc(ev.model) : ''}:`
        + ` <b class="${v === 'YES' ? 'yes' : 'no'}">${esc(v)}</b>`
        + (ev.probability != null ? ` p=${Number(ev.probability).toFixed(2)}` : '')
        + (ev.ms ? ` · ${ev.ms}ms` : '')
        + (ev.player ? ` · ${esc(ev.player)}` : '');
    }
    case 'trigger': return `trigger ${esc(ev.reason)} · ${esc(ev.player)}`;
    case 'held': return `held (${esc(ev.reason)}) · ${esc(ev.player)}: ${esc(ev.text)}`;
    case 'suppressed': return `suppressed ${esc(ev.kind || '')} (${esc(ev.reason || '')}) · ${esc(ev.player)}`;
    case 'skip': return `skip ${esc(ev.reason)} · ${esc(ev.player)}${ev.text ? ': ' + esc(ev.text) : ''}`;
    case 'draft_created': return `draft #${ev.draftId} · ${esc(ev.player)}${ev.replaced ? ' (regen)' : ''}`;
    case 'draft_resolved': return `draft #${ev.draftId} ${esc(ev.action)} · ${esc(ev.player)}`;
    case 'error': return `error (${esc(ev.where)}): ${esc(ev.message)}`;
    case 'mode': return `mode → ${esc(ev.mode)}`;
    case 'pause': return ev.paused ? 'bot held' : 'bot released';
    case 'presence': return ev.left ? 'bot left the server'
      + (ev.reason ? ` (${esc(ev.reason)})` : '') : 'bot rejoined the server';
    case 'mute': return `${esc(ev.player)} ${ev.muted ? 'muted' : 'unmuted'}`;
    default: return esc(ev.type);
  }
}
function activityPass(ev) {
  if (activityFilter === 'all') return true;
  if (activityFilter === 'errors') return ev.type === 'error';
  if (activityFilter === 'delivery') return ev.type === 'bot_message';
  if (activityFilter === 'decisions') {
    return ['judge', 'trigger', 'held', 'suppressed', 'skip',
      'draft_created', 'draft_resolved', 'mode', 'pause', 'presence', 'mute'].includes(ev.type);
  }
  return true;
}
function renderActivity() {
  const box = $('#activityList');
  const near = box.scrollHeight - box.scrollTop - box.clientHeight < 60;
  box.innerHTML = activity.filter(activityPass).slice(-400).map(ev =>
    `<div class="arow ${ev.type === 'error' ? 'error' : ''}">
      <span class="t">${timeStr(ev.at)}</span>${activityText(ev)}
    </div>`
  ).join('');
  if (near) box.scrollTop = box.scrollHeight;
}

// -------------------------------------------------------------------- sse

function onEvent(ev) {
  if (ev.id <= lastId) return;
  lastId = ev.id;

  activity.push(ev);
  if (activity.length > 1500) activity.shift();

  if (FEED_TYPES.has(ev.type)) {
    feed.push(ev);
    if (feed.length > 800) feed.shift();
    scheduleFeed();
  }
  if (ev.type === 'mode') { state.mode = ev.mode; renderModes(); }
  if (ev.type === 'pause') { state.paused = !!ev.paused; renderModes(); }
  renderActivity();
}
let es = null;
function connect() {
  if (es) es.close();
  es = new EventSource('/api/events?token=' + encodeURIComponent(TOKEN)
    + '&since=' + lastId);
  es.onopen = () => setStatus(true, statusLine());
  es.onerror = () => setStatus(false, 'reconnecting…');
  es.onmessage = (e) => {
    try { onEvent(JSON.parse(e.data)); } catch (err) { /* ignore bad frame */ }
  };
}
async function refreshState() {
  try {
    Object.assign(state, await api('/api/state'));
    renderModes();
  } catch (e) { /* 401 banner already shown */ }
}

// ---------------------------------------------------------------- actions

async function send() {
  if (state.left) { banner('bot left — press Rejoin first'); return; }
  const box = $('#input');
  const text = box.value.trim();
  if (!text) return;
  box.value = '';
  try {
    await api('/api/send', { method: 'POST', body: { to: '#server', text } });
  } catch (e) { /* banner already shown */ }
}
function lastSpeaker() {
  for (let i = feed.length - 1; i >= 0; i--) {
    const ev = feed[i];
    if (ev.type === 'player_message' && ev.player && !ev.system) return ev.player;
  }
  return '';
}
async function suggest() {
  if (state.left) { banner('bot left — press Rejoin first'); return; }
  const player = lastSpeaker() || '#server';
  const btn = $('#suggestBtn');
  btn.disabled = true;
  btn.textContent = 'thinking…';
  try {
    const r = await api('/api/draft', { method: 'POST', body: { player } });
    if (r.text) {
      $('#input').value = r.text;
      $('#input').focus();
    }
  } catch (e) { /* banner already shown */ } finally {
    btn.disabled = false;
    btn.textContent = 'Suggest';
  }
}
async function draftAction(btn, action, text) {
  const card = btn.closest('.draft');
  if (!card) return;
  btn.disabled = true;
  try {
    await api('/api/draft/' + card.dataset.id, { method: 'POST', body: { action, text } });
    // The server emits a draft_resolved event which removes the card;
    // for regenerate a fresh draft_created with the same id takes over.
  } catch (e) {
    btn.disabled = false;
  }
}

// ------------------------------------------------------------------- init

async function init() {
  try {
    Object.assign(state, await api('/api/state'));
    renderModes();
    const data = await api('/api/conversations/%23server');
    feed = data.events || [];
    for (const ev of feed) {
      if (ev.id > lastId) lastId = ev.id;
    }
    activity = feed.slice();
    renderFeed();
    renderActivity();
    connect();
  } catch (e) {
    setStatus(false, 'offline');
  }
}

// ------------------------------------------------------------------- wire

$('#modes').addEventListener('click', async (e) => {
  const b = e.target.closest('button');
  if (!b) return;
  try {
    const r = await api('/api/mode', { method: 'POST', body: { mode: b.dataset.mode } });
    state.mode = r.mode;
    renderModes();
  } catch (err) { /* banner already shown */ }
});
$('#pauseBtn').addEventListener('click', async () => {
  try {
    const r = await api('/api/pause', { method: 'POST', body: { paused: !state.paused } });
    state.paused = r.paused;
    renderModes();
  } catch (err) { /* banner already shown */ }
});
$('#leaveBtn').addEventListener('click', async () => {
  const action = state.left ? 'rejoin' : 'leave';
  const btn = $('#leaveBtn');
  btn.disabled = true;
  try {
    const r = await api('/api/presence', { method: 'POST', body: { action } });
    state.left = !!r.left;
    renderModes();
  } catch (err) { /* banner already shown */ } finally {
    btn.disabled = false;
  }
});
$('#sendBtn').addEventListener('click', send);
$('#suggestBtn').addEventListener('click', suggest);
$('#input').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    send();
  }
});
$('#transcript').addEventListener('click', (e) => {
  const btn = e.target.closest('button');
  if (!btn) return;
  const card = e.target.closest('.draft');
  if (!card) return;
  const text = card.querySelector('.draft-text').value;
  if (btn.classList.contains('act-send')) draftAction(btn, 'edit', text);
  else if (btn.classList.contains('act-discard')) draftAction(btn, 'discard');
  else if (btn.classList.contains('act-regen')) draftAction(btn, 'regenerate');
});
$('#transcript').addEventListener('scroll', () => {
  const b = $('#transcript');
  atBottom = b.scrollHeight - b.scrollTop - b.clientHeight < 60;
});
$('#filters').addEventListener('click', (e) => {
  const b = e.target.closest('button');
  if (!b) return;
  activityFilter = b.dataset.filter;
  for (const x of document.querySelectorAll('#filters button')) {
    x.classList.toggle('active', x === b);
  }
  renderActivity();
});
setInterval(refreshState, 10000);

init();
})();
