(defproject cljfst "0.1.0-SNAPSHOT"
  :description "Finite-state toolkit written in Clojure, based on foma"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [instaparse "1.4.3"]
                 [rhizome "0.2.7"]
                 [org.clojure/tools.cli "0.3.5"]]
  :test-selectors {:current :current}
  :main ^:skip-aot cljfst.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
