(ns agent.health-test
  (:require
   [agent.health :as health]
   [clojure.test :refer :all]))

(defn- component-entry [registry component]
  (get-in (health/snapshot registry) [:components component]))

(deftest mark-ok-initializes-component-test
  (let [registry (health/create-registry [])]
    (health/mark-ok! registry :api)
    (let [entry (component-entry registry "api")]
      (is (= "ok" (:status entry)))
      (is (string? (:updated-at entry)))
      (is (string? (:last-ok entry)))
      (is (nil? (:last-error entry)))
      (is (= 0 (:restart-count entry))))))

(deftest error-recovery-clears-last-error-test
  (let [registry (health/create-registry [])]
    (health/mark-error! registry :sqlite "database locked")
    (is (= "error" (:status (component-entry registry "sqlite"))))
    (is (= "database locked" (:last-error (component-entry registry "sqlite"))))
    (health/mark-ok! registry :sqlite)
    (let [entry (component-entry registry "sqlite")]
      (is (= "ok" (:status entry)))
      (is (nil? (:last-error entry)))
      (is (string? (:last-ok entry))))))

(deftest restart-count-increments-test
  (let [registry (health/create-registry [])]
    (health/bump-restart! registry :runtime)
    (health/bump-restart! registry :runtime)
    (is (= 2 (:restart-count (component-entry registry "runtime"))))))

(deftest snapshot-contains-process-and-components-test
  (let [registry (health/create-registry [:api])]
    (health/mark-ok! registry :api)
    (let [snapshot (health/snapshot registry)]
      (is (pos-int? (:pid snapshot)))
      (is (string? (:updated-at snapshot)))
      (is (integer? (:uptime-seconds snapshot)))
      (is (= "ok" (get-in snapshot [:components "api" :status]))))))
