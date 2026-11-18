// 3D view of the season's field and the simulated robots (Three.js, z-up, meters). The field
// comes from the simulator as plain shapes (FieldModel.describe): tape on the tiles, boxes and
// cylinders, AprilTags, and moving parts that turn on a hinge by the angles in the state stream.
import * as THREE from 'three';
import { OrbitControls } from '../vendor/OrbitControls.js';

const COLORS = {
  tile: '#50555c', seam: '#3a3e44', aluminum: 0xb9c0c8, frame: 0x2b2f35,
};
// Side plates of robots on each alliance, when there is more than one robot to tell apart.
const ALLIANCE_PLATES = { red: '#b3261e', blue: '#1e4fb3' };

// CAD models by URL (each version of a robot's model has its own), turned into GPU meshes once
// and shared by every robot drawn with it: { groups: [{ name, kind, parent, meshes: [{ geometry,
// material }] }], box }. A version no robot uses any more is freed (see setRobots).
const cadModels = new Map();

/** Fetches and parses a robot's CAD model (FTCMODL1, see ModelExport.java) and builds its meshes. */
function loadCadModel(url) {
  if (!cadModels.has(url)) {
    cadModels.set(url, fetch(url).then((res) => {
      if (!res.ok) throw new Error(`CAD model ${res.status}`);
      return res.arrayBuffer();
    }).then((buf) => {
      const magic = new TextDecoder().decode(new Uint8Array(buf, 0, 8));
      if (magic !== 'FTCMODL1') throw new Error('not a CAD model');
      const len = new DataView(buf).getUint32(8, true);
      const header = JSON.parse(new TextDecoder().decode(new Uint8Array(buf, 12, len)));
      let off = 12 + len + ((4 - ((12 + len) % 4)) % 4);
      const box = new THREE.Box3();
      for (const g of header.groups) {
        g.meshes = g.meshes.map((m) => {
          const positions = new Float32Array(buf, off, m.vertices * 3);
          off += m.vertices * 12;
          const indices = new Uint32Array(buf, off, m.triangles * 3);
          off += m.triangles * 12;
          const geometry = new THREE.BufferGeometry();
          geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
          geometry.setIndex(new THREE.BufferAttribute(indices, 1));
          geometry.computeVertexNormals();
          geometry.computeBoundingBox();
          box.union(geometry.boundingBox);
          geometry.userData.cad = true;
          const material = new THREE.MeshStandardMaterial({ color: new THREE.Color(m.color), metalness: 0.25, roughness: 0.6, side: THREE.DoubleSide });
          material.userData.cad = true;
          return { geometry, material };
        });
      }
      return { groups: header.groups, box };
    }).catch((e) => {
      cadModels.delete(url);
      throw e;
    }));
  }
  return cadModels.get(url);
}

/** Frees a CAD model's GPU meshes. */
function freeCadModel(model) {
  for (const g of model.groups) {
    for (const m of g.meshes) {
      m.geometry.dispose();
      m.material.dispose();
    }
  }
}

/** Frees the GPU buffers of a robot's own drawing (shared CAD meshes go with their model). */
function disposeTree(root) {
  root.traverse((o) => {
    if (o.geometry && !o.geometry.userData.cad) o.geometry.dispose();
    const mats = Array.isArray(o.material) ? o.material : (o.material ? [o.material] : []);
    for (const m of mats) {
      if (m.userData.cad) continue;
      m.map?.dispose();
      m.dispose();
    }
  });
}

const DIRECTIONS = { '+x': [1, 0], '-x': [-1, 0], '+y': [0, 1], '-y': [0, -1] };

/** Camera presets for where this season's audience and home-alliance drivers are. */
function cameraPresets(season) {
  const [ax, ay] = DIRECTIONS[season?.audience] ?? DIRECTIONS['-y'];
  const [dx, dy] = DIRECTIONS[season?.allianceWalls?.[season?.home ?? 'red']] ?? DIRECTIONS['-x'];
  return {
    audience: { pos: [ax * 4.5, ay * 4.5, 2.8], target: [ax * 0.15, ay * 0.15, 0.25] },
    overhead: { pos: [ax * 0.001, ay * 0.001, 6.4], target: [0, 0, 0] },
    driver: { pos: [dx * 3.75, dy * 3.75, 1.7], target: [-dx * 0.2, -dy * 0.2, 0.2] },
  };
}

const _p = new THREE.Vector3(), _q = new THREE.Quaternion(), _one = new THREE.Vector3(1, 1, 1);

