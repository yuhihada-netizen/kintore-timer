"use strict";
const $ = (id) => document.getElementById(id);
const KEY = "kintore-timer-v1";

/* ---------- データ ---------- */
const sample = () => ({
  id: uid(), name: "体幹サンプル", prep: 5, rest: 15,
  items: [
    { name: "ノーマルプランク", sec: 30, sets: 2, restSet: 10 },
    { name: "サイドプランク（右）", sec: 20, sets: 1, restSet: 0 },
    { name: "サイドプランク（左）", sec: 20, sets: 1, restSet: 0 },
  ],
});
function uid() { return Math.random().toString(36).slice(2, 9); }

let data = load();
function load() {
  try {
    const d = JSON.parse(localStorage.getItem(KEY));
    if (d && Array.isArray(d.menus) && d.menus.length) return d;
  } catch (e) {}
  const m = sample();
  return { menus: [m], cur: m.id };
}
function save() { try { localStorage.setItem(KEY, JSON.stringify(data)); } catch (e) {} }
const menu = () => data.menus.find((m) => m.id === data.cur) || data.menus[0];
const num = (v, d = 0) => { const n = Math.floor(Number(v)); return isFinite(n) && n >= 0 ? n : d; };

/* ---------- タイムライン作成 ---------- */
function buildTimeline(m) {
  const segs = [];
  if (num(m.prep) > 0) segs.push({ type: "rest", label: "準備", sub: m.items[0] ? "次: " + m.items[0].name : "", sec: num(m.prep) });
  m.items.forEach((it, i) => {
    const sets = Math.max(1, num(it.sets, 1)), sec = Math.max(1, num(it.sec, 1));
    for (let s = 1; s <= sets; s++) {
      segs.push({ type: "work", label: it.name || "種目", sub: sets > 1 ? `${s}/${sets}セット` : "", sec });
      if (s < sets && num(it.restSet) > 0)
        segs.push({ type: "rest", label: "休憩", sub: `次: ${it.name} ${s + 1}/${sets}セット`, sec: num(it.restSet) });
    }
    const next = m.items[i + 1];
    if (next && num(m.rest) > 0) segs.push({ type: "rest", label: "休憩", sub: "次: " + next.name, sec: num(m.rest) });
  });
  let t = 0;
  segs.forEach((s) => { s.start = t * 1000; t += s.sec; s.end = t * 1000; });
  return segs;
}
const fmt = (s) => (s >= 60 ? `${Math.floor(s / 60)}分${String(s % 60).padStart(2, "0")}秒` : `${s}秒`);

/* ---------- タイマー状態 ---------- */
const T = { segs: [], running: false, started: false, startMs: 0, acc: 0, done: false, lastSeg: -1 };
const elapsed = () => T.acc + (T.running ? Date.now() - T.startMs : 0);
const total = () => (T.segs.length ? T.segs[T.segs.length - 1].end : 0);

function view() {
  if (!T.started) return null;
  const e = elapsed(), tot = total();
  if (e >= tot) return { done: true, label: "完了！", sub: "おつかれさま", type: "rest", remain: 0, ratio: 1 };
  let i = T.segs.findIndex((s) => e < s.end);
  const s = T.segs[i];
  return { i, type: s.type, label: s.label, sub: s.sub, remain: Math.ceil((s.end - e) / 1000), ratio: (e - s.start) / (s.end - s.start) };
}

/* ---------- Worker で時刻を刻む（背景でも止まりにくい） ---------- */
let worker;
function startTicker() {
  if (worker) return;
  const src = "setInterval(()=>postMessage(0),250)";
  worker = new Worker(URL.createObjectURL(new Blob([src], { type: "text/javascript" })));
  worker.onmessage = tick;
}
function tick() {
  if (!T.started) return;
  const v = view();
  if (v.done && T.running) { T.acc = total(); T.running = false; }
  render(v);
  drawPip(v);
}

/* ---------- 画面描画 ---------- */
function render(v) {
  const now = $("now");
  if (!v) { $("nowLabel").textContent = "メニューを選んで開始"; $("nowSub").textContent = ""; $("nowTime").textContent = "--"; now.className = ""; $("bar").firstElementChild.style.width = "0"; return; }
  now.className = v.type;
  $("nowLabel").textContent = v.label;
  $("nowSub").textContent = v.sub || "";
  $("nowTime").textContent = v.done ? "🎉" : "残り " + fmt(v.remain);
  const b = $("bar").firstElementChild; b.style.width = (v.ratio * 100).toFixed(1) + "%"; b.style.background = v.type === "work" ? "var(--work)" : "var(--rest)";
  $("btnMain").textContent = v.done ? "もう一度" : T.running ? "一時停止" : "再開";
  $("btnPrev").disabled = $("btnNext").disabled = $("btnStop").disabled = false;
  if (v.done) $("btnPrev").disabled = $("btnNext").disabled = true;
}

