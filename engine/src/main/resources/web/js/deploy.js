// Deploying: puts the shown robot's TeamCode on the real robot, as Android Studio's Run button does.
// The simulator runs the robot project's own Gradle build (installDebug) with the Android SDK's adb,
// on the Control Hub or phone picked here (USB, or Wi-Fi), and the Robot Controller app restarts.
import { api } from './code.js';

const $ = (id) => document.getElementById(id);
const MAX_LOG_LINES = 3000;

export class DeployPanel {
  /** @param code the CodeEditor: deploying saves its open file first */
  constructor(code) {
    this.code = code;
    this.robot = 0;
    this.robotCount = 1;
    this.info = null;            // the shown robot's project, SDK and devices
    this.targets = new Map();    // robot -> chosen serial ('' = build only)
    this.runs = new Map();       // robot -> { state, message, log }
    $('deployBtn').addEventListener('click', () => this.setOpen($('deployPanel').hidden));
    $('deployRefresh').addEventListener('click', () => this.refresh());
    $('deployConnect').addEventListener('click', () => this.connectHub());
    $('deployGo').addEventListener('click', () => this.start());
    $('deployCancel').addEventListener('click', () => api('POST', `/deploy/${this.robot}/cancel`).catch(() => {}));
    $('deployTarget').addEventListener('change', (e) => { this.targets.set(this.robot, e.target.value); this.render(); });
  }

  setOpen(open) {
    $('deployPanel').hidden = !open;
    $('deployBtn').classList.toggle('on', open);
    if (open) this.refresh();
  }

  get isOpen() { return !$('deployPanel').hidden; }

  setRobots(n) { this.robotCount = n; }

  setRobot(i) {
    this.robot = i;
    this.info = null;
    this.render();
    if (this.isOpen) this.refresh();
  }

  run(i = this.robot) {
    if (!this.runs.has(i)) this.runs.set(i, { state: 'idle', message: '', log: [], at: 0 });
    return this.runs.get(i);
  }

  async refresh() {
    const i = this.robot;
    this.note('Looking for robots…');
    const asked = performance.now();
    try {
      const info = await api('GET', `/deploy/${i}/devices`);
      this.reconcile(info.busyRobot, asked);
      if (i !== this.robot) return;
      this.info = info;
      this.note('');
    } catch (e) {
      this.note(`Can't look for robots: ${e.message}`, 'err');
    }
    this.renderTargets();
    this.render();
  }

  /** ADB to a Control Hub over its own Wi-Fi network (the computer must have joined it). */
  async connectHub() {
    const i = this.robot;
    const address = this.info?.controlHub ?? '192.168.43.1:5555';
    this.note(`Connecting to a Control Hub at ${address}… (join the robot's Wi-Fi network first)`);
    $('deployConnect').disabled = true;
    try {
      const res = await fetch(`/deploy/${i}/connect`, {
        method: 'POST', cache: 'no-store',
        headers: { 'X-Ftcsim-Code': '1', 'Content-Type': 'application/json' },
        body: JSON.stringify({ address }),
      });
      if (!res.ok) throw new Error(await res.text());
      this.info = await res.json();
      const said = this.info.adb || '';
      this.renderTargets();
      const found = this.info.devices.some((d) => d.serial === address && d.state === 'device');
      if (found) this.targets.set(i, address);
      this.renderTargets();
      this.note(found ? `Connected to the Control Hub (${address}).` : `adb: ${said || 'no answer'}. Is this computer on the robot's Wi-Fi?`, found ? 'ok' : 'warn');
    } catch (e) {
      this.note(`Not connected: ${e.message}`, 'err');
    } finally {
      $('deployConnect').disabled = false;
      this.render();
    }
  }

  async start() {
    const i = this.robot;
    // What's in the editor is what goes on the robot: save it first.
    const dirty = this.code.dirtyFiles();
    if (dirty.length) {
      if (dirty.length === 1 && dirty[0] === this.code.buffer) {
        if (!(await this.code.save())) return;
      } else {
        this.note(`Save your changes first: ${dirty.map((b) => b.path.split('/').pop()).join(', ')}`, 'err');
        return;
      }
    }
    const serial = $('deployTarget').value || null;
    const r = this.run(i);
    r.state = 'running';
    r.message = 'Starting…';
    r.at = performance.now();
    r.log = [];
    this.render();
    try {
      await api('POST', `/deploy/${i}/start`, { serial });
    } catch (e) {
      r.state = 'failed';
      r.message = e.message;
      r.at = performance.now();
      this.render();
    }
  }

