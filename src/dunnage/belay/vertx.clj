(ns dunnage.belay.vertx
  (:require dunnage.belay.vertx-promesa)
  (:import
    clojure.lang.MapEntry

    io.vertx.core.buffer.Buffer
    (io.vertx.core VertxOptions Vertx Verticle Handler
                   Future MultiMap Context)
    (io.vertx.core.http
      HttpClient HttpClientOptions HttpClientRequest HttpClientResponse RequestOptions
      HttpServer HttpServerRequest HttpServerResponse HttpServerOptions ServerWebSocket WebSocket WebSocketConnectOptions WebSocketFrame)
    (io.vertx.core.json JsonObject)
    (io.vertx.core.net SocketAddress)
    (java.util Arrays Iterator Map Map$Entry)
    (java.util.function Function)))

(set! *warn-on-reflection* true)
(defn ^Map str-keys [x]
  (persistent!
    (reduce-kv
      (fn [acc k v]
        (if (string? k)
          acc
          (dissoc! acc k)))
      (transient x)
      x)))

(defn ^JsonObject json-object [data]
  (new JsonObject (str-keys data)))

(defn make-system [options]
  (Vertx/vertx (new VertxOptions (json-object options))))

(defn process-headers [^MultiMap h]
  (persistent!
    (reduce (fn [acc ^Map$Entry entry]
              (let [k (.toLowerCase ^String (key entry))
                    old (get acc k)]
                (if old
                  (assoc! acc k (str old "," (val entry)))
                  (assoc! acc k (val entry)))))
            (transient {})
            h)))

