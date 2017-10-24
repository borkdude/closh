(ns closh.parser
  (:require [clojure.string]
            [clojure.set]
            [clojure.spec.alpha :as s]))

(def pipes
  {'| 'pipe
   '|> 'pipe-multi
  ;  '|>> ' pipe-thread-last
   ; '|| ' pipe-mapcat
   '|? 'pipe-filter
   '|& 'pipe-reduce})
   ; '|! 'pipe-foreach

(def builtins #{'cd 'exit 'quit})

(def redirect-op #{'> '< '>> '&> '&>> '<> '>&})
(def pipe-op #{'| '|> '|? '|&})
(def clause-op #{'|| '&&})
(def cmd-op #{'&})

(def op (clojure.set/union redirect-op pipe-op clause-op cmd-op))

(s/def ::redirect-op redirect-op)
(s/def ::pipe-op pipe-op)
(s/def ::clause-op clause-op)
(s/def ::cmd-op cmd-op)


(s/def ::cmd-list (s/cat :cmd ::cmd-clause
                         :cmds (s/* (s/cat :op ::cmd-op
                                           :cmd ::cmd))))

(s/def ::cmd-clause (s/cat :pipeline ::pipeline
                           :pipelines (s/* (s/cat :op ::clause-op
                                                  :pipeline ::pipeline))))

(s/def ::pipeline (s/cat :not (s/? #{'!})
                         :cmd ::cmd
                         :cmds (s/* (s/cat :op ::pipe-op
                                           :cmd ::cmd))))

(s/def ::cmd (s/+ (s/alt :redirect ::redirect
                         :arg ::arg)))

(s/def ::redirect (s/cat :fd (s/? number?) :op ::redirect-op :arg ::arg))

(s/def ::arg #(not (op %)))
              ;  (s/or :list list?
              ;        :symbol symbol?
              ;        :string string?
              ;        :number number?))))

(declare parse)

(defn process-arg [arg]
  (if (list? arg)
    (if (= (first arg) 'sh)
      (list 'expand-command (parse (rest arg)))
      arg)
    (list (if (string? arg)
            'expand-partial
            'expand)
          (str arg))))

(defn process-redirect [{:keys [op fd arg]}]
  (let [arg (cond
              (list? arg) arg
              (number? arg) arg
              :else (list 'expand-redirect (str arg)))]
    (case op
      > [[:out (or fd 1) arg]]
      < [[:in (or fd 0) arg]]
      >> [[:append (or fd 1) arg]]
      &> [[:out 1 arg]
          [:set 2 1]]
      &>> [[:append 1 arg]
           [:set 2 1]]
      <> [[:rw (or fd 0) arg]]
      >& [[:set (or fd 1) arg]])))

(defn process-command [[cmd & args]]
  (if (and (= (first cmd) :arg)
           (list? (second cmd))
           (not= 'cmd (first (second cmd))))
    (if (seq args)
      (concat
        (list 'do (second cmd))
        (map second args))
      (second cmd))
    (let [name (second cmd)
          name-val (if (list? name)
                     (second name) ; when using cmd helper
                     (str name))
          redirects (->> args
                         (mapcat #(if (vector? (first %)) % [%]))
                         (filter #(= (first %) :redirect))
                         (mapcat (comp process-redirect second))
                         (into []))
          parameters (->> args
                          (filter #(= (first %) :arg))
                          (map #(process-arg (second %))))]
        (if (builtins name)
          (conj parameters name)
          (concat
            (list 'shx name-val)
            [(vec parameters)]
            (if (seq redirects) [{:redir redirects}]))))))

(defn special? [symb]
  (or
   (special-symbol? symb)
   (#{'shx 'fn} symb)))
   ; TODO: how to dynamically resolve and check for macro?
   ; (-> symb resolve meta :macro boolean)))

(defn process-pipeline [{:keys [cmd cmds]}]
  (concat
   (list '-> (process-command cmd))
   (for [{:keys [op cmd]} cmds]
     (let [cmd (process-command cmd)
           fn (pipes op)]
       (cond
         (and (= op '|>) (not (special? (first cmd)))) (list fn (conj cmd 'partial))
         (and (= op '|) (not (special? (first cmd)))) (list fn (conj cmd 'partial))
         :else (list fn cmd))))))

(defn process-command-clause [{:keys [pipeline pipelines]}]
  (let [items (reverse (conj (seq pipelines) {:pipeline pipeline}))]
    (:pipeline
      (reduce
        (fn [{op :op child :pipeline} pipeline]
          (let [condition (if (= op '&&) true false)
                neg (if (:not (:pipeline pipeline)) (not condition) condition)
                pred (if neg 'true? 'false?)
                tmp (gensym)]
            (assoc pipeline :pipeline
                   `(let [~tmp (closh.core/wait-for-pipeline ~(process-pipeline (:pipeline pipeline)))]
                      (if (~pred (closh.core/pipeline-condition ~tmp))
                        ~child
                        ~tmp)))))
        (-> items
            (first)
            (update :pipeline process-pipeline))
        (rest items)))))

(defn process-command-list [{:keys [cmd cmds]}]
  (process-command-clause cmd))

(defn parse [coll]
  (process-command-list (s/conform ::cmd-list coll)))

; (process-command-list (s/conform ::cmd-list '(diff < (sh sort L.txt) < (sh sort R.txt))))
