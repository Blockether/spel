/*
 * spel-sw.js — spel bridge service worker (same-origin network interception).
 *
 * The in-page engine (spel.js) can only see traffic that goes through
 * window.fetch / XMLHttpRequest. Passive subresources the browser loader pulls
 * on its own — <img>, <script>, <link rel=stylesheet>, fonts, media — never
 * touch those wrappers, so their real HTTP status + headers are invisible
 * (PerformanceObserver sees only timing/size, no status/headers/body).
 *
 * A service worker sits in front of the network for its whole scope, so it DOES
 * see those requests. This worker intercepts passive subresources, performs the
 * real fetch, and forwards a network entry to every controlled client via
 * postMessage. spel.js merges them into its capture, tagged `via:"sw"`.
 *
 * HARD REQUIREMENT: a service worker script is SAME-ORIGIN only. This file must
 * be served from the PAGE'S OWN ORIGIN, at a path whose directory covers the
 * wanted scope (root "/" for the whole origin). The bridge serves it at
 * /spel-sw.js (so it works on the bridge harness + any page on the bridge
 * origin); for a page you control, `spel bridge --eject-sw` writes it out so you
 * can drop it next to your HTML. It also needs a secure context (https or
 * localhost) — the same constraint as any service worker.
 *
 * Deliberately NOT intercepted: fetch()/XHR (destination "empty" — already
 * covered by the page wrappers, with request bodies), navigations (destination
 * "document"/"iframe"). This keeps the SW purely ADDITIVE — no double counting.
 */
"use strict";

var SPEL_SW = { version: "0.9.0", bodyCap: 65536, capture: true };

self.addEventListener("install", function () {
  self.skipWaiting();
});

self.addEventListener("activate", function (event) {
  event.waitUntil(self.clients.claim());
});

self.addEventListener("message", function (event) {
  var d = event.data || {};
  if (d.__spel_sw_cmd === "configure") {
    if (d.capture !== undefined) SPEL_SW.capture = !!d.capture;
    if (d.bodyCap) SPEL_SW.bodyCap = d.bodyCap;
    if (event.source && event.source.postMessage) {
      try {
        event.source.postMessage({ __spel_sw: true, type: "config", config: SPEL_SW });
      } catch (e) { /* ignore */ }
    }
  }
});

function headersToObj(h) {
  var out = {};
  try {
    if (h && typeof h.forEach === "function") {
      h.forEach(function (v, k) { out[String(k).toLowerCase()] = v; });
    }
  } catch (e) { /* ignore */ }
  return out;
}

function cap(s) {
  if (s == null) return s;
  s = String(s);
  return s.length > SPEL_SW.bodyCap ? s.slice(0, SPEL_SW.bodyCap) + "\u2026[truncated]" : s;
}

function isTexty(ct) {
  ct = String(ct || "").toLowerCase();
  return ct.indexOf("text/") === 0 || ct.indexOf("json") >= 0 ||
    ct.indexOf("javascript") >= 0 || ct.indexOf("ecmascript") >= 0 ||
    ct.indexOf("xml") >= 0 || ct.indexOf("+svg") >= 0 || ct.indexOf("css") >= 0;
}

// Passive subresource destinations — the gap the page wrappers cannot see.
// "empty" (fetch/XHR) and "document"/"iframe" (navigations) are excluded on
// purpose so this worker never duplicates what spel.js already records.
var PASSIVE = {
  image: 1, script: 1, style: 1, font: 1, audio: 1, video: 1,
  track: 1, manifest: 1, embed: 1, object: 1, worker: 1,
  sharedworker: 1, paintworklet: 1, audioworklet: 1
};

function report(entry) {
  self.clients.matchAll({ includeUncontrolled: true }).then(function (cs) {
    for (var i = 0; i < cs.length; i++) {
      try {
        cs[i].postMessage({ __spel_sw: true, type: "net", entry: entry });
      } catch (e) { /* ignore */ }
    }
  });
}

self.addEventListener("fetch", function (event) {
  var req = event.request;
  var dest = req.destination || "";
  if (!SPEL_SW.capture || !PASSIVE[dest]) return; // browser handles it normally
  var start = Date.now();
  event.respondWith(
    fetch(req).then(function (resp) {
      var entry = {
        url: req.url,
        method: req.method,
        status: resp.status,
        statusText: resp.statusText,
        resourceType: dest,
        ok: resp.ok,
        via: "sw",
        requestHeaders: headersToObj(req.headers),
        requestBody: null,
        responseHeaders: headersToObj(resp.headers),
        responseBody: null,
        fromCache: false,
        startTime: start,
        duration: Date.now() - start,
        size: null
      };
      var ct = resp.headers && resp.headers.get ? resp.headers.get("content-type") : "";
      if (resp.type !== "opaque" && isTexty(ct)) {
        try {
          resp.clone().text().then(function (t) {
            entry.responseBody = cap(t);
            entry.duration = Date.now() - start;
            report(entry);
          }, function () { report(entry); });
        } catch (e) { report(entry); }
      } else {
        report(entry);
      }
      return resp;
    }, function (err) {
      report({
        url: req.url,
        method: req.method,
        status: 0,
        statusText: "network error",
        resourceType: dest,
        ok: false,
        via: "sw",
        error: String((err && err.message) || err),
        requestHeaders: headersToObj(req.headers),
        requestBody: null,
        responseHeaders: {},
        responseBody: null,
        fromCache: false,
        startTime: start,
        duration: Date.now() - start,
        size: null
      });
      throw err;
    })
  );
});