  /**
   * What the simulator says is deploying (as of {@code asked}) wins over what this page saw: a page
   * opened during a deploy saw no start, and one that lost its connection may have missed the end.
   */
  reconcile(busyRobot, asked) {
    for (const [robot, r] of this.runs) {
      if (r.state === 'running' && robot !== busyRobot && r.at < asked) {
        r.state = 'unknown';
        r.message = 'This page missed how the deploy ended (it lost its connection to the simulator). Check the robot, or deploy again.';
        r.at = performance.now();
      }
    }
    if (busyRobot >= 0 && this.run(busyRobot).state !== 'running') {
      const r = this.run(busyRobot);
      r.state = 'running';
      r.message = 'Deploying (it started before this page connected)…';
      r.at = performance.now();
    }
  }

  /** A "deploy" message from the simulator. */
  onMessage(msg) {
    const r = this.run(msg.robot);
    r.state = msg.state;
    r.at = performance.now();
    // Which robot is deploying, as the simulator would answer now.
    if (this.info) {
      if (msg.state === 'running') this.info.busyRobot = msg.robot;
      else if (this.info.busyRobot === msg.robot) this.info.busyRobot = -1;
    }
    if (msg.message) r.message = msg.message;
    if (msg.lines) {
      r.log.push(...msg.lines);
      if (r.log.length > MAX_LOG_LINES) r.log.splice(0, r.log.length - MAX_LOG_LINES);
    }
    if (msg.robot === this.robot) this.render(!!msg.lines);
  }

  // ---- rendering --------------------------------------------------------------------------------

  note(text, kind = '') {
    this.noteText = { text, kind, at: performance.now() };
    this.render();
  }

  renderTargets() {
    const sel = $('deployTarget');
    const devices = this.info?.devices ?? [];
    const want = this.targets.get(this.robot);
    sel.replaceChildren();
    for (const d of devices) {
      const o = document.createElement('option');
      o.value = d.serial;
      const name = d.model ? `${d.model} (${d.serial})` : d.serial;
      const why = { unauthorized: 'allow USB debugging on it', offline: 'offline' }[d.state] ?? (d.state !== 'device' ? d.state : '');
      o.textContent = why ? `${name}: ${why}` : name;
      o.disabled = d.state !== 'device';
      sel.append(o);
    }
    const none = document.createElement('option');
    none.value = '';
    none.textContent = devices.length ? 'No robot: only check that it builds' : 'No robot found: only check that it builds';
    sel.append(none);
    const usable = devices.filter((d) => d.state === 'device').map((d) => d.serial);
    sel.value = want !== undefined && (want === '' || usable.includes(want)) ? want : (usable[0] ?? '');
  }

  render(scrollLog = false) {
    const several = this.robotCount > 1;
    const r = this.run();
    const running = r.state === 'running';
    const otherBusy = this.info && this.info.busyRobot >= 0 && this.info.busyRobot !== this.robot && !running;
    const problem = this.info?.problem;
    const target = $('deployTarget').value;
    $('deployGo').textContent = target ? `⇪ Deploy${several ? ` robot ${this.robot + 1}` : ''}` : 'Check the robot build';
    $('deployGo').title = target
      ? 'Save, build the Robot Controller app with this TeamCode (the project\'s own Gradle build), install it on the robot and restart it'
      : 'Build the Robot Controller app with this TeamCode, without installing it anywhere';
    $('deployGo').disabled = running || !!problem || !this.info;
    $('deployCancel').hidden = !running;
    $('deployTarget').disabled = running;
    $('deployConnect').disabled = running || !this.info || !!(problem && !this.info.sdk);
    // What to say: a missing tool, else whichever is newer of the last deploy and the last note.
    const msg = $('deployMsg');
    const note = this.noteText?.text ? this.noteText : null;
    let text = '', kind = '';
    if (problem) { text = problem; kind = 'err'; }
    else if (otherBusy) { text = `Robot ${this.info.busyRobot + 1} is being deployed; one at a time.`; kind = 'warn'; }
    else if (r.state !== 'idle' && (running || !note || r.at > note.at)) {
      text = r.message;
      kind = { ok: 'ok', failed: 'err', canceled: 'warn', unknown: 'warn' }[r.state] ?? '';
    } else if (note) { text = note.text; kind = note.kind; }
    else if (this.info) text = `Builds ${this.info.project} with its own Gradle wrapper, as Android Studio does.`;
    msg.textContent = text;
    msg.className = 'deploy-msg' + (kind ? ' ' + kind : '');
    const log = $('deployLog');
    const atBottom = log.scrollTop + log.clientHeight >= log.scrollHeight - 4;
    log.textContent = r.log.join('\n');
    log.hidden = r.log.length === 0;
    if (scrollLog && atBottom) log.scrollTop = log.scrollHeight;
  }
}
