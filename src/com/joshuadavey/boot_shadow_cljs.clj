(ns com.joshuadavey.boot-shadow-cljs
  {:boot/export-tasks true}
  (:refer-clojure :exclude [compile])
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [boot.core :as boot :refer [deftask]]
            [boot.pod :as pod]
            [clojure.string :as str]
            [clojure.edn :as edn])
  (:import [java.util Properties]
           [java.io File]))

(defn path [& segments]
  (.getCanonicalFile (File. (str/join File/separator segments))))

(defn ensure-shadow [dir]
  (when-not (.exists (path dir "package.json"))
    (println "Initializing npm project...")
    (sh "npm" "init" "--force"))
  (when-not (.exists (path dir "node_modules" "shadow-cljs"))
    (println "Installing shadow-cljs npm project")
    (sh "npm" "install" "--save-dev" "shadow-cljs"))
  (when-not (.exists (path dir "shadow-cljs.edn"))
    (println "WARNING: no shadow-cljs.edn file found")))

(defn prepare-runtime [pod]
  (pod/with-eval-in pod
    (require
     '[clojure.string :as str]
     '[shadow.cljs.devtools.errors :as e]
     '[shadow.cljs.devtools.config :as config]
     '[shadow.cljs.devtools.api :as api])
    (import java.io.File)
    (defn file-path [& segments]
      (.getCanonicalFile (File. (str/join File/separator segments))))))

(defn- read-config []
  (let [c (path "." "shadow-cljs.edn")]
    (when (.exists c)
      (-> (slurp c)
          (edn/read-string)))))

(defn- make-pod [env]
  (pod/make-pod (-> env
                    (update :dependencies into [['org.clojure/clojure "1.9.0-RC1"]
                                                ['thheller/shadow-cljs "2.0.102"]])
                    (update :dependencies into (:dependencies (read-config))))))

(deftask release
  "Docs"
  [b build BUILD str "name of build"
   d directory DIRECTORY str "path to shadow-cljs project root (default current dir)"]
  (let [env (boot/get-env)
        pod (make-pod env)
        target (boot/tmp-dir!)
        cache (boot/cache-dir! ::cache)
        output (str target)
        build-name (keyword (or build "app"))
        dir (or directory (System/getProperty "user.dir"))]
    (ensure-shadow dir)
    (prepare-runtime pod)
    (boot/with-pre-wrap fileset
      (println "<< Building for release >>")
      (pod/with-eval-in pod
        (api/with-runtime
          (try
            (let [{:keys [output-dir] :as bc} (api/get-build-config ~build-name)
                  build-config (assoc bc
                                      :cache-root ~(str cache)
                                      :output-dir
                                      (str (file-path ~output output-dir)))]
              (api/release* build-config {}))
            :done
            (catch Exception e
              (e/user-friendly-error e)))))
      (-> fileset
          (boot/add-resource target)
          boot/commit!))))
