;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Clojure FST: Regex Parsing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; This module contains functionality for parsing (reading )input regex and
;; rewrite rule expressions and converting (evaluating) them to FSTs.

;; TODO:
;; - transducer reversal: the reversal of a transducer may be accomplished by
;;   designating the set of final states initial and vice versa, and replacing
;;   each transition δ(p, x, q) with δ(q, x, p).

(ns cljfst.regex
  (:gen-class)
  (:require [clojure.pprint :refer [pprint]]
            [clojure.set :refer [intersection
                                 difference
                                 union]]
            [clojure.string :as string]
            [instaparse.core :as insta]
            [cljfst.common :refer [cart
                                   state-to-int
                                   int-to-state
                                   inc-state
                                   epsilon-symbol
                                   unknown-symbol
                                   identity-symbol]]
            [cljfst.determinize :refer [subset-construction]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; read
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; `read-regex` takes a regular expression string as input and returns an
;; Instaparse parse of it, using the CFG defined in resources/grammar.bn. This
;; is the "read" step. The eval step involves converting each node to an FST.
(def read-regex
  (insta/parser (clojure.java.io/resource "grammar.bnf")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; eval
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Basic 'Thompson' (Thompson 1968) construction methods
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sigma-from-mapping
  "Create a sigma (symbol set) for the two symbols in a simple mapping."
  [sym-i sym-o]
  (if (some #{unknown-symbol} [sym-i sym-o])
    (set [sym-i sym-o identity-symbol])
    (set [sym-i sym-o])))

(defn delta-from-mapping
  "Create a delta (transition vector set) for the two symbols in a simple
  mapping."
  [sym-i sym-o]
  (cond
    (= sym-o unknown-symbol) #{[:s0 sym-i :s1 sym-o] [:s0 sym-i :s1 sym-i]}
    (= sym-i unknown-symbol) #{[:s0 sym-i :s1 sym-o] [:s0 sym-o :s1 sym-o]}
    :else #{[:s0 sym-i :s1 sym-o]}))

(defn create-mapping
  "Create a simple FST that maps symbol `sym-i` to symbol `sym-o`"
  ([sym] (create-mapping sym sym))
  ([sym-i sym-o]
    (let [sigma (sigma-from-mapping sym-i sym-o)
          delta (delta-from-mapping sym-i sym-o)]
      {:sigma sigma
      :Q #{:s0 :s1}
      :s0 :s0
      :F #{:s1}
      :delta delta})))

(defn get-unique-states
  "Return a hash map mapping states in `target-states` to states not in
  `conflictee-states`."
  ([target-states conflictee-states] (get-unique-states target-states conflictee-states target-states))
  ([target-states conflictee-states candidates]
    (let [new-candidates
          (into [] (map (fn [state] (int-to-state (inc (state-to-int state))))
                        candidates))]
      (if (not-empty (intersection (set new-candidates) (set conflictee-states)))
        (get-unique-states target-states conflictee-states new-candidates)
        (zipmap target-states new-candidates)))))

(defn remove-state-conflicts
  "Return a new FST based on `target-fst` but where all states are renamed so
  that no states in `target-fst` are also in `conflictee-fst`"
  [target-fst, conflictee-fst]
  (let [state-fixer (get-unique-states (:Q target-fst) (:Q conflictee-fst))]
    {:sigma (set (concat (:sigma target-fst) (:sigma conflictee-fst)))
     :Q (into [] (vals state-fixer))
     :s0 ((:s0 target-fst) state-fixer)
     :F (into [] (map #(% state-fixer) (:F target-fst)))
     :delta (into [] (map
                       (fn [[st-i sym-i st-o sym-o]]
                         (let [st-i-fixed (st-i state-fixer)
                               st-o-fixed (st-o state-fixer)]
                           [(st-i state-fixer)
                            sym-i
                            (st-o state-fixer)
                            sym-o]))
                       (:delta target-fst)))}))

(defn concatenate
  "Concatenate two FSTs:
  - rename states in fst2 so no conflicts with fst1
  - add transitions from all final states in L1 to the initial state in L2
  - make all states in L1 nonfinal
  - make initial state of L1 the initial state of L3"
  [[fst1 fst2]]
  (let [fst2-no-confl (remove-state-conflicts fst2 fst1)]
    (let [tmp
          (assoc
            (reduce
              (fn [fst final-state]
                (assoc fst
                       :delta
                       (conj (:delta fst)
                             [final-state epsilon-symbol (:s0 fst)
                              epsilon-symbol])))
              fst2-no-confl
              (:F fst1))
            :s0
            :s0)]
      (assoc
        (assoc tmp :Q (into [] (set (concat (:Q fst1 ) (conj (:Q tmp) :s0)))))
        :delta
        (into [] (concat (:delta tmp) (:delta fst1)))))))

(defn inc-all-states
  "Increment all states in `fst`."
  [fst]
  {:sigma (:sigma fst)
   :Q (set (map inc-state (:Q fst)))
   :s0 (inc-state (:s0 fst))
   :F (set (map inc-state (:F fst)))
   :delta (set (map (fn
                      [[st-i sy-i st-o sy-o]]
                      [(inc-state st-i)
                       sy-i
                       (inc-state st-o)
                       sy-o])
                    (:delta fst)))})

(defn perform-union
  "Perform the union operation on two FSTs: fst1 and fst2. Note: the
  `product-construction`-based union should be used instead, i.e., `union-pc`."
  [[fst1 fst2]]
  (let [fst2-no-confl (remove-state-conflicts fst2 fst1)
        fst1 (inc-all-states fst1)
        fst2 (inc-all-states fst2-no-confl)]
    {:sigma (:sigma fst2)
     :Q (set (concat (:Q fst1) (:Q fst2) [:s0]))
     :s0 :s0
     :F (set (concat (:F fst1) (:F fst2)))
     :delta (set
              (concat
                (:delta fst1)
                (:delta fst2)
                (map
                  (fn [prev-init-st]
                    [:s0 epsilon-symbol prev-init-st epsilon-symbol])
                  [(:s0 fst1) (:s0 fst2)])))}))

(defn kleene-star-repeat
  "Perform the kleene star (*) repeat operation on a given FST, using the
  Thompson (1968) method."
  [fst]
  (let [tmp-fst (inc-all-states fst)
        prev-final-states (:F tmp-fst)
        result
        {:sigma (conj (:sigma tmp-fst) epsilon-symbol)
         :Q (conj (:Q tmp-fst) :s0)
         :s0 :s0
         :F #{:s0}
         :delta (set (concat
                  (:delta tmp-fst)
                  [[:s0 epsilon-symbol :s1 epsilon-symbol]]
                  (map (fn [prev-final-state]
                         [prev-final-state epsilon-symbol :s0
                          epsilon-symbol])
                       prev-final-states)))}]
    (subset-construction result)))

(defn process-regex-symbol
  "Process a single symbol (e.g., 'a' or '0') as a regular expression."
  [[symbol-parse]]
  (cond
    (= :atomic-symbol (first symbol-parse)) (second symbol-parse)
    (= :multi-char-symbol (first symbol-parse)) (apply str (rest symbol-parse))
    (= :wildcard (first symbol-parse)) unknown-symbol
    (= :nil-symbol (first symbol-parse)) epsilon-symbol
    (= :identity-symbol (first symbol-parse)) identity-symbol))


;; Merge Alphabets-related functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Notes from Hulden (2009)
;; - a @:@ transition signifies any identity pair not in the currently declared
;;   alphabet
;; - the ?-symbol on one side of a symbol pair signifies any symbol also not in
;;   the alphabet
;; - the combination ?:? is reserved for the non-identity relation of any
;;   symbol pair where each symbol is outside the alphabet.

(defn get-unkn-unkn-trs
  "Return new transitions to replace a ?:? transition, given the symbols in N."
  [N st-i st-o]
  (let [diff
        (map
          (fn [[n1 n2]] [st-i n1 st-o n2])
          (filter (fn [[n1 n2]] (not= n1 n2)) (cart [N N])))
        unkn-n
        (map (fn [n] [st-i unknown-symbol st-o n]) N)
        n-unkn
        (map (fn [n] [st-i n st-o unknown-symbol]) N)]
    (set (concat diff unkn-n n-unkn))))

(defn merge-alphabet
  "Take an FST and a set of symbols not in its alphabet and merge those symbols
  in to its delta transition."
  [fst N]
  (let [delta
        (reduce
          (fn [container [st-i sy-i st-o sy-o]]
            (cond
              (and (= sy-i identity-symbol)
                   (= sy-o identity-symbol))
              (apply conj container (map (fn [sy] [st-i sy st-o sy]) N))
              (= sy-o unknown-symbol)
              (apply conj container (map (fn [sy] [st-i sy-i st-o sy]) N))
              (= sy-i unknown-symbol)
              (apply conj container (map (fn [sy] [st-i sy st-o sy-o]) N))
              (and (= sy-i unknown-symbol)
                   (= sy-o unknown-symbol))
              (apply conj container (get-unkn-unkn-trs N st-i st-o))
              :else
              (conj container [st-i sy-i st-o sy-o])))
          (:delta fst)
          (:delta fst))]
    (assoc fst :delta delta)))

;; "Before each operation where two transducers are input arguments, their
;;  respective alphabets are merged or ‘harmonized’, converting some of the
;;  unknown symbols to known symbols if they are present in one of the two
;;  machines being combined."

(defn merge-alphabets
  "Merge the alphabets of two fsts. Takes two fsts and returns them, with
  modified delta transition values"
  [fst1 fst2]
  (let [N1 (difference (:sigma fst2) (:sigma fst1))
        N2 (difference (:sigma fst1) (:sigma fst2))]
    (list (merge-alphabet fst1 N1) (merge-alphabet fst2 N2))))


;; Product Construction-related functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sink-state :sink)

(defn get-trans-match
  "Get the transition in `delta` that reads in symbol `t-sy-i` in state `t-st-i`
  and writes symbol `t-sy-o`. If none exists, return the sink state."
  [t-st-i t-sy-i t-sy-o delta]
  (or (first
        (filter
          (fn [[st-i sy-i st-o sy-o]]
            (and (= st-i t-st-i) (= sy-i t-sy-i) (= sy-o t-sy-o)))
          delta))
      ;; sink state simulation
      [t-st-i t-sy-i sink-state t-sy-o]))

(defn process-pc-transitions
  "Modify `:delta` of `fst3`, and possibly also `:Q` of `fst3` and the agenda,
  and the index (of state pairs viewed). Returns a hash map with keys for
  `:fst3`, `:Agenda` and `:index`."
  [fst3 [p q] Agenda index delta-p delta-q]
  (let [result
        (reduce
          (fn [result [_ x-i p-pr x-o]]
            (let [[__ x-i q-pr x-o] (get-trans-match q x-i x-o delta-q)
                  curr-delta (get-in result [:fst3 :delta])
                  delta (conj curr-delta [[p q] x-i [p-pr q-pr] x-o])
                  fst3 (assoc (:fst3 result) :delta delta)
                  Agenda (:Agenda result)
                  index (:index result)]
              (if (some #{[p-pr q-pr]} index)
                (assoc result :fst3 fst3)
                (assoc result
                      :fst3 (assoc fst3 :Q (conj (:Q fst3) [p-pr q-pr]))
                      :Agenda (conj Agenda [p-pr q-pr])
                      :index (conj index [p-pr q-pr])))))
          {:fst3 fst3 :Agenda Agenda :index index}
          delta-p)
        result
        (reduce
          (fn [result [_ x-i q-pr x-o]]
            (let [[__ x-i p-pr x-o] (get-trans-match p x-i x-o delta-p)
                  curr-delta (get-in result [:fst3 :delta])
                  delta (conj curr-delta [[p q] x-i [p-pr q-pr] x-o])
                  fst3 (assoc (:fst3 result) :delta delta)
                  Agenda (:Agenda result)
                  index (:index result)]
              (if (some #{[p-pr q-pr]} index)
                (assoc result :fst3 fst3)
                (assoc result
                      :fst3 (assoc fst3 :Q (conj (:Q fst3) [p-pr q-pr]))
                      :Agenda (conj Agenda [p-pr q-pr])
                      :index (conj index [p-pr q-pr])))))
          result
          delta-q)]
    result))

(defn process-pc-agenda
  ([Agenda fst1 fst2 fst3] (process-pc-agenda Agenda fst1 fst2 fst3 #{}))
  ([Agenda fst1 fst2 fst3 index]
   (let [ag-item (first Agenda)
         [p q] ag-item
         delta-p (filter (fn [[st-i & oth]] (= st-i p)) (:delta fst1))
         delta-q (filter (fn [[st-i & oth]] (= st-i q)) (:delta fst2))
         result (process-pc-transitions fst3 ag-item (rest Agenda) index delta-p
                                       delta-q)]
     (if (empty? (:Agenda result))
       (:fst3 result)
       (recur (:Agenda result) fst1 fst2 (:fst3 result) (:index result))))))

(defn state-final?
  "Return `true` if `p` is in `F1` (i.e., is a final state) `OP` `q` is in
  `F2`. `OP` is a keyword denoting an operator that determines what the
  production construction algorithm returns, i.e., union, intersection or
  subtraction."
  [[p q] F1 F2 OP]
  (condp = OP
    :union (or (some #{p} F1) (some #{q} F2))
    :intersection (and (some #{p} F1) (some #{q} F2))
    :subtraction (and (some #{p} F1) (not (some #{q} F2)))))

(defn get-state-pair-converter
  "Returns a function (with a closue on a `mapping` hash) that maps pairs of
  states to unique state keywords. The `s0` param is an FST-specific state pair
  acting as the initial state; it is always mapped to the keyword `:s0`."
  [s0]
  (let [mapping (atom {s0 :s0 :index 1})]
    (fn [state-pair]
      (let [derefed-mapping @mapping
            result (get @mapping state-pair)]
        (if result
          result
          (let [index (:index derefed-mapping)
                result (int-to-state index)
                new-index (inc index)]
            (swap! mapping
                   (fn [current-mapping]
                     (assoc current-mapping
                            :index new-index
                            state-pair result)))
            result))))))

(defn state-pairs->states
  "Convert 2-ary vectors of states to unique state keywords."
  [fst]
  (let [s0 (:s0 fst)
        converter (get-state-pair-converter s0)]
    {:sigma (:sigma fst)
     :Q (set (map converter (:Q fst)))
     :s0 :s0
     :F (set (map converter (:F fst)))
     :delta (set (map (fn [[st-i sy-i st-o sy-o]]
                        [(converter st-i) sy-i (converter st-o) sy-o])
                      (:delta fst)))}))

(defn ingressible
  "Return `true` if you can enter into `state` from some state other than
  `state`."
  [state fst]
  (or (= state (:s0 fst))
      (let [is-ingressible
            (not
              (empty?
                (filter
                  (fn [[st-i _ st-o __]]
                    (and (= st-o state) (not= st-i state)))
                  (:delta fst))))]
        is-ingressible)))

(defn egressible
  "Return `true` if `state` is final or if it exits to a state other than
  itself."
  [state fst]
  (let [is-final (some #{state} (:F fst))
        is-exitable
        (not
          (empty?
            (filter
              (fn [[st-i _ st-o __]]
                (and (= st-i state)
                    (not= st-o state)))
              (:delta fst))))]
    (or is-final is-exitable)))

(defn get-good-states
  "Return the set of 'good' states in `fst`, i.e., those that you can get into
  or get out of."
  [fst]
  (set
    (filter
      (fn [state] (and (ingressible state fst) (egressible state fst)))
      (:Q fst))))

(defn remove-dead-states
  "Remove the dead states from an intermediate `fst`, i.e., the states which
  are not final or initial and which have lack either an entrance or an exit."
  [fst]
  (let [good-states (get-good-states fst)]
    {:sigma (:sigma fst)
     :Q good-states
     :s0 (:s0 fst)
     :F (intersection (:F fst) good-states)
     :delta (set
              (filter
                (fn [[st-i sy-i st-o sy-o]]
                  (and (some #{st-i} good-states)
                       (some #{st-o} good-states)))
                (:delta fst)))}))

(defn product-construction
  "Takes two FSTs and an operator OP (one of `:union`, `:intersection` and
  `:subtraction`) and returns a new FST that combines the two input ones via
  `OP`. Note: the input FSTs must be e-free."
  [fst1 fst2 OP]
  (let [[fst1 fst2] (merge-alphabets fst1 fst2)
        s0 (:s0 fst1)
        t0 (:s0 fst2)
        F1 (:F fst1)
        F2 (:F fst2)
        Agenda #{[s0 t0]}
        fst3 {:sigma (union (:sigma fst1) (:sigma fst2))
              :Q #{[s0 t0]}
              :s0 [s0 t0]
              :F #{}
              :delta #{}}
        fst3 (process-pc-agenda Agenda fst1 fst2 fst3)]
    (remove-dead-states
      (state-pairs->states
        (assoc fst3 :F
              (set (filter
                      (fn [state] (state-final? state F1 F2 OP))
                      (:Q fst3))))))))

(defn union-pc
  [fst1 fst2]
  (product-construction fst1 fst2 :union))

(defn intersection-pc
  [fst1 fst2]
  (product-construction fst1 fst2 :intersection))

(defn subtraction-pc
  [fst1 fst2]
  (product-construction fst1 fst2 :subtraction))

(defn eval-fst
  "Evaluate the regular expression `regex` to an FST. This means evaluating the
  parse output by instaparse."
  [fst regex]
  (cond
    (= :regex-stmt regex) fst
    (some #{(first regex)} #{:regex-cmd :stmt-trmntr}) fst
    (= :symbol (first regex)) (process-regex-symbol (rest regex))
    (string? regex) (eval-fst {} [:mapping regex regex])
    (= :mapping (first regex))
    (apply create-mapping (map #(eval-fst {} %) (rest regex)))
    (= :concatenation (first regex))
    (concatenate (map #(eval-fst {} %) (rest regex)))
    (= :union (first regex))
    (apply union-pc (map #(eval-fst {} %) (rest regex)))
    (= :kleene-star-repetition (first regex))
    (kleene-star-repeat (eval-fst {} (second regex)))))

(defn parse-to-fst
  "Take an instaparse parse of a regex expression and return an FST"
  [parse]
  (reduce
    eval-fst
    {}
    parse))
