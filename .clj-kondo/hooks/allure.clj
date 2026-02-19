(ns hooks.allure
  "clj-kondo hooks for com.blockether.spel.allure macros.

   Covers:
   - step, ui-step, api-step        (Allure step macros)
   - defdescribe, describe, it      (lazytest re-exports — test definition)
   - expect, expect-it, should      (lazytest re-exports — assertions)
   - context, specify               (lazytest re-exports — aliases)
   - before, after, before-each,
     after-each                     (lazytest re-exports — hooks)
   - around                         (lazytest re-exports — wrapper hook)

   Each hook rewrites the macro call into a form clj-kondo can analyze
   natively (def, do, fn, let) without requiring lazytest.core in scope."
  (:require [clj-kondo.hooks-api :as api]))

;; =============================================================================
;; Allure step macros
;; =============================================================================

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

;; =============================================================================
;; Lazytest re-exports — test definition
;; =============================================================================

(defn defdescribe
  "Hook for allure/defdescribe. Transforms:
     (defdescribe name doc? attr-map? & children)
     → (do (def name nil) children...)

   Registers `name` as a var and analyzes all children (describe, it,
   hook forms, etc.). Optional doc string is skipped."
  [{:keys [node]}]
  (let [args (rest (:children node))
        name-node (first args)
        rest-args (rest args)
        ;; Skip optional doc string
        [fst & after-fst] rest-args
        has-doc? (and fst (api/string-node? fst))
        children (if has-doc? after-fst rest-args)
        ;; Build: (do (def name nil) children...)
        def-form (api/list-node
                   [(api/token-node 'def) name-node (api/token-node nil)])
        new-node (api/list-node
                   (list* (api/token-node 'do) def-form children))]
    {:node new-node}))

(defn describe
  "Hook for allure/describe (and context alias). Transforms:
     (describe doc & children)
     (describe doc attr-map & children)
     → (do children...)  or  (do attr-map children...)

   Skips the doc string. Keeps the optional attr-map (contains
   :context refs that kondo should verify). Analyzes all children."
  [{:keys [node]}]
  (let [[_doc & children] (rest (:children node))
        new-node (api/list-node
                   (list* (api/token-node 'do) children))]
    {:node new-node}))

(defn it
  "Hook for allure/it (and specify alias). Transforms:
     (it doc & body)
     (it doc attr-map & body)
     → (do body...)  or  (do attr-map body...)

   Same shape as describe — skips doc, analyzes rest."
  [{:keys [node]}]
  (let [[_doc & body] (rest (:children node))
        new-node (api/list-node
                   (list* (api/token-node 'do) body))]
    {:node new-node}))

;; =============================================================================
;; Lazytest re-exports — assertions
;; =============================================================================

(defn expect
  "Hook for allure/expect (and should alias). Transforms:
     (expect expr)       → (do expr)
     (expect expr msg)   → (do expr msg)

   Analyzes the expression and optional message without requiring
   lazytest.core in scope."
  [{:keys [node]}]
  (let [children (rest (:children node))
        new-node (api/list-node
                   (list* (api/token-node 'do) children))]
    {:node new-node}))

(defn expect-it
  "Hook for allure/expect-it. Transforms:
     (expect-it doc expr)
     (expect-it doc attr-map expr)
     → (do expr)  or  (do attr-map expr)

   Same shape as it — skips doc, analyzes rest."
  [{:keys [node]}]
  (let [[_doc & rest-args] (rest (:children node))
        new-node (api/list-node
                   (list* (api/token-node 'do) rest-args))]
    {:node new-node}))

;; =============================================================================
;; Lazytest re-exports — hooks
;; =============================================================================

(defn body-only
  "Hook for before, after, before-each, after-each. Transforms:
     (macro & body) → (do body...)

   These are simple body-wrapping macros."
  [{:keys [node]}]
  (let [body (rest (:children node))
        new-node (api/list-node
                   (list* (api/token-node 'do) body))]
    {:node new-node}))

(defn around
  "Hook for allure/around. Transforms:
     (around [f] & body) → (fn [f] body...)

   Rewrites as fn so clj-kondo resolves the binding parameter."
  [{:keys [node]}]
  (let [[params & body] (rest (:children node))
        new-node (api/list-node
                   (list* (api/token-node 'fn) params body))]
    {:node new-node}))