/* ---------- PiP 用キャンバス ---------- */
const cv = $("cv"), ctx = cv.getContext("2d"), pv = $("pv");
const FONT = '"Noto Sans JP","Hiragino Sans",sans-serif';
function fit(text, maxW, size, weight) {
  do { ctx.font = `${weight} ${size}px ${FONT}`; size -= 2; } while (ctx.measureText(text).width > maxW && size > 20);
}
function drawPip(v) {
  const W = cv.width, H = cv.height;
  const col = !v ? "#9aa5b1" : v.type === "work" ? "#ff8a2b" : "#34c27a";
  ctx.fillStyle = "#111418"; ctx.fillRect(0, 0, W, H);
  ctx.fillStyle = col; ctx.fillRect(0, H - 14, W, 14);
  if (v && !v.done) { ctx.fillStyle = "#2d343d"; ctx.fillRect(0, H - 14, W, 14); ctx.fillStyle = col; ctx.fillRect(0, H - 14, W * v.ratio, 14); }
  /* 1行表示：「ノーマルプランク：残り20秒」 */
  let text = "筋トレタイマー";
  if (v) {
    const set = v.type === "work" && v.sub ? " " + v.sub.replace("セット", "") : "";
    text = v.done ? "完了！おつかれさま" : `${v.label}${set}：残り${fmt(v.remain)}`;
  }
  if (v && !T.running && !v.done) text = "⏸ " + text;
  ctx.textAlign = "center"; ctx.textBaseline = "middle";
  ctx.fillStyle = "#eef1f4"; fit(text, W - 40, 140, 800);
  ctx.fillText(text, W / 2, (H - 14) / 2);
  document.title = v && !v.done ? `${v.label}：残り${fmt(v.remain)}` : "筋トレタイマー";
}

let stream;
async function openPip() {
  const note = $("pipNote");
  if (!document.pictureInPictureEnabled || !cv.captureStream) { note.textContent = "このブラウザはPiP表示に対応していません。Android版Chromeで開いてください。"; return; }
  try {
    if (!stream) { stream = cv.captureStream(15); pv.srcObject = stream; }
    drawPip(view());
    await pv.play();
    if (document.pictureInPictureElement !== pv) await pv.requestPictureInPicture();
    note.textContent = "PiPを表示中。YouTubeを開き、ウィンドウを上にドラッグしてください。";
  } catch (e) {
    note.textContent = "PiPを開けませんでした（" + (e.message || e.name) + "）。もう一度「開始」を押してください。";
  }
}

/* PiPウィンドウの再生/一時停止と前後ボタンをタイマーに連動 */
pv.addEventListener("pause", () => { if (T.running && document.pictureInPictureElement) pause(); });
pv.addEventListener("play", () => { if (!T.running && T.started && !(view() || {}).done) resume(); });
pv.addEventListener("leavepictureinpicture", () => { $("pipNote").textContent = ""; });
if ("mediaSession" in navigator) {
  const h = (a, f) => { try { navigator.mediaSession.setActionHandler(a, f); } catch (e) {} };
  h("previoustrack", () => jump(-1));
  h("nexttrack", () => jump(1));
}

/* ---------- 操作 ---------- */
let wake;
async function keepAwake(on) {
  try {
    if (on && "wakeLock" in navigator) wake = await navigator.wakeLock.request("screen");
    else if (wake) { await wake.release(); wake = null; }
  } catch (e) {}
}
document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible" && T.running) keepAwake(true); });

function start() {
  const m = menu();
  if (!m.items.length) { alert("種目を追加してください"); return; }
  T.segs = buildTimeline(m); T.started = true; T.running = true; T.acc = 0; T.startMs = Date.now(); T.done = false;
  startTicker(); keepAwake(true); openPip(); tick();
}
function pause() { if (!T.running) return; T.acc = elapsed(); T.running = false; tick(); }
function resume() { if (T.running || !T.started) return; T.startMs = Date.now(); T.running = true; keepAwake(true); tick(); }
function stop() {
  T.started = false; T.running = false; T.acc = 0; keepAwake(false);
  if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
  render(null); drawPip(null); $("btnMain").textContent = "開始"; $("btnPrev").disabled = $("btnNext").disabled = $("btnStop").disabled = true;
  document.title = "筋トレタイマー";
}
function jump(dir) {
  const v = view(); if (!v || v.done) return;
  const s = T.segs[v.i];
  let target;
  if (dir > 0) target = T.segs[v.i + 1] ? T.segs[v.i + 1].start : total();
  else target = v.ratio > 0.05 || !T.segs[v.i - 1] ? s.start : T.segs[v.i - 1].start;
  T.acc = target; T.startMs = Date.now(); tick();
}
$("btnMain").onclick = () => {
  const v = view();
  if (!T.started || (v && v.done)) start();
  else if (T.running) pause(); else { resume(); openPip(); }
};
$("btnStop").onclick = stop;
$("btnPrev").onclick = () => jump(-1);
$("btnNext").onclick = () => jump(1);

