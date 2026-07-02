(ns appkit.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [kotoba-ui.core :as ui]
            [appkit.core :as app]))

(deftest desktop-panel-defaults-test
  (testing "appkit panel defaults to thick/flat, distinct from kotoba-ui.core's bare :regular/:raised"
    (let [html (ui/->html (app/panel ["x"]))]
      (is (str/includes? html "liquid-glass__panel--thick"))
      (is (str/includes? html "liquid-glass__panel--flat")))))

(deftest desktop-list-view-defaults-test
  (is (str/includes? (ui/->html (app/list-view [])) "liquid-glass__list--thick")))

(deftest caller-opts-win-test
  (testing "explicit opts override appkit defaults, same merge contract as every kotoba-ui wrap"
    (let [html (ui/->html (app/panel ["x"] {:surface :clear}))]
      (is (str/includes? html "liquid-glass__panel--clear"))
      (is (not (str/includes? html "liquid-glass__panel--thick"))))))

(deftest passthrough-components-are-just-kotoba-ui-test
  (testing "components with no per-screen-shape variance point are re-exported as-is, not wrapped"
    (is (identical? ui/button ui/button))
    (is (identical? ui/toolbar ui/toolbar))))