/** One geometry (positions and normals) from several; frees them. */
function mergeGeometries(parts) {
  const flat = parts.map((g) => (g.index ? g.toNonIndexed() : g));
  let n = 0;
  for (const g of flat) n += g.attributes.position.count;
  const position = new Float32Array(n * 3), normal = new Float32Array(n * 3);
  let at = 0;
  for (const g of flat) {
    position.set(g.attributes.position.array, at * 3);
    normal.set(g.attributes.normal.array, at * 3);
    at += g.attributes.position.count;
  }
  for (const g of new Set([...parts, ...flat])) g.dispose();
  const out = new THREE.BufferGeometry();
  out.setAttribute('position', new THREE.BufferAttribute(position, 3));
  out.setAttribute('normal', new THREE.BufferAttribute(normal, 3));
  return out;
}

export class FieldView {
  constructor(canvas) {
    this.canvas = canvas;
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.shadowMap.enabled = true;
    this.renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;

    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color(0x16181b);
    this.camera = new THREE.PerspectiveCamera(42, 1, 0.02, 100);
    this.camera.up.set(0, 0, 1);
    this.controls = new OrbitControls(this.camera, canvas);
    this.controls.enableDamping = true;
    this.controls.dampingFactor = 0.12;
    this.controls.maxDistance = 14;
    this.controls.minDistance = 0.4;

    this.scene.add(new THREE.HemisphereLight(0xdfe8ff, 0x2a2724, 1.4));
    const sun = new THREE.DirectionalLight(0xffffff, 1.6);
    sun.position.set(2.2, -3.0, 6.0);
    sun.castShadow = true;
    sun.shadow.mapSize.set(2048, 2048);
    Object.assign(sun.shadow.camera, { left: -3, right: 3, top: 3, bottom: -3, near: 0.5, far: 15 });
    sun.shadow.bias = -0.0005;
    this.scene.add(sun);

    this.fieldGroup = new THREE.Group();
    this.scene.add(this.fieldGroup);
    this.robots = [];
    this.focus = 0;           // the robot the chase camera follows and Place robot moves
    this.ballGroup = new THREE.Group();
    this.scene.add(this.ballGroup);
    this.balls = [];
    this.mode = 'audience';
    this.cameras = cameraPresets(null);
    this.camTween = null;
    this.placing = null;
    this.ghost = this.makeGhost();
    this.scene.add(this.ghost);
    this.raycaster = new THREE.Raycaster();
    this.floorPlane = new THREE.Plane(new THREE.Vector3(0, 0, 1), 0);

    this.setCameraMode('audience', true);
    new ResizeObserver(() => this.resize()).observe(canvas.parentElement);
    this.resize();
    this.bindPointer();
    const loop = () => { this.frame(); requestAnimationFrame(loop); };
    requestAnimationFrame(loop);
  }

  resize() {
    const el = this.canvas.parentElement;
    const w = el.clientWidth, h = el.clientHeight;
    if (w === 0 || h === 0) return;
    this.renderer.setSize(w, h, false);
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
  }

  // ---- field -------------------------------------------------------------------------------

  /** The season's field (FieldModel.describe) and where its audience and drivers are (Season.describe). */
  setField(field, season) {
    const presets = cameraPresets(season);
    const moved = JSON.stringify(presets) !== JSON.stringify(this.cameras);
    this.cameras = presets;
    if (moved && this.mode !== 'follow') this.setCameraMode(this.mode, true);
    // hello comes again whenever a robot changes: the same field stays drawn as it is.
    const key = JSON.stringify(field);
    if (key === this.fieldKey) return;
    this.fieldKey = key;
    disposeTree(this.fieldGroup);
    this._tagMat = this._tagTex = null;   // freed with the old field
    this.fieldGroup.clear();
    this.movers = [];
    this.field = field;
    const half = field.half;
    this.half = half;
    const g = this.fieldGroup;

    // Venue floor and the alliance areas around the field
    const venue = new THREE.Mesh(new THREE.PlaneGeometry(14, 14), new THREE.MeshStandardMaterial({ color: 0x1c1e22, roughness: 1 }));
    venue.position.z = -0.003;
    venue.receiveShadow = true;
    g.add(venue);
    for (const a of field.areas ?? []) {
      const area = new THREE.Mesh(new THREE.PlaneGeometry(a.x[1] - a.x[0], a.y[1] - a.y[0]), new THREE.MeshStandardMaterial({ color: a.rgb, roughness: 1 }));
      area.position.set((a.x[0] + a.x[1]) / 2, (a.y[0] + a.y[1]) / 2, -0.002);
      area.receiveShadow = true;
      g.add(area);
    }

    // Tiles with tape, drawn into one texture
    const tiles = new THREE.Mesh(new THREE.PlaneGeometry(2 * half, 2 * half),
      new THREE.MeshStandardMaterial({ map: this.floorTexture(field), roughness: 0.95 }));
    tiles.receiveShadow = true;
    g.add(tiles);

    this.addWalls(half, field.wallHeight);
    const materials = new Map();
    for (const mesh of this.solids(field.solids ?? [], materials)) g.add(mesh);
    for (const t of field.tags ?? []) g.add(this.tag(t));
    // Moving parts: drawn in their own frame inside a group that turns about the hinge axis.
    for (const m of field.movers ?? []) {
      const pivot = new THREE.Group();
      pivot.position.set(...m.pivot);
      const axis = new THREE.Vector3(...m.axis);
      pivot.quaternion.setFromAxisAngle(axis, m.start);
      for (const mesh of this.solids(m.solids, materials)) pivot.add(mesh);
      for (const t of m.tags) pivot.add(this.tag(t));
      g.add(pivot);
      this.movers.push({ pivot, axis });
    }
  }

