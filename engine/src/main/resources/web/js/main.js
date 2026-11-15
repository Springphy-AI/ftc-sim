// Connects the browser Driver Station to the simulator over a WebSocket.
// With several robots on the field, the side panel has a card per robot; clicking one shows its
// details. ?robot=N opens a page that shows and drives only robot N (one page per driver).
import { FieldView } from './field3d.js';
import { InputManager } from './input.js';
import { DriverStation } from './ds.js';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
let ws = null;
let freshConnection = false;   // the next hello starts a new session with the simulator
const send = (msg) => {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(typeof msg === 'string' ? msg : JSON.stringify(msg));
};

const param = Number(new URLSearchParams(location.search).get('robot'));
const locked = Number.isInteger(param) && param >= 1 ? param - 1 : null;
let robots = [];          // from the hello message
let robotFiles = [];
let current = locked ?? 0;
try {
  const saved = Number(sessionStorage.getItem('ftcsim.robot'));
  if (locked === null && Number.isInteger(saved) && saved >= 0) current = saved;
} catch { /* storage unavailable */ }

const view = new FieldView($('scene'));
const input = new InputManager(send);
const ds = new DriverStation(send, input);

// camera buttons
for (const b of document.querySelectorAll('[data-cam]')) {
  b.addEventListener('click', () => {
    for (const o of document.querySelectorAll('[data-cam]')) o.classList.toggle('on', o === b);
    view.setCameraMode(b.dataset.cam);
  });
}

// placing the robot on screen
const placeBtn = $('placeBtn');
const setPlacing = (on) => {
  placeBtn.classList.toggle('on', on);
  $('placeHint').hidden = !on;
  view.setPlaceMode(on, (x, y, h) => {
    send({ type: 'place', robot: current, x, y, h });
    setPlacing(false);
  });
};
placeBtn.addEventListener('click', () => setPlacing(!placeBtn.classList.contains('on')));
window.addEventListener('keydown', (e) => { if (e.key === 'Escape') setPlacing(false); });
$('resetBtn').addEventListener('click', () => send({ type: 'reset', robot: current }));
$('robotSelect').addEventListener('change', (e) => send({ type: 'robot', robot: current, file: e.target.value }));

/** Shows robot i's Driver Station (and points the chase camera and Place robot at it). */
function showRobot(i) {
  current = i;
  try { if (locked === null) sessionStorage.setItem('ftcsim.robot', String(i)); } catch { /* storage unavailable */ }
  ds.show(i);
  input.setCurrent(i);
  view.setFocus(i);
  renderTopBar();
}

/** The top bar describes the robot whose details are shown: its TeamCode and its robot file. */
function renderTopBar() {
  const several = robots.length > 1;
  const r = robots[current];
  if (!r) return;
  const name = `Robot ${current + 1}`;
  $('project').textContent = (several ? `${name} TeamCode: ` : '') + r.project;
  $('project').title = `TeamCode: ${r.project}`;
  $('robotPickName').textContent = several ? `${name} file` : 'Robot';
  const sel = $('robotSelect');
  sel.innerHTML = robotFiles.map((f) => `<option>${esc(f)}</option>`).join('');
  sel.value = r.robotFile;
  sel.title = r.robot.description || r.robot.name;
  placeBtn.textContent = several ? `Place robot ${current + 1}` : 'Place robot';
  $('resetBtn').title = several
    ? 'Power-cycle every robot, put them back at their start positions and restage the game pieces'
    : 'Power-cycle the robot, put it back at its start position and restage the game pieces';
  if (locked !== null) document.title = `Robot ${current + 1} · FTC Simulator`;
}

ds.onPick((i) => showRobot(i));

ds.onDsChange((i, st) => {
  if (robots[i] && st.robotFile) robots[i].robotFile = st.robotFile;
});

function setConnection(ok, text) {
  $('conn').className = 'conn ' + (ok ? 'ok' : 'bad');
  $('connText').textContent = text;
}

function onMessage(msg) {
  switch (msg.type) {
    case 'hello': {
      if (freshConnection) {
        ds.reset();   // nothing the previous connection said is current any more
        freshConnection = false;
      }
      robots = msg.robots;
      robotFiles = msg.robotFiles;
      if (current >= robots.length) current = locked !== null ? robots.length - 1 : 0;
      view.setField(msg.field);
      view.setRobots(robots);
      view.setBalls(msg.balls);
      ds.setRobots(robots);
      ds.setMulti(robots.length > 1 && locked === null);
      input.setRobots(robots.length, current, locked === null ? null : current);
      showRobot(current);
      break;
    }
    case 'state': {
      view.updateState(msg);
      msg.robots.forEach((r, i) => ds.setTimer(i, r.ds));
      const r = msg.robots[current];
      if (r) $('battery').textContent = `${r.battery.toFixed(1)} V`;
      $('rtf').textContent = `${Math.round(msg.rtf * 100)}%`;
      const p = view.pose(current);
      if (p) {
        $('pose').textContent = `x ${(p.x / 0.0254).toFixed(1).padStart(6)} in   y ${(p.y / 0.0254).toFixed(1).padStart(6)} in   heading ${p.h.toFixed(1).padStart(6)}°`;
      }
      break;
    }
    case 'devices':
      msg.robots.forEach((d, i) => {
        ds.setDevices(i, d);
        if (d.hubs.length) view.setHubLed(i, d.hubs[0].led);
      });
      break;
    case 'telemetry':
      ds.setTelemetry(msg.robot, msg);
      break;
    case 'ds':
      ds.setDs(msg.robot, msg);
      break;
    case 'build':
      ds.setBuild(msg.robot, msg);
      break;
    case 'log':
      ds.addLog(msg.robot, msg);
      break;
    case 'rumble':
      input.rumble(msg.robot, msg.gamepad, msg.strong, msg.weak, msg.durations);
      break;
    default:
  }
}

function connect() {
  ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`);
  ws.onopen = () => {
    freshConnection = true;
    setConnection(true, 'Connected');
  };
  ws.onmessage = (e) => {
    try { onMessage(JSON.parse(e.data)); } catch (err) { console.error(err); }
  };
  ws.onclose = () => {
    setConnection(false, 'Disconnected – retrying');
    setTimeout(connect, 1000);
  };
}
connect();
