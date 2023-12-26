(ns frontend.worker.pipeline
  "Pipeline work after transaction"
  (:require [datascript.core :as d]
            [logseq.outliner.datascript-report :as ds-report]
            [logseq.outliner.pipeline :as outliner-pipeline]
            [frontend.worker.react :as worker-react]))

(defn- path-refs-need-recalculated?
  [tx-meta]
  (when-let [outliner-op (:outliner-op tx-meta)]
    (not (or
          (contains? #{:collapse-expand-blocks :delete-blocks} outliner-op)
          (:undo? tx-meta) (:redo? tx-meta)))))

(defn compute-block-path-refs-tx
  [{:keys [tx-meta] :as tx-report} blocks]
  (when (and (:outliner-op tx-meta) (path-refs-need-recalculated? tx-meta))
    (outliner-pipeline/compute-block-path-refs-tx tx-report blocks)))

(defn- delete-property-parent-block-if-empty
  [tx-report deleted-block-uuids]
  (let [after-db (:db-after tx-report)
        empty-property-parents (->> (keep (fn [child-id]
                                            (let [e (d/entity (:db-before tx-report) [:block/uuid child-id])]
                                              (when (:created-from-property (:block/metadata (:block/parent e)))
                                                (let [parent-now (d/entity after-db (:db/id (:block/parent e)))]
                                                  (when (empty? (:block/_parent parent-now))
                                                    parent-now))))) deleted-block-uuids)
                                    distinct)]
    (when (seq empty-property-parents)
      (->>
       (mapcat (fn [b]
                 (let [{:keys [created-from-block created-from-property]} (:block/metadata b)
                       created-block (d/entity after-db [:block/uuid created-from-block])
                       properties (assoc (:block/properties created-block) created-from-property "")]
                   (when (and created-block created-from-property)
                     [[:db/retractEntity (:db/id b)]
                      [:db/add (:db/id created-block) :block/properties properties]])))
               empty-property-parents)
       (remove nil?)))))

(defn invoke-hooks
  [conn tx-report context]
  (let [tx-meta (:tx-meta tx-report)
        {:keys [from-disk? new-graph?]} tx-meta]
    (if (or from-disk? new-graph?)
      {:tx-report tx-report}
      (let [{:keys [pages blocks]} (ds-report/get-blocks-and-pages tx-report)
            deleted-block-uuids (set (outliner-pipeline/filter-deleted-blocks (:tx-data tx-report)))
            replace-tx (concat
                          ;; block path refs
                        (set (compute-block-path-refs-tx tx-report blocks))

                          ;; delete empty property parent block
                        (when (seq deleted-block-uuids)
                          (delete-property-parent-block-if-empty tx-report deleted-block-uuids))

                          ;; update block/tx-id
                        (let [updated-blocks (remove (fn [b] (contains? (set deleted-block-uuids)  (:block/uuid b))) blocks)
                              tx-id (get-in tx-report [:tempids :db/current-tx])]
                          (->>
                           (map (fn [b]
                                  (when-let [db-id (:db/id b)]
                                    {:db/id db-id
                                     :block/tx-id tx-id})) updated-blocks)
                           (remove nil?))))
            tx-report' (d/transact! conn replace-tx {:replace? true
                                                     :pipeline-replace? true})
            full-tx-data (concat (:tx-data tx-report) (:tx-data tx-report'))
            final-tx-report (assoc tx-report' :tx-data full-tx-data)
            affected-query-keys (when-not (:importing? context)
                                  (worker-react/get-affected-queries-keys final-tx-report context))]
        {:tx-report final-tx-report
         :replace-tx-data (:tx-data tx-report')
         :replace-tx-meta (:tx-meta tx-report')
         :affected-keys affected-query-keys
         :deleted-block-uuids deleted-block-uuids
         :pages pages
         :blocks blocks}))))