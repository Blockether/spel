(ns com.blockether.spel.config
  "Config file loader for `spel.json` — agent-browser parity.

   Layered precedence (lowest → highest):
     1. `~/.spel/config.json`      (user-level defaults)
     2. `./spel.json`              (project-level overrides)
     3. `SPEL_*` environment vars  (shell overrides)
     4. CLI flags                  (explicit per-invocation)

   Config values use camelCase keys (`headed`, `userAgent`, `allowedDomains`)
   and are normalized to the same kebab-case keywords the CLI flag parser
   already uses, so a single merged map feeds both layers.

   Unknown keys are silently ignored for forward compatibility. The
   `extensions` array merges by concatenation (user + project) rather than
   replacement so you can add project extensions on top of your user config."
  (:require
   [charred.api :as json])
  (:import
   [java.io File]))

;; =============================================================================
;; Key normalization
;; =============================================================================

(def ^:private camel->kebab-key
  "Maps supported camelCase JSON keys to the kebab-case keyword the CLI
   flag loop uses internally. Keys outside this whitelist are dropped."
  {"headed"              :headed           ;; special: flips :headless
   "headless"            :headless
   "session"             :session
   "profile"             :profile
   "browser"             :browser
   "channel"             :channel
   "proxy"               :proxy
   "proxyBypass"         :proxy-bypass
   "userAgent"           :user-agent
   "executablePath"      :executable-path
   "headers"             :headers
   "args"                :args
   "ignoreHttpsErrors"   :ignore-https-errors
   "allowFileAccess"     :allow-file-access
   "stealth"             :stealth
   "extensions"          :extensions
   "timeout"             :timeout
   "storageState"        :storage-state
   "device"              :device
   "allowedDomains"      :allowed-domains
   "contentBoundaries"   :content-boundaries
   "maxOutput"           :max-output
   "confirmActions"      :confirm-actions
   "noAutoDialog"        :no-auto-dialog
   "screenshotFormat"    :screenshot-format
   "screenshotQuality"   :screenshot-quality
   "screenshotDir"       :screenshot-dir})

(defn- normalize-config
  "Converts a raw config map (string keys, camelCase) into the kebab-case
   shape expected by the CLI flag layer. `headed: true` is mapped to
   `:headless false` because that's how the CLI represents headed mode."
  [raw]
  (when (map? raw)
    (let [kebab (reduce-kv
                  (fn [acc k v]
                    (if-let [kw (camel->kebab-key k)]
                      (assoc acc kw v)
                      acc))
                  {}
                  raw)]
      (cond-> (dissoc kebab :headed)
        ;; `headed: true` → `:headless false` (CLI convention)
        (contains? kebab :headed)
        (assoc :headless (not (boolean (:headed kebab))))))))

;; =============================================================================
;; File loading
;; =============================================================================

(defn- read-json-file
  "Reads and parses a JSON file. Returns a map on success, nil if the file is
   missing, and throws ex-info with context on parse errors (so a malformed
   config is a hard error — silent drop would be a footgun)."
  [^String path]
  (let [f (File. path)]
    (when (.isFile f)
      (try
        (json/read-json (slurp f))
        (catch Exception e
          (throw (ex-info (str "Failed to parse config file " path ": " (.getMessage e))
                   {:config-path path})))))))

(defn- user-config-path ^String []
  (str (System/getProperty "user.home") "/.spel/config.json"))

(defn- project-config-path ^String []
  ;; Resolved relative to CWD at CLI invocation time.
  "./spel.json")

(defn- merge-extensions
  "Merges :extensions arrays across layers by concatenation so project
   extensions can add to user extensions instead of replacing them."
  [user project]
  (let [combined (into (vec (:extensions user))
                   (vec (:extensions project)))]
    (cond-> (merge user project)
      (seq combined) (assoc :extensions combined))))

(defn load-config
  "Loads and merges the active config layers, returning a kebab-case map.

   `cli-config-path` (optional): explicit path from `--config <path>`. When
   present, it REPLACES both auto-discovered files instead of adding to them.
   A missing file at that path is a hard error.

   Auto-discovered files that do not exist are silently ignored.

   Returns {} when no config is found, so callers can unconditionally merge
   into their existing flag defaults."
  ([] (load-config nil))
  ([cli-config-path]
   (if cli-config-path
     (if-let [raw (read-json-file cli-config-path)]
       (normalize-config raw)
       (throw (ex-info (str "--config file not found: " cli-config-path)
                {:config-path cli-config-path})))
     (let [user    (normalize-config (read-json-file (user-config-path)))
           project (normalize-config (read-json-file (project-config-path)))]
       (or (merge-extensions (or user {}) (or project {})) {})))))
