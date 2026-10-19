// Acad – DWG okuma işçisi. libredwg-web (GPL-3.0) ile DWG'yi okur ve
// görüntüleyicinin ortak çizim modeline çevirir.
let binPromise = null;
let engine = null;

async function loadEngine() {
  if (!engine) engine = await import('./dwg/dist/libredwg-web.js');
  if (!binPromise) {
    binPromise = fetch(new URL('./dwg/wasm/libredwg-web.wasm', import.meta.url))
      .then(r => { if (!r.ok) throw new Error('wasm ' + r.status); return r.arrayBuffer(); });
  }
  return { engine, bin: await binPromise };
}

const num = v => (typeof v === 'number' && isFinite(v) ? v : 0);

function colorOf(e) {
  const ci = e.colorIndex;
  if (ci === 0 || ci === 256) return { c: ci };
  if (ci >= 1 && ci <= 255) return { c: ci };
  if (typeof e.color === 'number' && e.color >= 0) return { c: -1, rgb: e.color & 0xffffff };
  return { c: 256 };
}

function base(e) {
  const o = { l: e.layer || '0', ...colorOf(e) };
  const ex = e.extrusionDirection;
  if (ex && ex.z < -0.5) o.neg = 1;
  return o;
}

function textOf(t, e, isAttr) {
  // t: DwgTextBase-like
  if (!t || !t.text) return null;
  const ha = t.halign | 0, va = t.valign | 0;
  let p = t.startPoint || { x: 0, y: 0 };
  let h2 = ha;
  if (ha === 3 || ha === 5) h2 = 0; // aligned / fit -> start point, left
  else if (ha !== 0 || va !== 0) p = (isAttr ? e.alignmentPoint : null) || t.endPoint || p;
  return {
    k: 'T', ...base(e), x: num(p.x), y: num(p.y), h: num(t.textHeight) || 1, r: num(t.rotation),
    s: String(t.text), ha: h2, va: ha === 4 ? 2 : va, ws: num(t.xScale) || 1, m: 0,
  };
}

function edgeOf(g) {
  switch (g.type) {
    case 1: return ['l', g.start.x, g.start.y, g.end.x, g.end.y];
    case 2: return ['a', g.center.x, g.center.y, g.radius, num(g.startAngle), num(g.endAngle), g.isCCW === false ? 0 : 1];
    case 3: return ['e', g.center.x, g.center.y, g.end.x, g.end.y, num(g.lengthOfMinorAxis) || 1, num(g.startAngle), num(g.endAngle), g.isCCW === false ? 0 : 1];
    case 4: return ['s', g.degree | 0, flat(g.controlPoints), g.knots || [], (g.controlPoints || []).some(p => p.weight != null) ? g.controlPoints.map(p => p.weight ?? 1) : null, flat(g.fitDatum)];
    default: return null;
  }
}
function flat(pts) { const a = []; for (const p of pts || []) a.push(num(p.x), num(p.y)); return a; }

