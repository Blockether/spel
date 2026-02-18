(ns hooks.com.blockether.spel
  "clj-kondo hooks for com.blockether.spel macros.

   Covers core lifecycle (with-playwright, with-browser, with-context, with-page)
   and api lifecycle (with-api-context, with-api-contexts, with-hooks, with-retry).

   Each hook rewrites the macro call into a form clj-kondo can analyze
   (typically a `let` or `do`)."
  (:require [clj-kondo.hooks-api :as api]))

;; =============================================================================
;; Core — with-playwright, with-browser, with-context, with-page
;; API  — with-api-context (same shape)
;; =============================================================================

(defn with-resource
  "Hook for with-playwright, with-browser, with-context, with-page,
   and with-api-context.

   Rewrites (with-foo [sym expr] body...) or (with-foo [sym] body...)
   into (let [sym expr] body...) so clj-kondo resolves the binding correctly.
   For 1-element bindings, synthesizes a nil expression."
  [{:keys [node]}]
  (let [[binding-vec & body] (rest (:children node))
        children             (:children binding-vec)
        cnt                  (count children)]
    (when-not (and binding-vec (<= 1 cnt 2))
      (throw (ex-info "Expected a binding vector with [sym] or [sym expr]" {})))
    (let [sym      (first children)
          expr     (if (= 2 cnt)
                     (second children)
                     (api/token-node nil))
          new-node (api/list-node
                     (list*
                       (api/token-node 'let)
                       (api/vector-node [sym expr])
                       body))]
      {:node new-node})))

;; =============================================================================
;; API — with-api-contexts, with-hooks, with-retry
;; =============================================================================

(defn with-api-contexts
  "Hook for api/with-api-contexts.

   Rewrites (with-api-contexts [a expr-a b expr-b] body...)
   into (let [a expr-a b expr-b] body...).
   Flat binding pairs, same shape as with-open."
  [{:keys [node]}]
  (let [[bindings & body] (rest (:children node))
        new-node (api/list-node
                   (list* (api/token-node 'let)
                     bindings
                     body))]
    {:node new-node}))

(defn with-hooks
  "Hook for api/with-hooks.

   Rewrites (with-hooks {:on-request f :on-response g} body...)
   into (let [_ {:on-request f :on-response g}] body...).
   The hooks-map is a plain expression, not a binding vector."
  [{:keys [node]}]
  (let [[hooks-map & body] (rest (:children node))
        new-node (api/list-node
                   (list* (api/token-node 'let)
                     (api/vector-node [(api/token-node '_) hooks-map])
                     body))]
    {:node new-node}))

(defn with-retry
  "Hook for api/with-retry.

   Rewrites (with-retry opts body...) into (let [_ opts] body...)
   and     (with-retry body)          into (do body).
   First arg may be an opts map or the sole body expression."
  [{:keys [node]}]
  (let [args     (rest (:children node))
        new-node (if (> (count args) 1)
                   (let [[opts & body] args]
                     (api/list-node
                       (list* (api/token-node 'let)
                         (api/vector-node [(api/token-node '_) opts])
                         body)))
                   (api/list-node
                     (list* (api/token-node 'do) args)))]
    {:node new-node}))
