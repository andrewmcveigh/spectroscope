(def +project+ 'spectroscope)
(def +version+ "0.1.0-SNAPSHOT")

(def dependencies
  '[[org.clojure/clojure "1.9.0-alpha14"]
    [org.clojure/java.classpath "0.2.3"]
    [org.clojure/tools.analyzer.jvm "0.6.9"]
    [org.clojure/tools.namespace "0.3.0-alpha3"]
    [org.clojure/tools.reader "1.0.0-beta4"]])

(def dev-dependencies
  '[[org.clojure/test.check "0.9.0"]])

(set-env! :dependencies   dependencies
          :source-paths   #{"src"}
          :resource-paths #{"src"}
          :exclusions     '[org.clojure/clojure
                            org.clojure/test.check
                            org.clojure/tools.namespace
                            org.clojure/tools.reader])

(task-options!
 pom {:project +project+
      :version +version+
      :license {"Eclipse Public License"
                "http://www.eclipse.org/legal/epl-v10.html"}}
 push {:repo "clojars"})

(def test-paths
  (filter #(.exists (clojure.java.io/file %)) ["test" "test-resources"]))

(def dev-paths
  (concat test-paths
          (filter #(.exists (clojure.java.io/file %)) ["dev" "dev-resources"])))

(deftask dev
  "Dev profile"
  []
  (when (seq dev-dependencies)
    (set-env! :dependencies #(vec (concat % dev-dependencies))))
  (when (seq dev-paths)
    (set-env! :source-paths #(apply conj % dev-paths)))
  (fn [next-handler]
    (fn [fs]
      (next-handler fs))))

(defn cider? []
  (get (ns-publics 'boot.user) 'cider))

(ns-unmap 'boot.user 'test)
(deftask test []
  (set-env!
   :dependencies #(conj % '[adzerk/boot-test "1.1.2"])
   :source-paths #(apply conj % test-paths))
  (require 'adzerk.boot-test)
  (comp (dev) ((resolve 'adzerk.boot-test/test))))

(replace-task!
 [r repl] (comp ((or (cider?) (constantly identity))) (dev) r))

(deftask deploy []
  (comp (pom) (jar) (install) (push)))
