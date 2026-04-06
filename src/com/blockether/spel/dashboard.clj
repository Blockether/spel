(ns com.blockether.spel.dashboard
  "Embedded HTTP server for the spel Observability Dashboard.

   Serves a single-page web UI that shows live browser state: viewport
   screenshot, action log, console messages, and uncaught errors.

   Uses only JDK built-in com.sun.net.httpserver — no external deps,
   GraalVM native-image safe.

   Usage:
     (start-dashboard! 4848 state-fn)  ;; starts HTTP server
     (stop-dashboard!)                  ;; stops it
     (dashboard-running?)              ;; status check"
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [com.blockether.spel.page :as page])
  (:import
   [com.microsoft.playwright Page]
   [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
   [java.net InetSocketAddress]
   [java.nio.charset StandardCharsets]))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private !server (atom nil))
(defonce ^:private !state-fn (atom nil))

;; =============================================================================
;; HTML — Single-Page Dashboard
;; =============================================================================

(def ^:private dashboard-html
  "<!DOCTYPE html>
<html lang=\"en\">
<head>
<meta charset=\"utf-8\">
<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">
<title>spel — Observability Dashboard</title>
<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">
<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>
<link href=\"https://fonts.googleapis.com/css2?family=Manrope:wght@400;600;800&family=Atkinson+Hyperlegible:wght@400;700&family=IBM+Plex+Mono:wght@400;500&display=swap\" rel=\"stylesheet\">
<style>
:root {
  --mc-bg: #f6f1e8;
  --mc-bg-secondary: rgba(255, 251, 245, 0.88);
  --mc-bg-elevated: rgba(255, 255, 255, 0.94);
  --mc-border: rgba(125, 99, 68, 0.18);
  --mc-border-strong: rgba(125, 99, 68, 0.34);
  --mc-text: #1f2933;
  --mc-text-secondary: #55606e;
  --mc-accent: #b2652a;
  --mc-accent-green: #1f8a5c;
  --mc-accent-red: #c44536;
  --mc-accent-yellow: #b7791f;
  --mc-shadow-soft: 0 10px 24px rgba(43, 33, 22, 0.05);
  --mc-radius-md: 18px;
  --mc-radius-lg: 24px;
}

*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

body {
  font-family: 'Atkinson Hyperlegible', system-ui, sans-serif;
  background: var(--mc-bg);
  background-image: radial-gradient(ellipse at 20% 0%, rgba(178, 101, 42, 0.06) 0%, transparent 60%),
                    radial-gradient(ellipse at 80% 100%, rgba(31, 138, 92, 0.04) 0%, transparent 50%);
  color: var(--mc-text);
  min-height: 100vh;
  overflow: hidden;
}

/* ---- Header ---- */
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 28px;
  border-bottom: 1px solid var(--mc-border);
  background: var(--mc-bg-secondary);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}
.header-left { display: flex; align-items: center; gap: 14px; }
.logo {
  font-family: 'Manrope', sans-serif;
  font-weight: 800;
  font-size: 22px;
  color: var(--mc-accent);
  letter-spacing: -0.5px;
}
.logo-sub {
  font-family: 'Atkinson Hyperlegible', sans-serif;
  font-size: 13px;
  color: var(--mc-text-secondary);
  font-weight: 400;
}
.header-right { display: flex; align-items: center; gap: 16px; }
.session-badge {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 12px;
  color: var(--mc-text-secondary);
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  padding: 4px 10px;
}
.status-dot {
  width: 10px; height: 10px;
  border-radius: 50%;
  background: var(--mc-accent-red);
  transition: background 0.3s;
}
.status-dot.connected {
  background: var(--mc-accent-green);
  box-shadow: 0 0 0 0 rgba(31, 138, 92, 0.4);
  animation: pulse-green 2s infinite;
}
@keyframes pulse-green {
  0% { box-shadow: 0 0 0 0 rgba(31, 138, 92, 0.4); }
  70% { box-shadow: 0 0 0 8px rgba(31, 138, 92, 0); }
  100% { box-shadow: 0 0 0 0 rgba(31, 138, 92, 0); }
}

/* ---- Layout ---- */
.main {
  display: flex;
  height: calc(100vh - 57px);
  overflow: hidden;
}
.panel-left {
  flex: 0 0 60%;
  display: flex;
  flex-direction: column;
  padding: 20px;
  overflow: hidden;
}
.panel-right {
  flex: 0 0 40%;
  display: flex;
  flex-direction: column;
  padding: 20px 20px 20px 0;
  overflow: hidden;
}

