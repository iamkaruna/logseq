(ns frontend.persist-db
   "Backend of DB based graph"
   (:require [frontend.persist-db.browser :as browser]
             [frontend.persist-db.node :as node]
             [frontend.persist-db.protocol :as protocol]
             [frontend.util :as util]
             [promesa.core :as p]))


 (defonce electron-ipc-sqlite-db (node/->ElectronIPC))

 (defonce opfs-db (browser/->InBrowser))

 (defn- get-impl
   "Get the actual implementation of PersistentDB"
   []
   (cond
     (util/electron?)
     electron-ipc-sqlite-db

     :else
     opfs-db))

 (defn <list-db []
   (protocol/<list-db (get-impl)))

 (defn <unsafe-delete [repo]
   (protocol/<unsafe-delete (get-impl) repo))

(defn <new [repo]
  {:pre [(<= (count repo) 56)]}
  (protocol/<new (get-impl) repo))

(defn <transact-data [repo tx-data tx-meta]
  (protocol/<transact-data (get-impl) repo tx-data tx-meta))

(defn <fetch-init-data
  ([repo]
   (<fetch-init-data repo {}))
  ([repo opts]
   (p/let [ret (protocol/<fetch-initital-data (get-impl) repo opts)]
     (js/console.log "fetch-initital" ret)
     ret)))