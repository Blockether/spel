(ns com.blockether.spel.example-domain
  "Pinned copy of the example.com, example.org and example.net page.

   The live IANA page changed in 2026. It has no h1, a script adds its text,
   and it asks people not to use it for tests. Tests that read the page
   content call `route!` before they navigate. They keep the real URLs, but
   the browser gets `page-html` and does not contact IANA. `test-cli.sh`
   serves the same file through `spel network route`.
   NOT part of the public API — only used by our own test suite."
  (:require
   [clojure.java.io :as io]
   [com.blockether.spel.network :as net]
   [com.blockether.spel.page :as page]))

(def page-html
  "The IANA example page as web.archive.org captured it on 2026-03-01."
  (slurp (io/resource "com/blockether/spel/example_domain.html")))

(def ^:private example-url
  #"^https?://example\.(com|org|net)/")

(defn route!
  "Serves `page-html` to `pg` for each document request to example.com,
   example.org or example.net. Other requests to these hosts get an empty 404.
   Call it before `page/navigate`. The route stays on the page, so a reload
   gets the same page."
  [pg]
  (page/route! pg example-url
    (fn [route]
      (if (= "document" (net/request-resource-type (net/route-request route)))
        (net/route-fulfill! route {:status       200
                                   :content-type "text/html; charset=utf-8"
                                   :body         page-html})
        (net/route-fulfill! route {:status 404 :body ""})))))
