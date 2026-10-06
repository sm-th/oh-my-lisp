(ns oml.repl
  "A live REPL into the running agent: an nREPL server in the agent process.

  Connect CIDER, Calva, Conjure or `rep` to the port and evaluate code in the
  same image that serves the ACP client: a `defn` there changes the agent's
  next turn. The port is written to $XDG_STATE_HOME/oml/nrepl-port and logged
  to stderr; stdout stays reserved for ACP."
  (:require [babashka.fs :as fs]
            [babashka.nrepl.server :as nrepl]
            [oml.custom :refer [defsetting]]
            [oml.init :as init]))

(defsetting nrepl?
  "Start an nREPL server when the ACP agent starts."
  true)

(defsetting nrepl-host
  "Address the nREPL server binds to. Anyone who can connect can run code
  as you, so keep it on loopback."
  "127.0.0.1")

(defsetting nrepl-port
  "Port of the nREPL server; 0 picks a free one."
  0)

(defonce server (atom nil))

(defn port-file
  "Where the port of the running server is written."
  []
  (str (fs/path (init/state-dir) "nrepl-port")))

(defn start!
  "Start the nREPL server if nrepl? is true and none is running. Returns the
  port, or nil."
  []
  (when nrepl?
    (or (:port @server)
        (let [s (nrepl/start-server! {:host nrepl-host :port nrepl-port :quiet true})
              port (.getLocalPort ^java.net.ServerSocket (:socket s))
              f (port-file)]
          (reset! server (assoc s :port port))
          (fs/create-dirs (fs/parent f))
          (spit f (str port))
          (init/log (str "nREPL on " nrepl-host ":" port " (port written to " f ")"))
          port))))
