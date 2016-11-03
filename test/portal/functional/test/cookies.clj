(ns portal.functional.test.cookies)

(defn get-cookie-token
  "Given a response map, return the token"
  [response]
  (second (re-find #"token=([a-zA-Z0-9]*);"
                   (first (filter (partial re-matches #"token.*")
                                  (get-in response [:headers "Set-Cookie"]))))))

(defn get-cookie-user-id
  [response]
  (second (re-find #"user-id=([a-zA-Z0-9]*);"
                   (first (filter (partial re-matches #"user-id.*")
                                  (get-in response [:headers "Set-Cookie"]))))))
