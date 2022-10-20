(ns dunnage.belay.utils
  (:import (io.vertx.core Handler)
           (java.util.function Supplier)))


(defn fn->supplier
  [f]
  (reify Supplier
    (get [_] (f))))

(defn fn->handler
  [f]
  (reify Handler
    (handle [_ v]
      (f v))))