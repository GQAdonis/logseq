(ns electron.handler
  (:require ["electron" :refer [ipcMain dialog app autoUpdater shell]]
            [cljs-bean.core :as bean]
            ["fs" :as fs]
            ["buffer" :as buffer]
            ["fs-extra" :as fs-extra]
            ["path" :as path]
            ["os" :as os]
            ["diff-match-patch" :as google-diff]
            ["/electron/utils" :as js-utils]
            [electron.fs-watcher :as watcher]
            [electron.configs :as cfgs]
            [promesa.core :as p]
            [clojure.string :as string]
            [electron.utils :as utils]
            [electron.state :as state]
            [clojure.core.async :as async]
            [electron.search :as search]
            [electron.git :as git]
            [electron.plugin :as plugin]
            [electron.window :as win]
            [electron.file-sync-rsapi :as rsapi]
            [cljs.reader :as reader]))

(defmulti handle (fn [_window args] (keyword (first args))))

(defmethod handle :mkdir [_window [_ dir]]
  (fs/mkdirSync dir))

(defmethod handle :mkdir-recur [_window [_ dir]]
  (fs/mkdirSync dir #js {:recursive true}))

;; {encoding: 'utf8', withFileTypes: true}
(defn- readdir
  [dir]
  (->> (tree-seq
        (fn [^js f]
          (.isDirectory (fs/statSync f) ()))
        (fn [d]
          (let [files (fs/readdirSync d (clj->js {:withFileTypes true}))]
            (->> files
                 (remove #(.isSymbolicLink ^js %))
                 (remove #(string/starts-with? (.-name ^js %) "."))
                 (map #(.join path d (.-name %))))))
        dir)
       (doall)
       (vec)))

(defmethod handle :readdir [_window [_ dir]]
  (readdir dir))

(defmethod handle :unlink [_window [_ repo path]]
  (if (plugin/dotdir-file? path)
    (fs/unlinkSync path)
    (let [file-name   (-> (string/replace path (str repo "/") "")
                          (string/replace "/" "_")
                          (string/replace "\\" "_"))
          recycle-dir (str repo "/logseq/.recycle")
          _           (fs-extra/ensureDirSync recycle-dir)
          new-path    (str recycle-dir "/" file-name)]
      (fs/renameSync path new-path))))

(defonce Diff (google-diff.))
(defn string-some-deleted?
  [old new]
  (let [result (.diff_main Diff old new)]
    (some (fn [a] (= -1 (first a))) result)))

(defn- truncate-old-versioned-files!
  [dir]
  (let [files (fs/readdirSync dir (clj->js {:withFileTypes true}))
        files (map #(.-name %) files)
        old-versioned-files (drop 3 (reverse (sort files)))]
    (doseq [file old-versioned-files]
      (fs-extra/removeSync (path/join dir file)))))

(defn- get-backup-dir
  [repo path]
  (let [path (string/replace path repo "")
        bak-dir (str repo "/logseq/bak")
        path (str bak-dir path)
        parsed-path (path/parse path)]
    (path/join (.-dir parsed-path)
               (.-name parsed-path))))

(defn backup-file
  [repo path content]
  (let [path-dir (get-backup-dir repo path)
        ext (path/extname path)
        new-path (path/join path-dir
                            (str (string/replace (.toISOString (js/Date.)) ":" "_")
                                 ext))]
    (fs-extra/ensureDirSync path-dir)
    (fs/writeFileSync new-path content)
    (fs/statSync new-path)
    (truncate-old-versioned-files! path-dir)
    new-path))

(defmethod handle :backupDbFile [_window [_ repo path db-content new-content]]
  (when (and (string? db-content)
             (string? new-content)
             (string-some-deleted? db-content new-content))
    (backup-file repo path db-content)))

(defmethod handle :openFileBackupDir [_window [_ repo path]]
  (when (string? path)
    (let [dir (get-backup-dir repo path)]
      (.openPath shell dir))))

(defmethod handle :readFile [_window [_ path]]
  (utils/read-file path))

(defn writable?
  [path]
  (assert (string? path))
  (try
    (fs/accessSync path (aget fs "W_OK"))
    (catch :default _e
      false)))

(defmethod handle :writeFile [window [_ repo path content]]
  (let [^js Buf (.-Buffer buffer)
        ^js content (if (instance? js/ArrayBuffer content)
                      (.from Buf content)
                      content)]
    (try
      (when (and (fs/existsSync path) (not (writable? path)))
        (fs/chmodSync path "644"))
      (fs/writeFileSync path content)
      (fs/statSync path)
      (catch :default e
        (let [backup-path (try
                            (backup-file repo path content)
                            (catch :default e
                              (println "Backup file failed")
                              (js/console.dir e)))]
          (utils/send-to-renderer window "notification" {:type "error"
                                                         :payload (str "Write to the file " path
                                                                       " failed, "
                                                                       e
                                                                       (when backup-path
                                                                         (str ". A backup file was saved to "
                                                                              backup-path
                                                                              ".")))}))))))

(defmethod handle :rename [_window [_ old-path new-path]]
  (fs/renameSync old-path new-path))

(defmethod handle :stat [_window [_ path]]
  (fs/statSync path))

(defonce allowed-formats
  #{:org :markdown :md :edn :json :js :css :excalidraw})

(defn get-ext
  [p]
  (-> (.extname path p)
      (subs 1)
      keyword))

(defn- get-files
  [path]
  (let [result (->>
                (readdir path)
                (remove (partial utils/ignored-path? path))
                (filter #(contains? allowed-formats (get-ext %)))
                (map (fn [path]
                       (let [stat (fs/statSync path)]
                         (when-not (.isDirectory stat)
                           {:path    (utils/fix-win-path! path)
                            :content (utils/read-file path)
                            :stat    stat}))))
                (remove nil?))]
    (vec (cons {:path (utils/fix-win-path! path)} result))))

(defn open-dir-dialog []
  (p/let [result (.showOpenDialog dialog (bean/->js
                                          {:properties ["openDirectory" "createDirectory" "promptToCreate"]}))
          result (get (js->clj result) "filePaths")]
    (p/resolved (first result))))

(defmethod handle :openDir [^js _window _messages]
  (p/let [path (open-dir-dialog)]
    (if path
      (p/resolved (bean/->js (get-files path)))
      (p/rejected (js/Error "path empty")))))

(defmethod handle :getFiles [_window [_ path]]
  (get-files path))

(defn- sanitize-graph-name
  [graph-name]
  (when graph-name
    (-> graph-name
        (string/replace "/" "++")
        (string/replace ":" "+3A+"))))

(defn- graph-name->path
  [graph-name]
  (when graph-name
    (-> graph-name
        (string/replace "+3A+" ":")
        (string/replace "++" "/"))))

(defn- get-graphs-dir
  []
  (let [dir (if utils/ci?
              (.resolve path js/__dirname "../tmp/graphs")
              (.join path (.homedir os) ".logseq" "graphs"))]
    (fs-extra/ensureDirSync dir)
    dir))

(defn- get-graphs
  "Returns all graph names in the cache directory (strating with `logseq_local_`)"
  []
  (let [dir (get-graphs-dir)]
    (->> (readdir dir)
         (remove #{dir})
         (map #(path/basename % ".transit"))
         (map graph-name->path))))

;; TODO support alias mechanism
(defn get-graph-name
  "Given a graph's name of string, returns the graph's fullname.
   E.g., given `cat`, returns `logseq_local_<path_to_directory>/cat`
   Returns `nil` if no such graph exists."
  [graph-identifier]
  (->> (get-graphs)
       (some #(when (string/ends-with? (utils/normalize-lc %)
                                       (str "/" (utils/normalize-lc graph-identifier)))
                %))))

(defmethod handle :getGraphs [_window [_]]
  (get-graphs))

(defmethod handle :inflateGraphsInfo [_win [_ graphs]]
  (if (seq graphs)
    (for [{:keys [root] :as graph} graphs
          :let [sf #(.join path % "logseq/graphs-txid.edn")]]
      (try
        (if-let [sync-meta (and (not (string/blank? root))
                                  (.toString (.readFileSync fs (sf root))))]
          (assoc graph :sync-meta (reader/read-string sync-meta))
          graph)
        (catch js/Error _e
          (js/console.debug "[read txid meta] #" (sf root) (.-message _e))
          graph)))
    []))

(defn- get-graph-path
  [graph-name]
  (when graph-name
    (let [graph-name (sanitize-graph-name graph-name)
          dir (get-graphs-dir)]
      (.join path dir (str graph-name ".transit")))))

(defn- get-serialized-graph
  [graph-name]
  (when graph-name
    (when-let [file-path (get-graph-path graph-name)]
      (when (fs/existsSync file-path)
        (utils/read-file file-path)))))

(defmethod handle :getSerializedGraph [_window [_ graph-name]]
  (get-serialized-graph graph-name))

(defmethod handle :saveGraph [_window [_ graph-name value-str]]
  ;; NOTE: graph-name is a plain "local" for demo graph.
  (when (and graph-name value-str (not (= "local" graph-name)))
    (when-let [file-path (get-graph-path graph-name)]
      (fs/writeFileSync file-path value-str))))

(defmethod handle :deleteGraph [_window [_ graph-name]]
  (when graph-name
    (when-let [file-path (get-graph-path graph-name)]
      (when (fs/existsSync file-path)
        (fs-extra/removeSync file-path)))))

(defmethod handle :persistent-dbs-saved [_window _]
  (async/put! state/persistent-dbs-chan true)
  true)

(defmethod handle :search-blocks [_window [_ repo q opts]]
  (search/search-blocks repo q opts))

(defmethod handle :rebuild-blocks-indice [_window [_ repo data]]
  (search/truncate-blocks-table! repo)
  ;; unneeded serialization
  (search/upsert-blocks! repo (bean/->js data))
  (search/write-search-version! repo)
  [])

(defmethod handle :transact-blocks [_window [_ repo data]]
  (let [{:keys [blocks-to-remove-set blocks-to-add]} data]
    (when (seq blocks-to-remove-set)
      (search/delete-blocks! repo blocks-to-remove-set))
    (when (seq blocks-to-add)
      ;; unneeded serialization
      (search/upsert-blocks! repo (bean/->js blocks-to-add)))))

(defmethod handle :truncate-blocks [_window [_ repo]]
  (search/truncate-blocks-table! repo))

(defmethod handle :remove-db [_window [_ repo]]
  (search/delete-db! repo))

(defn clear-cache!
  [window]
  (let [graphs-dir (get-graphs-dir)]
    (fs-extra/removeSync graphs-dir))

  (let [path (.getPath ^object app "userData")]
    (doseq [dir ["search" "IndexedDB"]]
      (let [path (path/join path dir)]
        (try
          (fs-extra/removeSync path)
          (catch js/Error e
            (js/console.error e)))))
    (utils/send-to-renderer window "redirect" {:payload {:to :home}})))

(defmethod handle :clearCache [window _]
  (search/close!)
  (clear-cache! window)
  (search/ensure-search-dir!))

(defmethod handle :openDialog [^js _window _messages]
  (open-dir-dialog))

(defmethod handle :getLogseqDotDirRoot []
  (utils/get-ls-dotdir-root))

(defmethod handle :testProxyUrl [win [_ url]]
  (p/let [_ (utils/fetch url)]
    (utils/send-to-renderer win :notification {:type "success" :payload (str "Successfully: " url)})))

(defmethod handle :getUserDefaultPlugins []
  (utils/get-ls-default-plugins))

(defmethod handle :relaunchApp []
  (.relaunch app) (.quit app))

(defmethod handle :quitApp []
  (.quit app))

(defmethod handle :userAppCfgs [_window [_ k v]]
  (let [config (cfgs/get-config)]
    (if-not k
      config
      (if-not (nil? v)
        (cfgs/set-item! (keyword k) v)
        (cfgs/get-item (keyword k))))))

(defmethod handle :getDirname [_]
  js/__dirname)

(defmethod handle :getAppBaseInfo [^js win [_ _opts]]
  {:isFullScreen (.isFullScreen win)})

(defmethod handle :getAssetsFiles [^js win [_ {:keys [exts]}]]
  (when-let [graph-path (state/get-window-graph-path win)]
    (p/let [^js files (js-utils/getAllFiles (.join path graph-path "assets") (clj->js exts))]
           files)))

(defn close-watcher-when-orphaned!
  "When it's the last window for the directory, close the watcher."
  [window graph-path]
  (when (not (win/graph-has-other-windows? window graph-path))
    (watcher/close-watcher! graph-path)))

(defn set-current-graph!
  [window graph-path]
  (let [old-path (state/get-window-graph-path window)]
    (when old-path (close-watcher-when-orphaned! window old-path))
    (swap! state/state assoc :graph/current graph-path)
    (swap! state/state assoc-in [:window/graph window] graph-path)
    nil))

(defmethod handle :setCurrentGraph [^js window [_ graph-name]]
  (when graph-name
    (set-current-graph! window (utils/get-graph-dir graph-name))))

(defmethod handle :runGit [_ [_ args]]
  (when (seq args)
    (git/raw! args)))

(defmethod handle :runGitWithinCurrentGraph [_ [_ args]]
  (when (seq args)
    (git/init!)
    (git/run-git2! (clj->js args))))

(defmethod handle :gitCommitAll [_ [_ message]]
  (git/add-all-and-commit! message))

(defmethod handle :installMarketPlugin [_ [_ mft]]
  (plugin/install-or-update! mft))

(defmethod handle :updateMarketPlugin [_ [_ pkg]]
  (plugin/install-or-update! pkg))

(defmethod handle :uninstallMarketPlugin [_ [_ id]]
  (plugin/uninstall! id))

(defmethod handle :quitAndInstall []
  (.quitAndInstall autoUpdater))

(defmethod handle :graphUnlinked [^js _win [_ repo]]
  (doseq [window (win/get-all-windows)]
    (utils/send-to-renderer window "graphUnlinked" (bean/->clj repo))))

(defmethod handle :dbsync [^js _win [_ graph tx-data]]
  (let [dir (utils/get-graph-dir graph)]
    (doseq [window (win/get-graph-all-windows dir)]
      (utils/send-to-renderer window "dbsync"
                              (bean/->clj {:graph graph
                                           :tx-data tx-data})))))

(defmethod handle :graphHasOtherWindow [^js win [_ graph]]
  (let [dir (utils/get-graph-dir graph)]
    (win/graph-has-other-windows? win dir)))

(defmethod handle :graphHasMultipleWindows [^js _win [_ graph]]
  (let [dir (utils/get-graph-dir graph)
        windows (win/get-graph-all-windows dir)]
    (> (count windows) 1)))

(defmethod handle :addDirWatcher [^js window [_ dir]]
  ;; receive dir path (not repo / graph) from frontend
  ;; Windows on same dir share the same watcher
  ;; Only close file watcher when:
  ;;    1. there is no one window on the same dir
  ;;    2. reset file watcher to resend `add` event on window refreshing
  (when dir
    ;; adding dir watcher when the window has watcher already - must be cmd + r refreshing
    ;; maintain only one file watcher when multiple windows on the same dir
    (close-watcher-when-orphaned! window dir)
    (watcher/watch-dir! window dir)))

(defn open-new-window!
  []
  (let [win (win/create-main-window)]
    (win/on-close-actions! win close-watcher-when-orphaned!)
    (win/setup-window-listeners! win)
    win))

(defmethod handle :openNewWindow [_window [_]]
  (open-new-window!)
  nil)

(defmethod handle :graphReady [window [_ graph-name]]
  (when-let [f (:window/once-graph-ready @state/state)]
    (f window graph-name)
    (state/set-state! :window/once-graph-ready nil)))

(defmethod handle :searchVersionChanged?
  [^js _win [_ graph]]
  (search/version-changed? graph))


(defmethod handle :reloadWindowPage [^js win]
  (when-let [web-content (.-webContents win)]
    (.reload web-content)))


(defmethod handle :setHttpsAgent [^js _win [_ opts]]
  (utils/set-fetch-agent opts))

;;;;;;;;;;;;;;;;;;;;;;;
;; file-sync-rs-apis ;;
;;;;;;;;;;;;;;;;;;;;;;;

(defmethod handle :set-env [_ args]
  (apply rsapi/set-env (rest args)))

(defmethod handle :get-local-files-meta [_ args]
  (apply rsapi/get-local-files-meta (rest args)))

(defmethod handle :get-local-all-files-meta [_ args]
  (apply rsapi/get-local-all-files-meta (rest args)))

(defmethod handle :rename-local-file [_ args]
  (apply rsapi/rename-local-file (rest args)))

(defmethod handle :delete-local-files [_ args]
  (apply rsapi/delete-local-files (rest args)))

(defmethod handle :update-local-files [_ args]
  (apply rsapi/update-local-files (rest args)))

(defmethod handle :delete-remote-files [_ args]
  (apply rsapi/delete-remote-files (rest args)))

(defmethod handle :update-remote-file [_ args]
  (apply rsapi/update-remote-file (rest args)))

(defmethod handle :update-remote-files [_ args]
  (apply rsapi/update-remote-files (rest args)))

(defmethod handle :default [args]
  (println "Error: no ipc handler for: " (bean/->js args)))

(defn broadcast-persist-graph!
  "Sends persist graph event to the renderer contains the target graph.
   Returns a promise."
  [graph-name]
  (p/create (fn [resolve _reject]
              (let [graph-path (utils/get-graph-dir graph-name)
                    windows (win/get-graph-all-windows graph-path)
                    tar-graph-win (first windows)]
                (if tar-graph-win
                  ;; if no such graph, skip directly
                  (do (state/set-state! :window/once-persist-done #(resolve nil))
                      (utils/send-to-renderer tar-graph-win "persistGraph" graph-name))
                  (resolve nil))))))

(defmethod handle :broadcastPersistGraph [^js _win [_ graph-name]]
  (broadcast-persist-graph! graph-name))

(defmethod handle :broadcastPersistGraphDone [^js _win [_]]
  ;; main process -> renderer doesn't support promise, so we use a global var to store the callback
  (when-let [f (:window/once-persist-done @state/state)]
    (f)
    (state/set-state! :window/once-persist-done nil)))

(defn set-ipc-handler! [window]
  (let [main-channel "main"]
    (.handle ipcMain main-channel
             (fn [^js event args-js]
               (try
                 (let [message (bean/->clj args-js)]
                   (bean/->js (handle (or (utils/get-win-from-sender event) window) message)))
                 (catch js/Error e
                   (when-not (contains? #{"mkdir" "stat"} (nth args-js 0))
                     (println "IPC error: " {:event event
                                             :args args-js}
                              e))
                   e))))
    #(.removeHandler ipcMain main-channel)))
