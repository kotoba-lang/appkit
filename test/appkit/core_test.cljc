(ns appkit.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.lang.text :as str]
            [kotoba-ui.core :as ui]
            [appkit.core :as app]
            #?(:clj [clojure.java.shell :as shell])))

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
;; JVM-only: the sweep needs `ns-publics`. The rest of this file also runs on
;; cljs (`nbb test/appkit/cljs_runner.cljs` loads this namespace under
;; cljs.test), where the `#?(:clj …)` tests simply do not exist — the sweep is
;; a claim about kotoba-ui, measured once on the JVM is enough. What the cljs
;; run adds is the other direction: appkit's own `.cljc` staying loadable and
;; identical on the runtime its consumers use.
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

;; ---------------------------------------------------------------------------
;; Passthrough: appkit supplies defaults and nothing else
;;
;; Everything above pins *which* defaults appkit applies and that a caller can
;; override them. None of it pins that the caller's own content and options
;; survive the wrap at all. Measured on 2026-09-05, before these were added:
;; rendering a constant instead of `body`, dropping `rows` on the floor, and
;; running `opts` through `(select-keys opts [:surface :elevation])` each left
;; the whole suite at exit 0. A wrapper that quietly renders the wrong content
;; is the one failure this repo cannot afford, because appkit's only job is to
;; be invisible.
;;
;; These read the rendered HTML rather than the opt map, so they fail for the
;; consumer-visible reason and not for an internal shape appkit is free to
;; change.
;; ---------------------------------------------------------------------------

(deftest panel-renders-the-caller-body-test
  (testing "the body belongs to the caller: appkit adds options, never content"
    (is (str/includes? (ui/->html (app/panel [:p "appkit-body-marker"]))
                       "<p>appkit-body-marker</p>"))))

