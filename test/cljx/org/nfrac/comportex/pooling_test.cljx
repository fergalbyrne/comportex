(ns org.nfrac.comportex.pooling-test
  (:require [org.nfrac.comportex.pooling :as p]
            [org.nfrac.comportex.encoders :as enc]
            [org.nfrac.comportex.util :as util]
            [clojure.set :as set]
            #+clj [clojure.test :as t
                   :refer (is deftest testing run-tests)]
            #+cljs [cemerick.cljs.test :as t])
  #+cljs (:require-macros [cemerick.cljs.test
                           :refer (is deftest testing run-tests)]))

(def numb-bits 127)
(def numb-on-bits 21)
(def numb-domain [0 100])
(def n-in-items 3)
(def bit-width (* numb-bits n-in-items))
(def ncol 200)

(defn active-columns-at
  [r t]
  (reduce (fn [s col]
            (if (contains? (:active-history col) t)
              (conj s (:id col)) s))
          #{} (:columns r)))

(deftest pooling-test
  (let [efn (enc/juxtapose-encoder
             (enc/linear-number-encoder numb-bits numb-on-bits numb-domain))
        [lo hi] numb-domain
        gen-ins (fn []
                  (repeatedly n-in-items #(util/rand-int lo hi)))
        add-noise (fn [delta xs]
                    (map (fn [x]
                           (-> (+ x (util/rand-int (- delta) (inc delta)))
                               (min hi)
                               (max lo)))
                         xs))
        inseq (repeatedly gen-ins)
        enc-inseq (map efn inseq)
        r (p/region {:ncol ncol
                     :input-size bit-width
                     :potential-radius (quot bit-width 5)
                     :global-inhibition false
                     :stimulus-threshold 2
                     :duty-cycle-period 600})
        r1k (time
             (reduce (fn [r in] (p/pooling-step r in))
                     r (take 1000 enc-inseq)))]
    
    (testing "Spatial pooler column activation is distributed and moderated."
      (let [noverlaps (map (comp count :overlap-history) (:columns r1k))]
        (is (every? pos? noverlaps)
            "All columns have overlapped with input at least once."))
      (let [nactive (map (comp count :active-history) (:columns r1k))]
        (is (pos? (util/quantile nactive 0.8))
            "At least 20% of columns have been active."))
      (let [nactive-ts (for [t (range 900 1000)]
                         (count (active-columns-at r1k t)))]
        (is (every? #(< % (* ncol 0.6)) nactive-ts)
            "Inhibition limits active columns in each time step."))
      (let [nsyns (map (comp count :connected :in-synapses) (:columns r1k))]
        (is (>= (apply min nsyns) 1)
            "All columns have at least one connected input synapse."))
      (let [bs (map :boost (:columns r1k))]
        (is (== 1.0 (util/quantile bs 0.3))
            "At least 30% of columns are unboosted.")))

    (testing "Spatial pooler acts as a Locality Sensitive Hashing function."
      (let [in (gen-ins)
            in-near (add-noise 5 in)
            in-nearer (add-noise 1 in)
            in2 (gen-ins)
            ac (:active-columns (p/pooling-step r1k (efn in)))
            acnr (:active-columns (p/pooling-step r1k (efn in-near)))
            acnrr (:active-columns (p/pooling-step r1k (efn in-nearer)))
            ac2 (:active-columns (p/pooling-step r1k (efn in2)))]
        (is (> (count (set/intersection ac acnrr))
               (* (count ac) 0.5))
            "Minor noise leads to a majority of columns remaining active.")
        (is (< (count (set/intersection ac acnr))
               (count (set/intersection ac acnrr)))
            "Increasing noise level reduces similarity of active column set")
        (is (< (count (set/intersection ac ac2))
               (count (set/intersection ac acnr)))
            "Input close to original has more similar column activation than a random input does.")))))

(comment
  (require 'org.nfrac.comportex.pooling-test :reload-all)
  (in-ns 'org.nfrac.comportex.pooling-test)
  (use 'clojure.pprint)
  (use 'clojure.repl))