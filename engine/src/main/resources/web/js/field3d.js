// 3D view of the BIOBUZZ field and the simulated robots (Three.js, z-up, meters).
import * as THREE from 'three';
import { OrbitControls } from '../vendor/OrbitControls.js';

const COLORS = {
  tile: '#50555c', seam: '#3a3e44', red: '#d23a3f', blue: '#2f6fe0',
  aluminum: 0xb9c0c8, frame: 0x2b2f35, flower: 0x2fa84f, gold: 0xd9a52b,
};

// SCORING ELEMENTS: yellow POLLEN, red and blue NECTAR.
const BALL_COLORS = { yellow: 0xf4d23c, red: 0xd8343a, blue: 0x2f6fe0 };
// Side plates of robots on each alliance, when there is more than one robot to tell apart.
const ALLIANCE_PLATES = { red: '#b3261e', blue: '#1e4fb3' };

const CAMERAS = {
  audience: { pos: [0, -4.5, 2.8], target: [0, -0.15, 0.25] },
  overhead: { pos: [0, -0.001, 6.4], target: [0, 0, 0] },
  driver: { pos: [-3.75, 0, 1.7], target: [0.2, 0, 0.2] },
};

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

  setField(field) {
    this.fieldGroup.clear();
    this.field = field;
    const half = field.field_m[0] / 2;
    this.half = half;
    const g = this.fieldGroup;

    // Venue floor and alliance areas
    const venue = new THREE.Mesh(new THREE.PlaneGeometry(14, 14), new THREE.MeshStandardMaterial({ color: 0x1c1e22, roughness: 1 }));
    venue.position.z = -0.003;
    venue.receiveShadow = true;
    g.add(venue);
    for (const [sign, color] of [[-1, 0x4a1c1f], [1, 0x1b2a52]]) {
      const area = new THREE.Mesh(new THREE.PlaneGeometry(1.25, 2.8), new THREE.MeshStandardMaterial({ color, roughness: 1 }));
      area.position.set(sign * (half + 0.75), 0, -0.002);
      area.receiveShadow = true;
      g.add(area);
    }

    // Tiles with tape, drawn into one texture
    const tiles = new THREE.Mesh(new THREE.PlaneGeometry(2 * half, 2 * half),
      new THREE.MeshStandardMaterial({ map: this.floorTexture(field), roughness: 0.95 }));
    tiles.receiveShadow = true;
    g.add(tiles);

    this.addWalls(half, field.wall_height_m);
    for (const x of [-0.628, 0.628]) this.addAFrame(x);
    // Axle and sign board
    const axle = new THREE.Mesh(new THREE.CylinderGeometry(0.018, 0.018, 1.32, 16), new THREE.MeshStandardMaterial({ color: COLORS.aluminum, metalness: 0.6, roughness: 0.4 }));
    axle.rotation.z = Math.PI / 2;
    axle.position.set(0, 0, field.hives.red.pivot_m[2]);
    g.add(axle);
    const sb = field.structure?.sign_board;
    if (sb) {
      const sign = new THREE.Mesh(new THREE.BoxGeometry(...sb.size_m), new THREE.MeshStandardMaterial({ color: 0xf2c230, roughness: 0.6 }));
      sign.position.set(...sb.centre_m);
      sign.castShadow = true;
      g.add(sign);
    }
    for (const alliance of Object.keys(field.hives)) this.addHive(field, alliance);
    for (const name of Object.keys(field.flowers)) this.addFlower(field.flowers[name], field.structure?.flower_top);
  }

  floorTexture(field) {
    const N = 2048, half = field.field_m[0] / 2, tile = field.tile_m;
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
    for (let v = -half; v <= half + 1e-6; v += tile) {
      ctx.beginPath(); ctx.moveTo(px(v), 0); ctx.lineTo(px(v), N); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(0, py(v)); ctx.lineTo(N, py(v)); ctx.stroke();
    }
    const tape = (field.structure?.tape_m ?? 0.0254) / (2 * half) * N;
    const zone = (z, color, filled) => {
      const x0 = px(z.x_m[0]), x1 = px(z.x_m[1]), y0 = py(z.y_m[1]), y1 = py(z.y_m[0]);
      ctx.fillStyle = ctx.strokeStyle = color;
      if (filled) ctx.fillRect(x0, y0, x1 - x0, y1 - y0);
      else { ctx.lineWidth = tape; ctx.strokeRect(x0 + tape / 2, y0 + tape / 2, x1 - x0 - tape, y1 - y0 - tape); }
    };
    for (const [a, z] of Object.entries(field.loading_zones)) zone(z, a === 'red' ? COLORS.red : COLORS.blue, false);
    for (const [a, z] of Object.entries(field.gardens)) zone(z, a === 'red' ? COLORS.red : COLORS.blue, true);
    const tex = new THREE.CanvasTexture(c);
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.anisotropy = this.renderer.capabilities.getMaxAnisotropy();
    return tex;
  }

  beam(a, b, t, material) {
    const va = new THREE.Vector3(...a), vb = new THREE.Vector3(...b);
    const len = va.distanceTo(vb);
    const m = new THREE.Mesh(new THREE.BoxGeometry(t, t, len), material);
    m.position.copy(va).add(vb).multiplyScalar(0.5);
    m.lookAt(vb);
    m.castShadow = true;
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

  addAFrame(x) {
    const g = this.fieldGroup;
    const mat = new THREE.MeshStandardMaterial({ color: 0x8d949c, metalness: 0.5, roughness: 0.5 });
    const top = this.field.hives.red.pivot_m[2] + 0.04;
    g.add(this.beam([x, -0.5, 0.02], [x, 0, top], 0.045, mat));
    g.add(this.beam([x, 0.5, 0.02], [x, 0, top], 0.045, mat));
    g.add(this.beam([x, -0.53, 0.025], [x, 0.53, 0.025], 0.05, mat));
    g.add(this.beam([x, -0.39, 0.33], [x, 0.39, 0.33], 0.035, mat));
  }

  addHive(field, alliance) {
    const hive = field.hives[alliance];
    const cells = field.cells;
    const color = alliance === 'red' ? COLORS.red : COLORS.blue;
    const pivot = new THREE.Group();
    pivot.position.set(...hive.pivot_m);
    pivot.rotation.x = hive.stops_rad[`${hive.start_up}_up`];
    this.fieldGroup.add(pivot);

    const hw = cells.half_width_m, f = cells.floor_z_m, wall = cells.wall_height_m, peak = cells.peak_height_m;
    const depth = cells.along_arm_m[1] - cells.along_arm_m[0];
    const shape = new THREE.Shape();
    shape.moveTo(-hw, f); shape.lineTo(hw, f); shape.lineTo(hw, f + wall); shape.lineTo(0, f + peak); shape.lineTo(-hw, f + wall); shape.closePath();
    const geo = new THREE.ExtrudeGeometry(shape, { depth, bevelEnabled: false });
    geo.rotateX(Math.PI / 2);                 // extrusion now runs along -y
    geo.translate(0, -cells.along_arm_m[0], 0);
    // The CELL is a tube open at its outer end: draw the sides (group 1) but not the end caps
    // (group 0), then close only the inner end.
    const innerEnd = new THREE.ShapeGeometry(shape);
    innerEnd.rotateX(Math.PI / 2);
    innerEnd.translate(0, -cells.along_arm_m[0], 0);
    const shell = new THREE.MeshStandardMaterial({ color: 0xe6ebf0, transparent: true, opacity: 0.32, roughness: 0.2, side: THREE.DoubleSide, depthWrite: false });
    const noCaps = new THREE.MeshBasicMaterial({ visible: false });
    const edge = new THREE.LineBasicMaterial({ color });
    for (const flip of [false, true]) {          // audience cell (-y), then rear cell (+y)
      const cell = new THREE.Group();
      cell.add(new THREE.Mesh(geo, [noCaps, shell]));
      cell.add(new THREE.Mesh(innerEnd, shell));
      cell.add(new THREE.LineSegments(new THREE.EdgesGeometry(geo), edge));
      if (flip) cell.rotation.z = Math.PI;
      pivot.add(cell);
    }
    const arm = new THREE.Mesh(new THREE.BoxGeometry(0.05, 2 * cells.along_arm_m[1], 0.03), new THREE.MeshStandardMaterial({ color: 0x5b6169, metalness: 0.4 }));
    arm.position.z = f - 0.02;
    pivot.add(arm);
    // AprilTags under the cells
    const tagMat = new THREE.MeshBasicMaterial({ map: this.tagTexture(), side: THREE.DoubleSide });
    const size = field.apriltags.size_m;
    for (const [id, t] of Object.entries(field.apriltags.tags)) {
      if (t.hive !== alliance) continue;
      const m = new THREE.Mesh(new THREE.PlaneGeometry(size, size), tagMat);
      m.position.set(...t.center_m);
      m.position.z -= 0.001;
      m.name = `tag ${id}`;
      pivot.add(m);
    }
  }

  tagTexture() {
    if (this._tagTex) return this._tagTex;
    const c = document.createElement('canvas');
    c.width = c.height = 80;
    const ctx = c.getContext('2d');
    ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, 80, 80);
    ctx.fillStyle = '#000'; ctx.fillRect(10, 10, 60, 60);
    ctx.fillStyle = '#fff';
    for (let i = 0; i < 16; i++) if ((i * 7 + 3) % 3) ctx.fillRect(20 + (i % 4) * 10, 20 + Math.floor(i / 4) * 10, 10, 10);
    this._tagTex = new THREE.CanvasTexture(c);
    this._tagTex.magFilter = THREE.NearestFilter;
    return this._tagTex;
  }

  addFlower(flower, top) {
    const [x, y] = flower.axis_m;
    const h = flower.opening_height_m;
    const grp = new THREE.Group();
    grp.position.set(x, y, 0);
    const bars = new THREE.MeshStandardMaterial({ color: COLORS.flower, roughness: 0.5 });
    for (let i = 0; i < 6; i++) {
      const a = i / 6 * Math.PI * 2;
      const bar = new THREE.Mesh(new THREE.CylinderGeometry(0.006, 0.006, h, 6), bars);
      bar.rotation.x = Math.PI / 2;
      bar.position.set(Math.cos(a) * 0.055, Math.sin(a) * 0.055, h / 2);
      bar.castShadow = true;
      grp.add(bar);
    }
    for (const z of [0.12, 0.3]) {
      const hoop = new THREE.Mesh(new THREE.TorusGeometry(0.055, 0.005, 6, 24), bars);
      hoop.position.z = z;
      grp.add(hoop);
    }
    const ring = new THREE.Mesh(new THREE.TorusGeometry(top?.radius_m ?? 0.07, 0.012, 10, 32),
      new THREE.MeshStandardMaterial({ color: COLORS.gold, metalness: 0.6, roughness: 0.35 }));
    ring.position.z = h;
    ring.castShadow = true;
    grp.add(ring);
    const base = new THREE.Mesh(new THREE.CylinderGeometry(0.08, 0.08, 0.012, 24), new THREE.MeshStandardMaterial({ color: 0x24262a }));
    base.rotation.x = Math.PI / 2;
    base.position.z = 0.006;
    grp.add(base);
    this.fieldGroup.add(grp);
  }

  // ---- scoring elements --------------------------------------------------------------------

  /** One sphere per POLLEN / NECTAR, in the order the simulator streams their positions. */
  setBalls(list) {
    this.ballGroup.clear();
    this.balls = [];
    const geos = {};
    const mats = {};
    for (const b of list || []) {
      const geo = geos[b.r] ??= new THREE.SphereGeometry(b.r, 20, 14);
      const mat = mats[b.color] ??= new THREE.MeshStandardMaterial({ color: BALL_COLORS[b.color] ?? 0xffffff, roughness: 0.45 });
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
    for (const r of this.robots) this.scene.remove(r.group);
    const several = list.length > 1;
    this.robots = list.map((r) => this.buildRobot(r.robot, r.wheels, r.wheelRadius, r.chassisZ,
      several ? { plate: ALLIANCE_PLATES[r.alliance], number: r.index + 1, alliance: r.alliance } : {}));
    if (this.focus >= this.robots.length) this.focus = 0;
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

    if (opts.number) {
      const tag = this.numberTag(opts.number, opts.alliance);
      tag.position.set(0, 0, top + 0.55);
      group.add(tag);
    }

    this.scene.add(group);
    return { group, wheels: wheelMeshes, mech, led, chassisZ, cfg };
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
    for (let k = 0; k < this.robots.length && k < (s.robots?.length ?? 0); k++) {
      const r = this.robots[k], rs = s.robots[k];
      r.group.position.set(rs.p[0], rs.p[1], rs.p[2]);
      r.group.quaternion.set(rs.q[1], rs.q[2], rs.q[3], rs.q[0]);
      for (let i = 0; i < r.wheels.length && i < rs.wheels.length; i++) r.wheels[i].rotation.y = rs.wheels[i];
      for (const [name, apply] of Object.entries(r.mech)) {
        const v = rs.mech?.[name];
        if (typeof v === 'number') apply(v);
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
    const preset = CAMERAS[mode];
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
