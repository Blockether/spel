(ns hooks.allure
  "clj-kondo hooks for com.blockether.spel.allure macros.

   step, ui-step, api-step all share the same shape:
     (macro-name name-expr body...)
   Rewritten to (do name-expr body...) or (do body...) for analysis."
  (:require [clj-kondo.hooks-api :as api]))

(defn step
  "Hook for allure/step macro. Transforms:
     (step \"name\")        → (do \"name\")
     (step \"name\" exprs)  → (do \"name\" exprs)"
  [{:keys [node]}]
  (let [children (rest (:children node))
        new-node (api/list-node
                   (list* (api/token-node 'do) children))]
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
