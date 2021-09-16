(ns cljs-http.core
  (:import [goog.net EventType XhrIo]
           [goog.net Jsonp])
  (:require [cljs-http.util :as util]
            [promesa.core :as prom]))

(defn- aborted? [xhr]
  (= (.getLastErrorCode xhr) goog.net.ErrorCode.ABORT))

(defn apply-default-headers!
  "Takes an XhrIo object and applies the default-headers to it."
  [xhr headers]
  (let [formatted-h (zipmap (map util/camelize (keys headers)) (vals headers))]
    (dorun
      (map (fn [[k v]]
             (.set (.-headers xhr) k v))
           formatted-h))))

(defn apply-response-type!
  "Takes an XhrIo object and sets response-type if not nil."
  [xhr response-type]
  (.setResponseType xhr
   (case response-type
     :array-buffer XhrIo.ResponseType.ARRAY_BUFFER
     :blob XhrIo.ResponseType.BLOB
     :document XhrIo.ResponseType.DOCUMENT
     :text XhrIo.ResponseType.TEXT
     :default XhrIo.ResponseType.DEFAULT
     nil XhrIo.ResponseType.DEFAULT)))

(defn build-xhr
  "Builds an XhrIo object from the request parameters."
  [{:keys [with-credentials? default-headers response-type] :as request}]
  (let [timeout (or (:timeout request) 0)
        send-credentials (if (nil? with-credentials?)
                           true
                           with-credentials?)]
    (doto (XhrIo.)
          (apply-default-headers! default-headers)
          (apply-response-type! response-type)
          (.setTimeoutInterval timeout)
          (.setWithCredentials send-credentials))))

;; goog.net.ErrorCode constants to CLJS keywords
(def error-kw
  {0 :no-error
   1 :access-denied
   2 :file-not-found
   3 :ff-silent-error
   4 :custom-error
   5 :exception
   6 :http-error
   7 :abort
   8 :timeout
   9 :offline})

(defn xhr
  "Execute the HTTP request corresponding to the given Ring request
  map and return a promesa promise."
  [{:keys [request-method headers body progress cancel] :as request}]
  (let [prom (prom/deferred)
        request-url (util/build-url request)
        method (name (or request-method :get))
        headers (util/build-headers headers)
        xhr (build-xhr request)]
    (.listen xhr EventType.COMPLETE
             (fn [evt]
               (let [target (.-target evt)
                     response {:status (.getStatus target)
                               :success (.isSuccess target)
                               :body (.getResponse target)
                               :headers (util/parse-headers (.getAllResponseHeaders target))
                               :trace-redirects [request-url (.getLastUri target)]
                               :error-code (error-kw (.getLastErrorCode target))
                               :error-text (.getLastError target)}]
                 (if (aborted? xhr)
                   (prom/resolve! prom nil)
                   (prom/resolve! prom response)))))

    (when progress
      (println "progress is on")
      (let [listener (fn [direction evt]
                       (println "pogress!" direction evt)
                       (progress (merge {:direction direction :loaded (.-loaded evt)}
                                        (when (.-lengthComputable evt) {:total (.-total evt)}))))]
        (doto xhr
          (.setProgressEventsEnabled true)
          (.listen EventType.UPLOAD_PROGRESS (partial listener :upload))
          (.listen EventType.DOWNLOAD_PROGRESS (partial listener :download)))))

    (when cancel
      (prom/then cancel
                 (fn [_]
                   (when (not (.isComplete xhr))
                     (.abort xhr)))))

    (.send xhr request-url method body headers)

    prom))

(defn jsonp
  "Execute the JSONP request corresponding to the given Ring request
  map and return a promesa promise."
  [{:keys [timeout callback-name keywordize-keys? cancel]
    :or {keywordize-keys? true}
    :as request}]
  (let [prom (prom/deferred)
        jsonp (Jsonp. (util/build-url request) callback-name)]
    (.setRequestTimeout jsonp timeout)
    (let [req (.send jsonp nil
                     (fn success-callback [data]
                       (let [response {:status 200
                                       :success true
                                       :body (js->clj data :keywordize-keys keywordize-keys?)}]
                         (prom/resolve! prom response)))
                     (fn error-callback [error]
                       (prom/reject! prom error)))]

      (when cancel
        (prom/then cancel (fn [_]
                            (.cancel jsonp req)))))

    prom))

(defn request
  "Execute the HTTP request corresponding to the given Ring request
  map and return a core.async channel."
  [{:keys [request-method] :as request}]
  (if (= request-method :jsonp)
    (jsonp request)
    (xhr request)))
