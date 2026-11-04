;; Temporary Windows diagnostic: which step of a deep-DOM snapshot kills the renderer.
(require '[com.blockether.spel.snapshot :as snap]
         '[com.blockether.anomaly.core :as anomaly])
(import '[com.microsoft.playwright Playwright BrowserType$LaunchOptions Page])

(def build-js
  "(d)=>{let n=document.body;for(let i=0;i<d;i++){const e=document.createElement('div');e.setAttribute('role','group');e.setAttribute('aria-label','g'+i);n.appendChild(e);n=e;}const b=document.createElement('button');b.textContent='DEEPBOTTOM';n.appendChild(b);return document.querySelectorAll('div').length;}")

(def probes
  {:build    "()=>document.querySelectorAll('div').length"
   :layout   "()=>{const b=document.querySelector('button');return b.getBoundingClientRect().y}"
   :style    "()=>{const b=document.querySelector('button');return getComputedStyle(b).color}"
   :rec-pure "()=>{function w(e,d){let m=d;for(const c of e.children)m=Math.max(m,w(c,d+1));return m}return w(document.body,0)}"
   :rec-dom  "()=>{function w(e,d){const r=e.getBoundingClientRect();const s=getComputedStyle(e).display;let m=d;for(const c of e.children)m=Math.max(m,w(c,d+1));return m}return w(document.body,0)}"
   :stringify "()=>{let o={};let r=o;for(let i=0;i<document.querySelectorAll('div').length;i++){o.c={};o=o.c}return JSON.stringify(r).length}"})

(defn run-one [^Playwright pw headless-shell? depth probe]
  (let [opts (cond-> (BrowserType$LaunchOptions.) (not headless-shell?) (.setChannel "chromium"))
        browser (.launch (.chromium pw) opts)]
    (try
      (let [^Page pg (.newPage browser)]
        (.setDefaultTimeout pg 30000)
        (.evaluate pg build-js (int depth))
        (if (= probe :snapshot)
          (let [s (snap/capture-snapshot pg)]
            (if (anomaly/anomaly? s) (str "ANOMALY " (subs (str (::anomaly/message s)) 0 (min 60 (count (str (::anomaly/message s))))))
                (str "ok counter=" (:counter s))))
          (str "ok " (.evaluate pg (probes probe)))))
      (catch Throwable e (let [m (str (.getMessage e))] (str "FAIL " (or (second (re-find #"message='([^\n]*)" m)) (first (clojure.string/split-lines m))))))
      (finally (try (.close browser) (catch Throwable _))))))

(with-open [pw (Playwright/create)]
  (println "chromium" (.version (.launch (.chromium pw))))
  (doseq [shell? [true false]
          depth [100 200 300 400 600 1000 2000]
          probe [:build :layout :style :rec-pure :rec-dom :stringify :snapshot]]
    (println (format "DIAG %-14s depth=%-5d %-10s => %s" (if shell? "headless-shell" "full-chromium") depth (name probe)
               (run-one pw shell? depth probe)))
    (flush)))
(System/exit 0)
