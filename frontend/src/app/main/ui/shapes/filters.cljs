;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.filters
  (:require
   [app.common.colors :as cc]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn get-filter-id []
  (dm/str "filter-" (uuid/next)))

(defn filter-str
  [filter-id shape]
  (when (or (seq (->> (:shadow shape) (remove :hidden)))
            (and (:blur shape) (-> shape :blur :hidden not)))
    (str/ffmt "url(#%)" filter-id)))

(mf/defc color-matrix
  [{:keys [color]}]
  (let [{:keys [color opacity]} color
        [r g b a] (cc/hex->rgba color opacity)
        [r g b] [(/ r 255) (/ g 255) (/ b 255)]]
    [:feColorMatrix
     {:type "matrix"
      :values (str/ffmt "0 0 0 0 % 0 0 0 0 % 0 0 0 0 % 0 0 0 % 0" r g b a)}]))

(mf/defc drop-shadow-filter
  [{:keys [filter-in filter-id params]}]

  (let [{:keys [color offset-x offset-y blur spread]} params]
    [:*
     [:feColorMatrix {:in "SourceAlpha" :type "matrix"
                      :values "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0"}]
     (when (> spread 0)
       [:feMorphology {:radius spread
                       :operator "dilate"
                       :in "SourceAlpha"
                       :result filter-id}])

     [:feOffset {:dx offset-x :dy offset-y}]
     [:feGaussianBlur {:stdDeviation (/ blur 2)}]
     [:& color-matrix {:color color}]

     [:feBlend {:mode "normal"
                :in2 filter-in
                :result filter-id}]]))

(mf/defc inner-shadow-filter
  [{:keys [filter-in filter-id params]}]

  (let [{:keys [color offset-x offset-y blur spread]} params]
    [:*
     [:feColorMatrix {:in "SourceAlpha" :type "matrix"
                      :values "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0"
                      :result "hardAlpha"}]

     (when (> spread 0)
       [:feMorphology {:radius spread
                       :operator "erode"
                       :in "SourceAlpha"
                       :result filter-id}])

     [:feOffset {:dx offset-x :dy offset-y}]
     [:feGaussianBlur {:stdDeviation (/ blur 2)}]

     [:feComposite {:in2 "hardAlpha"
                    :operator "arithmetic"
                    :k2 "-1"
                    :k3 "1"}]

     [:& color-matrix {:color color}]

     [:feBlend {:mode "normal"
                :in2 filter-in
                :result filter-id}]]))

(mf/defc background-blur-filter
  [{:keys [filter-id params]}]
  [:*
   [:feGaussianBlur {:in "BackgroundImage"
                     :stdDeviation (/ (:value params) 2)}]
   [:feComposite {:in2 "SourceAlpha"
                  :operator "in"
                  :result filter-id}]])

(mf/defc layer-blur-filter
  [{:keys [filter-id params]}]

  [:feGaussianBlur {:stdDeviation (:value params)
                    :result filter-id}])

(mf/defc image-fix-filter [{:keys [filter-id]}]
  [:feFlood {:flood-opacity 0 :result filter-id}])

(mf/defc blend-filters [{:keys [filter-id filter-in]}]
  [:feBlend {:mode "normal"
             :in "SourceGraphic"
             :in2 filter-in
             :result filter-id}])

(mf/defc filter-entry [{:keys [entry]}]
  (let [props #js {:filter-id (:id entry)
                   :filter-in (:filter-in entry)
                   :params (:params entry)}]
    (case (:type entry)
      :drop-shadow [:> drop-shadow-filter props]
      :inner-shadow [:> inner-shadow-filter props]
      :background-blur [:> background-blur-filter props]
      :layer-blur [:> layer-blur-filter props]
      :image-fix [:> image-fix-filter props]
      :blend-filters [:> blend-filters props])))

(defn change-filter-in
  "Adds the previous filter as `filter-in` parameter"
  [filters]
  (map #(assoc %1 :filter-in %2) filters (cons nil (map :id filters))))

(mf/defc filters
  [{:keys [filter-id shape]}]

  (let [filters       (-> shape gsb/shape->filters change-filter-in)
        bounds        (gsb/get-rect-filter-bounds (:selrect shape) filters (or (-> shape :blur :value) 0))
        padding       (gsb/calculate-padding shape)
        selrect       (:selrect shape)
        filter-x      (/ (- (:x bounds) (:x selrect) (:horizontal padding)) (:width selrect))
        filter-y      (/ (- (:y bounds) (:y selrect) (:vertical padding)) (:height selrect))
        filter-width  (/ (+ (:width bounds) (* 2 (:horizontal padding))) (:width selrect))
        filter-height (/ (+ (:height bounds) (* 2 (:vertical padding))) (:height selrect))]
    (when (> (count filters) 2)
      [:filter {:id          filter-id
                :x           filter-x
                :y           filter-y
                :width       filter-width
                :height      filter-height
                :filterUnits "objectBoundingBox"
                :color-interpolation-filters "sRGB"}
       (for [[index entry] (d/enumerate filters)]
         [:& filter-entry {:key (dm/str filter-id "-" index)
                           :entry entry}])])))

