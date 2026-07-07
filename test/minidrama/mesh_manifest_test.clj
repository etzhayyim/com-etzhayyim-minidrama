(ns minidrama.mesh-manifest-test
  "Shape tests for the ON-MESH surface manifest (mesh/minidrama.app.edn).
  The guests themselves (mesh/*.clj) are kotoba-clj components — bare ns +
  kqe host imports — so they are NOT JVM-loadable and are exercised by
  `kotoba component build`, not here. What we can and do verify on the JVM:
  the manifest parses as EDN, carries the keys kotoba-lattice's
  manifest.rs reads, every :src file exists next to the manifest, and the
  trigger types are ones the lattice dispatches (http / tick / kse / cron)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(def manifest-file (io/file "mesh" "minidrama.app.edn"))

(defn- manifest [] (edn/read-string (slurp manifest-file)))

(deftest manifest-parses-and-names-the-app
  (let [m (manifest)]
    (is (= "minidrama" (:kotoba.app/name m)))
    (is (string? (:kotoba.app/version m)))))

(deftest components-have-src-triggers-and-caps
  (doseq [c (:kotoba.app/components (manifest))]
    (testing (:name c)
      (is (string? (:name c)))
      (is (.exists (io/file "mesh" (:src c)))
          (str ":src must exist next to the manifest: " (:src c)))
      (is (pos-int? (:scale c)))
      (is (seq (:triggers c)))
      (doseq [t (:triggers c)]
        (is (contains? #{:http :tick :kse :cron} (:type t))
            (str "unknown trigger type " (:type t))))
      (is (contains? (:requires c) :cap/kqe)))))

(deftest surface-is-profile-plus-heartbeat
  (let [names (set (map :name (:kotoba.app/components (manifest))))]
    (is (= #{"drama-profile" "drama-heartbeat"} names))))

(deftest http-route-is-the-actor-namespace
  (let [routes (for [c (:kotoba.app/components (manifest))
                     t (:triggers c)
                     :when (= :http (:type t))]
                 (:route t))]
    (is (= ["/minidrama/profile"] routes))))

(deftest placement-targets-the-edge-tribe
  (let [p (:kotoba.app/placement (manifest))]
    (is (= :zone (:spread p)))
    (is (= "edge" (get-in p [:require :tier])))))
