// Gamepad and keyboard input for every robot's gamepad1/gamepad2, sent to the simulator 50 times a second.
// Button layout follows the W3C "standard" gamepad mapping, which is also what the simulator expects:
// 0 A, 1 B, 2 X, 3 Y, 4 LB, 5 RB, 6 LT, 7 RT, 8 back, 9 start, 10 LS, 11 RS,
// 12 up, 13 down, 14 left, 15 right, 16 guide, 17 touchpad.

const N_BUTTONS = 18;
const DEADZONE = 0.06;
const KEY_BUTTONS = {
  KeyK: 0, KeyL: 1, KeyJ: 2, KeyI: 3, KeyQ: 4, KeyE: 5,
  BracketLeft: 8, BracketRight: 9, KeyT: 12, KeyG: 13, KeyF: 14, KeyH: 15, Backslash: 16,
};
const KEY_CAPTURE = new Set(['KeyW', 'KeyA', 'KeyS', 'KeyD', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'KeyZ', 'KeyC',
  ...Object.keys(KEY_BUTTONS)]);
// Inputs you type into; the keys stay theirs. A checkbox, slider or dropdown doesn't take text.
const TEXT_INPUTS = new Set(['text', 'search', 'email', 'password', 'url', 'tel', 'number']);
const SLOTS = [1, 2];

function kindOf(id) {
  if (/xbox|xinput|045e/i.test(id)) return 'XBOX_360';
  if (/054c|dualshock|dualsense|playstation|wireless controller/i.test(id)) return 'SONY_PS4';
  if (/logitech|046d/i.test(id)) return 'LOGITECH_F310';
  return 'UNKNOWN';
}

