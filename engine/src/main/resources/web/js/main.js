// Connects the browser Driver Station to the simulator over a WebSocket.
// With several robots on the field, the side panel has a card per robot; clicking one shows its
// details. ?robot=N opens a page that shows and drives only robot N (one page per driver).
import { FieldView } from './field3d.js';
import { InputManager } from './input.js';
import { DriverStation } from './ds.js';
import { convertIfNeeded } from './cadconvert.js';

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

// Human player: one NECTAR from the ALLIANCE AREA into that alliance's LOADING ZONE.
const addNectar = (color) => send({ type: 'nectar', color });
$('nectarRedBtn').addEventListener('click', () => addNectar('red'));
$('nectarBlueBtn').addEventListener('click', () => addNectar('blue'));
window.addEventListener('keydown', (e) => {
  if (e.repeat || e.ctrlKey || e.metaKey || e.altKey || input.typingIn(e.target)) return;
  if (e.code === 'KeyN') addNectar('red');
  else if (e.code === 'KeyM') addNectar('blue');
});
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
  $('cadImportBtn').title = `Make ${several ? `robot ${current + 1}` : 'this robot'} from your CAD: pick the STEP file of its assembly, or drop it on the page`;
  // With a card per robot, each card has its robot file menu and "+ From CAD" instead.
  const cards = several && locked === null;
  $('robotPick').hidden = cards;
  $('cadImportBtn').hidden = cards;
  $('resetBtn').title = several
    ? 'Power-cycle every robot, put them back at their start positions and restage the game pieces'
    : 'Power-cycle the robot, put it back at its start position and restage the game pieces';
  if (locked !== null) document.title = `Robot ${current + 1} · FTC Simulator`;
}

ds.onPick((i) => showRobot(i));

ds.onDsChange((i, st) => {
  if (robots[i] && st.robotFile) robots[i].robotFile = st.robotFile;
});

// ---- CAD models: conversion progress and problems --------------------------------------------
let cadNoticeTimer = null;
function cadNotice(text, kind = '', hideAfterMs = 0) {
  const el = $('cadNotice');
  clearTimeout(cadNoticeTimer);
  el.hidden = !text;
  el.textContent = text || '';
  el.className = 'cad-notice' + (kind ? ' ' + kind : '');
  if (hideAfterMs) cadNoticeTimer = setTimeout(() => { el.hidden = true; }, hideAfterMs);
}

let imported = null;       // {robot, file}: a robot just made from a STEP file, until it's built
let importShownAt = 0;

/** After a hello: converts STEP files that need it, and says what's wrong with CAD that can't be used. */
function checkCad() {
  const problems = [];
  robots.forEach((r, i) => {
    if (!r.cad) return;
    if (r.cad.status === 'error') problems.push(`Robot ${i + 1}: ${r.cad.message} (drawn from the robot file's numbers instead)`);
    if (r.cad.status === 'convert') {
      convertIfNeeded(i, r.cad, (text, done, failed) => {
        if (done && !failed && performance.now() - importShownAt < 3000) return;   // what it found is showing
        cadNotice(text, failed ? 'err' : (done ? 'ok' : ''), done && !failed ? 6000 : 0);
      });
    }
    if (r.cad.status === 'ready' && imported && imported.robot === i && imported.file === r.robotFile) {
      // What the simulator worked out by itself, and where to go from here.
      const notes = r.cad.notes || [];
      const lines = [`${robots.length > 1 ? `Robot ${i + 1}: ` : ''}${r.cad.message}`, ...notes.slice(0, 6)];
      if (notes.length > 6) lines.push(`… ${notes.length - 6} more in the robot log`);
      lines.push(`Arms, slides and claws: list them in cad.links in robots/${r.robotFile} (README: Robots from CAD).`);
      cadNotice(lines.join('\n'), 'ok', 30000);
      importShownAt = performance.now();
      imported = null;
    }
  });
  if (problems.length) cadNotice(problems.join('\n'), 'err');
}

