(ns job-streamer.agent.component.runtime
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.data.xml :as xml]
            [environ.core :refer [env]]
            [com.stuartsierra.component :as component]
            [liberator.core :as liberator]
            (job-streamer.agent [entity :refer [make-job to-xml]]))
  (:import [java.util UUID Properties]
           [javax.batch.runtime BatchRuntime]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [net.unit8.wscl WebSocketClassLoader]))

(defonce default-instance-id (UUID/randomUUID))

(defn tracer-bullet-fn [])

(defmacro with-classloader [loader & body]
  `(let [original-loader# (.getContextClassLoader (Thread/currentThread))]
     (try
       (.setContextClassLoader (Thread/currentThread) ~loader)
       ~@body
       (finally
         (.setContextClassLoader (Thread/currentThread) original-loader#)))))

(defn find-loader [{:keys [classloaders base-url]} class-loader-id]
  (log/info "find-loader " class-loader-id)
  (if-let [wscl (get @classloaders (or class-loader-id :default))]
    wscl
    (let [wscl (WebSocketClassLoader.
                (str @base-url (when class-loader-id (str "?classLoaderId=" (.toString class-loader-id))))
                (.getClassLoader (class tracer-bullet-fn)))]
      (log/info "ClassLoader URL=" (str @base-url (when class-loader-id (str "?classLoaderId=" (.toString class-loader-id)))))
      (swap! classloaders assoc (or class-loader-id :default) wscl)
      wscl)))

(defn- body-as-string [ctx]
  (if-let [body (get-in ctx [:request :body])]
    (condp instance? body
      java.lang.String body
      (slurp (io/reader body)))))

(defn- parse-edn [context key]
  (when (#{:put :post} (get-in context [:request :request-method]))
    (try
      (if-let [body (body-as-string context)]
        (let [data (edn/read-string body)]
          [false {key data}])
        {:message "No body"})
      (catch Exception e
        (.printStackTrace e)
        {:message (format "IOException: %s" (.getMessage e))}))))

(defn- keywordize-status [execution]
  (keyword "batch-status"
           (.. execution getBatchStatus name toLowerCase)))

(defn jobs-resource [{:keys [job-operator] :as runtime}]
  (liberator/resource
   :available-media-types ["application/edn"]
   :allowed-methods [:get :post]
   :malformed? #(parse-edn % ::data)
   :post! (fn [ctx]
            (let [job-file (Files/createTempFile "job" ".xml"
                                                 (into-array FileAttribute []))
                  job (-> (make-job (get-in ctx [::data :job]))
                          (assoc-in [:properties :request-id]
                                    (get-in ctx [::data :request-id])))
                  parameters (Properties.)
                  loader (find-loader runtime (get-in ctx [::data :class-loader-id]))]
              (try
                (spit (.toFile job-file) (xml/emit-str (to-xml job)))
                (doseq [[k v] (get-in ctx [::data :parameters])]
                  (.setProperty parameters (name k) (str v)))
                (let [execution-id (with-classloader loader
                                     (.start job-operator
                                             (.. job-file toAbsolutePath toString)
                                             parameters))
                      execution (with-classloader loader
                                  (.getJobExecution job-operator execution-id))]
                  {:execution-id execution-id
                   :batch-status (keywordize-status execution)
                   :start-time   (.getStartTime execution)})
                (finally (Files/deleteIfExists job-file)))))
   :post-redirect? false
   :handle-created (fn [ctx]
                     (select-keys ctx [:execution-id :batch-status :start-time]))
   :handle-ok (fn [ctx]
                ;; TODO JBERET doesn't return empty set...
                (vec (. job-operator getJobNames)))))

(defn job-instances-resource [job-id]
  (liberator/resource))

(defn job-executions-resource [{:keys [job-operator]} job-name]
  (liberator/resource
   :available-media-types ["application/edn"]
   :handle-ok (fn [ctx]
                (if-let [job-instance (some-> job-operator
                                              (.getJobInstances job-name 0 1)
                                              first)]
                  (map bean (. job-operator getJobExecutions job-instance))))))

(defn job-execution-resource [{:keys [job-operator] :as runtime}
                              execution-id & [cmd]]
  (liberator/resource
   :available-media-types ["application/edn"]
   :allowed-methods [:get :put]
   :malformed? #(parse-edn % ::data)
   :exists? (fn [_]
              (when-let [execution (.getJobExecution job-operator execution-id)]
                {:execution execution
                 :step-executions (.getStepExecutions job-operator
                                                      (.getExecutionId execution))}))
   :put! (fn [ctx]
           (case cmd
             :abandon
             (do (log/info "Abandon " execution-id)
                 (.abandon job-operator execution-id))

             :stop
             (do (log/info "Stop " execution-id)
                 (.stop job-operator execution-id))

             :restart
             (let [parameters (Properties.)
                   loader (find-loader runtime (get-in ctx [::data :class-loader-id]))]
               (doseq [[k v] (get-in ctx [::data :parameters])]
                 (.setProperty parameters (name k) (str v)))
               (let [execution-id (with-classloader loader
                                    (.restart job-operator execution-id parameters))
                     execution (with-classloader loader
                                 (.getJobExecution job-operator execution-id))]
                 {:execution-id execution-id
                  :batch-status (keywordize-status execution)
                  :start-time   (.getStartTime execution)}))))
   :handle-created (fn [ctx]
                     (select-keys ctx [:execution-id :batch-status :start-time]))
   :handle-ok (fn [{execution :execution step-executions :step-executions}]
                {:execution-id (.getExecutionId execution)
                 :start-time (.getStartTime execution)
                 :end-time   (.getEndTime execution)
                 :batch-status (keywordize-status execution)
                 :exit-status (.getExitStatus execution)
                 :step-executions (->> step-executions
                                       (map (fn [se]
                                              {:start-time (.getStartTime se)
                                               :end-time (.getEndTime se)
                                               :step-execution-id (.getStepExecutionId se)
                                               :exit-status (.getExitStatus se)
                                               :batch-status (keywordize-status se)
                                               :step (.getStepName se)}))
                                       (vec))})))

(defn step-execution-resource [{:keys [job-operator]} execution-id step-execution-id]
  (liberator/resource
   :available-media-types ["application/edn"]
   :exists? (fn [_]
              (when-let [execution (.getJobExecution job-operator execution-id)]
                (when-let [step-execution (->> (.getStepExecutions job-operator (.getExecutionId execution))
                                               (filter #(= (.getStepExecutionId %) step-execution-id))
                                               first)]
                  {:step-execution step-execution})))
   :handle-ok (fn [{step-execution :step-execution}]
                {:step-execution-id (.getStepExecutionId step-execution)
                 :start-time (.getStartTime step-execution)
                 :end-time   (.getEndTime step-execution)
                 :batch-status (keywordize-status step-execution)
                 :exit-status (.getExitStatus step-execution)
                 :step-name   (.getStepName step-execution)})))

(defrecord JSR352Runtime [instance-id]
  component/Lifecycle

  (start [component]
    (let [job-operator (BatchRuntime/getJobOperator)]
      (assoc component
             :job-operator job-operator
             :classloaders (atom {})
             :base-url     (atom nil))))

  (stop [component]
    (dissoc component :job-operator :classloaders :base-url)))

(defn runtime-component [options]
  (map->JSR352Runtime
   {:instance-id (or (:instance-id options) default-instance-id)}))