  floorTexture(field) {
    const N = 2048, half = field.half, tile = field.tile;
    const c = document.createElement('canvas');
    c.width = c.height = N;
    const ctx = c.getContext('2d');
    const px = (x) => (x + half) / (2 * half) * N;
    const py = (y) => (half - y) / (2 * half) * N;
    ctx.fillStyle = COLORS.tile;
    ctx.fillRect(0, 0, N, N);
    // subtle foam texture
    for (let i = 0; i < 9000; i++) {
      ctx.fillStyle = `rgba(255,255,255,${Math.random() * 0.025})`;
      ctx.fillRect(Math.random() * N, Math.random() * N, 2, 2);
    }
    ctx.strokeStyle = COLORS.seam;
    ctx.lineWidth = 3;
    for (let k = 0; k * tile <= 2 * half + 1e-6; k++) {
      const v = -half + k * tile;
      ctx.beginPath(); ctx.moveTo(px(v), 0); ctx.lineTo(px(v), N); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(0, py(v)); ctx.lineTo(N, py(v)); ctx.stroke();
    }
    const css = (rgb) => `#${rgb.toString(16).padStart(6, '0')}`;
    for (const t of field.tape ?? []) {
      const p = t.points;
      ctx.fillStyle = ctx.strokeStyle = css(t.rgb);
      ctx.beginPath();
      ctx.moveTo(px(p[0]), py(p[1]));
      for (let i = 2; i < p.length; i += 2) ctx.lineTo(px(p[i]), py(p[i + 1]));
      if (t.kind === 'area') {
        ctx.closePath();
        ctx.fill();
      } else {
        ctx.lineWidth = t.width / (2 * half) * N;
        ctx.lineCap = 'butt';
        ctx.stroke();
      }
    }
    const tex = new THREE.CanvasTexture(c);
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.anisotropy = this.renderer.capabilities.getMaxAnisotropy();
    return tex;
  }

  /**
   * Boxes and cylinders of the field (center c, size, rot row-major from their axes to the
   * world's), merged into one mesh per material: a FLOWER's rings alone are dozens of bars.
   */
  solids(list, materials) {
    const groups = new Map();
    for (const s of list) {
      const key = `${s.rgb}/${s.opacity ?? 1}`;
      if (!groups.has(key)) groups.set(key, { first: s, parts: [] });
      let geo;
      if (s.shape === 'box') {
        geo = new THREE.BoxGeometry(s.size[0], s.size[1], s.size[2]);
      } else if (s.shape === 'prism') {
        // a convex outline in its x-y plane, size[0] thick along its z, centered
        const shape = new THREE.Shape();
        const o = s.outline;
        shape.moveTo(o[0], o[1]);
        for (let i = 2; i < o.length; i += 2) shape.lineTo(o[i], o[i + 1]);
        shape.closePath();
        geo = new THREE.ExtrudeGeometry(shape, { depth: s.size[0], bevelEnabled: false });
        geo.translate(0, 0, -s.size[0] / 2);
      } else {
        const r = s.size[0];
        geo = new THREE.CylinderGeometry(r, r, s.size[1], r < 0.01 ? 6 : r < 0.05 ? 14 : 28);
        geo.rotateX(Math.PI / 2);   // its axis along z, as in the simulator
      }
      const R = s.rot;
      geo.applyMatrix4(new THREE.Matrix4().set(R[0], R[1], R[2], s.c[0], R[3], R[4], R[5], s.c[1], R[6], R[7], R[8], s.c[2], 0, 0, 0, 1));
      groups.get(key).parts.push(geo);
    }
    const out = [];
    for (const [key, { first, parts }] of groups) {
      let mat = materials.get(key);
      if (!mat) {
        const see = (first.opacity ?? 1) < 1;
        mat = new THREE.MeshStandardMaterial({
          color: first.rgb, roughness: see ? 0.2 : 0.55, metalness: see ? 0 : 0.25,
          transparent: see, opacity: first.opacity ?? 1, depthWrite: !see, side: see ? THREE.DoubleSide : THREE.FrontSide,
        });
        materials.set(key, mat);
      }
      const mesh = new THREE.Mesh(mergeGeometries(parts), mat);
      if ((first.opacity ?? 1) >= 1) {
        mesh.castShadow = true;
        mesh.receiveShadow = true;
      }
      out.push(mesh);
    }
    return out;
  }