function shortName(id) {
  const m = id.match(/^(.*?)\s*\(/);
  return (m ? m[1] : id).slice(0, 40) || 'Controller';
}

function stick(x, y) {
  const mag = Math.hypot(x, y);
  if (mag < DEADZONE) return [0, 0];
  const scale = Math.min(1, (mag - DEADZONE) / (1 - DEADZONE)) / mag;
  return [x * scale, y * scale];
}

const key = (robot, slot) => `${robot}:${slot}`;

export class InputManager {
  constructor(send) {
    this.send = send;
    this.keys = new Set();
    this.tapped = new Set();
    this.shift = false;
    this.robotCount = 1;
    this.current = 0;        // the robot whose Driver Station is on screen
    this.locked = null;      // ?robot=N: this page only drives robot N
    this.sources = {};       // 'robot:slot' -> 'auto' | 'keyboard' | 'pad:N' | 'none'
    this.lastSent = {};
    this.lastTime = {};
    this.fed = {};           // 'robot:slot' -> true while this page feeds it from a controller or the keyboard
    this.states = {};
    this.listeners = [];

    window.addEventListener('keydown', (e) => this.onKey(e, true));
    window.addEventListener('keyup', (e) => this.onKey(e, false));
    window.addEventListener('blur', () => { this.keys.clear(); this.shift = false; });
    window.addEventListener('gamepadconnected', () => this.emit());
    window.addEventListener('gamepaddisconnected', () => this.emit());
    setInterval(() => this.tick(), 20);
  }

  onChange(fn) { this.listeners.push(fn); }

  emit() { for (const fn of this.listeners) fn(); }

  /** How many robots are on the field, and which one this page shows (and drives, if locked). */
  setRobots(count, current, locked) {
    this.robotCount = count;
    this.current = current;
    this.locked = locked;
    this.emit();
  }

  setCurrent(robot) {
    this.current = robot;
    this.emit();
  }

  /** Robots this page sends gamepad input for: none when its ?robot=N isn't on the field. */
  activeRobots() {
    if (this.locked !== null) return this.locked < this.robotCount ? [this.locked] : [];
    return Array.from({ length: this.robotCount }, (_, i) => i);
  }

  /** Whether keys pressed in {@code target} are text being typed (and not for driving). */
  /** The season's human player keys (main.js): like the driving keys, a focused dropdown lets go of them. */
  setPageKeys(codes) {
    this.pageKeys = new Set([...KEY_CAPTURE, ...codes]);
  }

  typingIn(target) {
    if (!target) return false;
    if (target.isContentEditable || target.tagName === 'TEXTAREA') return true;
    return target.tagName === 'INPUT' && TEXT_INPUTS.has((target.type || 'text').toLowerCase());
  }

  onKey(e, down) {
    if (e.key === 'Shift') this.shift = down;
    // A key let go always lets go, wherever the focus went and whatever else is held: held to drive
    // and released in the code editor, or after pressing Ctrl, it would otherwise drive on by
    // itself. macOS sends no key-up for keys released while Cmd is down, so letting go of Cmd
    // lets go of everything.
    if (!down) {
      this.keys.delete(e.code);
      if (e.key === 'Meta') this.keys.clear();
    }
    if (e.ctrlKey || e.metaKey || e.altKey || this.typingIn(e.target)) return;
    // A dropdown or checkbox keeps the focus after a mouse click on it, and would take the driving
    // keys: arrows and letters change a dropdown's choice (a robot file, an OpMode). It lets go
    // instead, and the key drives.
    const tag = e.target?.tagName;
    if (down && (tag === 'SELECT' || tag === 'INPUT') && (this.pageKeys ?? KEY_CAPTURE).has(e.code)) {
      e.preventDefault();
      e.target.blur();
    }
    if (!KEY_CAPTURE.has(e.code)) return;
    if (down) {
      this.keys.add(e.code);
      this.tapped.add(e.code);   // seen down at least once before the next send, even if released already
    }
    if (e.code.startsWith('Arrow')) e.preventDefault();
  }

  pads() {
    return Array.from(navigator.getGamepads ? navigator.getGamepads() : []).filter((p) => p && p.connected);
  }

  /** Options for the source pickers. */
  options() {
    const opts = [{ value: 'auto', label: 'Auto' }, { value: 'keyboard', label: 'Keyboard' }];
    for (const p of this.pads()) opts.push({ value: `pad:${p.index}`, label: `${p.index + 1}: ${shortName(p.id)}` });
    opts.push({ value: 'none', label: 'None' });
    return opts;
  }

  /** The picker setting: gamepad 1 is Auto and gamepad 2 None until changed. */
  source(robot, slot) {
    return this.sources[key(robot, slot)] ?? (slot === 1 ? 'auto' : 'none');
  }

  setSource(robot, slot, value) {
    this.sources[key(robot, slot)] = value;
    this.emit();
  }

  /**
   * What feeds every slot right now: a Gamepad, 'keyboard', or null. Auto slots take the controllers
   * nobody picked explicitly, every robot's gamepad 1 first (in robot order), then gamepad 2s set to Auto.
   * The keyboard goes to one Auto gamepad 1 still without a controller: the robot on screen if it is one
   * of them, unless a slot picked the keyboard explicitly.
   */
  assignments() {
    const pads = this.pads();
    const robots = this.activeRobots();
    const out = {};
    const explicitPads = new Set();
    let keyboardTaken = false;
    for (const r of robots) {
      for (const s of SLOTS) {
        const src = this.source(r, s);
        if (src.startsWith('pad:')) explicitPads.add(Number(src.slice(4)));
        if (src === 'keyboard') keyboardTaken = true;
      }
    }
    const free = pads.filter((p) => !explicitPads.has(p.index));
    const autos = [];
    for (const s of SLOTS) for (const r of robots) if (this.source(r, s) === 'auto') autos.push([r, s]);
    for (const r of robots) {
      for (const s of SLOTS) {
        const src = this.source(r, s);
        if (src === 'none' || src === 'auto') out[key(r, s)] = null;
        else if (src === 'keyboard') out[key(r, s)] = 'keyboard';
        else out[key(r, s)] = pads.find((p) => p.index === Number(src.slice(4))) || null;
      }
    }
    for (const [r, s] of autos) out[key(r, s)] = free.shift() || null;
    if (!keyboardTaken) {
      const waiting = autos.filter(([r, s]) => s === 1 && out[key(r, s)] === null).map(([r]) => r);
      const pick = waiting.includes(this.current) ? this.current : waiting[0];
      if (pick !== undefined) out[key(pick, 1)] = 'keyboard';
    }
    return out;
  }

  resolve(robot, slot) {
    return this.assignments()[key(robot, slot)] ?? null;
  }

  describe(robot, slot) {
    const r = this.resolve(robot, slot);
    if (r === null) return 'not assigned';
    if (r === 'keyboard') return 'keyboard';
    return shortName(r.id);
  }

  read(r) {
    const buttons = new Array(N_BUTTONS).fill(false);
    let axes = [0, 0, 0, 0, 0, 0];
    let kind = 'UNKNOWN';
    if (r === 'keyboard') {
      const k = (c) => (this.keys.has(c) || this.tapped.has(c) ? 1 : 0);
      const s = this.shift ? 0.4 : 1;
      axes = [(k('KeyD') - k('KeyA')) * s, (k('KeyS') - k('KeyW')) * s, (k('ArrowRight') - k('ArrowLeft')) * s,
        (k('ArrowDown') - k('ArrowUp')) * s, k('KeyZ'), k('KeyC')];
      for (const [code, i] of Object.entries(KEY_BUTTONS)) buttons[i] = this.keys.has(code) || this.tapped.has(code);
      kind = 'XBOX_360';
    } else if (r) {
      const [lx, ly] = stick(r.axes[0] || 0, r.axes[1] || 0);
      const [rx, ry] = stick(r.axes[2] || 0, r.axes[3] || 0);
      axes = [lx, ly, rx, ry, r.buttons[6]?.value || 0, r.buttons[7]?.value || 0];
      for (let i = 0; i < N_BUTTONS && i < r.buttons.length; i++) buttons[i] = !!r.buttons[i]?.pressed;
      kind = kindOf(r.id);
    }
    return { axes: axes.map((v) => Math.round(v * 1000) / 1000), buttons, kind, source: r === null ? null : (r === 'keyboard' ? 'keyboard' : 'pad') };
  }

  tick() {
    const now = performance.now();
    const assign = this.assignments();
    for (const robot of this.activeRobots()) {
      for (const slot of SLOTS) {
        const k = key(robot, slot);
        const source = assign[k] ?? null;
        const st = this.read(source);
        this.states[k] = st;
        const msg = JSON.stringify({ type: 'gamepad', robot, gamepad: slot, kind: st.kind, axes: st.axes, buttons: st.buttons });
        if (source === null) {
          // Nothing feeds this slot from here, so this page says nothing about it: another page (a
          // driver's ?robot=N page) may be driving it. Only a slot fed until now gets one neutral
          // state, so a controller taken away doesn't leave the robot driving.
          if (this.fed[k]) {
            this.send(msg);
            this.lastSent[k] = msg;
            this.lastTime[k] = now;
            this.fed[k] = false;
          }
          continue;
        }
        this.fed[k] = true;
        if (msg !== this.lastSent[k] || now - (this.lastTime[k] ?? 0) > 250) {
          this.send(msg);
          this.lastSent[k] = msg;
          this.lastTime[k] = now;
        }
      }
    }
    this.tapped.clear();
  }

  /** The [robot, slot] the keyboard drives, or null. */
  keyboardSlot() {
    const assign = this.assignments();
    for (const robot of this.activeRobots()) for (const slot of SLOTS) if (assign[key(robot, slot)] === 'keyboard') return [robot, slot];
    return null;
  }

  /** Plays a rumble effect sent by robot code (gamepad1.rumble(...)). */
  rumble(robot, slot, strong, weak, durations) {
    const pad = this.resolve(robot, slot);
    if (!pad || pad === 'keyboard' || !pad.vibrationActuator) return;
    let delay = 0;
    for (let i = 0; i < durations.length; i++) {
      const effect = { duration: Math.max(0, durations[i]), strongMagnitude: strong[i] || 0, weakMagnitude: weak[i] || 0 };
      setTimeout(() => { pad.vibrationActuator.playEffect('dual-rumble', effect).catch(() => {}); }, delay);
      delay += durations[i];
    }
  }
}
