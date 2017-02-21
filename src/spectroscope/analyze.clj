(ns spectroscope.analyze
  (:refer-clojure :exclude [alias])
  (:require
   [clojure.spec :as s]
   [clojure.string :as string]
   [clojure.java.classpath :as cp]
   [clojure.java.io :as io]
   [clojure.tools.analyzer :as ana]
   [clojure.tools.namespace :as nsp]
   [clojure.tools.namespace.find :as nsf]
   [clojure.tools.namespace.parse :as p]
   [clojure.tools.reader :as r]
   [clojure.walk :as walk]
   [clojure.set :as set])
  (:import
   [java.io PushbackReader]
   [java.net URI]
   [java.util.jar JarFile]))

(def ns-sources (atom nil))

(defn pb-reader [uri]
  (PushbackReader. (io/reader uri)))

(defn find-clojure-sources
  ([files]
   (find-clojure-sources files nil))
  ([files platform]
   (concat
    (mapcat #(nsf/find-clojure-sources-in-dir %)
            (filter #(.isDirectory ^java.io.File %) files))
    (mapcat #(nsf/sources-in-jar % platform)
            (map #(java.util.jar.JarFile. (io/file %))
                 (filter cp/jar-file? files))))))

(defn clj-source-files-on-classpath []
  (find-clojure-sources (cp/classpath (.get clojure.lang.Compiler/LOADER))))

(defn jar-file?
  "Returns true if file is a normal file with a .jar or .JAR extension."
  [f]
  (let [file (io/file f)]
    (and (.isFile file)
         (.endsWith (.. file getName toLowerCase) ".jar"))))

(defn get-clojure-sources-in-jar
  [^JarFile jar]
  (let [path-to-jar (.getName jar)]
    (map #(java.net.URI. (str "jar:file:" path-to-jar "!/" %))
         (nsf/clojure-sources-in-jar jar))))

(defn refers-clojure-spec? [f]
  (re-find #"clojure\.spec" (slurp f)))

(defn clojure-source-files-in-jars-on-cp []
  (let [jars-on-cp (map #(JarFile. %) (filter jar-file? (cp/classpath)))]
    (->> jars-on-cp
         (mapcat get-clojure-sources-in-jar)
         (map (fn [x] {:type :jar :source x})))))

(defn clojure-source-files-on-cp []
  (let [dirs-on-cp (filter #(.isDirectory %) (cp/classpath))]
    (->> dirs-on-cp
         (mapcat nsf/find-clojure-sources-in-dir)
         (map (fn [x] {:type :file :source x})))))

(defn file-seq->ns-map [files]
  (->> files
       (map (juxt (comp p/name-from-ns-decl p/read-ns-decl pb-reader :source)
                  identity))
       (remove (comp nil? first))
       (into {})))

(defn build-ns-source-map! []
  (let [file-nss    (file-seq->ns-map (clojure-source-files-on-cp))
        jarfile-nss (file-seq->ns-map (clojure-source-files-in-jars-on-cp))]
    (reset! ns-sources (merge file-nss jarfile-nss))))

(defn get-ns-sources []
  (or @ns-sources (build-ns-source-map!)))

(defn ns-source [ns-sym]
  (get (get-ns-sources) ns-sym))

(defn ns-reader [ns-sym]
  (->> ns-sym ns-source :source pb-reader))

(defn namespaces-in-project []
  (->> (get-ns-sources)
       (filter (comp #{:file} :type val))
       (into {})))

(defn path [sym]
  (-> sym
      name
      (string/split #"\.")
      (->> (string/join java.io.File/separator))
      (str ".clj")))

(defn load-namespaces-in-project []
  (doseq [[ns {:keys [source]}] (namespaces-in-project)]
    (with-open [r (io/reader source)]
      (load-reader r))))

(s/def :clojure.core/ns-decl
  (s/cat :ns simple-symbol?
         :name simple-symbol?
         :docstring (s/? string?)
         :attr-map (s/? map?)
         :clauses :clojure.core.specs/ns-clauses))

(defn ns-dep-children [ns]
  (->> ns
       (ns-reader)
       (p/read-ns-decl)
       (s/conform :clojure.core/ns-decl)
       (:clauses)
       (filter (comp #{:require :use} first))
       (mapcat (comp :libs second))
       (map (fn [[k v]]
              (if (= k :lib) v (:prefix v))))))

(defn ns-dep-decendents [ns]
  (letfn [(f [ns]
            (if-let [children (seq (ns-dep-children ns))]
              (concat (mapcat f children ) children) '()))]
    (set (f ns))))

(defn reachable-namespaces []
  (let [ns-in-p (namespaces-in-project)]
    (->> ns-in-p
         (keys)
         (map ns-dep-decendents)
         (reduce set/union (set (keys ns-in-p))))))

(def EOF (Object.))

(defn parse-aliases [ns-decl]
  (->> ns-decl
       :clauses
       (filter (comp (partial = :require) first))
       (map (fn [[_ {:keys [libs]}]]
              (->> libs
                   (map (fn [[t m]]
                          (if (= t :prefix-list)
                            [(:as (:refer m)) (:prefix m)])))
                   (into {}))))
       (reduce merge)))

(defn resolve-alias [aliases ns-name x]
  (if (symbol? x)
    (if (.contains (name x) ".")
      x
      (or (if-let [v (ns-resolve ns-name x)]
            (let [m (meta v)
                  ns (:ns m)]
              (if ns
                (symbol (str ns) (name (:name m)))
                x)))
          (if-let [a (some->> x namespace symbol (get aliases))]
            (symbol (name a) (name x))
            x)
          x))
    x))

(defn spec-defs-in-ns [ns-name]
  (with-open [rdr (ns-reader ns-name)]
    [ns-name
     (loop [specs {}
            aliases {}]
       (let [form (binding [*ns* ns-name r/*alias-map* aliases]
                    (r/read {:eof EOF :read-cond :allow} rdr))]
         (if (= EOF form)
           specs
           (if (seq? form)
             (let [[op arg1 arg2 :as form]
                   (walk/postwalk #(resolve-alias {} ns-name %) form)]
               (cond (= 'alias op)
                     (recur specs (assoc aliases arg1 arg2))
                     (and (contains? `#{s/def s/fdef} op)
                          (seq? arg2))
                     (recur (assoc specs arg1 arg2) aliases)
                     :else
                     (recur specs aliases)))
             (recur specs aliases)))))]))

(defn all-specs []
  (->> (reachable-namespaces)
       (map (comp second spec-defs-in-ns))
       (remove empty?)
       (reduce merge {})))


(comment

  (s/def :test/nilllllable (s/nilable :clojure.core.specs/arg-list))
  (time (all-specs))

  )