  /** An AprilTag, drawn on its face (the black square is size; its white margin goes around it). */
  tag(t) {
    this._tagMat ??= new THREE.MeshBasicMaterial({ map: this.tagTexture(), side: THREE.DoubleSide });
    const full = t.size * 10 / 8;
    const m = new THREE.Mesh(new THREE.PlaneGeometry(full, full), this._tagMat);
    const n = t.normal, r = t.right, u = t.up;
    m.matrixAutoUpdate = false;
    // The plane's x, y and z (its face) along the tag's right, up and normal, a hair in front of its mount.
    m.matrix.set(r[0], u[0], n[0], t.c[0] + n[0] * 0.001, r[1], u[1], n[1], t.c[1] + n[1] * 0.001,
      r[2], u[2], n[2], t.c[2] + n[2] * 0.001, 0, 0, 0, 1);
    m.name = `tag ${t.id}`;
    return m;
  }

  addWalls(half, h) {
    const g = this.fieldGroup;
    const panel = new THREE.MeshStandardMaterial({ color: 0xa9bfd6, transparent: true, opacity: 0.16, roughness: 0.1, depthWrite: false });
    const rail = new THREE.MeshStandardMaterial({ color: COLORS.aluminum, metalness: 0.5, roughness: 0.45 });
    const L = 2 * half + 0.1;
    for (const [x, y, sx, sy] of [[half + 0.025, 0, 0.012, L], [-half - 0.025, 0, 0.012, L], [0, half + 0.025, L, 0.012], [0, -half - 0.025, L, 0.012]]) {
      const p = new THREE.Mesh(new THREE.BoxGeometry(sx, sy, h), panel);
      p.position.set(x, y, h / 2);
      g.add(p);
      for (const z of [0.02, h]) {
        const r = new THREE.Mesh(new THREE.BoxGeometry(Math.max(sx, 0.04), Math.max(sy, 0.04), 0.035), rail);
        r.position.set(x, y, z);
        r.castShadow = true;
        g.add(r);
      }
    }
    for (const sx of [-1, 1]) for (const sy of [-1, 1]) {
      const post = new THREE.Mesh(new THREE.BoxGeometry(0.05, 0.05, h + 0.04), rail);
      post.position.set(sx * (half + 0.025), sy * (half + 0.025), (h + 0.04) / 2);
      g.add(post);
    }
  }

