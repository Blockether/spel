(ns com.blockether.spel.platform
  "Platform detection utilities — WSL detection, default-gateway
   resolution, and OS identification. These are pure functions with
   no dependency on daemon state, browser sessions, or SCI context.

   Extracted from daemon.clj to break the circular dependency between
   sci_env.clj (which needs WSL functions for the spel SCI namespace)
   and daemon.clj (which requires sci_env.clj for evaluation).

   Both daemon.clj and sci_env.clj can safely require this namespace."
  (:require
   [clojure.string :as str]))

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

(defn wsl-default-gateway-ip
  "Returns the default-gateway IP as seen from inside WSL (which under
   classic NAT networking IS the Windows host), or nil if we're not in
   WSL / can't determine it.

   Reads `/proc/net/route` directly instead of shelling out to `ip route`,
   so the call is pure-file and GraalVM-native-image friendly. The kernel
   stores the gateway as a little-endian 32-bit hex string; reversing the
   byte pairs and joining with dots yields the dotted-quad form."
  []
  (when (wsl?)
    (try
      (let [content (slurp "/proc/net/route")
            lines   (str/split-lines content)]
        (first
          (keep
            (fn [line]
              (let [cols (str/split line #"\s+")]
                ;; First non-header line whose Destination column is all
                ;; zeros IS the default route — its Gateway column holds
                ;; the host we want.
                (when (and (>= (count cols) 3)
                        (= (nth cols 1) "00000000"))
                  (let [hex (nth cols 2)]
                    (when (and (string? hex) (= (count hex) 8))
                      (try
                        (str/join "."
                          (reverse
                            (for [^long i [0 1 2 3]]
                              (Integer/parseInt
                                (subs hex (int (* 2 i)) (int (* 2 (inc i)))) 16))))
                        (catch NumberFormatException _ nil)))))))
            lines)))
      (catch Exception _ nil))))
