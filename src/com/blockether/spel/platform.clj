(ns com.blockether.spel.platform
  "Platform detection and CDP discovery utilities. Pure functions with
   no dependency on daemon state, browser sessions, or SCI context.

   Extracted from daemon.clj to break the circular dependency between
   sci_env.clj (which needs WSL + CDP functions for the spel namespace)
   and daemon.clj (which requires sci_env.clj for evaluation).

   Both daemon.clj and sci_env.clj can safely require this namespace."
  (:require
   [clojure.string :as str])
  (:import
   [java.io File]
   [java.net HttpURLConnection URL]
   [java.nio.file Files]))

(def ^:const common-cdp-ports
  "Default TCP ports scanned when auto-discovering a running CDP browser.
   Single source of truth — referenced from daemon.clj, sci_env.clj, and
   the discover-cdp default below.

   - 9222: Chrome / Edge / Chromium default --remote-debugging-port
   - 9223: common Windows proxy fallback when 9222 is already bound
   - 9229: Node.js inspector / Electron devtools"
  [9222 9223 9229])

(defn wsl?
  "Returns true when running inside Windows Subsystem for Linux.
   Checks /proc/version for \"microsoft\"/\"WSL\" markers and the WSL_DISTRO_NAME
   env var set by wsl.exe — either is sufficient."
  []
  (boolean
    (or (some? (System/getenv "WSL_DISTRO_NAME"))
      (some? (System/getenv "WSL_INTEROP"))
      (try
        (let [f (java.io.File. "/proc/version")]
          (and (.isFile f)
            (let [content (str/lower-case (slurp f))]
              (or (str/includes? content "microsoft")
                (str/includes? content "wsl")))))
        (catch Exception _ false)))))

(defn parse-proc-net-route-gateway
  "Pure: given the text of `/proc/net/route`, returns the default route's
   gateway as a dotted quad, or nil when the table holds no usable default
   route.

   The kernel stores the gateway as a little-endian 32-bit hex string, so
   reversing the byte pairs and joining with dots yields the dotted-quad
   form. A default route with gateway `00000000` (0.0.0.0) is on-link and
   points at no host, so it is skipped like any other unusable row."
  [route-table]
  (when (string? route-table)
    (first
      (keep
        (fn [line]
          (let [cols (str/split (str/trim line) #"\s+")]
            ;; First non-header line whose Destination column is all
            ;; zeros IS the default route — its Gateway column holds
            ;; the host we want.
            (when (and (>= (count cols) 3)
                    (= (nth cols 1) "00000000"))
              (let [hex (nth cols 2)]
                (when (and (= (count hex) 8)
                        (not= hex "00000000"))
                  (try
                    (str/join "."
                      (reverse
                        (for [^long i [0 1 2 3]]
                          (Integer/parseInt
                            (subs hex (int (* 2 i)) (int (* 2 (inc i)))) 16))))
                    (catch NumberFormatException _ nil)))))))
        (str/split-lines route-table)))))

(defn read-proc-net-route
  "Returns the text of `/proc/net/route`, or nil when it cannot be read.

   Reads through `Files/readString` and not `slurp`: this file reports
   `length 0` and the WSL2 kernel's procfs answers the small chunked reads an
   `InputStreamReader` performs with `IOException: Invalid argument`, so `slurp`
   and `line-seq` both fail there while one NIO read of the whole file succeeds
   (proven in the WSL <-> Windows CDP diagnostic job). Stays pure-file, so no
   `ip route` subprocess and no native-image trouble."
  []
  (try
    (Files/readString (.toPath (File. "/proc/net/route")))
    (catch Exception _ nil)))

(defn wsl-default-gateway-ip
  "Returns the default-gateway IP as seen from inside WSL (which under
   classic NAT networking IS the Windows host), or nil if we're not in
   WSL / can't determine it."
  []
  (when (wsl?)
    (parse-proc-net-route-gateway (read-proc-net-route))))

;; =============================================================================
;; CDP probe — shared between daemon.clj and sci_env.clj
;; =============================================================================

(defn probe-cdp
  "Probes http://<host>:<port>/json/version and returns a map with
   :host, :port, :browser, :ws-url on success, or nil on failure.

   This is the canonical CDP liveness check used by both the daemon's
   auto-discovery and SCI's cdp-connect. Having it in one place
   prevents probe logic from drifting between the two call-sites."
  [^String host ^long port ^long timeout-ms]
  (try
    (let [url  (URL. (str "http://" host ":" port "/json/version"))
          conn (doto (.openConnection url)
                 (.setConnectTimeout (int timeout-ms))
                 (.setReadTimeout (int timeout-ms))
                 (.connect))]
      (try
        (when (= 200 (.getResponseCode ^HttpURLConnection conn))
          (let [body (slurp (.getInputStream ^HttpURLConnection conn))
                ;; Minimal JSON parsing without requiring charred —
                ;; platform.clj must stay dependency-light. Extract
                ;; Browser and webSocketDebuggerUrl via regex.
                browser (second (re-find #"\"Browser\"\s*:\s*\"([^\"]+)\"" body))
                ws-url  (second (re-find #"\"webSocketDebuggerUrl\"\s*:\s*\"(ws://[^\"]+)\"" body))]
            (when (and (string? browser) (not (str/blank? browser)))
              {:host    host
               :port    port
               :browser browser
               :ws-url  ws-url})))
        (finally
          (.disconnect ^HttpURLConnection conn))))
    (catch Exception _ nil)))

(defn cdp-candidate-hosts
  "Returns the list of hosts to probe for CDP endpoints.
   Default: [\"127.0.0.1\"]. Under WSL with a resolvable gateway:
   [\"127.0.0.1\" \"<gateway-ip>\"] — loopback first for the fast path
   on mirrored networking, gateway second for NAT fallback."
  []
  (let [loopback "127.0.0.1"
        gw       (when (wsl?) (wsl-default-gateway-ip))]
    (if (and gw (not= gw loopback))
      [loopback gw]
      [loopback])))

(defn discover-cdp
  "Auto-discovers a running CDP browser by probing candidate hosts and
   common ports. Returns the first successful probe result (a map with
   :host :port :browser :ws-url), or nil if no browser is found.

   Probes [hosts] × [ports] with the given timeout per probe. Hosts
   are determined by `cdp-candidate-hosts` (WSL-aware). Default ports
   come from `common-cdp-ports`."
  ([] (discover-cdp common-cdp-ports 2000))
  ([ports timeout-ms]
   (let [hosts (cdp-candidate-hosts)]
     (first
       (keep identity
         (for [host hosts, port ports]
           (probe-cdp host port timeout-ms)))))))