(defn ring-request [^HttpServerRequest vertx-request]
  (let [laddr ^SocketAddress (.remoteAddress vertx-request)
        h (.host vertx-request)
        [host port] (clojure.string/split h #":")
        query-string (.query vertx-request)]
    (cond-> {:server-port    (Long/parseLong port)
             ;(Required, Integer)
             ; The port on which the request is being handled.

             :server-name    host
             ;(Required, String)
             ;The resolved server name, or the server IP address.

             :remote-addr    (.hostAddress laddr)
             ;(Required, String)
             ;The IP address of the client or the last proxy that sent the request.

             :uri            (.uri vertx-request)
             ;(Required, String)
             ;The request URI, excluding the query string and the "?" separator.
             ;Must start with "/".


             ;(Optional, String)
             ;The query string, if present.

             :scheme         (keyword (.scheme vertx-request))
             ;(Required, clojure.lang.Keyword)
             ;The transport protocol, must be one of :http or :https.

             :request-method (keyword (.toLowerCase (.toString (.method vertx-request))))
             ;(Required, clojure.lang.Keyword)
             ;The HTTP request method, must be a lowercase keyword corresponding to a HTTP
             ;request method, such as :get or :post.

             :protocol       (.toString (.version vertx-request))
             ;(Required, String)
             ;The protocol the request was made with, e.g. "HTTP/1.1".

             ;:content-type                                            ;[DEPRECATED]
             ;(Optional, String)
             ;The MIME type of the request body, if known.

             ;:content-length                                          ;[DEPRECATED]
             ;(Optional, Integer)
             ;The number of bytes in the request body, if known.

             ;:character-encoding                                      ;[DEPRECATED]
             ;(Optional, String)
             ;The name of the character encoding used in the request body, if known.

             ; :ssl-client-cert  (.peerCertificateChain vertx-request)
             ;(Optional, java.security.cert.X509Certificate)
             ;The SSL client certificate, if supplied.

             :headers        (process-headers (.headers vertx-request))
             ;(Required, clojure.lang.IPersistentMap)
             ;A Clojure map of downcased header name Strings to corresponding header value
             ;Strings. When there are multiple headers with the same name, the header
             ;values are concatenated together, separated by the "," character.

             ;:body (.body)
             ;(Optional, java.io.InputStream)
             ;An InputStream for the request body, if present.
             ;:vertx-request vertx-request
             ::request   vertx-request
             }
            query-string
            (assoc :query-string query-string))
    )
  )

(defn status-headers [^HttpClientResponse response]
  {:status  (.statusCode response)
   :headers (process-headers (.headers response))})

(defn ring-response [{:keys [as] :as req} ^HttpClientResponse response]
  (case as
    nil (.map ^Future (.body response)
              (reify Function
                (apply [_ x]
                  (assoc (status-headers response) :body x))))
    :string (.map ^Future (.body response)
                  (reify Function
                    (apply [_ x]
                      (assoc (status-headers response) :body (.getString ^Buffer x 0 (.length ^Buffer x)))))))
  )

(defn ws-handler-handler [^ServerWebSocket ws
                          f]
  (let [{:keys [textMessageHandler
                binaryMessagehandler
                frameHandler
                closeHandler
                pongHandler]
         :as   handlers} (f ws)]
    (when textMessageHandler
      (.textMessageHandler ws
                           (reify Handler
                             (handle [_ message]
                               (textMessageHandler message)
                               ))))
    (when binaryMessagehandler
      (.binaryMessageHandler ws
                             (reify Handler
                               (handle [_ message]
                                 (binaryMessagehandler message)))))
    (when pongHandler
      (.pongHandler ws
                    (reify Handler
                      (handle [_ message]
                        (pongHandler message)))))
    (when frameHandler
      (.frameHandler ws
                     (reify Handler
                       (handle [_ frame]
                         (frameHandler frame)))))
    (when closeHandler
      (.closeHandler ws
                     (reify Handler
                       (handle [_ _]
                         (closeHandler)))))))

(defn upgrade-websocket [ request websocket-handler]
  (.flatMap (.toWebSocket
              ^HttpServerRequest (::request request))
            (reify Function
              (apply [_ ws]
                (ws-handler-handler ws websocket-handler)))))

(defn make-request-handler [request-handler]
  (reify Handler
    (handle [_ request]
      (let [response ^HttpServerResponse (.response ^HttpServerRequest request)
            ring-request (ring-request request)
            respond (fn [{:keys [status headers body]}]
                      ;(prn :rsp status)
                      (.setStatusCode response (int status))
                      (run!
                        (fn [header]
                          (let [v (val header)]
                            (if (sequential? v)
                              (run!
                                (fn [v]
                                  (.putHeader response
                                              ^String (key header)
                                              ^String v))
                                v)
                              (.putHeader response
                                          ^String (key header)
                                          ^String v))))
                        headers)
                      (if body
                        (.write response body)
                        (.end response)))
            raise (fn [exception]
                    (.setStatusCode response 500)
                    (.end response))]
        (request-handler ring-request respond raise)))))

(defn http-server
  ^HttpServer [^Vertx system {:strs [request-handler
                                     invalid-requesthandler
                                     websocket-handler
                                     ] :as options}]
  (cond-> (.createHttpServer system (new HttpServerOptions (json-object options)))
          websocket-handler
          (.webSocketHandler (reify Handler
                               (handle [_ request]
                                 (ws-handler-handler request websocket-handler))))
          request-handler
          (.requestHandler (make-request-handler request-handler))
          invalid-requesthandler
          (.invalidRequestHandler (reify Handler
                                    (handle [_ request] (invalid-requesthandler request))))))

(defn http-client [^Vertx system {:strs [] :as options}]
  (.createHttpClient system (new HttpClientOptions (json-object options))))

(defmulti ^Future send-body (fn [body ^HttpClientRequest req] (type body)))

(defmethod send-body String
  [^String body ^HttpClientRequest req]
  (.send ^HttpClientRequest req body))

(defn make-request [^HttpClient client req]
  (.flatMap (.request client (new RequestOptions (json-object req)))
            (reify Function
              (apply [_ x]
                ;(prn x)
                (let [rsp (if-some [body (get req "body")]
                            (send-body body x)
                            (.send ^HttpClientRequest x))]
                  (.flatMap rsp
                            (reify Function
                              (apply [_ x]
                                (ring-response req x)))))))))

(defn make-ws [^HttpClient client req]
  (.webSocket client (new WebSocketConnectOptions (json-object req))))



(defn get-context ^Context []
  (Vertx/currentContext))

(defn get-system ^Vertx []
  (.owner (get-context)))



(comment
  (def vs (make-system {}))
  (def ws-handler (fn [^ServerWebSocket ws]
                    (prn :ws ws)
                    {:textMessageHandler
                     (fn [^String message]
                       (prn :strmsg message)
                       )
                     :binaryMessagehandler
                     (fn [^Buffer message]
                       (prn :binmsg message)
                       )
                     ;:frameHandler
                     :closeHandler
                     (fn []
                       (prn :close))
                     ;:pongHandler
                     }))
  (def s (http-server
           vs
           {"request-handler" (fn [req respond raise]
                                (prn :hi req)
                                (if (= "websocket" (-> req :headers (get "upgrade")))
                                  (upgrade-websocket req ws-handler)
                                  (respond {:status 200})))
            #_"websocket-handler"}))
  (def client (http-client vs {}))

  (time @(.toCompletionStage (make-request client {"port" 8080
                                                   :as    :string})))

  (def running-server @(.toCompletionStage (.listen s 8080)))
  @(.toCompletionStage (.close s))

  (def ws-request @(.toCompletionStage (make-ws client {"port" 8080
                                                        :as    :string})))

  @(.toCompletionStage (.writePing ws-request (Buffer/buffer "")))
  @(.toCompletionStage (.writeTextMessage ws-request "hi there server"))

  @(.toCompletionStage *1)
  @(.toCompletionStage (.close ws-request))

  )



