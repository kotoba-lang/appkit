(ns appkit.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [kotoba-ui.core :as ui]
            [appkit.core :as app]))

;; ---------------------------------------------------------------------------
;; The variance-point sweep
;;
;; appkit's whole reason to exist is the claim in `appkit.core`'s docstring:
;; of kotoba-ui.core's component catalog, only `panel` (`:surface`/`:elevation`)
;; and `list-view` (`:surface`) actually have a per-screen-shape variance point,
;; so those two are the only things worth wrapping and everything else is left
;; to kotoba-ui as-is.
;;
;; That claim is about *kotoba-ui*, not about appkit — nothing in this repo can
;; keep it true. If kotoba-ui grows a third variance point (a `:surface` on
;; `toolbar`, say) or drops one, appkit is silently incomplete and every prose
;; restatement of the claim becomes wrong with no signal. So it is measured
;; against the pinned kotoba-ui rather than asserted in prose.
;;
;; JVM-only: the sweep needs `ns-publics`, and `clojure -M:test` is this repo's
;; only runner (there is no cljs build here). If one is ever added, this needs
;; an explicit component table instead — the assertion is the point, not the
;; reflection.
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- render-or-nil
     "Render `(apply f args)`, or nil if that arity/shape isn't what `f` takes.
      Components in the catalog have several call shapes; we don't know which."
     [f args]
     (try (ui/->html (apply f args))
          (catch Throwable _ nil))))

#?(:clj
   (def ^:private call-shapes
     "The (body opts) / (rows opts) / (opts) shapes used across the catalog.
      Each returns [args-without-opts args-with-opts]."
     [(fn [opts] [["x"] ["x" opts]])
      (fn [opts] [[[]]  [[]  opts]])
      (fn [opts] [[{}]  [opts]])]))

#?(:clj
   (defn- emits-modifier?
     "True iff some call shape of `f` accepts `opts` AND passing it introduces
      a `--<token>` modifier class the no-opt render did not already carry.

      Deliberately narrower than `output changed`: nearly every var in the
      catalog changes its output when handed a map (token/CSS helpers take the
      map as data, inputs echo unknown keys into attributes). Only a real
      variance point turns the opt into a BEM modifier on its own block."
     [f opts token]
     (boolean
      (some (fn [mk]
              (let [[base with] (mk opts)
                    b (render-or-nil f base)
                    w (render-or-nil f with)]
                (and b w
                     (not (str/includes? b (str "--" token)))
                     (str/includes? w (str "--" token)))))
            call-shapes))))

#?(:clj
   (defn- variance-points
     "Names in kotoba-ui.core that turn `opts` into a `--<token>` modifier."
     [opts token]
     (into (sorted-set)
           (keep (fn [[sym v]]
                   (let [f @v]
                     (when (and (fn? f) (emits-modifier? f opts token))
                       sym)))
                 (ns-publics 'kotoba-ui.core)))))

#?(:clj
   (deftest surface-variance-points-are-exactly-what-appkit-wraps-test
     (testing "only `panel` and `list-view` take a :surface opt — appkit wraps exactly these"
       (is (= '#{list-view panel} (variance-points {:surface :thick} "thick"))))
     (testing "only `panel` takes an :elevation opt — so only `panel` gets an elevation default"
       (is (= '#{panel} (variance-points {:elevation :flat} "flat"))))))

#?(:clj
   (deftest appkit-adds-no-components-test
     (testing "appkit owns no component catalog: the two wrappers and their two default maps, nothing else"
       ;; The rule is that product repos require kotoba-ui.core and get the
       ;; catalog from there. Re-exporting a component here (`(def button
       ;; ui/button)`) would give them a second, partial place to reach for it.
       (is (= '#{panel list-view default-panel-opts default-list-view-opts}
              (set (keys (ns-publics 'appkit.core))))))))

;; ---------------------------------------------------------------------------
;; appkit's own contract
;; ---------------------------------------------------------------------------

(deftest desktop-panel-defaults-test
  (testing "appkit panel defaults to thick/flat, distinct from kotoba-ui.core's bare :regular/:raised"
    (let [html (ui/->html (app/panel ["x"]))]
      (is (str/includes? html "liquid-glass__panel--thick"))
      (is (str/includes? html "liquid-glass__panel--flat")))))

(deftest desktop-list-view-defaults-test
  (is (str/includes? (ui/->html (app/list-view [])) "liquid-glass__list--thick")))

(deftest defaults-actually-change-the-render-test
  (testing "if appkit's wrappers rendered the same as the bare kotoba-ui ones, appkit would have nothing to do"
    (is (not= (ui/->html (ui/panel ["x"])) (ui/->html (app/panel ["x"]))))
    (is (not= (ui/->html (ui/list-view [])) (ui/->html (app/list-view []))))))

(deftest default-opts-are-published-contract-test
  (testing "these maps are public vars — consumers compose their own opts from them, so the values are API"
    (is (= {:surface :thick :elevation :flat} app/default-panel-opts))
    (is (= {:surface :thick} app/default-list-view-opts))))

(deftest caller-opts-win-test
  (testing "explicit opts override appkit defaults, same merge contract as every kotoba-ui wrap"
    (let [html (ui/->html (app/panel ["x"] {:surface :clear}))]
      (is (str/includes? html "liquid-glass__panel--clear"))
      (is (not (str/includes? html "liquid-glass__panel--thick"))))))

(deftest caller-opts-win-per-key-test
  (testing "override is per key: an explicit :elevation leaves the :surface default standing"
    ;; :raised is liquid-glass's own default and emits no modifier of its own,
    ;; so this reads the override as the *absence* of --flat. Merging the wrong
    ;; way round (defaults over caller) would leave --flat in place.
    (let [html (ui/->html (app/panel ["x"] {:elevation :raised}))]
      (is (str/includes? html "liquid-glass__panel--thick"))
      (is (not (str/includes? html "liquid-glass__panel--flat"))))))

(deftest arity-1-is-arity-2-with-empty-opts-test
  (testing "the 1-arity form is exactly the 2-arity form with {}, not a separate default path"
    (is (= (ui/->html (app/panel ["x"] {})) (ui/->html (app/panel ["x"]))))
    (is (= (ui/->html (app/list-view [] {})) (ui/->html (app/list-view []))))))
