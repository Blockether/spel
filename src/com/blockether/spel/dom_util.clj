(ns com.blockether.spel.dom-util
  "Shared helpers for DOM scripts.")

(def fnv1a-js
  "JavaScript for the ref hash."
  "function fnv1a(str) {
  let h = 0x811c9dc5;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h;
}")

(defn scroll-options
  "Returns the scroll arguments and result for a direction and options map."
  [direction opts]
  (let [dir      (name (or direction :down))
        amount   (long (get opts :amount 500))
        smooth?  (boolean (get opts :smooth? false))
        [dx dy]  (case dir
                  "up"    [0 (- amount)]
                  "down"  [0 amount]
                  "left"  [(- amount) 0]
                  "right" [amount 0]
                  [0 amount])
        behavior (if smooth? "'smooth'" "'instant'")
        js-opts  (str "{left: " dx ", top: " dy ", behavior: " behavior "}")]
    {:js-opts js-opts
     :smooth? smooth?
     :result {:scrolled dir :amount amount :smooth smooth?}}))
