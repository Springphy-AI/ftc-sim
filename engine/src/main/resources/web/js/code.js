// The code editor: each robot's own TeamCode, edited in the page. Saving writes the file in that
// robot's project folder (the same file Android Studio deploys), the simulator rebuilds that
// robot, and the build's compile errors are underlined where they are. "Save & run" stops the
// robot's OpMode, saves, waits for the build, then INITs and STARTs the OpMode in the file (or the
// one selected in the Driver Station).
import { EditorView, basicSetup } from 'codemirror';
import { EditorState, Compartment, Prec } from '@codemirror/state';
import { keymap, placeholder } from '@codemirror/view';
import { indentWithTab } from '@codemirror/commands';
import { indentUnit, HighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { setDiagnostics, forEachDiagnostic, lintGutter } from '@codemirror/lint';
import { java } from '@codemirror/lang-java';
import { tags as t } from '@lezer/highlight';

const $ = (id) => document.getElementById(id);
const IDENT = /^[A-Za-z_$][A-Za-z0-9_$]*$/;
const DEFAULT_PACKAGE_DIR = 'org/firstinspires/ftc/teamcode';

/** Calls the simulator's code and deploy endpoints (they need the X-Ftcsim-Code header). */
export async function api(method, url, body) {
  const res = await fetch(url, {
    method,
    cache: 'no-store',
    headers: { 'X-Ftcsim-Code': '1', ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let data;
  try { data = JSON.parse(text); } catch { data = { error: text }; }
  if (!res.ok) {
    const e = new Error(data?.error || `the simulator answered ${res.status}`);
    e.status = res.status;
    e.data = data;
    throw e;
  }
  return data;
}

// Colors close to VS Code's dark theme, on the page's own panel colors.
const theme = EditorView.theme({
  '&': { height: '100%', backgroundColor: '#14171a', color: '#d4d4d4', fontSize: '13px' },
  '.cm-scroller': { fontFamily: 'ui-monospace, "Cascadia Mono", Consolas, "SF Mono", Menlo, monospace', lineHeight: '1.5' },
  '.cm-content': { caretColor: '#e7e9ec' },
  '.cm-cursor, .cm-dropCursor': { borderLeftColor: '#e7e9ec' },
  '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': { backgroundColor: '#264f78' },
  '.cm-gutters': { backgroundColor: '#14171a', color: '#5c6470', border: 'none' },
  '.cm-activeLineGutter': { backgroundColor: '#1d2126', color: '#aab2bc' },
  '.cm-activeLine': { backgroundColor: '#1a1e22' },
  '.cm-matchingBracket': { backgroundColor: 'rgba(62, 123, 250, .25)', outline: '1px solid rgba(62, 123, 250, .5)' },
  '.cm-searchMatch': { backgroundColor: 'rgba(240, 180, 41, .25)' },
  '.cm-searchMatch.cm-searchMatch-selected': { backgroundColor: 'rgba(240, 180, 41, .45)' },
  '.cm-panels': { backgroundColor: '#1f2328', color: '#e7e9ec' },
  '.cm-panels.cm-panels-bottom': { borderTop: '1px solid #2f353d' },
  '.cm-tooltip': { backgroundColor: '#1f2328', border: '1px solid #2f353d', color: '#e7e9ec' },
  '.cm-tooltip-lint': { maxWidth: '560px' },
  '.cm-diagnostic': { whiteSpace: 'pre-wrap', fontFamily: 'ui-monospace, Consolas, monospace', fontSize: '12px' },
  '.cm-tooltip-autocomplete > ul > li[aria-selected]': { backgroundColor: '#264f78', color: '#fff' },
  '.cm-foldPlaceholder': { backgroundColor: '#262b31', border: 'none', color: '#9aa3ad' },
}, { dark: true });

const highlight = HighlightStyle.define([
  { tag: [t.keyword, t.modifier, t.null, t.bool, t.self], color: '#569cd6' },
  { tag: [t.controlKeyword, t.moduleKeyword], color: '#c586c0' },
  { tag: [t.string, t.special(t.string), t.character], color: '#ce9178' },
  { tag: [t.number], color: '#b5cea8' },
  { tag: [t.comment, t.lineComment, t.blockComment, t.docComment], color: '#6a9955', fontStyle: 'italic' },
  { tag: [t.typeName, t.className, t.namespace], color: '#4ec9b0' },
  { tag: [t.function(t.variableName), t.function(t.propertyName)], color: '#dcdcaa' },
  { tag: [t.annotation, t.meta], color: '#dcdcaa' },
  { tag: [t.variableName, t.propertyName, t.definition(t.variableName)], color: '#9cdcfe' },
  { tag: [t.operator, t.punctuation, t.bracket], color: '#d4d4d4' },
  { tag: t.invalid, color: '#f44747' },
]);

/** A new OpMode, as Android Studio's FTC templates start one. */
function template(pkg, name, kind) {
  const label = name.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/\bOp Mode\b/g, 'OpMode').replace(/\bTele Op\b/g, 'TeleOp');
  return `${pkg ? `package ${pkg};\n\n` : ''}import com.qualcomm.robotcore.eventloop.opmode.${kind};
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

@${kind}(name = "${label}")
public class ${name} extends LinearOpMode {

    @Override
    public void runOpMode() {
        telemetry.addData("Status", "Initialized");
        telemetry.update();

        waitForStart();

        while (opModeIsActive()) {
            telemetry.addData("Status", "Running");
            telemetry.update();
        }
    }
}
`;
}

export class CodeEditor {
  /**
   * @param send   sends a Driver Station message over the WebSocket
   * @param layout the element whose class "coding" shows the editor beside the field
   * @param ds     what the Driver Station shows for a robot: samplesShown(i), whether it lists the
   *               FTC samples (the editor lists their files only then), and chosenOpMode(i), the
   *               OpMode in its menu (the editor opens its file)
   */
  constructor(send, layout, ds = {}) {
    this.send = send;
    this.layout = layout;
    this.samplesShown = ds.samplesShown ?? (() => true);
    this.chosenOpMode = ds.chosenOpMode ?? ((i) => this.dsStates[i]?.selected ?? null);
    this.robot = 0;
    this.robotCount = 1;
    this.builds = [];          // each robot's latest build message
    this.dsStates = [];        // each robot's latest ds message
    this.projects = new Map(); // project key (its folders) -> { key, roots, files, buffers, open, loading }
    this.dsSeq = [];           // how many ds messages each robot has sent
    this.lastTickets = [];     // each robot's build request for the latest save
    this.waiters = [];
    this.running = false;
    this.sticky = false;       // the status line shows an action's outcome until the next action
    this.samplesWereShown = null;
    this.readOnly = new Compartment();
    this.view = new EditorView({ parent: $('codeEditor'), state: this.newState('', true) });
    this.buffer = null;        // the file in the editor

    $('codeBtn').addEventListener('click', () => this.setOpen(this.layout.classList.contains('coding') ? false : true));
    $('codeClose').addEventListener('click', () => this.setOpen(false));
    $('codeFile').addEventListener('change', (e) => this.openFile(e.target.value));
    $('codeSave').addEventListener('click', () => this.save());
    $('codeRun').addEventListener('click', () => this.run());
    $('codeNew').addEventListener('click', () => this.showNewForm(true));
    $('codeNewCancel').addEventListener('click', () => this.showNewForm(false));
    $('codeNewForm').addEventListener('submit', (e) => { e.preventDefault(); this.createFile(); });
    $('codeNewForm').addEventListener('keydown', (e) => { if (e.key === 'Escape') this.showNewForm(false); });
    window.addEventListener('beforeunload', (e) => {
      if (this.anyDirty()) { e.preventDefault(); e.returnValue = ''; }
    });
    this.initResizer();
    let open = false;
    try { open = localStorage.getItem('ftcsim.code.open') === '1'; } catch { /* storage unavailable */ }
    if (open) this.setOpen(true);
  }

  /** @param hint shown in an empty editor */
  newState(text, readOnly, hint = '') {
    return EditorState.create({
      doc: text,
      extensions: [
        hint ? placeholder(hint) : [],
        basicSetup,
        Prec.highest(keymap.of([
          { key: 'Mod-s', run: () => { this.save(); return true; } },
          { key: 'Mod-Enter', run: () => { this.run(); return true; } },
        ])),
        keymap.of([indentWithTab]),
        java(),
        lintGutter(),
        indentUnit.of('    '),
        EditorState.tabSize.of(4),
        theme,
        syntaxHighlighting(highlight),
        this.readOnly.of([EditorState.readOnly.of(readOnly), EditorView.editable.of(!readOnly)]),
        EditorView.updateListener.of((u) => {
          if (!u.docChanged || !this.buffer || u.state !== this.view.state) return;
          const b = this.buffer;
          // Changes since the file as saved, and (while a save is on its way) since the text it sends.
          b.changes = b.changes ? b.changes.compose(u.changes) : u.changes;
          if (b.inflight) b.inflight.since = b.inflight.since ? b.inflight.since.compose(u.changes) : u.changes;
          this.renderDirty();
        }),
      ],
    });
  }

  // ---- showing it -----------------------------------------------------------------------------

  setOpen(open) {
    this.layout.classList.toggle('coding', open);
    $('codePane').hidden = !open;
    $('codeResizer').hidden = !open;
    $('codeBtn').classList.toggle('on', open);
    try { localStorage.setItem('ftcsim.code.open', open ? '1' : '0'); } catch { /* storage unavailable */ }
    if (open) {
      this.loadProject(this.robot);
      requestAnimationFrame(() => this.view.focus());
    }
  }

  get isOpen() { return this.layout.classList.contains('coding'); }

  initResizer() {
    const handle = $('codeResizer');
    try {
      const w = Number(localStorage.getItem('ftcsim.code.width'));
      if (w >= 20 && w <= 75) this.layout.style.setProperty('--code-w', `${w}%`);
    } catch { /* storage unavailable */ }
    handle.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      handle.setPointerCapture(e.pointerId);
      const box = this.layout.getBoundingClientRect();
      const move = (ev) => {
        const pct = Math.min(75, Math.max(20, ((ev.clientX - box.left) / box.width) * 100));
        this.layout.style.setProperty('--code-w', `${pct.toFixed(1)}%`);
        try { localStorage.setItem('ftcsim.code.width', pct.toFixed(1)); } catch { /* storage unavailable */ }
      };
      const up = () => {
        handle.removeEventListener('pointermove', move);
        handle.removeEventListener('pointerup', up);
      };
      handle.addEventListener('pointermove', move);
      handle.addEventListener('pointerup', up);
    });
  }

  // ---- robots and their projects ----------------------------------------------------------------

  /** The robots on the field changed (a hello message). */
  setRobots(n) {
    this.robotCount = n;
    this.builds.length = Math.min(this.builds.length, n);
    this.dsStates.length = Math.min(this.dsStates.length, n);
    this.renderInfo();
  }

  /** The Driver Station shows robot i: edit its TeamCode. */
  setRobot(i) {
    this.robot = i;
    if (this.isOpen) this.loadProject(i);
    this.renderInfo();
    this.renderStatus();
  }

  /** A project is the set of source folders a robot compiles; robots started on the same folder share it. */
  projectKey(i) {
    const roots = this.builds[i]?.roots;
    return roots ? roots.join('\n') : null;
  }

  project(i = this.robot) {
    const key = this.projectKey(i);
    return key ? this.projects.get(key) : null;
  }

  robotsSharing(key) {
    const out = [];
    for (let i = 0; i < this.robotCount; i++) if (this.projectKey(i) === key) out.push(i);
    return out;
  }

  /**
   * A robot that runs a project, to address its files to: the shown robot if it does. A file always
   * goes to its own project, even while the editor switches to another robot's.
   */
  robotFor(p) {
    const r = this.robotsSharing(p.key);
    return r.includes(this.robot) ? this.robot : (r[0] ?? this.robot);
  }

  /**
   * The files to list: TeamCode, the FTC samples only while the Driver Station lists them, and
   * files with unsaved changes that disappeared from disk (so they can still be saved).
   */
  visibleFiles(p) {
    const samples = this.samplesShown(this.robot);
    const list = p.files.filter((f) => samples || !p.roots[f.root]?.readOnly);
    for (const b of p.buffers.values()) {
      if (this.isDirty(b) && !p.files.some((f) => this.fileId(f) === b.id)) list.push({ root: b.root, path: b.path, gone: true });
    }
    return list;
  }

  /** Whether a source file declares a class: TeamCode paths start at the package, the samples folder may be the package's own. */
  static declares(path, className) {
    const cls = path.replace(/\.java$/, '').replace(/\//g, '.');
    const outer = className.split('$')[0];
    return outer === cls || outer.endsWith('.' + cls);
  }

  /** The file of an OpMode: in TeamCode for one of yours, in the samples for a sample. */
  fileOfOpMode(p, op) {
    const fits = (f) => !!p.roots[f.root]?.readOnly === !!op.sample && CodeEditor.declares(f.path, op.className);
    const outer = op.className.split('$')[0];
    return p.files.find((f) => fits(f) && f.path.replace(/\.java$/, '').replace(/\//g, '.') === outer) || p.files.find(fits) || null;
  }

  async loadProject(i, refresh = false) {
    const key = this.projectKey(i);
    if (!key) return;   // its first build message hasn't come yet
    let p = this.projects.get(key);
    if (!p) {
      p = { key, roots: [], files: [], buffers: new Map(), open: null, loaded: false };
      this.projects.set(key, p);
    }
    if (!p.loaded || refresh) {
      try {
        const data = await api('GET', `/code/${i}/files`);
        p.roots = data.roots;
        p.files = data.files;
        p.loaded = true;
      } catch (e) {
        this.status(`Can't list the TeamCode files: ${e.message}`, 'err', true);
        return;
      }
    }
    if (this.projectKey(this.robot) !== key) return;   // another robot was picked meanwhile
    // Keep the open file while it is listed (or has unsaved changes), else the default one.
    const listed = this.visibleFiles(p).map((f) => this.fileId(f));
    if (!p.open || !listed.includes(p.open)) p.open = this.defaultFile(p, i);
    this.renderFiles();
    if (p.open && (!this.buffer || this.buffer.id !== p.open || this.buffer.project !== p)) await this.openFile(p.open);
    else if (!p.open) this.showBuffer(null);
  }

  fileId(f) { return `${f.root}:${f.path}`; }

  /** The file of the OpMode in the robot's menu if it is listed, else its first listed file. */
  defaultFile(p, i) {
    const listed = this.visibleFiles(p);
    const chosen = this.chosenOpMode(i);
    const op = (this.builds[i]?.opmodes || []).find((o) => o.name === chosen);
    const f = op ? this.fileOfOpMode(p, op) : null;
    if (f && listed.some((x) => this.fileId(x) === this.fileId(f))) return this.fileId(f);
    const first = listed.find((x) => !p.roots[x.root]?.readOnly) ?? listed[0];
    return first ? this.fileId(first) : null;
  }

  /** Someone picked an OpMode in the Driver Station: show its file. */
  showOpMode(i, name) {
    if (i !== this.robot || !this.isOpen) return;
    const p = this.project();
    const op = (this.builds[i]?.opmodes || []).find((o) => o.name === name);
    const f = p && op ? this.fileOfOpMode(p, op) : null;
    if (f) this.openFile(this.fileId(f));
  }

  /** The Driver Station's OpMode list was drawn again: list the FTC samples' files with it or not. */
  onSamplesChange() {
    const shown = this.samplesShown(this.robot);
    if (shown === this.samplesWereShown) return;
    this.samplesWereShown = shown;
    if (this.isOpen && this.project()) this.loadProject(this.robot);
  }

  // ---- files --------------------------------------------------------------------------------------

  async openFile(id) {
    const p = this.project();
    if (!p || !id) return;
    this.sticky = false;
    this.stash();
    p.open = id;
    let b = p.buffers.get(id);
    const cached = !!b;
    if (!b) {
      const [root, ...rest] = id.split(':');
      const path = rest.join(':');
      try {
        const c = await api('GET', `/code/${this.robotFor(p)}/file?root=${root}&path=${encodeURIComponent(path)}`);
        b = { id, project: p, root: Number(root), path, hash: c.hash, readOnly: c.readOnly, state: null, savedDoc: null, changes: null, problems: [] };
        b.state = this.newState(c.text, c.readOnly);
        b.savedDoc = b.state.doc;
        p.buffers.set(id, b);
      } catch (e) {
        this.status(`Can't open ${path}: ${e.message}`, 'err', true);
        return;
      }
    }
    if (this.project() !== p || p.open !== id) return;   // something else was opened meanwhile
    this.showBuffer(b);
    if (cached) this.reload(b);   // another editor may have saved it since
  }

  /** Keeps the editor's state (text, cursor, undo history) in the file it shows. */
  stash() {
    if (this.buffer) this.buffer.state = this.view.state;
  }

  showBuffer(b) {
    if (this.buffer && this.buffer !== b) this.stash();
    this.buffer = b;
    this.view.setState(b ? b.state : this.newState('', true, this.emptyHint()));
    this.renderFiles();
    this.applyProblems();
    this.renderDirty();
    this.renderInfo();
    this.renderStatus();
  }

  /** What an empty editor says: there is no file to show yet. */
  emptyHint() {
    const p = this.project();
    if (!p) return '';
    if (p.files.some((f) => !p.roots[f.root]?.readOnly)) return '';
    return 'No TeamCode files yet.\n\nClick "+ New" above to add an OpMode to TeamCode.\n'
      + 'To read the FTC samples here, tick "FTC samples" in the Driver Station.';
  }

  docOf(b) {
    return b === this.buffer ? this.view.state.doc : b.state.doc;
  }

  isDirty(b) {
    if (!b) return false;
    return !!b.changes && !this.docOf(b).eq(b.savedDoc);
  }

  /** The shown robot's files with unsaved changes. */
  dirtyFiles() {
    this.stash();
    const p = this.project();
    return p ? [...p.buffers.values()].filter((b) => this.isDirty(b)) : [];
  }

  anyDirty() {
    this.stash();
    for (const p of this.projects.values()) for (const b of p.buffers.values()) if (this.isDirty(b)) return true;
    return false;
  }

  /**
   * Saves the open file. Returns {robot: build request number} for the robots that rebuild (the
   * last save's when there was nothing new to save), or null when it wasn't saved.
   */
  async save(options = {}, b = this.buffer) {
    if (!b || b.readOnly) return null;
    // One save of a file at a time: a second one waits, then saves only what is still unsaved.
    while (b.saving) await b.saving.catch(() => {});
    b.saving = this.saveNow(b, options);
    try {
      return await b.saving;
    } finally {
      b.saving = null;
    }
  }

  async saveNow(b, { overwrite = false } = {}) {
    this.sticky = false;
    if (!overwrite && !this.isDirty(b)) return b.lastBuilds ?? {};
    const doc = this.docOf(b);
    // diskHash: the file as it is on disk after a conflict, null if it was deleted (saving makes it again).
    const base = overwrite && b.diskHash !== undefined ? b.diskHash : b.hash;
    b.inflight = { since: null };
    let res;
    try {
      res = await api('PUT', `/code/${this.robotFor(b.project)}/file`, { root: b.root, path: b.path, text: doc.toString(), base });
    } catch (e) {
      b.inflight = null;
      if (e.status === 409) {
        b.diskHash = e.data?.hash ?? null;
        this.conflict(b, e.message);
      } else {
        this.status(`Not saved: ${e.message}`, 'err', true);
      }
      return null;
    }
    // What was typed while the save was on its way stays unsaved.
    b.hash = res.hash;
    b.savedDoc = doc;
    b.changes = b.inflight.since;
    b.inflight = null;
    b.diskHash = undefined;
    b.lastBuilds = res.builds;
    for (const [robot, ticket] of Object.entries(res.builds)) this.lastTickets[robot] = Math.max(this.lastTickets[robot] ?? 0, ticket);
    this.renderFiles();
    this.renderDirty();
    this.status(`Saved ${b.path.split('/').pop()}`, 'ok');
    return res.builds;
  }

  /** The file changed on disk since it was opened (saved from another editor). */
  conflict(b, message) {
    this.status('', 'err', true);
    const box = $('codeStatus');
    box.replaceChildren(`${message}. `);
    const keep = document.createElement('button');
    keep.className = 'ghost';
    keep.textContent = 'Save mine over it';
    keep.addEventListener('click', () => this.save({ overwrite: true }, b));
    const reload = document.createElement('button');
    reload.className = 'ghost';
    reload.textContent = b.diskHash === null ? 'Discard my changes' : 'Load the file from disk';
    reload.addEventListener('click', () => this.reload(b, true));
    box.append(keep, ' ', reload);
  }

  /** Reads a file again from disk; with {@code force} even over unsaved changes. */
  async reload(b, force = false) {
    if (b.saving || b.inflight) return;   // the save's answer brings the new hash
    try {
      const c = await api('GET', `/code/${this.robotFor(b.project)}/file?root=${b.root}&path=${encodeURIComponent(b.path)}`);
      if (c.hash === b.hash && !force) return;
      if (!force && this.isDirty(b)) {
        b.diskHash = c.hash;
        this.status(`${b.path.split('/').pop()} was changed on disk. Saving will ask what to keep.`, 'warn', true);
        return;
      }
      const state = b === this.buffer ? this.view.state : b.state;
      const tr = state.update({ changes: { from: 0, to: state.doc.length, insert: c.text } });
      b.hash = c.hash;
      b.diskHash = undefined;
      if (b === this.buffer) {
        this.view.dispatch(tr);
        b.savedDoc = this.view.state.doc;
      } else {
        b.state = tr.state;
        b.savedDoc = b.state.doc;
      }
      b.changes = null;
      this.renderDirty();
      if (force) this.status(`Loaded ${b.path.split('/').pop()} from disk`, 'ok', true);
    } catch (e) {
      if (e.status !== 404) return;
      if (!force) {
        this.status(`${b.path} no longer exists on disk`, 'warn', true);
        return;
      }
      // Gone, and its changes let go: close it.
      const p = b.project;
      p.buffers.delete(b.id);
      if (p.open === b.id) p.open = null;
      if (this.buffer === b) this.buffer = null;
      this.status(`Closed ${b.path.split('/').pop()}: it no longer exists on disk`, 'warn', true);
      if (this.project() === p) this.loadProject(this.robot);
    }
  }

  showNewForm(on) {
    $('codeNewForm').hidden = !on;
    if (on) {
      $('codeNewName').value = '';
      $('codeNewName').focus();
    }
  }

  async createFile() {
    const p = this.project();
    if (!p) return;
    const name = $('codeNewName').value.trim().replace(/\.java$/, '');
    if (!IDENT.test(name)) {
      this.status(`"${name}" can't be a Java class name: start with a letter, then letters, digits or _`, 'err', true);
      return;
    }
    const kind = $('codeNewKind').value;
    // Next to the open TeamCode file, else in TeamCode's usual package.
    const open = this.buffer && !this.buffer.readOnly ? this.buffer : null;
    const root = open ? open.root : p.roots.find((r) => !r.readOnly)?.index;
    if (root === undefined) {
      this.status('This robot has no TeamCode folder to add a file to', 'err', true);
      return;
    }
    const dir = open ? open.path.split('/').slice(0, -1).join('/') : DEFAULT_PACKAGE_DIR;
    const path = `${dir ? dir + '/' : ''}${name}.java`;
    try {
      await api('PUT', `/code/${this.robot}/file`, { root, path, text: template(dir.replace(/\//g, '.'), name, kind), base: null });
    } catch (e) {
      this.status(`Not created: ${e.message}`, 'err', true);
      return;
    }
    this.showNewForm(false);
    await this.loadProject(this.robot, true);
    await this.openFile(`${root}:${path}`);
    this.status(`Created ${path}`, 'ok', true);
  }

  // ---- builds and compile errors ------------------------------------------------------------------

  /** A robot's build message. */
  onBuild(i, b) {
    const before = this.projectKey(i);
    this.builds[i] = b;
    if (this.isOpen && i === this.robot && (before !== this.projectKey(i) || !this.project())) this.loadProject(i);
    if (!b.building) {
      const p = this.project(i);
      if (p && this.isOpen && i === this.robot) {
        // Files added, removed or changed by other editors since: list and re-read them.
        this.loadProject(i, true).then(() => { if (this.buffer) this.reload(this.buffer); });
      }
      if (p) for (const buf of p.buffers.values()) buf.problems = (b.problems || []).filter((x) => x.root === buf.root && x.file === buf.path);
      if (this.buffer && this.buffer.project === p) this.applyProblems();
    }
    if (i === this.robot) this.renderStatus();
    this.poke();
  }

  /** A robot's Driver Station state. */
  onDs(i, ds) {
    this.dsStates[i] = ds;
    this.dsSeq[i] = (this.dsSeq[i] ?? 0) + 1;
    if (i === this.robot) this.renderStatus();
    this.poke();
  }

  /**
   * Underlines the open file's compile errors. They were found in the file as saved; edits made
   * since move them along with the text they belong to.
   */
  applyProblems() {
    const b = this.buffer;
    if (!b) return;
    const doc = b.savedDoc;
    const pos = (line, col) => {
      const l = doc.line(Math.min(Math.max(line, 1), doc.lines));
      return Math.min(l.from + Math.max(col, 0), l.to);
    };
    if (b.changes && !this.isDirtyAgainst(b)) b.changes = null;
    const diagnostics = [];
    for (const x of b.problems) {
      let from = pos(x.line, x.column);
      let to = pos(x.endLine, x.endColumn);
      if (to <= from) {
        const l = doc.lineAt(from);
        if (from < l.to) to = from + 1;
        else if (from > l.from) from -= 1;
      }
      if (b.changes) {
        from = b.changes.mapPos(from, 1);
        to = Math.max(from, b.changes.mapPos(to, -1));
      }
      diagnostics.push({ from, to, severity: 'error', source: 'javac', message: x.message });
    }
    this.view.dispatch(setDiagnostics(this.view.state, diagnostics));
    this.renderStatus();
  }

  isDirtyAgainst(b) {
    return !this.view.state.doc.eq(b.savedDoc);
  }

  errorCount() {
    let n = 0;
    forEachDiagnostic(this.view.state, () => { n++; });
    return n;
  }

  // ---- save & run ---------------------------------------------------------------------------------

  /** Resolves when pred() holds (checked whenever a build or ds message comes), or rejects after timeoutMs. */
  waitFor(pred, timeoutMs, what) {
    if (pred()) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const w = { pred, resolve, timer: setTimeout(() => { this.waiters = this.waiters.filter((x) => x !== w); reject(new Error(`timed out waiting for ${what}`)); }, timeoutMs) };
      this.waiters.push(w);
    });
  }

  poke() {
    for (const w of [...this.waiters]) {
      if (!w.pred()) continue;
      clearTimeout(w.timer);
      this.waiters = this.waiters.filter((x) => x !== w);
      w.resolve();
    }
  }

  /** The OpMode the file of buffer b declares, if it declares one. */
  opModeInFile(i, b) {
    if (!b) return null;
    const p = b.project;
    return (this.builds[i]?.opmodes || []).find((o) => {
      const f = this.fileOfOpMode(p, o);
      return f && this.fileId(f) === b.id;
    }) || null;
  }

  async run() {
    if (this.running) return;
    const i = this.robot;
    const b = this.buffer;
    this.running = true;
    this.sticky = false;
    $('codeRun').disabled = true;
    try {
      const st = () => this.dsStates[i]?.state ?? 'IDLE';
      if (st() !== 'IDLE') {
        this.status('Stopping the OpMode…');
        if (st() !== 'STOPPING') this.send({ type: 'stop', robot: i });
        await this.waitFor(() => st() === 'IDLE', 20000, 'the OpMode to stop');
      }
      // Wait for the build that has the latest save in it (this one's, or one still on its way).
      if (b && !b.readOnly && (this.isDirty(b) || b.saving)) {
        const builds = await this.save({}, b);
        if (!builds) return;   // not saved: save() said why
      }
      const ticket = this.lastTickets[i] ?? null;
      this.status('Building…');
      await this.waitFor(() => {
        const x = this.builds[i];
        return x && !x.building && !x.pending && (ticket === null || x.built >= ticket);
      }, 120000, 'the build');
      const errors = b ? b.problems.length : 0;
      if (errors) {
        this.status(`${errors} compile error${errors === 1 ? '' : 's'} in ${b.path.split('/').pop()}: fix ${errors === 1 ? 'it' : 'them'} to run`, 'err', true);
        this.view.focus();
        return;
      }
      // The file Save & run was pressed in: another may be on screen after the build.
      const op = this.opModeInFile(i, b);
      const name = op ? op.name : this.chosenOpMode(i);
      if (!name) {
        this.status('No OpMode to run: this file has none and the Driver Station lists none', 'err', true);
        return;
      }
      // Only what the robot says once the old error is cleared counts: an update already on its
      // way (after the build) may still carry an error from before.
      const seq = this.dsSeq[i] ?? 0;
      let cleared = false;
      this.send({ type: 'clearError', robot: i });
      this.send({ type: 'select', robot: i, opmode: name });
      this.send({ type: 'init', robot: i, opmode: name });
      this.status(`INIT ${name}…`);
      await this.waitFor(() => {
        const d = this.dsStates[i];
        if ((this.dsSeq[i] ?? 0) <= seq || !d) return false;
        if (!cleared) {
          if (d.error) return false;
          cleared = true;
        }
        return d.state !== 'IDLE' || !!d.error;
      }, 60000, 'INIT');
      if (st() !== 'INIT') {
        this.status(this.dsStates[i]?.error ? `${name} didn't start: see the Driver Station` : `${name} stopped during INIT`, 'err', true);
        return;
      }
      this.send({ type: 'start', robot: i });
    } catch (e) {
      this.status(`Not run: ${e.message}`, 'err', true);
    } finally {
      this.running = false;
      $('codeRun').disabled = false;
      this.renderStatus();
    }
  }

  // ---- rendering --------------------------------------------------------------------------------

  renderFiles() {
    const p = this.project();
    const sel = $('codeFile');
    if (!p) { sel.innerHTML = ''; return; }
    sel.replaceChildren();
    const listed = this.visibleFiles(p);
    for (const root of p.roots) {
      const files = listed.filter((f) => f.root === root.index);
      if (!files.length) continue;
      const group = document.createElement('optgroup');
      group.label = root.readOnly ? 'FTC samples (read-only)' : 'TeamCode';
      // Show paths from the folder all of them share (usually org/firstinspires/ftc/teamcode).
      const dirs = files.map((f) => f.path.split('/').slice(0, -1));
      let common = dirs[0] || [];
      for (const d of dirs) { let k = 0; while (k < common.length && k < d.length && common[k] === d[k]) k++; common = common.slice(0, k); }
      for (const f of files) {
        const o = document.createElement('option');
        o.value = this.fileId(f);
        const label = f.path.split('/').slice(common.length).join('/');
        const buf = p.buffers.get(o.value);
        o.textContent = (this.isDirty(buf) ? '● ' : '') + label + (f.gone ? ' (deleted on disk: save to keep it)' : '');
        o.title = f.path;
        group.append(o);
      }
      sel.append(group);
    }
    if (!sel.options.length) {
      const none = document.createElement('option');
      none.value = '';
      none.textContent = 'No TeamCode files';
      sel.append(none);
    }
    sel.value = p.open ?? '';
  }

  /** A new connection (the simulator may have restarted): its build numbers start again. */
  reconnected() {
    this.lastTickets = [];
    for (const p of this.projects.values()) for (const b of p.buffers.values()) b.lastBuilds = undefined;
  }

  renderDirty() {
    const b = this.buffer;
    const dirty = this.isDirty(b);
    $('codeSave').disabled = !b || b.readOnly || (!dirty && b.diskHash !== null);
    const opt = b ? [...$('codeFile').options].find((o) => o.value === b.id) : null;
    if (opt) {
      const label = opt.textContent.replace(/^● /, '');
      opt.textContent = (dirty ? '● ' : '') + label;
    }
  }

  renderInfo() {
    const p = this.project();
    const several = this.robotCount > 1;
    $('codeRobot').textContent = several ? `Robot ${this.robot + 1}` : '';
    $('codeRobot').hidden = !several;
    const info = $('codeInfo');
    if (!p) { info.textContent = ''; return; }
    const b = this.buffer;
    const root = b ? p.roots[b.root] : p.roots.find((r) => !r.readOnly);
    let text = root ? root.dir : '';
    if (b?.readOnly) text = 'FTC sample, read-only: copy what you need into a TeamCode file. ' + text;
    const sharing = this.robotsSharing(p.key);
    if (sharing.length > 1) {
      text += ` · shared by ${sharing.map((k) => `Robot ${k + 1}`).join(' and ')}: saving rebuilds ${sharing.length === 2 ? 'both' : 'all of them'}`;
    }
    info.textContent = text;
    info.title = text;
  }

  /**
   * The line under the editor. A sticky message (what an action did, a problem to deal with) stays
   * until the next action; otherwise it follows the robot's build and Driver Station.
   */
  status(text, kind = '', sticky = false) {
    this.sticky = sticky;
    const box = $('codeStatus');
    box.className = 'code-status' + (kind ? ' ' + kind : '');
    box.textContent = text;
  }

  renderStatus() {
    if (this.running || this.sticky) return;
    const b = this.builds[this.robot];
    const ds = this.dsStates[this.robot];
    const errors = this.errorCount();
    if (!b) return this.status('');
    if (b.building) return this.status('Building…');
    if (b.pending) return this.status('Saved; the rebuild waits until the OpMode stops', 'warn');
    if (errors) return this.status(`${errors} compile error${errors === 1 ? '' : 's'} in this file (point at the red underline to read ${errors === 1 ? 'it' : 'them'})`, 'err');
    if (ds?.error) return this.status('The robot reported an error: see the Driver Station', 'err');
    if (ds?.state === 'RUNNING') return this.status(`Running ${ds.active}`, 'ok');
    if (ds?.state === 'INIT') return this.status(`INIT ${ds.active}: press START in the Driver Station`);
    if ((b.problems || []).length) return this.status(`${b.message} · errors in other files: see the Build tab`, 'warn');
    this.status(b.message || '', b.ok === false ? 'err' : '');
  }
}
