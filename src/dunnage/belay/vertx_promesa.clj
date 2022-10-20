(ns dunnage.belay.vertx-promesa
  (:require [promesa.protocols :as pt]
    ;[promesa.impl :as impl]
            [promesa.util :as pu])
  (:import (io.vertx.core Future Promise)
           (java.util.concurrent CompletionException Executor)
           (java.util.function Function)))

(extend-protocol pt/IPromise
  Future
  (-map
    ([it f]
     (.map ^Future it
           ^Function (pu/->FunctionWrapper f))))

  (-bind
    ([it f]
     (.compose ^Future it
               ^Function (pu/->FunctionWrapper f))))

  (-then
    ([it f]
     (.compose ^Future it
               ^Function (pu/->FunctionWrapper (comp pt/-promise f)))))

  (-mapErr
    ([it f]
     (letfn [(handler [e]
               (if (instance? CompletionException e)
                 (f (.getCause ^Exception e))
                 (f e)))]
       (.otherwise ^Future it
                   ^Function (pu/->FunctionWrapper handler)))))

  (-thenErr
    ([it f]
     (letfn [(handler [e]
               (if e
                 (if (instance? CompletionException e)
                   (pt/-promise (f (.getCause ^Exception e)))
                   (pt/-promise (f e)))
                 it))]
       (.recover ^Future it
                 (pu/->FunctionWrapper handler)))))

  (-handle
    ([it f]
     (.compose it
               (reify Function
                 (apply [_ success] (f success nil)))
               (reify Function
                 (apply [_ failure] (f nil failure))))))

  (-finally
    ([it f]
     (.eventually ^Future it
                    ^Function (pu/->FunctionWrapper f))))

  )