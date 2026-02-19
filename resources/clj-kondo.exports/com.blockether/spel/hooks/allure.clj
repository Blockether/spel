(ns hooks.allure
  "clj-kondo hooks for com.blockether.spel.allure macros.

   step, ui-step, api-step all share the same shape:
     (macro-name name-expr body...)
   Rewritten to (do name-expr body...) or (do body...) for analysis."
  (:require [clj-kondo.hooks-api :as api]))

(defn step
  "Hook for allure/step macro. Transforms:
     (step \"name\")             → (do \"name\")
     (step \"name\" body...)     → (do body...)
   The step-name is a label — only lint the body in multi-arg form."
  [{:keys [node]}]
  (let [children (rest (:children node))
        new-node (if (= 1 (count children))
                   ;; Marker step — keep name as expression
                   (api/list-node (list* (api/token-node 'do) children))
                   ;; Lambda step — drop name, lint body only
                   (let [[_step-name & body] children]
                     (api/list-node (list* (api/token-node 'do) body))))]
    {:node new-node}))

(defn ui-step
  "Hook for allure/ui-step macro. Transforms:
     (ui-step \"name\" body...) → (do body...)
   The step-name is a string — lint only the body."
  [{:keys [node]}]
  (let [[_step-name & body] (rest (:children node))
        new-node (api/list-node
                   (list* (api/token-node 'do) body))]
    {:node new-node}))

(defn api-step
  "Hook for allure/api-step macro. Transforms:
     (api-step \"name\" body...) → (do body...)
   The step-name is a string — lint only the body."
  [{:keys [node]}]
  (let [[_step-name & body] (rest (:children node))
        new-node (api/list-node
                   (list* (api/token-node 'do) body))]
    {:node new-node}))

(defn expect
  "Hook for allure/expect macro. Transforms:
     (expect expr)       → (lazytest.core/expect expr)
     (expect expr msg)   → (lazytest.core/expect expr msg)
   Delegates to lazytest's expect for linting."
  [{:keys [node]}]
  (let [children (rest (:children node))
        new-node (api/list-node
                   (list* (api/token-node 'lazytest.core/expect) children))]
    {:node new-node}))