/* ---------- エディタ ---------- */
function esc(s) { return String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c])); }
function renderEditor() {
  const m = menu();
  $("tabs").innerHTML = data.menus.map((x) => `<button data-id="${x.id}" class="${x.id === m.id ? "on" : ""}">${esc(x.name || "無題")}</button>`).join("");
  $("mName").value = m.name; $("mPrep").value = m.prep; $("mRest").value = m.rest;
  $("items").innerHTML = m.items.length ? m.items.map((it, i) => `
    <div class="item" data-i="${i}">
      <div class="grid4">
        <div><label>表示名</label><input data-f="name" value="${esc(it.name)}"></div>
        <div><label>秒数</label><input data-f="sec" type="number" inputmode="numeric" min="1" value="${it.sec}"></div>
        <div><label>セット数</label><input data-f="sets" type="number" inputmode="numeric" min="1" value="${it.sets}"></div>
        <div><label>セット間休憩</label><input data-f="restSet" type="number" inputmode="numeric" min="0" value="${it.restSet}"></div>
      </div>
      <div class="itemhead">
        <button class="small" data-act="up">↑</button><button class="small" data-act="down">↓</button>
        <button class="small" data-act="copy">複製</button><button class="small danger" data-act="del">削除</button>
      </div>
    </div>`).join("") : '<p class="note">種目がありません。</p>';
  updateTotal();
}
function updateTotal() { $("total").textContent = fmt(Math.round(total2() / 1000)) || "0秒"; }
function total2() { const s = buildTimeline(menu()); return s.length ? s[s.length - 1].end : 0; }

$("tabs").onclick = (e) => { const b = e.target.closest("button"); if (!b) return; data.cur = b.dataset.id; save(); renderEditor(); if (T.started) stop(); };
$("addMenu").onclick = () => { const m = { id: uid(), name: "新しいメニュー", prep: 5, rest: 15, items: [{ name: "種目1", sec: 30, sets: 1, restSet: 0 }] }; data.menus.push(m); data.cur = m.id; save(); renderEditor(); };
$("dupMenu").onclick = () => { const c = JSON.parse(JSON.stringify(menu())); c.id = uid(); c.name += " のコピー"; data.menus.push(c); data.cur = c.id; save(); renderEditor(); };
$("delMenu").onclick = () => {
  if (data.menus.length < 2) { alert("最後のメニューは削除できません"); return; }
  if (!confirm(`「${menu().name}」を削除しますか？`)) return;
  data.menus = data.menus.filter((x) => x.id !== data.cur); data.cur = data.menus[0].id; save(); renderEditor();
};
$("mName").oninput = (e) => { menu().name = e.target.value; save(); $("tabs").querySelector(".on").textContent = e.target.value || "無題"; };
$("mPrep").oninput = (e) => { menu().prep = num(e.target.value); save(); updateTotal(); };
$("mRest").oninput = (e) => { menu().rest = num(e.target.value); save(); updateTotal(); };
$("addItem").onclick = () => { menu().items.push({ name: "種目" + (menu().items.length + 1), sec: 30, sets: 1, restSet: 0 }); save(); renderEditor(); };
$("items").addEventListener("input", (e) => {
  const f = e.target.dataset.f, row = e.target.closest(".item"); if (!f || !row) return;
  const it = menu().items[+row.dataset.i];
  it[f] = f === "name" ? e.target.value : num(e.target.value, f === "sets" || f === "sec" ? 1 : 0);
  save(); updateTotal();
});
$("items").addEventListener("click", (e) => {
  const a = e.target.dataset.act, row = e.target.closest(".item"); if (!a || !row) return;
  const arr = menu().items, i = +row.dataset.i;
  if (a === "del") arr.splice(i, 1);
  else if (a === "copy") arr.splice(i + 1, 0, { ...arr[i] });
  else if (a === "up" && i > 0) [arr[i - 1], arr[i]] = [arr[i], arr[i - 1]];
  else if (a === "down" && i < arr.length - 1) [arr[i + 1], arr[i]] = [arr[i], arr[i + 1]];
  save(); renderEditor();
});

renderEditor();
drawPip(null);
if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