(deftest list-view-renders-the-caller-rows-test
  (testing "every row arrives, in the caller's order — a wrapper that drops rows still looks like a list"
    ;; One assertion over the extracted rows rather than three over the string:
    ;; a dropped row then reads as `[]` instead of erroring on a nil index, and
    ;; a reordered one reads as the order it actually came out in.
    (let [html (ui/->html (app/list-view [[:li "row-alpha"] [:li "row-beta"]]))]
      (is (= ["row-alpha" "row-beta"]
             (map second (re-seq #"<li>(row-[a-z]+)</li>" html)))))))

(deftest opts-appkit-knows-nothing-about-still-arrive-test
  (testing "appkit merges defaults *under* the caller's opts; it does not select from them"
    ;; In liquid-glass v1 `panel` takes :id and `list-view` takes :class, and
    ;; neither is an appkit default — so each reaches kotoba-ui only if appkit
    ;; hands the opt map on whole. Narrowing it to the keys appkit knows about
    ;; still satisfies every override test above, while silently breaking any
    ;; consumer that composes its own opts on top of the published default maps
    ;; (which is the documented way to use them).
    (is (str/includes? (ui/->html (app/panel ["x"] {:id "appkit-id-marker"}))
                       "appkit-id-marker"))
    (is (str/includes? (ui/->html (app/list-view [] {:class "appkit-class-marker"}))
                       "appkit-class-marker"))))

;; ---------------------------------------------------------------------------
;; Both wrappers, held to the same contract
;;
;; Everything above pins `panel` hard and `list-view` softly. Measured on
;; 2026-09-06 against the suite as it then stood (12 tests, 20 assertions):
;;
;;   (merge opts default-list-view-opts)                     -> 12 green, exit 0
;;   (ui/panel body (merge default-panel-opts {:id ..} opts)) -> 12 green, exit 0
;;
;; The first reverses the merge on `list-view` only, so the caller's `:surface`
;; loses to appkit's default. `panel`'s merge order has been pinned since the
;; first pass (`caller-opts-win-test`); `list-view`'s never was. That asymmetry
;; is this repo's characteristic failure — two of the existing mutations
;; (`*-arity-1-bypasses-defaults`) exist because `panel` was fixed and
;; `list-view` was missed in exactly this way.
;;
;; The second adds a default the published map does not mention. Both maps are
;; public vars and the documented way to use them is to compose your own opts
;; on top, so they mean something only if they are the *whole* of what each
;; wrapper adds. `default-opts-are-published-contract-test` pins their values;
;; nothing pinned that the wrappers apply those values and no others, so the
;; maps could keep their asserted contents while ceasing to describe appkit.
;; ---------------------------------------------------------------------------

(deftest list-view-caller-opts-win-test
  (testing "list-view honours an explicit :surface, same merge contract as panel"
    (let [html (ui/->html (app/list-view [] {:surface :clear}))]
      (is (str/includes? html "liquid-glass__list--clear"))
      (is (not (str/includes? html "liquid-glass__list--thick"))))))

(deftest published-default-maps-are-complete-test
  (testing "each published map is the whole of what its wrapper adds: composing from it matches calling the wrapper"
    ;; Rendered HTML rather than the opt map, so an undeclared default is
    ;; caught only when it reaches the consumer — appkit stays free to change
    ;; how it gets there.
    (is (= (ui/->html (ui/panel ["x"] app/default-panel-opts))
           (ui/->html (app/panel ["x"]))))
    (is (= (ui/->html (ui/list-view [] app/default-list-view-opts))
           (ui/->html (app/list-view []))))))

;; ---------------------------------------------------------------------------
;; The two remaining panel/list-view asymmetries
;;
;; Everything above pins both wrappers to the same contract, but two of the
;; assertions reach that contract through a single example each, and the
;; example was chosen on the `panel` side. Measured on 2026-09-07 against the
;; suite as it then stood (14 tests, 24 assertions):
;;
;;   (merge default-panel-opts (dissoc opts :class))      -> 14 green, exit 0
;;   (ui/list-view rows (merge default-panel-opts opts))  -> 14 green, exit 0
;;
;; Both are this repo's characteristic failure — `panel` covered, `list-view`
;; or the second key missed — and both are a plausible edit rather than a
;; contrived one: the first is the shape `:appkit/panel-opts-filtered` already
;; guards, one key narrower; the second is a copy-paste of the line above it.
;;
;; The mirror of each is already dead (`panel-drops-id`,
;; `panel-uses-list-view-defaults` both go red), which is what makes these two
;; asymmetries rather than gaps.
;; ---------------------------------------------------------------------------

(def ^:private probe-opt-keys
  "Attribute-ish opts a consumer might compose on top of a published default
   map. A probe list, not a claim: kotoba-ui honours some subset of these and
   `forwarded-opt-keys` measures which, so a key liquid-glass starts honouring
   is picked up here without an edit. A key outside this list is still
   unmeasured — the bound is the list, and it is deliberately visible."
  [:id :class :label :role :style :title :data-testid])

(defn- forwarded-opt-keys
  "The subset of `probe-opt-keys` that `component` actually renders — measured
   against the pinned kotoba-ui rather than asserted in prose, same reason as
   the variance-point sweep above. Opts a component ignores (`:id` on
   `list-view`, in liquid-glass v1) are excluded, so appkit is never held to
   forwarding something no one can observe."
  [component base]
  (into (sorted-set)
        (filter (fn [k]
                  (let [marker (str "appkit-probe-" (name k))]
                    (str/includes? (ui/->html (component base {k marker})) marker)))
                probe-opt-keys)))

(deftest every-forwarded-opt-survives-the-wrap-test
  (testing "every opt kotoba-ui honours reaches it through appkit — not only the one key a test happened to pick"
    ;; `opts-appkit-knows-nothing-about-still-arrive-test` pins :id on panel and
    ;; :class on list-view. panel honours both, so dropping :class there left
    ;; the suite green while breaking the one opt a multi-pane desktop layout
    ;; needs most — which is the layer appkit exists to serve.
    (let [panel-keys (forwarded-opt-keys ui/panel ["x"])
          list-keys  (forwarded-opt-keys ui/list-view [])]
      ;; Evidence floor. Every assertion below is `doseq`-driven, so a sweep
      ;; that measured nothing would run zero assertions and read exactly like
      ;; a sweep that measured everything and found no fault.
      (is (seq panel-keys) "sweep measured no forwarded opt on kotoba-ui panel")
      (is (seq list-keys) "sweep measured no forwarded opt on kotoba-ui list-view")
      (doseq [k panel-keys
              :let [marker (str "appkit-probe-" (name k))]]
        (is (str/includes? (ui/->html (app/panel ["x"] {k marker})) marker)
            (str "appkit panel dropped a forwarded opt: " k)))
      (doseq [k list-keys
              :let [marker (str "appkit-probe-" (name k))]]
        (is (str/includes? (ui/->html (app/list-view [] {k marker})) marker)
            (str "appkit list-view dropped a forwarded opt: " k))))))

;; ---------------------------------------------------------------------------
;; Wiring `list-view` to `default-panel-opts` renders identically today,
;; because liquid-glass's `list-view` reads only :surface and :class and drops
;; :elevation on the floor. So `published-default-maps-are-complete-test`,
;; which compares renders, cannot see it — and neither can any other assertion
;; in this file, all of which are render-based on principle ("fail for the
;; consumer-visible reason, not for an internal shape appkit is free to
;; change").
;;
;; This one deliberately breaks that principle, because here the render cannot
;; carry the fault: the published maps are public vars whose documented use is
;; to compose your own opts on top, so they mean something only if each is the
;; map its own wrapper actually applies. A wrapper applying the other map keeps
;; every render identical while making the published var decorative — and it
;; becomes consumer-visible the moment liquid-glass gives `list-view` an
;; :elevation variance point, which is exactly the drift the sweep at the top
;; of this file exists to detect.
;;
;; JVM-only, alongside the sweep: it observes the call rather than the output.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest each-wrapper-applies-its-own-published-map-test
     (testing "each wrapper hands kotoba-ui its own published map merged under the caller's opts, and no other"
       ;; `seen` starts at ::never-called, so a with-redefs that failed to
       ;; intercept fails the assertion instead of comparing nothing.
       (let [seen (atom ::never-called)]
         (with-redefs [ui/panel (fn [_body opts] (reset! seen opts) [:div])]
           (app/panel ["x"] {:id "appkit-applied-marker"}))
         (is (= (merge app/default-panel-opts {:id "appkit-applied-marker"}) @seen)))
       (let [seen (atom ::never-called)]
         (with-redefs [ui/list-view (fn [_rows opts] (reset! seen opts) [:div])]
           (app/list-view [] {:class "appkit-applied-marker"}))
         (is (= (merge app/default-list-view-opts {:class "appkit-applied-marker"}) @seen))))))

;; ---------------------------------------------------------------------------
;; The cljs runner has to be able to start
;;
;; `test/appkit/cljs_runner.cljs` is launched as a bare `nbb <script>` with no
;; --classpath, because the classpath is the thing the script itself resolves.
;; So every namespace in the runner's own `ns` form must be one nbb ships. The
;; suite it then runs is under no such constraint: `appkit.core-test` loads on
;; the classpath the runner computed, and requires `kotoba.lang.text` freely.
;;
;; Nothing measured that distinction, and on 2026-09-09 a mechanical
;; clojure.string -> kotoba.lang.text rewrite (74ad15c) reached the runner
;; along with the code. The runner stopped starting — it died on `Could not
;; find namespace: kotoba.lang.text` before loading a single test. Measured
;; 2026-09-10 at that commit:
;;
;;   clojure -M:test                    -> 16 tests / 31 assertions, exit 0
;;   nbb test/appkit/cljs_runner.cljs   -> could not begin, exit 1
;;
;; The entire cljs half of this repo went dark and every signal the repo
;; emitted said it was fine. That is the failure the cljs runner was added to
;; prevent, arriving through the runner rather than through the code — and the
;; runner, by construction, cannot check its own requires: if one does not
;; resolve, nothing in the file runs.
;;
;; Which namespaces nbb ships is nbb's to change, so this measures rather than
;; lists: each entry of the runner's `:require` is handed to a bare `nbb -e`.
;; A run that could not measure (no nbb on PATH) fails and says which — it does
;; not pass. JVM-only: it reads a file and spawns a process.
;; ---------------------------------------------------------------------------

#?(:clj (def ^:private runner-path "test/appkit/cljs_runner.cljs"))

#?(:clj
   (defn- runner-require-entries
     "The entries of the `:require` clause in the runner's `ns` form, or nil if
      the file or that form is not there. Read from the slurped string starting
      at `(ns `, because the file opens with a `#!` shebang the Clojure reader
      does not accept."
     []
     (let [f (java.io.File. runner-path)]
       (when (.isFile f)
         (let [src (slurp f)
               i   (.indexOf src "(ns ")]
           (when (nat-int? i)
             (some (fn [x] (when (and (sequential? x) (= :require (first x)))
                             (seq (rest x))))
                   (read-string (subs src i)))))))))

#?(:clj
   (defn- require-target
     "What a `:require` entry names: a symbol namespace, or a string JS module."
     [e]
     (cond (or (symbol? e) (string? e)) e
           (and (vector? e) (or (symbol? (first e)) (string? (first e)))) (first e))))

#?(:clj
   (defn- bare-nbb-require
     "Whether nbb resolves `target` with no --classpath.
      Three values, not a boolean: a measurement that could not be taken must
      not read like one that was taken and found nothing wrong."
     [target]
     (let [form (if (string? target)
                  (str "(require '[\"" target "\"])")
                  (str "(require '[" target "])"))]
       (try
         (if (zero? (:exit (shell/sh "nbb" "-e" form))) :ok :unresolvable)
         (catch java.io.IOException _ :no-nbb)))))

#?(:clj
   (deftest cljs-runner-is-bare-launchable-test
     (testing "every namespace the cljs runner requires is one nbb resolves with no classpath"
       (let [entries (runner-require-entries)
             targets (keep require-target entries)]
         ;; Evidence floor. The `doseq` below is driven by what was parsed, so
         ;; a parse that found nothing would run zero assertions and read
         ;; exactly like a runner whose every require checked out.
         (is (seq entries)
             (str "could not read a :require clause out of " runner-path
                  " — the runner's requires went unmeasured, which is not a pass"))
         (is (= (count targets) (count entries))
             (str "a :require entry in " runner-path " was not in a shape this "
                  "test knows how to name, so it went unmeasured: "
                  (pr-str (remove require-target entries))))
         (doseq [t targets
                 :let [verdict (bare-nbb-require t)]]
           (is (= :ok verdict)
               (str "the cljs runner requires " (pr-str t) ", and a bare "
                    "`nbb " runner-path "` cannot resolve it (measured: "
                    (name verdict) "). The runner dies on this before loading "
                    "any test, and the JVM suite stays green.")))))))

;; ---------------------------------------------------------------------------
;; Composition: every child arrives as markup, whole and in the caller's order
;;
;; Every body/rows assertion above hands its wrapper a FLAT body — `["x"]`,
;; `[:p "marker"]`, `{k marker}`. `list-view`'s rows happen to nest one level
;; (`[[:li "row-alpha"] [:li "row-beta"]]`), so mangling them is already caught.
;; `panel`'s body never was — and `panel` is the one that nests. A desktop pane
;; holds a toolbar above a list; that composition is the whole of the README's
;; usage example and the reason this repo exists.
;;
;; Measured 2026-09-10 against the suite as it then stood (17 tests, 36
;; assertions), on both runtimes:
;;
;;   (ui/panel (vec (flatten body)) …)         -> 17 tests / 36 assertions, exit 0
;;   (ui/panel (vec (remove vector? body)) …)  -> 17 tests / 36 assertions, exit 0
;;
;; The first collapses every nested element into loose keywords and attribute
;; maps, which kotoba-ui then renders as ESCAPED TEXT — the pane shows the user
;; `:div{:class &quot;liquid-glass__list…&quot;}` as visible garbage. The second
;; drops element children on the floor and keeps only strings. Both mirrors on
;; `list-view` (`flatten` and `remove vector?` over rows) are already red, which
;; makes this an asymmetry rather than a gap: the same panel / list-view
;; asymmetry this repo keeps producing, with the sides reversed.
;;
;; ⚠ Substring assertions cannot carry this fault, and look like they can. Under
;; the first mutation the composed markup still `includes?`
;; "liquid-glass__list--thick", "Row 1" and every other marker one would reach
;; for — as escaped text, in a pane that is visibly broken. So these compare
;; each child against its OWN standalone render instead: appkit adds options and
;; never content, so a child rendered through the wrap is byte-identical to the
;; same child rendered alone, and the only freedom the wrap has is where it puts
;; it. That is a property of the wrap, not of any string that happens to appear.
;; ---------------------------------------------------------------------------

(def ^:private did-not-render "APPKIT-COMPOSITION-DID-NOT-RENDER: ")

(defn- render-or-report
  "Render `node`, or a marker carrying the reason it could not be rendered.

   A body mangled badly enough stops rendering rather than rendering wrongly —
   `(ui/panel [] …)` throws in liquid-glass v1, where `(ui/panel nil …)` and
   `(ui/panel [\"x\"] …)` are both fine. Left uncaught that surfaces as an error
   from inside the render library, so the suite goes red for a reason no
   assertion here named. The marker can never contain a child's markup, so
   every assertion below still fails — by its own name, with the reason."
  [node]
  (try (ui/->html node)
       (catch #?(:clj Throwable :cljs :default) e
         (str did-not-render (ex-message e)))))

(defn- composition
  "The README's usage shape — a pane holding a toolbar above a list — as
   [composed-html [child-html …]]. Each child is built once and rendered both
   ways, so the comparison is against the caller's own value rather than a
   literal this file would have to keep in step with liquid-glass."
  []
  (let [toolbar (ui/toolbar [(ui/icon-button "appkit-menu-marker")])
        listing (app/list-view [(ui/list-row "appkit-row-marker")])]
    [(render-or-report (app/panel [toolbar listing]))
     [(ui/->html toolbar) (ui/->html listing)]]))

(deftest composed-children-arrive-as-markup-test
  (testing "each child of a panel renders through the wrap exactly as it renders alone"
    (let [[composed children] (composition)]
      ;; Evidence floor: the doseq is driven by `children`, so a composition
      ;; that built nothing would run zero assertions and read exactly like one
      ;; whose every child survived.
      (is (= 2 (count children)) "the composition under test lost a child before it was rendered")
      (is (not (str/starts-with? composed did-not-render))
          (str "appkit's panel could not render the caller's composition at all — " composed))
      (doseq [child children]
        (is (str/includes? composed child)
            (str "a child did not survive appkit's panel as markup. appkit adds "
                 "options and never content, so this child must appear "
                 "byte-identical to its standalone render. Rendered alone: "
                 (pr-str child)))))))

(deftest composed-children-keep-caller-order-test
  (testing "the toolbar stays above the list: a pane's children arrive in the order the caller wrote them"
    ;; Separate from the assertion above because presence and order fail for
    ;; different reasons — a wrap that reverses `body` keeps every child whole.
    ;; `index-of` is nil when absent, so this reports "not in the markup"
    ;; rather than comparing against nil.
    (let [[composed [toolbar listing]] (composition)
          i (str/index-of composed toolbar)
          j (str/index-of composed listing)]
      (is (and i j (< i j))
          (str "appkit's panel did not keep its children in the caller's order "
               "(toolbar at " (pr-str i) ", list at " (pr-str j) "; nil means the "
               "child is not in the markup at all)")))))