/* ---- Glassmorphism Card ---- */
.card {
  background: var(--mc-bg-elevated);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid var(--mc-border);
  border-radius: var(--mc-radius-lg);
  box-shadow: var(--mc-shadow-soft);
  overflow: hidden;
}

/* ---- Viewport Panel ---- */
.viewport-card {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.viewport-frame {
  flex: 1;
  position: relative;
  background: #e8e2d8;
  border-radius: var(--mc-radius-md);
  overflow: hidden;
  margin: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.viewport-frame img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  border-radius: 8px;
}
.viewport-placeholder {
  color: var(--mc-text-secondary);
  font-size: 14px;
  text-align: center;
  padding: 40px;
}
.viewport-placeholder .icon { font-size: 32px; margin-bottom: 8px; display: block; }
.viewport-overlay {
  position: absolute;
  bottom: 0; left: 0; right: 0;
  background: linear-gradient(transparent, rgba(0,0,0,0.72));
  padding: 24px 16px 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  pointer-events: none;
}
.overlay-url {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: rgba(255,255,255,0.92);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-shadow: 0 1px 3px rgba(0,0,0,0.5);
}
.overlay-title {
  font-size: 13px;
  font-weight: 600;
  color: rgba(255,255,255,0.8);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-shadow: 0 1px 3px rgba(0,0,0,0.5);
}
/* Network request entry */
.net-entry {
  padding: 6px 12px;
  border-bottom: 1px solid var(--mc-border);
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.net-entry:last-child { border-bottom: none; }
.net-status {
  font-family: 'IBM Plex Mono', monospace;
  font-weight: 700;
  font-size: 11px;
  min-width: 32px;
  text-align: center;
}
.net-status.s2 { color: var(--mc-accent-green); }
.net-status.s3 { color: var(--mc-accent-yellow); }
.net-status.s4, .net-status.s5 { color: var(--mc-accent-red); }
.net-method {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  color: var(--mc-text-secondary);
  min-width: 36px;
}
.net-url {
  flex: 1;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: var(--mc-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* ---- Sessions panel ---- */
.sessions-info { padding: 12px; }
.session-current {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 13px;
  font-weight: 700;
  color: var(--mc-accent);
  margin-bottom: 8px;
}
.session-engine {
  font-size: 12px;
  color: var(--mc-text-secondary);
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.engine-badge {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  padding: 2px 8px;
  border-radius: 6px;
  background: rgba(31, 138, 92, 0.1);
  color: var(--mc-accent-green);
}
.session-list { display: flex; flex-direction: column; gap: 6px; }
.session-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 10px;
  background: var(--mc-bg-secondary);
  border: 1px solid var(--mc-border);
  font-family: 'IBM Plex Mono', monospace;
  font-size: 12px;
  color: var(--mc-text);
}
.session-item.active {
  border-color: var(--mc-accent);
  background: rgba(178, 101, 42, 0.08);
}
.session-dot {
  width: 8px; height: 8px;
  border-radius: 50%;
  background: var(--mc-accent-green);
  flex-shrink: 0;
}

/* ---- Tabs ---- */
.tabs-card {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.tab-bar {
  display: flex;
  border-bottom: 1px solid var(--mc-border);
  padding: 0 16px;
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
}
.tab-bar::-webkit-scrollbar { display: none; }
.tab-btn {
  font-family: 'Manrope', sans-serif;
  font-weight: 600;
  font-size: 13px;
  color: var(--mc-text-secondary);
  background: none;
  border: none;
  padding: 12px 16px;
  cursor: pointer;
  position: relative;
  transition: color 0.2s;
}
.tab-btn:hover { color: var(--mc-text); }
.tab-btn.active {
  color: var(--mc-accent);
}
.tab-btn.active::after {
  content: '';
  position: absolute;
  bottom: -1px;
  left: 16px;
  right: 16px;
  height: 2px;
  background: var(--mc-accent);
  border-radius: 2px 2px 0 0;
}
.tab-badge {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 10px;
  font-weight: 500;
  background: var(--mc-accent-red);
  color: #fff;
  border-radius: 8px;
  padding: 1px 6px;
  margin-left: 6px;
  vertical-align: middle;
}
.tab-badge:empty { display: none; }

.tab-content {
  flex: 1;
  overflow: hidden;
  position: relative;
}
.tab-panel {
  position: absolute;
  inset: 0;
  overflow-y: auto;
  padding: 12px 16px;
  display: none;
}
.tab-panel.active { display: block; }

/* ---- Activity Feed ---- */
.activity-entry {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid var(--mc-border);
}
.activity-entry:last-child { border-bottom: none; }
.activity-entry { cursor: pointer; transition: background 0.15s ease; }
.activity-entry:hover { background: rgba(125, 99, 68, 0.06); }
.activity-json {
  display: none;
  margin-top: 6px;
  padding: 8px 10px;
  background: rgba(0,0,0,0.04);
  border-radius: 6px;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: var(--mc-text-secondary);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 240px;
  overflow-y: auto;
}
.activity-entry.expanded .activity-json { display: block; }
.activity-entry.expanded { flex-wrap: wrap; }
.activity-entry.expanded .activity-json { width: 100%; }
.action-pill {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  padding: 3px 8px;
  border-radius: 6px;
  white-space: nowrap;
  flex-shrink: 0;
}
.action-pill.success {
  background: rgba(31, 138, 92, 0.1);
  color: var(--mc-accent-green);
}
.action-pill.error {
  background: rgba(196, 69, 54, 0.1);
  color: var(--mc-accent-red);
}
.activity-detail {
  font-size: 12px;
  color: var(--mc-text-secondary);
  line-height: 1.4;
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.activity-time {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: var(--mc-text-secondary);
  opacity: 0.6;
  white-space: nowrap;
  flex-shrink: 0;
}
.activity-duration {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 10px;
  color: var(--mc-text-secondary);
  opacity: 0.5;
}

/* ---- Console ---- */
.console-entry {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 12px;
  padding: 5px 0;
  border-bottom: 1px solid var(--mc-border);
  line-height: 1.5;
  word-break: break-all;
}
.console-entry:last-child { border-bottom: none; }
.console-entry.log { color: var(--mc-text-secondary); }
.console-entry.warn { color: var(--mc-accent-yellow); }
.console-entry.error { color: var(--mc-accent-red); }
.console-type {
  font-size: 10px;
  text-transform: uppercase;
  opacity: 0.6;
  margin-right: 6px;
}

/* ---- Error Cards ---- */
.error-card {
  background: rgba(196, 69, 54, 0.06);
  border: 1px solid rgba(196, 69, 54, 0.2);
  border-radius: 12px;
  padding: 12px 14px;
  margin-bottom: 8px;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 12px;
  color: var(--mc-accent-red);
  line-height: 1.5;
  word-break: break-all;
}

/* ---- Empty state ---- */
.empty-state {
  text-align: center;
  padding: 40px 20px;
  color: var(--mc-text-secondary);
  font-size: 13px;
}

/* ---- Responsive ---- */
@media (max-width: 900px) {
  .main { flex-direction: column; }
  .panel-left, .panel-right {
    flex: none;
    width: 100%;
    padding: 12px;
    height: 50vh;
  }
  .panel-right { padding-left: 12px; }
}
</style>
</head>
<body>
<div class=\"header\">
  <div class=\"header-left\">
    <svg class=\"logo-img\" height=\"36\" viewBox=\"0 0 400 350\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\"><defs><radialGradient id=\"sp\" cx=\"50%\" cy=\"50%\" r=\"50%\"><stop offset=\"0%\" stop-color=\"#2EAD33\" stop-opacity=\"0.12\"/><stop offset=\"100%\" stop-color=\"#2EAD33\" stop-opacity=\"0\"/></radialGradient><linearGradient id=\"cL\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"0\"><stop offset=\"0%\" stop-color=\"#C04B41\"/><stop offset=\"100%\" stop-color=\"#E2574C\"/></linearGradient><linearGradient id=\"cR\" x1=\"1\" y1=\"0\" x2=\"0\" y2=\"0\"><stop offset=\"0%\" stop-color=\"#C04B41\"/><stop offset=\"100%\" stop-color=\"#E2574C\"/></linearGradient></defs><g transform=\"translate(162,28)\"><path d=\"M4,0C0,0-2,4,0,8L6,30C8,36,14,40,20,40C26,40,30,36,30,30L30,8C30,3,26,0,22,0Z\" fill=\"#E2574C\"/><ellipse cx=\"10\" cy=\"16\" rx=\"3.5\" ry=\"4.5\" fill=\"white\" opacity=\"0.9\"/><ellipse cx=\"22\" cy=\"16\" rx=\"3.5\" ry=\"4.5\" fill=\"white\" opacity=\"0.9\"/><path d=\"M9,28Q16,35,23,28\" stroke=\"white\" stroke-width=\"2\" fill=\"none\" stroke-linecap=\"round\" opacity=\"0.9\"/></g><g transform=\"translate(206,28)\"><path d=\"M8,0C4,0,0,3,0,8L0,30C0,36,4,40,10,40C16,40,22,36,24,30L30,8C32,4,30,0,26,0Z\" fill=\"#2EAD33\"/><ellipse cx=\"10\" cy=\"16\" rx=\"3.5\" ry=\"4.5\" fill=\"white\" opacity=\"0.9\"/><ellipse cx=\"22\" cy=\"16\" rx=\"3.5\" ry=\"4.5\" fill=\"white\" opacity=\"0.9\"/><path d=\"M9,32Q16,25,23,32\" stroke=\"white\" stroke-width=\"2\" fill=\"none\" stroke-linecap=\"round\" opacity=\"0.9\"/></g><path d=\"M148,60C68,102,68,184,148,226\" stroke=\"url(#cL)\" stroke-width=\"18\" fill=\"none\" stroke-linecap=\"round\"/><path d=\"M252,60C332,102,332,184,252,226\" stroke=\"url(#cR)\" stroke-width=\"18\" fill=\"none\" stroke-linecap=\"round\"/><circle cx=\"205\" cy=\"143\" r=\"50\" fill=\"url(#sp)\"/><path d=\"M183,102L237,143L183,184Z\" fill=\"#2EAD33\"/><text x=\"200\" y=\"300\" text-anchor=\"middle\" font-family=\"serif\" font-size=\"58\" font-weight=\"bold\" fill=\"#2D4552\" letter-spacing=\"5\">spel</text></svg>
    <span class=\"logo-sub\">Observability Dashboard</span>
  </div>
  <div class=\"header-right\">
    <span class=\"session-badge\" id=\"session-badge\">session: ---</span>
    <div class=\"status-dot\" id=\"status-dot\" title=\"Disconnected\"></div>
  </div>
</div>
<div class=\"main\">
  <div class=\"panel-left\">
    <div class=\"card viewport-card\">
      <div class=\"viewport-frame\" id=\"viewport-frame\">
        <div class=\"viewport-placeholder\" id=\"viewport-placeholder\">
          <span class=\"icon\">&#x1f310;</span>
          No browser connected
        </div>
        <img id=\"viewport-img\" style=\"display:none\" alt=\"Browser viewport\">
        <div class=\"viewport-overlay\" id=\"viewport-overlay\">
          <div class=\"overlay-url\" id=\"page-url\">—</div>
          <div class=\"overlay-title\" id=\"page-title\">—</div>
        </div>
      </div>
    </div>
  </div>
  <div class=\"panel-right\">
    <div class=\"card tabs-card\">
      <div class=\"tab-bar\">
        <button class=\"tab-btn active\" data-tab=\"activity\">Activity</button>
        <button class=\"tab-btn\" data-tab=\"network\">Network<span class=\"tab-badge\" id=\"network-badge\"></span></button>
        <button class=\"tab-btn\" data-tab=\"console\">Console</button>
        <button class=\"tab-btn\" data-tab=\"errors\">Errors<span class=\"tab-badge\" id=\"error-badge\"></span></button>
        <button class=\"tab-btn\" data-tab=\"sessions\">Sessions</button>
      </div>
      <div class=\"tab-content\">
        <div class=\"tab-panel active\" id=\"tab-activity\">
          <div class=\"empty-state\" id=\"activity-empty\">No activity yet</div>
        </div>
        <div class=\"tab-panel\" id=\"tab-network\">
          <div class=\"empty-state\" id=\"network-empty\">No network requests tracked</div>
        </div>
        <div class=\"tab-panel\" id=\"tab-console\">
          <div class=\"empty-state\" id=\"console-empty\">No console messages</div>
        </div>
        <div class=\"tab-panel\" id=\"tab-errors\">
          <div class=\"empty-state\" id=\"errors-empty\">No errors</div>
        </div>
        <div class=\"tab-panel\" id=\"tab-sessions\">
          <div class=\"sessions-info\" id=\"sessions-info\">
            <div class=\"session-current\" id=\"session-current\"></div>
            <div class=\"session-engine\" id=\"session-engine\"></div>
            <div class=\"session-list\" id=\"session-list\"></div>
          </div>
        </div>
      </div>
    </div>
  </div>
</div>
<script>
(function() {
  'use strict';

  // --- DOM refs ---
  var img = document.getElementById('viewport-img');
  var placeholder = document.getElementById('viewport-placeholder');
  var pageUrl = document.getElementById('page-url');
  var pageTitle = document.getElementById('page-title');
  var sessionBadge = document.getElementById('session-badge');
  var statusDot = document.getElementById('status-dot');
  var errorBadge = document.getElementById('error-badge');
  var networkBadge = document.getElementById('network-badge');
  var tabActivity = document.getElementById('tab-activity');
  var tabNetwork = document.getElementById('tab-network');
  var tabConsole = document.getElementById('tab-console');
  var tabErrors = document.getElementById('tab-errors');
  var activityEmpty = document.getElementById('activity-empty');
  var networkEmpty = document.getElementById('network-empty');
  var consoleEmpty = document.getElementById('console-empty');
  var errorsEmpty = document.getElementById('errors-empty');
  var tabSessions = document.getElementById('tab-sessions');
  var sessionCurrent = document.getElementById('session-current');
  var sessionEngine = document.getElementById('session-engine');
  var sessionList = document.getElementById('session-list');

  // --- State ---
  var connected = false;
  var activityCount = 0;
  var networkCount = 0;
  var consoleCount = 0;
  var errorsCount = 0;

  // --- Tabs ---
  document.querySelectorAll('.tab-btn').forEach(function(btn) {
    btn.addEventListener('click', function() {
      document.querySelectorAll('.tab-btn').forEach(function(b) { b.classList.remove('active'); });
      document.querySelectorAll('.tab-panel').forEach(function(p) { p.classList.remove('active'); });
      btn.classList.add('active');
      document.getElementById('tab-' + btn.dataset.tab).classList.add('active');
    });
  });

  // --- Screenshot polling ---
  function pollScreenshot() {
    var newImg = new Image();
    newImg.onload = function() {
      img.src = newImg.src;
      img.style.display = 'block';
      placeholder.style.display = 'none';
    };
    newImg.onerror = function() {
      img.style.display = 'none';
      placeholder.style.display = 'block';
    };
    newImg.src = '/api/screenshot?' + Date.now();
  }

  // --- Status polling ---
  function pollStatus() {
    fetch('/api/status').then(function(r) {
      if (!r.ok) throw new Error(r.status);
      return r.json();
    }).then(function(d) {
      connected = true;
      statusDot.className = 'status-dot connected';
      statusDot.title = 'Connected';
      pageUrl.textContent = d.url || '—';
      pageTitle.textContent = d.title || '—';
      if (d.session) sessionBadge.textContent = 'session: ' + d.session;
    }).catch(function() {
      connected = false;
      statusDot.className = 'status-dot';
      statusDot.title = 'Disconnected';
    });
  }

  // --- Activity polling ---
  function pollActivity() {
    fetch('/api/activity').then(function(r) {
      if (!r.ok) throw new Error(r.status);
      return r.json();
    }).then(function(entries) {
      if (!entries || entries.length === 0) return;
      if (entries.length === activityCount) return;
      activityCount = entries.length;
      activityEmpty.style.display = 'none';
      var html = '';
      var shown = entries.slice(-200);
      // Deduplicate consecutive navigate entries with identical URL
      var deduped = [];
      for (var j = 0; j < shown.length; j++) {
        var cur = shown[j];
        var prev = deduped.length > 0 ? deduped[deduped.length - 1] : null;
        if (prev && prev.action === cur.action && prev.url === cur.url && cur.action === 'navigate') continue;
        deduped.push(cur);
      }
      for (var i = 0; i < deduped.length; i++) {
        var e = deduped[i];
        var isErr = e.error || (e.status === 'error');
        var cls = isErr ? 'error' : 'success';
        var ts = e.timestamp ? new Date(e.timestamp).toLocaleTimeString() : '';
        var dur = e.duration_ms ? e.duration_ms + 'ms' : '';
        var detail = e.url || e.selector || e.text || '';
        if (!detail && e.key) detail = e.key;
        var jsonStr = '';
        try {
          // Filter null/undefined values and raw timestamp for cleaner display
          var clean = {};
          for (var k in e) {
            if (e[k] !== null && e[k] !== undefined && k !== 'timestamp' && k !== 'idx') clean[k] = e[k];
          }
          jsonStr = JSON.stringify(clean, null, 2);
        } catch(_) {}
        html += '<div class=\"activity-entry\" onclick=\"this.classList.toggle(&#39;expanded&#39;)\">' +
          '<span class=\"action-pill ' + cls + '\">' + esc(e.action || e.command || '?') + '</span>' +
          '<span class=\"activity-detail\" title=\"' + esc(detail) + '\">' + esc(detail) + '</span>' +
          '<span class=\"activity-time\">' + esc(ts) + (dur ? ' <span class=\"activity-duration\">' + esc(dur) + '</span>' : '') + '</span>' +
          '<div class=\"activity-json\">' + esc(jsonStr) + '</div>' +
          '</div>';
      }
      tabActivity.innerHTML = html;
      tabActivity.scrollTop = tabActivity.scrollHeight;
    }).catch(function() {});
  }

  // --- Console polling ---
  function pollConsole() {
    fetch('/api/console').then(function(r) {
      if (!r.ok) throw new Error(r.status);
      return r.json();
    }).then(function(entries) {
      if (!entries || entries.length === 0) return;
      if (entries.length === consoleCount) return;
      consoleCount = entries.length;
      consoleEmpty.style.display = 'none';
      var html = '';
      var shown = entries.slice(-300);
      for (var i = 0; i < shown.length; i++) {
        var e = shown[i];
        var t = (e.type || 'log').toLowerCase();
        var cls = (t === 'error') ? 'error' : (t === 'warning' || t === 'warn') ? 'warn' : 'log';
        html += '<div class=\"console-entry ' + cls + '\">' +
          '<span class=\"console-type\">' + esc(t) + '</span>' +
          esc(e.text || '') +
          '</div>';
      }
      tabConsole.innerHTML = html;
      tabConsole.scrollTop = tabConsole.scrollHeight;
    }).catch(function() {});
  }

  // --- Errors polling ---
  function pollErrors() {
    fetch('/api/errors').then(function(r) {
      if (!r.ok) throw new Error(r.status);
      return r.json();
    }).then(function(entries) {
      if (!entries || entries.length === 0) {
        errorBadge.textContent = '';
        return;
      }
      if (entries.length === errorsCount) return;
      errorsCount = entries.length;
      errorBadge.textContent = entries.length > 99 ? '99+' : String(entries.length);
      errorsEmpty.style.display = 'none';
      var html = '';
      var shown = entries.slice(-100);
      for (var i = 0; i < shown.length; i++) {
        html += '<div class=\"error-card\">' + esc(shown[i].message || String(shown[i])) + '</div>';
      }
      tabErrors.innerHTML = html;
      tabErrors.scrollTop = tabErrors.scrollHeight;
    }).catch(function() {});
  }

  function esc(s) {
    if (!s) return '';
    var d = document.createElement('div');
    d.appendChild(document.createTextNode(s));
    return d.innerHTML;
  }

  // --- Network polling ---
  function pollNetwork() {
    fetch('/api/network').then(function(r) {
      if (!r.ok) throw new Error(r.status);
      return r.json();
    }).then(function(entries) {
      if (!entries || entries.length === 0) return;
      if (entries.length === networkCount) return;
      networkCount = entries.length;
      networkEmpty.style.display = 'none';
      networkBadge.textContent = entries.length;
      var html = '';
      var shown = entries.slice(-300);
      for (var i = shown.length - 1; i >= 0; i--) {
        var e = shown[i];
        var st = e.status || 0;
        var sCls = st >= 500 ? 's5' : st >= 400 ? 's4' : st >= 300 ? 's3' : 's2';
        html += '<div class=\"net-entry\">' +
          '<span class=\"net-status ' + sCls + '\">' + st + '</span>' +
          '<span class=\"net-method\">' + esc(e.method || 'GET') + '</span>' +
          '<span class=\"net-url\" title=\"' + esc(e.url) + '\">' + esc(e.url) + '</span>' +
          '</div>';
      }
      tabNetwork.innerHTML = html;
    }).catch(function() {});
  }

  // --- Sessions polling ---
  function pollSessions() {
    fetch('/api/sessions').then(function(r) {
      if (!r.ok) throw new Error(r.status);
      return r.json();
    }).then(function(data) {
      var cur = data.current || 'default';
      var eng = data.engine || 'chrome';
      sessionCurrent.textContent = 'Current session: ' + cur;
      sessionEngine.innerHTML = 'Engine: <span class=\"engine-badge\">' + esc(eng) + '</span>';
      var sessions = data.sessions || [];
      if (sessions.length === 0) {
        sessionList.innerHTML = '<div style=\"color:var(--mc-text-secondary);font-size:12px\">No active sessions found</div>';
        return;
      }
      var html = '';
      for (var i = 0; i < sessions.length; i++) {
        var s = sessions[i];
        var isActive = s.session === cur;
        html += '<div class=\"session-item' + (isActive ? ' active' : '') + '\">' +
          '<span class=\"session-dot\"></span>' +
          '<span>' + esc(s.session) + '</span>' +
          (isActive ? ' <span style=\"font-size:10px;color:var(--mc-accent);font-weight:700\">(current)</span>' : '') +
          '</div>';
      }
      sessionList.innerHTML = html;
    }).catch(function() {});
  }

  // --- Start polling ---
  setInterval(pollScreenshot, 500);
  setInterval(pollStatus, 1000);
  setInterval(pollActivity, 1000);
  setInterval(pollNetwork, 1500);
  setInterval(pollConsole, 2000);
  setInterval(pollErrors, 2000);
  setInterval(pollSessions, 5000);
  pollScreenshot();
  pollStatus();
  pollActivity();
  pollNetwork();
  pollConsole();
  pollErrors();
  pollSessions();
})();
</script>
</body>
</html>")

;; =============================================================================
;; HTTP Helpers
;; =============================================================================

(defn- send-response
  "Writes an HTTP response and closes the exchange."
  [^HttpExchange exchange ^long status ^String content-type ^bytes body]
  (let [headers (.getResponseHeaders exchange)]
    (.set headers "Content-Type" content-type)
    (.set headers "Access-Control-Allow-Origin" "*")
    (.set headers "Cache-Control" "no-cache, no-store"))
  (.sendResponseHeaders exchange status (alength body))
  (let [os (.getResponseBody exchange)]
    (.write os body)
    (.close os)))

(defn- send-json
  "Sends a JSON response."
  [^HttpExchange exchange ^long status data]
  (let [body (.getBytes ^String (json/write-json-str data) StandardCharsets/UTF_8)]
    (send-response exchange status "application/json; charset=utf-8" body)))

(defn- send-html
  "Sends an HTML response."
  [^HttpExchange exchange ^long status ^String html]
  (let [body (.getBytes html StandardCharsets/UTF_8)]
    (send-response exchange status "text/html; charset=utf-8" body)))

(defn- send-error
  "Sends a JSON error response."
  [^HttpExchange exchange ^long status ^String message]
  (send-json exchange status {:error message}))

;; =============================================================================
;; Route Handlers
;; =============================================================================

(defn- handle-index
  "Serves the dashboard HTML page."
  [^HttpExchange exchange]
  (send-html exchange 200 dashboard-html))

(defn- handle-screenshot
  "Captures a JPEG screenshot of the current page."
  [^HttpExchange exchange state-fn]
  (try
    (let [{:keys [page-fn]} (state-fn)]
      (if-let [^Page pg (try (page-fn) (catch Exception _ nil))]
        (let [^bytes img-bytes (page/screenshot pg {:type :jpeg :quality 60})]
          (if img-bytes
            (send-response exchange 200 "image/jpeg" img-bytes)
            (send-error exchange 503 "Screenshot failed")))
        (send-error exchange 503 "No browser page available")))
    (catch Exception e
      (send-error exchange 500 (str "Screenshot error: " (.getMessage e))))))

(defn- handle-status
  "Returns JSON with current page status."
  [^HttpExchange exchange state-fn]
  (try
    (let [{:keys [page-fn state]} (state-fn)
          pg (try (page-fn) (catch Exception _ nil))]
      (send-json exchange 200
        {:url          (when pg (try (page/url pg) (catch Exception _ nil)))
         :title        (when pg (try (page/title pg) (catch Exception _ nil)))
         :viewport     (when pg (try (page/viewport-size pg) (catch Exception _ nil)))
         :refs_count   (:counter state 0)
         :session      (:session state "unknown")
         :har_recording (boolean (:har-recording? state))
         :tracing      (boolean (:tracing? state))}))
    (catch Exception e
      (send-error exchange 500 (str "Status error: " (.getMessage e))))))

(defn- handle-activity
  "Returns JSON array of recent action-log entries."
  [^HttpExchange exchange state-fn]
  (try
    (let [{:keys [activity]} (state-fn)]
      (send-json exchange 200 (or activity [])))
    (catch Exception e
      (send-error exchange 500 (str "Activity error: " (.getMessage e))))))

(defn- handle-console
  "Returns JSON array of console messages."
  [^HttpExchange exchange state-fn]
  (try
    (let [{:keys [console]} (state-fn)]
      (send-json exchange 200 (or console [])))
    (catch Exception e
      (send-error exchange 500 (str "Console error: " (.getMessage e))))))

(defn- handle-errors
  "Returns JSON array of page errors."
  [^HttpExchange exchange state-fn]
  (try
    (let [{:keys [errors]} (state-fn)]
      (send-json exchange 200 (or errors [])))
    (catch Exception e
      (send-error exchange 500 (str "Errors error: " (.getMessage e))))))

(defn- handle-network
  "Returns JSON array of tracked network requests."
  [^HttpExchange exchange state-fn]
  (try
    (let [{:keys [network]} (state-fn)]
      (send-json exchange 200 (or network [])))
    (catch Exception e
      (send-error exchange 500 (str "Network error: " (.getMessage e))))))

(defn- handle-sessions
  "Scans tmpdir for active spel session socket files and returns a list of
   sessions with their status. Gives the dashboard a read-only view of all
   active daemon processes on this machine."
  [^HttpExchange exchange state-fn]
  (try
    (let [tmpdir     (System/getProperty "java.io.tmpdir")
          dir        (java.io.File. ^String tmpdir)
          ;; Scan for both socket files AND pid files (sockets may be
          ;; unix-domain and not show as regular files on some OS)
          spel-files (->> (.listFiles dir)
                       (filter (fn [^java.io.File f]
                                 (let [n (.getName f)]
                                   (and (or (.endsWith n ".sock")
                                          (.endsWith n ".pid"))
                                     (.startsWith n "spel-"))))))
          sessions   (->> spel-files
                       (map (fn [^java.io.File f]
                              (-> (.getName f)
                                (.replace "spel-" "")
                                (.replace ".sock" "")
                                (.replace ".pid" ""))))
                       distinct
                       sort
                       (mapv (fn [s] {:session s})))
          current    (get-in (state-fn) [:state :session])
          engine     (get-in (state-fn) [:state :launch-flags "engine"] "chrome")]
      (send-json exchange 200
        {:current  current
         :engine   engine
         :sessions sessions}))
    (catch Exception e
      (send-error exchange 500 (str "Sessions error: " (.getMessage e))))))

;; =============================================================================
;; Request Router
;; =============================================================================

(defn- make-handler
  "Creates the HttpHandler that routes requests to handlers."
  ^HttpHandler [state-fn]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (let [path (.getPath (.getRequestURI exchange))]
          (cond
            (= "/" path)
            (handle-index exchange)

            (= "/api/screenshot" path)
            (handle-screenshot exchange state-fn)

            (= "/api/status" path)
            (handle-status exchange state-fn)

            (= "/api/activity" path)
            (handle-activity exchange state-fn)

            (= "/api/console" path)
            (handle-console exchange state-fn)

            (= "/api/errors" path)
            (handle-errors exchange state-fn)

            (= "/api/network" path)
            (handle-network exchange state-fn)

            (= "/api/sessions" path)
            (handle-sessions exchange state-fn)

            (= "/logo.svg" path)
            (let [svg (try (slurp (io/resource "spel-logo.svg"))
                           (catch Exception _ "<svg/>"))]
              (doto (.getResponseHeaders exchange)
                (.set "Content-Type" "image/svg+xml")
                (.set "Cache-Control" "public, max-age=86400"))
              (.sendResponseHeaders exchange 200 (count (.getBytes ^String svg "UTF-8")))
              (with-open [os (.getResponseBody exchange)]
                (.write os (.getBytes ^String svg "UTF-8"))))

            :else
            (send-error exchange 404 "Not found")))
        (catch Exception e
          (try
            (send-error exchange 500 (str "Internal error: " (.getMessage e)))
            (catch Exception _ nil)))))))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn start-dashboard!
  "Starts the dashboard HTTP server on the given port.

   Params:
   `port`     - Long. TCP port to listen on.
   `state-fn` - Function of zero args returning a map with keys:
                 :page-fn  — fn of zero args returning the current Page (or nil)
                 :state    — daemon state map (derefed atom)
                 :console  — vector of console message maps
                 :errors   — vector of error maps
                 :activity — vector of action-log entries

   Returns:
   The HttpServer instance."
  [^long port state-fn]
  (when @!server
    (throw (ex-info "Dashboard already running" {:port port})))
  (let [^HttpServer server (HttpServer/create (InetSocketAddress. (int port)) 0)]
    (.createContext server "/" (make-handler state-fn))
    (.setExecutor server nil)
    (.start server)
    (reset! !state-fn state-fn)
    (reset! !server server)
    server))

(defn stop-dashboard!
  "Stops the dashboard HTTP server."
  []
  (when-let [^HttpServer server @!server]
    (.stop server 0)
    (reset! !server nil)
    (reset! !state-fn nil)))

(defn dashboard-running?
  "Returns true if the dashboard HTTP server is running."
  []
  (some? @!server))

(defn dashboard-port
  "Returns the port the dashboard is listening on, or nil."
  []
  (when-let [^HttpServer server @!server]
    (.getPort (.getAddress server))))