function conv(e) {
  switch (e.type) {
    case 'LINE': return { k: 'L', ...base(e), a: [e.startPoint.x, e.startPoint.y, e.endPoint.x, e.endPoint.y] };
    case 'LWPOLYLINE': {
      const p = []; for (const v of e.vertices || []) p.push(num(v.x), num(v.y), num(v.bulge));
      return { k: 'P', ...base(e), p, cl: (e.flag & 1) ? 1 : 0 };
    }
    case 'POLYLINE2D': case 'POLYLINE3D': {
      if (e.flag & 64) return null; // polyface mesh
      let vs = e.vertices || [];
      if (vs.some(v => v.flag & 8)) vs = vs.filter(v => v.flag & 8);
      else vs = vs.filter(v => !(v.flag & 16));
      const p = []; for (const v of vs) p.push(num(v.x), num(v.y), e.type === 'POLYLINE2D' ? num(v.bulge) : 0);
      return { k: 'P', ...base(e), p, cl: (e.flag & 1) ? 1 : 0 };
    }
    case 'ARC': return { k: 'A', ...base(e), x: e.center.x, y: e.center.y, r: e.radius, s: e.startAngle, e: e.endAngle };
    case 'CIRCLE': return { k: 'C', ...base(e), x: e.center.x, y: e.center.y, r: e.radius };
    case 'ELLIPSE': return { k: 'E', ...base(e), x: e.center.x, y: e.center.y, mx: e.majorAxisEndPoint.x, my: e.majorAxisEndPoint.y, ra: e.axisRatio, s: e.startAngle, e: e.endAngle };
    case 'SPLINE': return { k: 'SP', ...base(e), deg: e.degree | 0, cp: flat(e.controlPoints), kn: e.knots || [], w: e.weights && e.weights.length ? e.weights : null, fp: flat(e.fitPoints), cl: (e.flag & 1) ? 1 : 0 };
    case 'TEXT': return textOf(e, e, false);
    case 'ATTRIB': if (e.flags & 1) return null; return e.text ? textOf(e.text, e, true) : null;
    case 'MTEXT': {
      const d = e.direction;
      let r = num(e.rotation);
      if (d && (d.x || d.y)) r = Math.atan2(d.y, d.x);
      return { k: 'T', ...base(e), x: e.insertionPoint.x, y: e.insertionPoint.y, h: num(e.textHeight) || 1, r, s: String(e.text || ''), m: 1, at: e.attachmentPoint || 1, rw: num(e.rectWidth), ls: num(e.lineSpacing) || 1, ws: 1 };
    }
    case 'INSERT': return { k: 'I', ...base(e), h: e.handle, n: e.name, x: e.insertionPoint.x, y: e.insertionPoint.y, sx: num(e.xScale) || 1, sy: num(e.yScale) || 1, r: num(e.rotation), nc: e.columnCount | 0, nr: e.rowCount | 0, cs: num(e.columnSpacing), rs: num(e.rowSpacing), att: [] };
    case 'DIMENSION': return e.name ? { k: 'D', ...base(e), n: e.name } : null;
    case 'SOLID': case 'TRACE': {
      const c4 = e.corner4 || e.corner3;
      return { k: 'F', ...base(e), p: [e.corner1.x, e.corner1.y, e.corner2.x, e.corner2.y, c4.x, c4.y, e.corner3.x, e.corner3.y] };
    }
    case '3DFACE': {
      const c4 = e.corner4 || e.corner3;
      return { k: 'P', ...base(e), p: [e.corner1.x, e.corner1.y, 0, e.corner2.x, e.corner2.y, 0, e.corner3.x, e.corner3.y, 0, c4.x, c4.y, 0], cl: 1 };
    }
    case 'LEADER': {
      const p = []; for (const v of e.vertices || []) p.push(num(v.x), num(v.y), 0);
      return p.length >= 4 ? { k: 'P', ...base(e), p, cl: 0, arrow: e.isArrowheadEnabled ? 1 : 0 } : null;
    }
    case 'HATCH': {
      const loops = [];
      for (const bp of e.boundaryPaths || []) {
        if (bp.vertices) {
          const p = []; for (const v of bp.vertices) p.push(num(v.x), num(v.y), num(v.bulge));
          loops.push([['p', p, 1]]);
        } else if (bp.edges) {
          const ed = bp.edges.map(edgeOf).filter(Boolean);
          if (ed.length) loops.push(ed);
        }
      }
      if (!loops.length) return null;
      const defs = (e.definitionLines || []).map(d => [num(d.angle), num(d.base?.x), num(d.base?.y), num(d.offset?.x), num(d.offset?.y), d.dashLengths || []]);
      return { k: 'H', ...base(e), sol: e.solidFill === 1 ? 1 : 0, pat: e.patternName || '', loops, defs, ps: num(e.patternScale) || 1, pa: num(e.patternAngle) };
    }
    case 'WIPEOUT': return null;
    default: return null;
  }
}

function convertDb(db) {
  const layers = (db.tables.LAYER?.entries || []).map(l => {
    const o = { name: l.name, c: Math.abs(l.colorIndex || 7), off: !!(l.off || l.frozen || l.colorIndex < 0) };
    if (!(l.colorIndex >= 1 && l.colorIndex <= 255) && typeof l.color === 'number') o.rgb = l.color & 0xffffff;
    return o;
  });
  // ATTRIBs are not inside block lists; attach them to their INSERT by owner handle
  const attByOwner = new Map();
  for (const e of db.entities) {
    if (e.type === 'ATTRIB') {
      const t = conv(e);
      if (!t) continue;
      const k = e.ownerBlockRecordSoftId;
      if (!attByOwner.has(k)) attByOwner.set(k, []);
      attByOwner.get(k).push(t);
    }
  }
  const counts = {};
  const convList = list => {
    const out = [];
    for (const e of list || []) {
      if (e.type === 'ATTRIB' || e.type === 'ATTDEF' || e.isVisible === false) continue;
      let c = null;
      try { c = conv(e); } catch (err) { c = null; }
      if (!c) { counts['?' + e.type] = (counts['?' + e.type] || 0) + 1; continue; }
      if (c.k === 'I') { c.att = attByOwner.get(e.handle) || []; delete c.h; }
      out.push(c);
    }
    return out;
  };
  const blocks = {};
  let model = null;
  for (const b of db.tables.BLOCK_RECORD?.entries || []) {
    const nm = b.name || '';
    const up = nm.toUpperCase();
    if (up === '*MODEL_SPACE') { model = convList(b.entities); continue; }
    if (up.startsWith('*PAPER_SPACE')) continue;
    blocks[nm] = { bx: num(b.basePoint?.x), by: num(b.basePoint?.y), ents: convList(b.entities) };
  }
  if (!model) model = convList(db.entities.filter(e => !e.isInPaperSpace));
  return { units: db.header?.INSUNITS | 0, layers, blocks, ents: model, skipped: counts };
}

self.onmessage = async ev => {
  const { id, buf } = ev.data;
  try {
    self.postMessage({ id, stage: 'engine' });
    const { engine, bin } = await loadEngine();
    self.postMessage({ id, stage: 'parse' });
    const inst = await engine.createModule({ wasmBinary: bin });
    const lib = engine.LibreDwg.createByWasmInstance(inst);
    const ptr = lib.dwg_read_data(buf, engine.Dwg_File_Type.DWG);
    if (ptr == null) throw new Error('read');
    const db = lib.convert(ptr);
    const model = convertDb(db);
    self.postMessage({ id, model });
  } catch (err) {
    self.postMessage({ id, error: String(err && err.message || err) });
  }
};
