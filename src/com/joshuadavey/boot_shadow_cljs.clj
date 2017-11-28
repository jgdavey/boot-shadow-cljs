(ns com.joshuadavey.boot-shadow-cljs
  {:boot/export-tasks true}
  (:refer-clojure :exclude [compile])
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [boot.core :as boot :refer [deftask]]
            [boot.pod :as pod]
            [clojure.string :as str]
            [cheshire.core :as json]
            [clojure.edn :as edn])
  (:import [java.util Properties]
           [java.io File]))

(defn path [& segments]
  (.getCanonicalFile (File. (str/join File/separator segments))))

(defn shadow-project-paths [dir]
  {:config (path dir "shadow-cljs.edn")
   :node-module-package-json (path dir "node_modules" "shadow-cljs" "package.json")
   :package-json (path dir "package.json")})

(defn ensure-shadow [paths]
  (when-not (.exists (:config paths))
    (throw (ex-info "no shadow-cljs.edn file found")))
  (when-not (.exists (:package-json paths))
    (println "Initializing npm project...")
    (sh "npm" "init" "--force"))
  (when-not (.exists (:node-module-package-json paths))
    (println "Installing shadow-cljs npm project")
    (sh "npm" "install" "--save-dev" "shadow-cljs")))

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

(defn- read-config [^File config]
  (when (.exists config)
    (-> (slurp config)
        (edn/read-string))))

(defn shadow-cljs-version [package-json]
  (when (.exists package-json)
    (-> package-json
        slurp
        (json/parse-string true)
        :jar-version)))

(defn- make-pod [env config shadow-version]
  (pod/make-pod
   (-> env
       (update :dependencies into [['org.clojure/clojure "1.9.0-RC1"]
                                   ['thheller/shadow-cljs (or shadow-version "2.0.105")]])
       (update :dependencies into (:dependencies config)))))

(deftask release
  "Docs"
  [b build BUILD str "name of build"
   d directory DIRECTORY str "path to shadow-cljs project root (default current dir)"]
  (let [env (boot/get-env)
        target (boot/tmp-dir!)
        cache (boot/cache-dir! ::cache)
        output (str target)
        build-name (keyword (or build "app"))
        dir (or directory (System/getProperty "user.dir"))
        paths (shadow-project-paths dir)
        _ (ensure-shadow paths)
        pod (make-pod env
                      (read-config (:config paths))
                      (shadow-cljs-version (:node-module-package-json paths)))]
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
