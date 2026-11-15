// The Driver Station side panel: OpMode selection, INIT/START/STOP, telemetry, gamepads, devices, build, log.
// With several robots on the field each has its own Driver Station; the panel shows one at a time
// and keeps the others' state so switching is instant.

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const BUTTON_LABELS = ['A', 'B', 'X', 'Y', 'LB', 'RB', 'LT', 'RT', 'BACK', 'START', 'LS', 'RS', '▲', '▼', '◀', '▶', 'GUIDE'];
const MAX_LOG = 300;

/** Keeps only simple formatting tags from HTML telemetry. */
function sanitizeHtml(html) {
  const allowed = new Set(['B', 'I', 'U', 'BR', 'BIG', 'SMALL', 'TT', 'STRONG', 'EM', 'FONT', 'SPAN', 'P', 'H1', 'H2', 'H3', 'H4', 'SUB', 'SUP']);
  const doc = new DOMParser().parseFromString(`<div>${html}</div>`, 'text/html');
  const walk = (node) => {
    for (const child of Array.from(node.childNodes)) {
      if (child.nodeType === Node.ELEMENT_NODE) {
        if (!allowed.has(child.tagName)) { child.replaceWith(...child.childNodes); walk(node); return; }
        for (const attr of Array.from(child.attributes)) {
          if (attr.name === 'color' && /^#?[\w]+$/.test(attr.value)) continue;
          if (attr.name === 'style' && /^\s*(color|background-color)\s*:\s*[#\w(),.\s%]+;?\s*$/i.test(attr.value)) continue;
          child.removeAttribute(attr.name);
        }
        walk(child);
      }
    }
  };
  walk(doc.body.firstChild);
  return doc.body.firstChild.innerHTML;
}

/** m:ss of a Driver Station timer: time left in an Autonomous with the 30 s timer, else time since INIT/START. */
function clock(t) {
  if (!t) return '';
  const s = t.remaining !== undefined ? Math.ceil(t.remaining) : t.elapsed !== undefined ? Math.floor(t.elapsed) : null;
  return s === null ? '' : `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

const STATE_LABELS = { IDLE: 'Stopped', INIT: 'Init', RUNNING: 'Running', STOPPING: 'Stopping' };

/** Everything the panel shows for one robot. */
function blankRobot() {
  return {
    opmodes: [], ds: { state: 'IDLE' }, build: null, telemetry: null, log: [], devices: null, timer: null,
    showSamples: false, samplesTouched: false, pendingSelect: undefined,
  };
}

export class DriverStation {
  constructor(send, input) {
    this.sendRaw = send;
    this.input = input;
    this.current = 0;
    this.robots = [blankRobot()];
    this.meta = [{ alliance: 'red' }];
    this.rows = [];               // a card per robot, shown with several robots
    this.multi = false;
    this.listeners = [];
    this.pickListeners = [];

    $('opmodeSelect').addEventListener('change', (e) => {
      // Remember the choice until the simulator confirms it, so an older update can't undo it.
      this.r.pendingSelect = e.target.value;
      this.send({ type: 'select', opmode: e.target.value });
    });
    $('showSamples').addEventListener('change', (e) => { this.r.showSamples = e.target.checked; this.r.samplesTouched = true; this.renderOpModes(); });
    $('mainBtn').addEventListener('click', () => this.mainAction());
    $('stopBtn').addEventListener('click', () => this.send({ type: 'stop' }));
    $('autoTimer').addEventListener('change', (e) => this.send({ type: 'timer', enabled: e.target.checked }));
    $('errorDismiss').addEventListener('click', () => this.send({ type: 'clearError' }));
    $('errorFix').addEventListener('click', () => { if (this.r.ds.fixRobot) this.send({ type: 'robot', file: this.r.ds.fixRobot }); });
    $('buildBtn').addEventListener('click', () => this.send({ type: 'build' }));
    $('initAllBtn').addEventListener('click', () => {
      for (const r of this.robots) r.pendingSelect = undefined;
      this.sendRaw({ type: 'initAll' });
    });
    $('startAllBtn').addEventListener('click', () => this.sendRaw({ type: 'startAll' }));
    $('stopAllBtn').addEventListener('click', () => this.sendRaw({ type: 'stopAll' }));
    $('autoTimerAll').addEventListener('change', (e) => {
      this.robots.forEach((_, i) => this.sendTo(i, { type: 'timer', enabled: e.target.checked }));
    });
    $('showSamplesAll').addEventListener('change', (e) => {
      for (const r of this.robots) { r.showSamples = e.target.checked; r.samplesTouched = true; }
      this.robots.forEach((_, i) => this.renderRow(i, true));
      this.renderOpModes();
    });
    for (const b of document.querySelectorAll('.tabs button')) {
      b.addEventListener('click', () => {
        for (const o of document.querySelectorAll('.tabs button')) o.classList.toggle('on', o === b);
        for (const t of document.querySelectorAll('.tab')) t.hidden = t.dataset.tab !== b.dataset.tab;
      });
    }
    for (const slot of [1, 2]) {
      $(`gp${slot}Source`).addEventListener('change', (e) => this.input.setSource(this.current, slot, e.target.value));
      this.buildGamepadViz(slot);
    }
    input.onChange(() => this.renderGamepadSources());
    this.renderGamepadSources();
    setInterval(() => this.renderGamepads(), 50);
  }

  /** The robot on screen. */
  get r() { return this.robots[this.current]; }

  /** Sends a command to the robot on screen. */
  send(msg) { this.sendTo(this.current, msg); }

  sendTo(i, msg) { this.sendRaw({ ...msg, robot: i }); }

  /** Called when a robot's Driver Station state changes: fn(robot, ds). */
  onDsChange(fn) { this.listeners.push(fn); }

  /** Called when a robot's row is clicked to show its Driver Station: fn(robot). */
  onPick(fn) { this.pickListeners.push(fn); }

  /**
   * A new connection (the simulator may have restarted): forget what the previous one said about
   * each robot, keeping only the viewer's own FTC samples choice.
   */
  reset() {
    this.robots = this.robots.map((r) => ({ ...blankRobot(), showSamples: r.samplesTouched && r.showSamples, samplesTouched: r.samplesTouched }));
  }

  /** The robots on the field (the hello message's list). */
  setRobots(list) {
    const n = list.length;
    while (this.robots.length < n) this.robots.push(blankRobot());
    this.robots.length = n;
    this.meta = list.map((r) => ({ alliance: r.alliance }));
    if (this.current >= n) this.current = 0;
    this.buildRows();
  }

  /** Several robots on this page: a card per robot with its controls, the selected robot's details below. */
  setMulti(on) {
    this.multi = on;
    document.querySelector('aside.ds').classList.toggle('multi', on);
    $('robotsCard').hidden = !on;
    this.markSelected();
  }

  /** Shows robot {@code i}'s Driver Station. */
  show(i) {
    this.current = i;
    this.markSelected();
    this.renderOpModes();
    this.renderDs();
    this.renderBuild();
    this.renderTelemetry();
    this.renderLog();
    this.renderDevices();
    this.renderTimer();
    this.renderGamepadSources();
  }

  mainAction() {
    const st = this.r.ds.state;
    if (st === 'IDLE') {
      this.r.pendingSelect = undefined;
      this.send({ type: 'init', opmode: $('opmodeSelect').value });
    }
    else if (st === 'INIT') this.send({ type: 'start' });
    else if (st === 'RUNNING') this.send({ type: 'stop' });
  }

  // ---- OpModes -------------------------------------------------------------------------------

  setBuild(i, b) {
    const r = this.robots[i];
    if (!r) return;
    r.build = b;
    r.opmodes = b.opmodes || r.opmodes;
    if (!r.samplesTouched) {
      // With no OpModes of your own enabled, show the SDK samples so there is something to run;
      // also when the robot has a sample selected.
      r.showSamples = !r.opmodes.some((o) => !o.sample && !o.disabled)
        || r.opmodes.some((o) => o.sample && o.name === r.ds.selected);
    }
    this.renderRow(i, true);
    this.renderMatch();
    if (i !== this.current) return;
    this.renderOpModes();
    this.renderBuild();
  }

  renderBuild() {
    const b = this.r.build;
    const msg = $('buildMsg');
    if (!b) { msg.textContent = ''; $('buildRoots').innerHTML = ''; $('skipped').innerHTML = ''; $('buildBadge').hidden = true; return; }
    if (b.building) { msg.textContent = 'Building…'; msg.className = 'build-msg'; }
    else if (b.ok === undefined) { msg.textContent = ''; }
    else { msg.textContent = b.message + (b.pending ? ' · rebuild waiting for the OpMode to stop' : ''); msg.className = 'build-msg ' + (b.ok ? 'ok' : 'fail'); }
    $('buildRoots').innerHTML = (b.roots || []).map((root) => esc(root)).join('<br>');
    const skipped = b.skipped || [];
    $('skipped').innerHTML = skipped.map((s) => `<li><code>${esc(s.file)}</code><span class="why">${esc(s.reason)}</span></li>`).join('');
    const badge = $('buildBadge');
    badge.hidden = !(skipped.length || b.ok === false);
    badge.textContent = b.ok === false ? '!' : String(skipped.length);
  }

  visibleOpModes(r = this.r) {
    return r.opmodes.filter((o) => (o.sample ? r.showSamples : !o.disabled || r.showSamples));
  }

  /** A robot's OpMode list as select options, Autonomous first. */
  optionsHtml(r) {
    const list = this.visibleOpModes(r);
    const groups = [['AUTONOMOUS', 'Autonomous'], ['TELEOP', 'TeleOp']];
    let html = '';
    for (const [kind, label] of groups) {
      const items = list.filter((o) => o.kind === kind);
      if (!items.length) continue;
      html += `<optgroup label="${label}">` + items.map((o) =>
        `<option value="${esc(o.name)}">${esc(o.name)}${o.group ? ' · ' + esc(o.group) : ''}${o.disabled && !o.sample ? ' (@Disabled)' : ''}</option>`).join('') + '</optgroup>';
    }
    return html || '<option value="">No OpModes found</option>';
  }

  renderOpModes() {
    const r = this.r;
    $('showSamples').checked = r.showSamples;
    const sel = $('opmodeSelect');
    const list = this.visibleOpModes();
    sel.innerHTML = this.optionsHtml(r);
    this.renderRow(this.current, true);
    if (r.ds.selected && list.some((o) => o.name === r.ds.selected)) sel.value = r.ds.selected;
    // The robot chose an OpMode that isn't listed (a sample with FTC samples off): pick the first one
    // shown, so INIT runs what you see. Never while the robot hasn't chosen yet (it picks its default
    // after building), or a page that just connected would overwrite that choice.
    else if (r.ds.type === 'ds' && r.ds.selected && list.length && r.ds.state === 'IDLE' && sel.value && sel.value !== r.ds.selected) this.send({ type: 'select', opmode: sel.value });
    this.renderControls();
  }

  setDs(i, ds) {
    const r = this.robots[i];
    if (!r) return;
    if (r.ds.state === 'IDLE' && ds.state === 'INIT') r.telemetry = null;   // a new OpMode starts with empty telemetry
    if (r.pendingSelect !== undefined) {
      if (ds.selected === r.pendingSelect || ds.state !== 'IDLE') r.pendingSelect = undefined;
      else ds.selected = r.pendingSelect;
    }
    r.ds = ds;
    if (ds.state === 'IDLE') r.timer = null;
    for (const fn of this.listeners) fn(i, ds);
    this.renderRow(i);
    this.renderMatch();
    if (i !== this.current) return;
    if (r.telemetry === null) this.renderTelemetry();
    this.renderDs();
  }

  renderDs() {
    const r = this.r;
    const ds = r.ds;
    const sel = $('opmodeSelect');
    if (ds.selected && Array.from(sel.options).some((o) => o.value === ds.selected)) sel.value = ds.selected;
    else if (ds.selected && !r.showSamples && r.opmodes.some((o) => o.name === ds.selected && o.sample)) {
      r.showSamples = true;
      this.renderOpModes();
    }
    $('autoTimer').checked = ds.timer !== false;
    const err = $('errorBanner');
    err.hidden = !ds.error;
    $('errorText').textContent = ds.error || '';
    const fix = $('errorFix');
    fix.hidden = !ds.fixRobot;
    fix.textContent = ds.fixRobot ? `Use ${ds.fixRobot}` : '';
    const warn = $('warnBanner');
    warn.hidden = !ds.warning;
    warn.textContent = ds.warning || '';
    this.renderControls();
  }

  renderControls() {
    const ds = this.r.ds;
    const st = ds.state;
    const btn = $('mainBtn');
    const hasSel = !!$('opmodeSelect').value;
    btn.className = 'big ' + { IDLE: 'init', INIT: 'start', RUNNING: 'running', STOPPING: 'stopping' }[st];
    btn.textContent = { IDLE: 'INIT', INIT: '▶  START', RUNNING: '■  STOP', STOPPING: 'Stopping…' }[st];
    btn.disabled = st === 'STOPPING' || (st === 'IDLE' && !hasSel);
    $('stopBtn').disabled = !(st === 'INIT' || st === 'RUNNING');
    $('opmodeSelect').disabled = st !== 'IDLE';
    const robotSel = $('robotSelect');
    robotSel.disabled = st !== 'IDLE';
    robotSel.closest('label').title = st === 'IDLE' ? 'Robot description (robots/*.json)' : 'Stop the OpMode to change the robot';
    const pill = $('dsState');
    pill.className = 'pill ' + st.toLowerCase();
    pill.textContent = st === 'IDLE' ? 'Stopped' : st === 'INIT' ? `Init · ${ds.active}` : st === 'RUNNING' ? `Running · ${ds.active}` : 'Stopping';
    if (st === 'IDLE') $('timer').textContent = '';
  }

  /** Elapsed / remaining OpMode time from the state stream. */
  setTimer(i, ds) {
    const r = this.robots[i];
    if (!r) return;
    r.timer = ds;
    this.renderRowStatus(i);
    if (i === this.current) this.renderTimer();
  }

  renderTimer() {
    const t = $('timer');
    const ds = this.r.timer;
    t.textContent = clock(ds);
    t.classList.toggle('low', ds?.remaining !== undefined && Math.ceil(ds.remaining) <= 5);
  }

  // ---- a card per robot (several robots on the field) -----------------------------------------

  buildRows() {
    const host = $('robotRows');
    host.replaceChildren();
    this.rows = this.robots.map((_, i) => {
      const n = i + 1;
      const alliance = this.meta[i]?.alliance ?? '';
      const root = document.createElement('div');
      root.className = `robot-row ${alliance}`;
      root.innerHTML = `<button class="rr-head" title="Show Robot ${n}'s telemetry, gamepads and devices below">`
          + `<i class="dot idle"></i><b>Robot ${n}</b><span class="rr-alliance">${esc(alliance)} alliance</span>`
          + '<span class="rr-status">Stopped</span></button>'
        + '<div class="rr-controls">'
          + `<select aria-label="Robot ${n} OpMode"></select>`
          + '<button class="go init" disabled>INIT</button>'
          + `<button class="halt" title="Stop Robot ${n}" aria-label="Stop Robot ${n}" disabled>■</button>`
        + '</div>'
        + '<div class="rr-msg error" hidden><div class="text"></div><div class="actions">'
          + '<button class="fix" hidden></button><button class="ghost dismiss">Dismiss</button></div></div>'
        + '<div class="rr-msg warn" hidden></div>';
      const q = (sel) => root.querySelector(sel);
      const row = {
        root, dot: q('.dot'), status: q('.rr-status'), select: q('select'), main: q('.go'),
        stop: q('.halt'), error: q('.rr-msg.error'), errorText: q('.rr-msg.error .text'), fix: q('.fix'), warn: q('.rr-msg.warn'),
      };
      // Clicking anywhere on the card except its controls shows that robot's details below.
      root.addEventListener('click', (e) => {
        if (e.target.closest('select, .rr-controls button, .rr-msg')) return;
        for (const fn of this.pickListeners) fn(i);
      });
      row.select.addEventListener('change', (e) => {
        // Remember the choice until the simulator confirms it, so an older update can't undo it.
        this.robots[i].pendingSelect = e.target.value;
        this.sendTo(i, { type: 'select', opmode: e.target.value });
      });
      row.main.addEventListener('click', () => {
        const st = this.robots[i].ds.state;
        if (st === 'IDLE') {
          this.robots[i].pendingSelect = undefined;
          this.sendTo(i, { type: 'init', opmode: row.select.value });
        } else if (st === 'INIT') this.sendTo(i, { type: 'start' });
        else if (st === 'RUNNING') this.sendTo(i, { type: 'stop' });
      });
      row.stop.addEventListener('click', () => this.sendTo(i, { type: 'stop' }));
      row.fix.addEventListener('click', () => {
        const f = this.robots[i].ds.fixRobot;
        if (f) this.sendTo(i, { type: 'robot', file: f });
      });
      q('.dismiss').addEventListener('click', () => this.sendTo(i, { type: 'clearError' }));
      host.appendChild(root);
      return row;
    });
    this.robots.forEach((_, i) => this.renderRow(i, true));
    this.markSelected();
    this.renderMatch();
  }

  /** Updates robot i's card; {@code options} also refills its OpMode list. */
  renderRow(i, options = false) {
    const row = this.rows[i], r = this.robots[i];
    if (!row || !r) return;
    const ds = r.ds, st = ds.state;
    if (options) row.select.innerHTML = this.optionsHtml(r);
    if (ds.selected && Array.from(row.select.options).some((o) => o.value === ds.selected)) row.select.value = ds.selected;
    row.select.disabled = st !== 'IDLE';
    row.main.className = 'go ' + { IDLE: 'init', INIT: 'start', RUNNING: 'running', STOPPING: 'stopping' }[st];
    row.main.textContent = { IDLE: 'INIT', INIT: '▶ START', RUNNING: '■ STOP', STOPPING: 'Stopping…' }[st];
    row.main.disabled = st === 'STOPPING' || (st === 'IDLE' && !row.select.value);
    row.stop.disabled = !(st === 'INIT' || st === 'RUNNING');
    row.dot.className = `dot ${st.toLowerCase()}`;
    row.error.hidden = !ds.error;
    row.errorText.textContent = ds.error || '';
    row.fix.hidden = !ds.fixRobot;
    row.fix.textContent = ds.fixRobot ? `Use ${ds.fixRobot}` : '';
    row.warn.hidden = !ds.warning;
    row.warn.textContent = ds.warning || '';
    this.renderRowStatus(i);
  }

  /** "Stopped", "Init · 0:03", "Running · 0:12", or "Running · 0:28 left" for an Autonomous with the 30 s timer. */
  renderRowStatus(i) {
    const row = this.rows[i], r = this.robots[i];
    if (!row || !r) return;
    const st = r.ds.state;
    const t = st === 'IDLE' ? '' : clock(r.timer);
    const left = !!t && r.timer.remaining !== undefined;
    row.status.textContent = t ? `${STATE_LABELS[st]} · ${t}${left ? ' left' : ''}` : STATE_LABELS[st];
    row.status.className = `rr-status ${st.toLowerCase()}` + (left && Math.ceil(r.timer.remaining) <= 5 ? ' low' : '');
  }

  /** Highlights the robot whose details are shown, and names it in the section titles below. */
  markSelected() {
    this.rows.forEach((row, k) => row.root.classList.toggle('on', k === this.current));
    const name = `Robot ${this.current + 1}`;
    for (const el of document.querySelectorAll('.for-robot')) el.textContent = this.multi ? ` · ${name}` : '';
    for (const el of document.querySelectorAll('.robot-name')) el.textContent = this.multi ? name : '';
  }

  /** The match controls: buttons are enabled when some robot can do that; the switches apply to every robot. */
  renderMatch() {
    $('initAllBtn').disabled = !this.robots.some((r) => r.ds.state === 'IDLE' && r.ds.selected);
    $('startAllBtn').disabled = !this.robots.some((r) => r.ds.state === 'INIT');
    $('stopAllBtn').disabled = !this.robots.some((r) => r.ds.state === 'INIT' || r.ds.state === 'RUNNING');
    $('autoTimerAll').checked = this.robots.every((r) => r.ds.timer !== false);
    $('showSamplesAll').checked = this.robots.every((r) => r.showSamples);
  }

  // ---- telemetry & logs -----------------------------------------------------------------------

  setTelemetry(i, tm) {
    const r = this.robots[i];
    if (!r) return;
    r.telemetry = tm;
    if (i === this.current) this.renderTelemetry();
  }

  renderTelemetry() {
    const el = $('telemetry');
    const tm = this.r.telemetry;
    if (!tm) {
      el.className = 'telemetry';
      el.innerHTML = '<span class="empty">No telemetry</span>';
      $('telemetryLog').textContent = '';
      return;
    }
    el.className = 'telemetry' + (tm.format === 'CLASSIC' ? ' classic' : '');
    if (!tm.lines.length) el.innerHTML = '<span class="empty">No telemetry</span>';
    else if (tm.format === 'HTML') el.innerHTML = tm.lines.map((l) => sanitizeHtml(l)).join('<br>');
    else el.textContent = tm.lines.join('\n');
    $('telemetryLog').textContent = (tm.log || []).join('\n');
  }

  /** A Robot log line; robot -1 (the simulator itself) goes to every robot's log. */
  addLog(i, entry) {
    const targets = i >= 0 ? [i] : this.robots.map((_, k) => k);
    for (const k of targets) {
      const r = this.robots[k];
      if (!r) continue;
      r.log.push(entry);
      if (r.log.length > MAX_LOG) r.log.shift();
      if (k === this.current) this.appendLogLine(entry);
    }
  }

  logLine(entry) {
    const div = document.createElement('div');
    div.className = entry.level;
    div.textContent = `${entry.level}/${entry.tag}: ${entry.msg}`;
    return div;
  }

  appendLogLine(entry) {
    const el = $('robotLog');
    const atBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 4;
    el.appendChild(this.logLine(entry));
    while (el.childNodes.length > MAX_LOG) el.removeChild(el.firstChild);
    if (atBottom) el.scrollTop = el.scrollHeight;
  }

  renderLog() {
    const el = $('robotLog');
    el.replaceChildren(...this.r.log.map((e) => this.logLine(e)));
    el.scrollTop = el.scrollHeight;
  }

  // ---- devices -------------------------------------------------------------------------------

  setDevices(i, d) {
    const r = this.robots[i];
    if (!r) return;
    r.devices = d;
    if (i === this.current) this.renderDevices();
  }

  renderDevices() {
    const d = this.r.devices || { motors: [], servos: [], sensors: {}, hubs: [] };
    const bar = (v) => {
      if (v === null || v === undefined) return '<span class="bar"></span>';
      const w = Math.min(1, Math.abs(v)) * 23;
      return `<span class="bar"><i class="${v < 0 ? 'neg' : ''}" style="left:${v < 0 ? 23 - w : 23}px;width:${w}px"></i></span>`;
    };
    const fmt = (v, n = 2) => (v === null || v === undefined ? '–' : Number(v).toFixed(n));
    $('motorTable').tBodies[0].innerHTML = d.motors.map((m) => {
      const power = m.power !== null ? `${bar(m.power)}${fmt(m.power)}` : `${bar(m.duty)}v ${m.velocityTarget}`;
      const target = m.target !== null && m.mode === 'RUN_TO_POSITION' ? ` → ${m.target}` : '';
      const mode = { RUN_WITHOUT_ENCODER: 'NO_ENC', RUN_USING_ENCODER: 'USING_ENC', RUN_TO_POSITION: 'TO_POS', STOP_AND_RESET_ENCODER: 'RESET' }[m.mode] || m.mode;
      return `<tr title="${esc(m.hub)} motor port ${m.port}, ${esc(m.zpb)}"><td>${esc(m.name)}</td><td>${mode}</td><td>${power}</td><td>${m.pos}${target}</td><td>${m.vel}</td><td>${fmt(m.amps, 1)}</td></tr>`;
    }).join('');
    $('servoTable').tBodies[0].innerHTML = d.servos.map((s) =>
      `<tr title="${esc(s.hub)} servo port ${s.port}"><td>${esc(s.name)}${s.continuous ? ' (CR)' : ''}</td><td>${s.pos === null ? '–' : fmt(s.pos, 3)}</td><td>${fmt(s.angle, 1)}°</td><td>${s.pwm ? 'on' : 'off'}</td></tr>`).join('');
    $('sensorTable').tBodies[0].innerHTML = Object.entries(d.sensors).map(([name, v]) => {
      let text;
      if (v === null) text = 'out of range';
      else if (typeof v === 'boolean') text = v ? 'pressed' : 'released';
      else if (typeof v === 'number') text = `${(v * 100).toFixed(1)} cm`;
      else if (Array.isArray(v) && v.length === 4) text = `r ${v[0].toFixed(3)} g ${v[1].toFixed(3)} b ${v[2].toFixed(3)} a ${v[3].toFixed(3)}`;
      else if (Array.isArray(v) && v.length === 3 && Math.abs(v[0]) > 5) text = `x ${v[0].toFixed(0)} mm  y ${v[1].toFixed(0)} mm  h ${v[2].toFixed(1)}°`;
      else if (Array.isArray(v)) text = `x ${(v[0] / 0.0254).toFixed(1)} in  y ${(v[1] / 0.0254).toFixed(1)} in  h ${(v[2] * 180 / Math.PI).toFixed(1)}°`;
      else text = JSON.stringify(v);
      return `<tr><td>${esc(name)}</td><td>${esc(text)}</td></tr>`;
    }).join('');
    $('hubs').innerHTML = d.hubs.map((h) => `<span><i class="led" style="background:${esc(h.led)}"></i>${esc(h.name)} · bulk cache ${esc(h.cache)}</span>`).join('');
  }

  // ---- gamepads ------------------------------------------------------------------------------

  buildGamepadViz(slot) {
    const viz = document.querySelector(`#gp${slot} .gp-viz`);
    viz.innerHTML = '<div class="stick"><i></i></div><div class="stick"><i></i></div><div class="btns">' +
      BUTTON_LABELS.map((l) => `<span>${l}</span>`).join('') + '</div>';
  }

  renderGamepadSources() {
    const opts = this.input.options();
    for (const slot of [1, 2]) {
      const sel = $(`gp${slot}Source`);
      const cur = this.input.source(this.current, slot);
      sel.innerHTML = opts.map((o) => `<option value="${esc(o.value)}">${esc(o.label)}</option>`).join('');
      sel.value = opts.some((o) => o.value === cur) ? cur : 'auto';
      sel.title = `Now: ${this.input.describe(this.current, slot)}`;
    }
    const kb = this.input.keyboardSlot();
    $('keys').hidden = !kb;
    if (kb) {
      const who = this.robots.length > 1 ? `Robot ${kb[0] + 1} gamepad ${kb[1]}` : `gamepad ${kb[1]}`;
      $('keysTitle').textContent = `Keyboard → ${who}`;
    }
  }

  renderGamepads() {
    for (const slot of [1, 2]) {
      const st = this.input.states[`${this.current}:${slot}`];
      const root = document.querySelector(`#gp${slot} .gp-viz`);
      const sticks = root.querySelectorAll('.stick');
      const place = (el, x, y) => {
        el.classList.toggle('active', Math.hypot(x, y) > 0.05);
        el.firstChild.style.transform = `translate(${x * 11}px, ${y * 11}px)`;
      };
      const axes = st ? st.axes : [0, 0, 0, 0, 0, 0];
      place(sticks[0], axes[0], axes[1]);
      place(sticks[1], axes[2], axes[3]);
      const spans = root.querySelectorAll('.btns span');
      spans.forEach((s, i) => {
        const on = st && (i === 6 ? axes[4] > 0.2 : i === 7 ? axes[5] > 0.2 : st.buttons[i]);
        s.classList.toggle('on', !!on);
      });
      document.querySelector(`#gp${slot} .gp-name`).textContent = `Gamepad ${slot}` + (st?.source ? ` · ${this.input.describe(this.current, slot)}` : '');
    }
  }
}
