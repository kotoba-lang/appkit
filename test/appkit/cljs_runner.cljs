#!/usr/bin/env nbb
;; test/appkit/cljs_runner.cljs — run the portable test namespace on the
;; runtime appkit's consumers actually use.
;;
;; appkit is `.cljc` and its consumers are browsers, but `clojure -M:test` was
;; this repo's only runner. So a JVM-only regression left every JVM test green:
;; a `#?(:clj …)` guard around a wrapper, a `clojure.java.*` require, a reader
;; conditional whose :cljs branch drifts from the :clj one. Measured 2026-09-07
;; before this file existed — each of those three edits ran `clojure -M:test`
;; at 16 tests / 31 assertions / 0 failures. Under nbb the same suite refuses
;; to load the first two and fails the third.
;;
;; No second copy of the assertions: this loads `appkit.core-test` — the same
;; .cljc file `clojure -M:test` runs — under cljs.test. The `#?(:clj …)` tests
;; (the variance-point sweep, the public-var census, the with-redefs probe) do
;; not exist on this side, so the count is smaller than the JVM one on purpose.
;;
;; The classpath is not written down here. It is whatever `clojure -Spath`
;; resolves for deps.edn — the same pinned shas the JVM run uses — with the
;; jars dropped (nbb reads directories only). A hand-written sibling list would
;; rot the day a transitive dep moved; this one cannot.
;;
;; Usage:
;;   nbb test/appkit/cljs_runner.cljs               # aliases :test
;;   nbb test/appkit/cljs_runner.cljs :local:test   # local ../kotoba-ui override
;;
;; Exit: 0 green / 1 a test failed or the suite did not load (the reason is in
;; the output above the summary) / 2 REFUSED — the classpath could not be
;; resolved or the run measured nothing. 2 is not 0: a run that could not
;; happen must not print like a run that happened and found nothing.
(ns appkit.cljs-runner
  (:require ["node:child_process" :as cp]
            ["node:path" :as path]
            [clojure.string :as str]))

;; `*file*` is nbb's own binding (the script path); clj-kondo does not know it.
(def repo (path/resolve (path/dirname #_{:clj-kondo/ignore [:unresolved-symbol]} *file*) ".." ".."))
(def aliases (or (first *command-line-args*) ":test"))

(defn- sh [cmd args]
  (let [r (cp/spawnSync cmd (clj->js args)
                        #js {:cwd repo :encoding "utf8" :maxBuffer (* 64 1024 1024)})]
    {:out (str (.-stdout r))
     :err (str (.-stderr r)
               (when-let [e (.-error r)] (str "spawn failed: " (.-message e))))
     :code (if (nil? (.-status r)) 1 (.-status r))}))

(defn- refuse! [& msg]
  (binding [*print-fn* *print-err-fn*] (println "REFUSED:" (apply str msg)))
  (js/process.exit 2))

(def classpath
  (let [{:keys [out err code]} (sh "clojure" ["-Spath" (str "-A" aliases)])]
    (when-not (zero? code)
      (refuse! "clojure -Spath -A" aliases " exited " code "\n" err))
    (let [dirs (->> (str/split (str/trim out) #":")
                    (remove #(str/ends-with? % ".jar"))
                    vec)]
      (when-not (and (some #{"src"} dirs) (some #{"test"} dirs))
        (refuse! "classpath from clojure -Spath has no src + test: " (pr-str dirs)))
      (str/join ":" dirs))))

(def expr
  (str "(require '[cljs.test :as t] '[appkit.core-test])"
       "(defmethod t/report [:cljs.test/default :end-run-tests] [m]"
       "  (when-not (t/successful? m) (js/process.exit 1)))"
       "(t/run-tests 'appkit.core-test)"))

(let [{:keys [out err code]} (sh "nbb" ["--classpath" classpath "-e" expr])
      text (str out err)
      [_ tests assertions] (re-find #"Ran (\d+) tests containing (\d+) assertions" text)
      n (fn [s] (if s (js/parseInt s 10) 0))]
  (print text)
  (cond
    (not (zero? code))
    (js/process.exit 1)

    ;; Evidence floor: a namespace that loaded but ran nothing prints
    ;; "0 failures, 0 errors" exactly like one that ran everything.
    (or (zero? (n tests)) (zero? (n assertions)))
    (refuse! "the cljs run measured nothing (tests=" tests " assertions=" assertions ")")

    :else
    (do (println (str "cljs: " tests " tests / " assertions " assertions on nbb"))
        (js/process.exit 0))))