// ---- a robot from CAD: pick or drop the STEP file of its assembly -----------------------------
async function importCad(file, i = current) {
  const who = robots.length > 1 ? `Robot ${i + 1}: ` : '';
  if (!/\.(step|stp)$/i.test(file.name)) {
    cadNotice(`${who}${file.name} isn't a STEP file. Export the robot's assembly as STEP (.step or .stp) from your CAD and use that.`, 'err', 12000);
    return;
  }
  cadNotice(`${who}sending ${file.name} (${(file.size / 1e6).toFixed(1)} MB)…`);
  try {
    const res = await fetch(`/cad/${i}/import?name=${encodeURIComponent(file.name)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream', 'X-Ftcsim-Upload': '1' },
      body: file,
    });
    if (!res.ok) throw new Error(await res.text() || `the simulator refused it (${res.status})`);
    const r = await res.json();
    imported = { robot: i, file: r.robotFile };
    cadNotice(`${who}${r.updated ? `updated robots/${r.step}` : `saved robots/${r.step} and robots/${r.robotFile}`}; building the robot…`);
  } catch (e) {
    cadNotice(`${who}${e.message}`, 'err');
  }
}
let cadTarget = null;     // the robot the file picker is choosing a STEP file for
const pickCad = (i) => {
  cadTarget = i;
  $('cadFile').click();
};
$('cadImportBtn').addEventListener('click', () => pickCad(current));
ds.onImportCad((i) => pickCad(i));
$('cadFile').addEventListener('change', (e) => {
  const f = e.target.files[0];
  e.target.value = '';
  if (f) importCad(f, cadTarget ?? current);
});

// Dropping a STEP file: on a robot's card in the Robots panel makes that robot, anywhere else
// the robot whose details are shown.
let dragDepth = 0;
const draggingFiles = (e) => [...(e.dataTransfer?.types || [])].includes('Files');
const dropTarget = (e) => {
  const card = e.target?.closest?.('.robot-row[data-robot]');
  return card ? Number(card.dataset.robot) : current;
};
const showDrop = (i) => {
  $('dropZone').textContent = `Drop the STEP file of your robot's assembly to make ${robots.length > 1 ? `robot ${i + 1}` : 'the robot'} from it`;
  $('dropZone').hidden = false;
  for (const card of document.querySelectorAll('.robot-row[data-robot]')) card.classList.toggle('drop', Number(card.dataset.robot) === i);
};
const hideDrop = () => {
  dragDepth = 0;
  $('dropZone').hidden = true;
  for (const card of document.querySelectorAll('.robot-row.drop')) card.classList.remove('drop');
};
window.addEventListener('dragenter', (e) => {
  if (!draggingFiles(e)) return;
  dragDepth++;
  showDrop(dropTarget(e));
});
window.addEventListener('dragleave', (e) => {
  if (draggingFiles(e) && --dragDepth <= 0) hideDrop();
});
window.addEventListener('dragover', (e) => {
  if (!draggingFiles(e)) return;
  e.preventDefault();
  showDrop(dropTarget(e));
});
window.addEventListener('drop', (e) => {
  if (!draggingFiles(e)) return;
  e.preventDefault();
  const i = dropTarget(e);
  hideDrop();
  const f = e.dataTransfer.files[0];
  if (f) importCad(f, i);
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
      ds.setRobotFiles(robotFiles);
      ds.setRobots(robots);
      ds.setMulti(robots.length > 1 && locked === null);
      input.setRobots(robots.length, current, locked === null ? null : current);
      showRobot(current);
      checkCad();
      break;
    }
    case 'state': {
      view.updateState(msg);
      msg.robots.forEach((r, i) => ds.setTimer(i, r.ds));
      const r = msg.robots[current];
      if (r) $('battery').textContent = `${r.battery.toFixed(1)} V`;
      $('rtf').textContent = `${Math.round(msg.rtf * 100)}%`;
      if (msg.nectarWaiting) {
        for (const [color, btn, left] of [['red', 'nectarRedBtn', 'nectarRedLeft'], ['blue', 'nectarBlueBtn', 'nectarBlueLeft']]) {
          const n = msg.nectarWaiting[color] ?? 0;
          $(left).textContent = `(${n})`;
          $(btn).disabled = n === 0;
        }
      }
      if (msg.cells) {
        for (const [alliance, id] of [['red', 'hiveRed'], ['blue', 'hiveBlue']]) {
          const tips = msg.hives?.[alliance]?.tips ?? 0;
          $(id).textContent = `${msg.cells[`${alliance} audience`] ?? 0} / ${msg.cells[`${alliance} rear`] ?? 0}`
            + (tips ? ` · ${tips} tip${tips > 1 ? 's' : ''}` : '');
        }
      }
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