  tagTexture() {
    if (this._tagTex) return this._tagTex;
    const c = document.createElement('canvas');
    c.width = c.height = 80;
    const ctx = c.getContext('2d');
    ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, 80, 80);
    ctx.fillStyle = '#000'; ctx.fillRect(8, 8, 64, 64);
    ctx.fillStyle = '#fff';
    for (let i = 0; i < 16; i++) if ((i * 7 + 3) % 3) ctx.fillRect(16 + (i % 4) * 12, 16 + Math.floor(i / 4) * 12, 12, 12);
    this._tagTex = new THREE.CanvasTexture(c);
    this._tagTex.magFilter = THREE.NearestFilter;
    return this._tagTex;
  }

  // ---- scoring elements --------------------------------------------------------------------

  /** One sphere per game piece, in the order the simulator streams their positions. */
  setBalls(list) {
    this.ballGroup.clear();
    this.balls = [];
    const geos = {};
    const mats = {};
    for (const b of list || []) {
      const geo = geos[b.r] ??= new THREE.SphereGeometry(b.r, 20, 14);
      const mat = mats[b.rgb] ??= new THREE.MeshStandardMaterial({ color: b.rgb ?? 0xffffff, roughness: 0.45 });
      const m = new THREE.Mesh(geo, mat);
      m.castShadow = true;
      m.receiveShadow = true;
      m.visible = false;
      this.ballGroup.add(m);
      this.balls.push(m);
    }
  }

  // ---- robots ------------------------------------------------------------------------------

  /** One model per robot on the field (the hello message's robots). */
  setRobots(list) {
    for (const r of this.robots) {
      this.scene.remove(r.group);
      disposeTree(r.group);
    }
    const used = new Set(list.map((r) => r.cad?.model).filter(Boolean));
    for (const [url, model] of cadModels) {
      if (used.has(url)) continue;
      cadModels.delete(url);
      model.then(freeCadModel, () => {});
    }
    const several = list.length > 1;
    this.robots = list.map((r) => this.buildRobot(r.robot, r.wheels, r.wheelRadius, r.chassisZ,
      several ? { plate: ALLIANCE_PLATES[r.alliance], number: r.index + 1, alliance: r.alliance } : {}));
    list.forEach((r, i) => { if (r.cad?.model) this.attachCad(this.robots[i], r.cad.model, r.chassisZ); });
    if (this.focus >= this.robots.length) this.focus = 0;
  }

  /**
   * Replaces a robot's drawn-from-numbers model with its CAD model once that has loaded. Each
   * group (base, links, rollers, wheels) is posed from the state stream's {@code links}.
   */
  attachCad(robot, url, chassisZ) {
    loadCadModel(url).then(({ groups, box }) => {
      if (!this.robots.includes(robot)) return;   // replaced meanwhile
      const root = new THREE.Group();
      root.position.set(0, 0, -chassisZ);         // robot-frame origin: the floor under its center
      const objects = [];
      for (const g of groups) {
        const obj = new THREE.Group();
        obj.matrixAutoUpdate = false;
        for (const m of g.meshes) {
          const mesh = new THREE.Mesh(m.geometry, m.material);
          mesh.castShadow = true;
          mesh.receiveShadow = true;
          obj.add(mesh);
        }
        root.add(obj);
        objects.push(obj);
      }
      for (const child of robot.group.children) if (child !== robot.tag) child.visible = false;
      if (robot.tag) robot.tag.position.z = box.max.z - chassisZ + 0.12;
      robot.group.add(root);
      robot.cad = objects;
      robot.wheels = [];        // the CAD wheels turn as groups
      robot.mech = {};
    }).catch((e) => console.warn('CAD model not shown:', e));
  }

  setFocus(i) {
    this.focus = i;
  }

  /** A sprite with the robot's number, above it, in its alliance color. */
  numberTag(n, alliance) {
    const c = document.createElement('canvas');
    c.width = c.height = 64;
    const ctx = c.getContext('2d');
    ctx.fillStyle = alliance === 'blue' ? '#2f6fe0' : '#d23a3f';
    ctx.beginPath(); ctx.arc(32, 32, 29, 0, Math.PI * 2); ctx.fill();
    ctx.lineWidth = 4; ctx.strokeStyle = '#ffffff'; ctx.stroke();
    ctx.fillStyle = '#ffffff';
    ctx.font = 'bold 38px system-ui, sans-serif';
    ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText(String(n), 32, 34);
    const tex = new THREE.CanvasTexture(c);
    tex.colorSpace = THREE.SRGBColorSpace;
    const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, depthTest: false }));
    sprite.scale.set(0.13, 0.13, 1);
    sprite.renderOrder = 10;
    return sprite;
  }

  buildRobot(cfg, wheels, wheelRadius, chassisZ, opts) {
    const c = cfg.chassis;
    const L = c.length_m, W = c.width_m, H = c.frame_height_m;
    const group = new THREE.Group();
    const mats = {
      frame: new THREE.MeshStandardMaterial({ color: COLORS.frame, metalness: 0.4, roughness: 0.55 }),
      plate: new THREE.MeshStandardMaterial({ color: new THREE.Color(opts.plate || c.color || '#b3261e'), roughness: 0.5 }),
      metal: new THREE.MeshStandardMaterial({ color: COLORS.aluminum, metalness: 0.6, roughness: 0.35 }),
      dark: new THREE.MeshStandardMaterial({ color: 0x1b1d20, roughness: 0.8 }),
      accent: new THREE.MeshStandardMaterial({ color: 0xf0b429, roughness: 0.4 }),
    };
    const add = (mesh, x, y, z) => { mesh.position.set(x, y, z); mesh.castShadow = true; group.add(mesh); return mesh; };

    // frame rails and side plates in the robot's alliance color
    add(new THREE.Mesh(new THREE.BoxGeometry(L, 0.03, H), mats.frame), 0, W / 2 - 0.015, 0);
    add(new THREE.Mesh(new THREE.BoxGeometry(L, 0.03, H), mats.frame), 0, -W / 2 + 0.015, 0);
    add(new THREE.Mesh(new THREE.BoxGeometry(0.03, W - 0.06, H * 0.8), mats.frame), L / 2 - 0.015, 0, 0);
    add(new THREE.Mesh(new THREE.BoxGeometry(0.03, W - 0.06, H * 0.8), mats.frame), -L / 2 + 0.015, 0, 0);
    add(new THREE.Mesh(new THREE.BoxGeometry(L * 0.98, 0.006, H * 0.9), mats.plate), 0, W / 2 + 0.003, 0);
    add(new THREE.Mesh(new THREE.BoxGeometry(L * 0.98, 0.006, H * 0.9), mats.plate), 0, -W / 2 - 0.003, 0);
    add(new THREE.Mesh(new THREE.BoxGeometry(L - 0.06, W - 0.06, 0.006), mats.dark), 0, 0, -H / 2 + 0.01);
    // Control Hub with its status LED, and a heading arrow
    add(new THREE.Mesh(new THREE.BoxGeometry(0.143, 0.103, 0.032), mats.dark), -0.1, 0.1, -H / 2 + 0.03);
    const led = add(new THREE.Mesh(new THREE.SphereGeometry(0.008, 10, 8), new THREE.MeshBasicMaterial({ color: 0x00ff00 })), -0.04, 0.1, -H / 2 + 0.05);
    const arrow = new THREE.Mesh(new THREE.ConeGeometry(0.045, 0.09, 3), mats.plate);
    arrow.rotation.z = -Math.PI / 2;
    add(arrow, L / 2 - 0.07, 0, H / 2 + 0.01);
    arrow.scale.z = 0.25;

    // wheels
    const wheelMeshes = [];
    const wheelTex = this.wheelTexture(cfg.drive.type === 'mecanum');
    for (const w of wheels) {
      const mecanum = cfg.drive.type === 'mecanum';
      const omni = !mecanum && Math.abs(w.x) > 0.01 && cfg.drive.type === 'tank';
      const geo = new THREE.CylinderGeometry(wheelRadius, wheelRadius, mecanum ? 0.045 : 0.03, 28);
      const mat = new THREE.MeshStandardMaterial({ map: (mecanum || omni) ? wheelTex : null, color: (mecanum || omni) ? 0xffffff : 0x26282c, roughness: 0.7 });
      const m = new THREE.Mesh(geo, mat);
      const holder = new THREE.Group();
      holder.position.set(w.x, w.y, wheelRadius - chassisZ);
      holder.add(m);
      m.castShadow = true;
      group.add(holder);
      wheelMeshes.push(m);
    }

    const mech = {};
    const top = H / 2;
    const roleOf = (d) => d.role || '';
    const devices = cfg.devices || [];
    let armTip = null;
    let launcherTop = top + 0.31;
    for (const d of devices) {
      switch (roleOf(d)) {
        case 'intake': {
          const roller = new THREE.Mesh(new THREE.CylinderGeometry(0.03, 0.03, W * 0.82, 20), new THREE.MeshStandardMaterial({ map: this.stripeTexture('#3a7d44', '#9be0a5'), roughness: 0.6 }));
          roller.position.set(L / 2 + 0.04, 0, -chassisZ + 0.045);
          roller.castShadow = true;
          group.add(roller);
          for (const s of [-1, 1]) add(new THREE.Mesh(new THREE.BoxGeometry(0.09, 0.012, 0.05), mats.metal), L / 2 + 0.02, s * W * 0.43, -chassisZ + 0.06);
          mech[d.name] = (v) => { roller.rotation.y = -v; };
          break;
        }
        case 'launcher': {
          add(new THREE.Mesh(new THREE.BoxGeometry(0.12, 0.16, 0.28), mats.frame), -0.08, 0, top + 0.14);
          const wheel = new THREE.Mesh(new THREE.CylinderGeometry(0.048, 0.048, 0.034, 28), new THREE.MeshStandardMaterial({ map: this.stripeTexture('#2d3136', '#f0b429'), roughness: 0.5 }));
          wheel.position.set(-0.08, 0, top + 0.31);
          wheel.castShadow = true;
          group.add(wheel);
          mech[d.name] = (v) => { wheel.rotation.y = -v; };
          break;
        }
        case 'hood': {
          const pivot = new THREE.Group();
          pivot.position.set(-0.13, 0, launcherTop + 0.055);
          const plate = new THREE.Mesh(new THREE.BoxGeometry(0.13, 0.12, 0.006), mats.accent);
          plate.position.x = 0.065;
          plate.castShadow = true;
          pivot.add(plate);
          group.add(pivot);
          mech[d.name] = (v) => { pivot.rotation.y = -0.35 - v * 0.35; };
          break;
        }
        case 'feeder': {
          const f = new THREE.Mesh(new THREE.CylinderGeometry(0.025, 0.025, 0.05, 16), new THREE.MeshStandardMaterial({ map: this.stripeTexture('#444a52', '#dfe3e8') }));
          f.position.set(0.0, 0, top + 0.22);
          group.add(f);
          mech[d.name] = (v) => { f.rotation.y = -v; };
          break;
        }
        case 'arm': {
          const pivot = new THREE.Group();
          pivot.position.set(0.02, 0, top + 0.16);
          const len = d.load?.length_m ?? 0.3;
          const bar = new THREE.Mesh(new THREE.BoxGeometry(len, 0.04, 0.03), mats.metal);
          bar.position.x = len / 2;
          bar.castShadow = true;
          pivot.add(bar);
          add(new THREE.Mesh(new THREE.BoxGeometry(0.05, 0.06, 0.16), mats.frame), 0.02, 0, top + 0.08);
          group.add(pivot);
          armTip = new THREE.Group();
          armTip.position.x = len;
          pivot.add(armTip);
          mech[d.name] = (v) => { pivot.rotation.y = -v; };
          break;
        }
        case 'lift': {
          const carriage = add(new THREE.Mesh(new THREE.BoxGeometry(0.06, 0.2, 0.05), mats.accent), -0.05, 0, top + 0.05);
          add(new THREE.Mesh(new THREE.BoxGeometry(0.03, 0.03, 0.45), mats.metal), -0.09, 0, top + 0.22);
          mech[d.name] = (v) => { carriage.position.z = top + 0.05 + v * 0.5; };
          break;
        }
        default:
      }
    }
    for (const d of devices) {
      const role = roleOf(d);
      if (role !== 'claw_left' && role !== 'claw_right') continue;
      const parent = armTip || group;
      const side = role === 'claw_left' ? 1 : -1;
      const pivot = new THREE.Group();
      pivot.position.set(armTip ? 0 : L / 2 + 0.02, side * 0.04, armTip ? 0 : top + 0.05);
      const finger = new THREE.Mesh(new THREE.BoxGeometry(0.08, 0.012, 0.03), mats.accent);
      finger.position.x = 0.04;
      finger.castShadow = true;
      pivot.add(finger);
      parent.add(pivot);
      mech[d.name] = (v) => { pivot.rotation.z = side * (0.5 - v * 0.5); };
    }

    let tag = null;
    if (opts.number) {
      tag = this.numberTag(opts.number, opts.alliance);
      tag.position.set(0, 0, top + 0.55);
      group.add(tag);
    }

    this.scene.add(group);
    return { group, wheels: wheelMeshes, mech, led, chassisZ, cfg, tag };
  }

  stripeTexture(a, b) {
    const c = document.createElement('canvas');
    c.width = 64; c.height = 16;
    const ctx = c.getContext('2d');
    ctx.fillStyle = a; ctx.fillRect(0, 0, 64, 16);
    ctx.fillStyle = b; ctx.fillRect(0, 0, 10, 16); ctx.fillRect(32, 0, 10, 16);
    const t = new THREE.CanvasTexture(c);
    t.colorSpace = THREE.SRGBColorSpace;
    return t;
  }

  wheelTexture(mecanum) {
    const c = document.createElement('canvas');
    c.width = 128; c.height = 32;
    const ctx = c.getContext('2d');
    ctx.fillStyle = '#26282c'; ctx.fillRect(0, 0, 128, 32);
    ctx.strokeStyle = '#8a9099'; ctx.lineWidth = 4;
    for (let i = -2; i < 12; i++) {
      ctx.beginPath();
      if (mecanum) { ctx.moveTo(i * 12, 0); ctx.lineTo(i * 12 + 18, 32); } else { ctx.moveTo(i * 12, 0); ctx.lineTo(i * 12, 32); }
      ctx.stroke();
    }
    const t = new THREE.CanvasTexture(c);
    t.colorSpace = THREE.SRGBColorSpace;
    return t;
  }

  updateState(s) {
    if (s.movers && this.movers) {
      // The field's moving parts (a HIVE tipping, a GATE swinging) at their angle now.
      for (let i = 0; i < this.movers.length && i < s.movers.length; i++) {
        const m = this.movers[i];
        m.pivot.quaternion.setFromAxisAngle(m.axis, s.movers[i]);
      }
    }
    for (let k = 0; k < this.robots.length && k < (s.robots?.length ?? 0); k++) {
      const r = this.robots[k], rs = s.robots[k];
      r.group.position.set(rs.p[0], rs.p[1], rs.p[2]);
      r.group.quaternion.set(rs.q[1], rs.q[2], rs.q[3], rs.q[0]);
      for (let i = 0; i < r.wheels.length && i < rs.wheels.length; i++) r.wheels[i].rotation.y = rs.wheels[i];
      for (const [name, apply] of Object.entries(r.mech)) {
        const v = rs.mech?.[name];
        if (typeof v === 'number') apply(v);
      }
      if (r.cad && rs.links) {
        // x, y, z, qw, qx, qy, qz per CAD group after the base, relative to the robot frame
        for (let g = 1, k = 0; g < r.cad.length && k + 6 < rs.links.length; g++, k += 7) {
          const L = rs.links;
          _q.set(L[k + 4], L[k + 5], L[k + 6], L[k + 3]);
          _p.set(L[k], L[k + 1], L[k + 2]);
          r.cad[g].matrix.compose(_p, _q, _one);
          r.cad[g].matrixWorldNeedsUpdate = true;
        }
      }
    }
    if (s.balls) {
      for (let i = 0; i < this.balls.length && 3 * i + 2 < s.balls.length; i++) {
        this.balls[i].position.set(s.balls[3 * i], s.balls[3 * i + 1], s.balls[3 * i + 2]);
        this.balls[i].visible = true;
      }
    }
    this.lastState = s;
  }

  setHubLed(i, color) {
    if (this.robots[i]) this.robots[i].led.material.color.set(color);
  }

  /** A robot's pose for the HUD: field x, y (m) and heading (deg). */
  pose(i = this.focus) {
    const s = this.lastState?.robots?.[i];
    if (!s) return null;
    const [w, x, y, z] = s.q;
    const yaw = Math.atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z));
    return { x: s.p[0], y: s.p[1], h: yaw * 180 / Math.PI };
  }

  // ---- camera -------------------------------------------------------------------------------

  setCameraMode(mode, instant = false) {
    this.mode = mode;
    this.controls.enabled = mode !== 'follow' && !this.placing;
    if (mode === 'follow') { this.camTween = null; return; }
    const preset = this.cameras[mode];
    const to = { pos: new THREE.Vector3(...preset.pos), target: new THREE.Vector3(...preset.target) };
    if (instant) {
      this.camera.position.copy(to.pos);
      this.controls.target.copy(to.target);
      this.controls.update();
      return;
    }
    this.camTween = { from: { pos: this.camera.position.clone(), target: this.controls.target.clone() }, to, t: 0 };
  }

  frame() {
    const now = performance.now();
    const dt = Math.min(0.1, (now - (this._last ?? now)) / 1000);
    this._last = now;
    const followed = this.robots[this.focus];
    if (this.mode === 'follow' && followed) {
      const g = followed.group;
      const fwd = new THREE.Vector3(1, 0, 0).applyQuaternion(g.quaternion);
      fwd.z = 0; fwd.normalize();
      const want = g.position.clone().addScaledVector(fwd, -1.25).add(new THREE.Vector3(0, 0, 0.75));
      this.camera.position.lerp(want, Math.min(1, dt * 6));
      const look = g.position.clone().addScaledVector(fwd, 0.5);
      this.controls.target.lerp(look, Math.min(1, dt * 8));
      this.camera.lookAt(this.controls.target);
    } else if (this.camTween) {
      const tw = this.camTween;
      tw.t = Math.min(1, tw.t + dt / 0.45);
      const e = tw.t < 0.5 ? 2 * tw.t * tw.t : 1 - Math.pow(-2 * tw.t + 2, 2) / 2;
      this.camera.position.lerpVectors(tw.from.pos, tw.to.pos, e);
      this.controls.target.lerpVectors(tw.from.target, tw.to.target, e);
      if (tw.t >= 1) this.camTween = null;
      this.controls.update();
    } else {
      this.controls.update();
    }
    this.renderer.render(this.scene, this.camera);
  }

  // ---- placing the robot -------------------------------------------------------------------

  makeGhost() {
    const g = new THREE.Group();
    const ring = new THREE.Mesh(new THREE.RingGeometry(0.2, 0.235, 40), new THREE.MeshBasicMaterial({ color: 0x3e7bfa, transparent: true, opacity: 0.85, depthWrite: false }));
    g.add(ring);
    const arrow = new THREE.Mesh(new THREE.ConeGeometry(0.06, 0.16, 3), new THREE.MeshBasicMaterial({ color: 0x3e7bfa }));
    arrow.rotation.z = -Math.PI / 2;
    arrow.position.x = 0.3;
    g.add(arrow);
    g.position.z = 0.004;
    g.visible = false;
    return g;
  }

  setPlaceMode(on, onPlace) {
    this.placing = on ? { onPlace, down: null } : null;
    this.ghost.visible = false;
    this.controls.enabled = !on && this.mode !== 'follow';
    this.canvas.style.cursor = on ? 'crosshair' : '';
  }

  floorPoint(ev) {
    const rect = this.canvas.getBoundingClientRect();
    const ndc = new THREE.Vector2(((ev.clientX - rect.left) / rect.width) * 2 - 1, -((ev.clientY - rect.top) / rect.height) * 2 + 1);
    this.raycaster.setFromCamera(ndc, this.camera);
    const p = new THREE.Vector3();
    return this.raycaster.ray.intersectPlane(this.floorPlane, p) ? p : null;
  }

  bindPointer() {
    this.canvas.addEventListener('pointerdown', (ev) => {
      if (!this.placing) return;
      const p = this.floorPoint(ev);
      if (!p) return;
      this.placing.down = p;
      this.ghost.position.set(p.x, p.y, 0.004);
      const pose = this.pose();
      this.ghost.rotation.z = pose ? pose.h * Math.PI / 180 : 0;
      this.ghost.visible = true;
      this.canvas.setPointerCapture(ev.pointerId);
    });
    this.canvas.addEventListener('pointermove', (ev) => {
      if (!this.placing) return;
      const p = this.floorPoint(ev);
      if (!p) return;
      if (!this.placing.down) {
        this.ghost.visible = true;
        this.ghost.position.set(p.x, p.y, 0.004);
        return;
      }
      const d = this.placing.down;
      if (p.distanceTo(d) > 0.05) this.ghost.rotation.z = Math.atan2(p.y - d.y, p.x - d.x);
    });
    this.canvas.addEventListener('pointerup', () => {
      if (!this.placing || !this.placing.down) return;
      const d = this.placing.down;
      const h = this.ghost.rotation.z * 180 / Math.PI;
      const cb = this.placing.onPlace;
      this.placing.down = null;
      cb(d.x, d.y, h);
    });
  }
}
