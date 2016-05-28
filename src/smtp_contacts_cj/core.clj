(ns smtp-contacts-cj.core
  (:require
   ;[clojure-mail]
   [clojure-mail.gmail :as gmail]
   ;[clojure-mail.core :refer :all]
   ;[clojure-mail.message :refer (read-message)]
   ;[clojure-mail.message :as message]
   [smtp-contacts-cj.db :as db]
   [clojure.tools.cli :refer [parse-opts]]
   [clojure.tools.logging :as log]
   )
  (:gen-class))





(defn message-name-address-map-list [message]
  ";=> ({:address \"ealfonso@cmu.edu\", :name \"my name\"} {:address \"notification+bla-bla@facebookmail.com\", :name \"Facebook\"})"
  (apply concat (map #(% message)
                     [clojure-mail.message/to
                      clojure-mail.message/from
                      clojure-mail.message/cc
                      clojure-mail.message/bcc])))

(def cli-options
  [["-e" "--email EMAIL" "email address"
    :default "erjoalgo@gmail.com"]
   ["-d" "--db DB" "path to sqlite db"
    ;:default "resources/smtp-contacts.db"]]
    :default (format "%s/.smtp-contacts.db" (System/getenv "HOME"))]
  ["-m" "--max-results MAX" "max results to fetch, default 600, 0 for infinite"
    ;:parse-fn Integer/parseInt 
    :parse-fn #(Integer/parseInt %)
    :default 600]])

(defn process-messages [db store & {:keys [folder max-messages] :or {folder "INBOX"}}]
  (let [last-uid (db/last-known-uid db)
        messages (clojure-mail.core/all-messages store folder
                                                 :since-uid (and last-uid (+ 1 last-uid))
                                                 :oldest-first true)
        current-uid-validity (clojure-mail.core/get-folder-uid-validity
                              (clojure-mail.core/get-folder store folder))
        index 0]
    (log/debugf "current, last validity: %s, %s\n" current-uid-validity
                (db/last-uid-validity db))
    (db/update-uid-validity-if-changed! db current-uid-validity)
    (log/debugf "count: %d, first msg uid: %s, last uid: %s\n" (count messages)
                (clojure-mail.message/uid (first messages))
                last-uid)
    (assert (or (not (first messages))
                (empty? (rest messages))
                (not last-uid)
                (< last-uid (clojure-mail.message/uid (first messages)) )))
    (loop [messages messages
           index 1]
      (when (and (first messages) (or (not max-messages) (< index max-messages)))
        (let [message (first messages)
              uid (clojure-mail.message/uid message)
              name-address-maps (smtp-contacts-cj.core/message-name-address-map-list message)]
          (assert (not (= uid last-uid)))
          (do
            ;;TODO verbosity level
            (printf "\ron message %d (uid: %d, date: %s, subject: %s)"
                    index uid
                    (clojure-mail.message/date-sent message)
                    (clojure-mail.message/subject message))
            (flush))
          (db/insert-name-address-to-db! db name-address-maps)
          (db/store-uid! db uid))
        (recur (rest messages) (+ 1 index))))))

(defn -main
  "fetch new mail, extact and store contacts"
  [& args]
  (let [args (parse-opts args cli-options)]
    (if (:errors args)
      (do (println (:summary args))
          (println (:errors args)))
      
      (let [max-results (:max-results (:options args))
            db-filename (:db (:options args))
            email (:email (:options args))
            pass (slurp "pass")

            db (db/sqlite-db-connection-for-file db-filename)
            gstore (clojure-mail.gmail/store email pass)]
        
                                        ;(db/ensure-tables-exist db/db :drop true)
        (db/ensure-tables-exist db)
        (process-messages db gstore :max-messages (if-not (= 0 max-results) max-results))))))

;;(def gstore (gmail/store "erjoalgo@gmail.com" (slurp "pass")))

;; Local Variables:
;; compile-command: "lein run -- -m 100 --db ~/.smtp-contacts.db -e erjoalgo@gmail.com"
;; End: