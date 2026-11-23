// Turns a robot's STEP file into triangles with OpenCascade (occt-import-js, WebAssembly), in a
// worker so the Driver Station keeps running, and sends them to the simulator, which keeps them
// next to the STEP file (<name>.step.cadmesh) and builds the robot from them.

export const CONVERTER = 'occt-import-js 0.0.23';

// Fine enough for round parts to look round, coarse enough for a whole robot to stay small.
export const TESSELLATION = {
  linearUnit: 'meter',
  linearDeflectionType: 'bounding_box_ratio',
  linearDeflection: 0.0015,
  angularDeflection: 0.35,
};

/** OpenCascade gives linear RGB (0..1); the simulator stores sRGB. */
function srgbHex(c) {
  if (!c || c.length < 3) return null;
  const to = (v) => {
    const l = Math.max(0, Math.min(1, v));
    const s = l <= 0.0031308 ? 12.92 * l : 1.055 * Math.pow(l, 1 / 2.4) - 0.055;
    return Math.round(s * 255).toString(16).padStart(2, '0');
  };
  return '#' + to(c[0]) + to(c[1]) + to(c[2]);
}

/** The color most of a mesh's faces have, when the mesh itself has none. */
function meshColor(m) {
  const own = srgbHex(m.color);
  if (own) return own;
  const area = new Map();
  for (const f of m.brep_faces || []) {
    const hex = srgbHex(f.color);
    if (hex) area.set(hex, (area.get(hex) || 0) + (f.last - f.first + 1));
  }
  let best = null, n = -1;
  for (const [hex, k] of area) if (k > n) { best = hex; n = k; }
  return best || '#9aa0a6';
}

/**
 * Encodes occt-import-js's result in the simulator's mesh file format (see CadMeshFile.java):
 * "FTCMESH1", a uint32 JSON length, the JSON, padding to 4 bytes, then each part's float32
 * positions and uint32 indices. Part paths are the assembly names down to the part, "/"-separated.
 */
export function encodeCadMesh(result, { source, sha256 }) {
  const parts = [];
  const walk = (node, path) => {
    const here = node.name ? [...path, node.name] : path;
    for (const mi of node.meshes || []) {
      const m = result.meshes[mi];
      const last = here[here.length - 1];
      const name = m.name && m.name !== last ? [...here, m.name] : (here.length ? here : [m.name || 'part']);
      parts.push({ path: name.join('/'), mesh: m });
    }
    for (const c of node.children || []) walk(c, here);
  };
  walk(result.root, []);
  const header = {
    source, sha256, converter: CONVERTER,
    parts: parts.map(({ path, mesh }) => ({
      path,
      color: meshColor(mesh),
      vertices: mesh.attributes.position.array.length / 3,
      triangles: mesh.index.array.length / 3,
    })),
  };
  const json = new TextEncoder().encode(JSON.stringify(header));
  const pad = (4 - ((8 + 4 + json.length) % 4)) % 4;
  let body = 0;
  for (const { mesh } of parts) body += 4 * (mesh.attributes.position.array.length + mesh.index.array.length);
  const buf = new ArrayBuffer(12 + json.length + pad + body);
  const bytes = new Uint8Array(buf);
  bytes.set(new TextEncoder().encode('FTCMESH1'), 0);
  new DataView(buf).setUint32(8, json.length, true);
  bytes.set(json, 12);
  let off = 12 + json.length + pad;
  for (const { mesh } of parts) {
    const p = mesh.attributes.position.array, ix = mesh.index.array;
    new Float32Array(buf, off, p.length).set(p);
    off += p.length * 4;
    new Uint32Array(buf, off, ix.length).set(ix);
    off += ix.length * 4;
  }
  return { buffer: buf, parts: parts.length, triangles: header.parts.reduce((a, p) => a + p.triangles, 0) };
}

/**
 * Runs occt-import-js on a STEP file's bytes in a worker (js/occt-worker.js), which always
 * answers: the result, or why OpenCascade couldn't read it. A worker that dies without a word
 * (out of memory) is given up on after a time that grows with the file.
 */
function readStep(bytes) {
  return new Promise((resolve, reject) => {
    const worker = new Worker('js/occt-worker.js');
    const minutes = 5 + Math.ceil(bytes.length / 20e6);
    let timer = 0;
    const done = (settle, value) => {
      clearTimeout(timer);
      worker.terminate();
      settle(value);
    };
    timer = setTimeout(() => done(reject, new Error(`OpenCascade didn't finish within ${minutes} minutes`)), minutes * 60000);
    worker.onmessage = (ev) => {
      if (ev.data?.ok) done(resolve, ev.data.result);
      else done(reject, new Error(`OpenCascade could not read it: ${ev.data?.error || 'unknown error'}`));
    };
    worker.onerror = (ev) => {
      ev.preventDefault();
      done(reject, new Error(ev.message || 'the STEP reader failed'));
    };
    worker.onmessageerror = () => done(reject, new Error('the STEP reader sent back something unreadable'));
    worker.postMessage({ format: 'step', buffer: bytes, params: TESSELLATION }, [bytes.buffer]);   // moved, not copied
  });
}

const started = new Set();

/**
 * Converts robot {@code index}'s STEP file if the hello message says it needs it. Only once per
 * file version per page; {@code report(text, done, failed)} shows progress, naming the robot when
 * {@code several} robots are on the field.
 */
export async function convertIfNeeded(index, cad, report, several = true) {
  if (!cad || cad.status !== 'convert' || !cad.source || started.has(cad.sha256)) return;
  started.add(cad.sha256);
  const label = `${several ? `Robot ${index + 1}: ` : ''}${cad.file}`;
  try {
    report(`${label}: reading the STEP file…`);
    const res = await fetch(cad.source);
    if (!res.ok) throw new Error(`the simulator didn't send the STEP file (${res.status})`);
    const bytes = new Uint8Array(await res.arrayBuffer());
    const mb = (bytes.length / 1e6).toFixed(1);   // the bytes move to the worker
    report(`${label}: making triangles with OpenCascade (${mb} MB; big assemblies take a minute)…`);
    const t0 = performance.now();
    const result = await readStep(bytes);
    if (!result || !result.success) throw new Error('OpenCascade could not read it as a STEP file');
    const mesh = encodeCadMesh(result, { source: cad.file, sha256: cad.sha256 });
    // Out of memory, OpenCascade still reports success, with every part empty (see CadMeshFile.EMPTY).
    if (mesh.triangles === 0) {
      throw new Error('no triangles came out of the conversion. A big STEP assembly can need more memory than'
        + " the Driver Station page's converter has (2 GB): convert it on this computer with tools/step2cadmesh.py"
        + ' (README: Robots from CAD)');
    }
    report(`${label}: ${mesh.parts} parts, ${mesh.triangles.toLocaleString()} triangles in ${((performance.now() - t0) / 1000).toFixed(1)} s; sending…`);
    const up = await fetch(cad.upload, {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream', 'X-Ftcsim-Upload': '1' },
      body: mesh.buffer,
    });
    if (!up.ok) throw new Error(await up.text() || `the simulator refused it (${up.status})`);
    report(`${label}: converted. Building the robot…`, true);
  } catch (e) {
    started.delete(cad.sha256);
    report(`${label}: ${e.message}`, true, true);
  }
}
