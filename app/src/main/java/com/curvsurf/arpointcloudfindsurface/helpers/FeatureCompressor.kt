package com.curvsurf.arpointcloudfindsurface.helpers

import androidx.xr.runtime.math.Vector3
import androidx.xr.runtime.math.Vector4
import com.curvsurf.arpointcloudfindsurface.helpers.math.distance2
import com.curvsurf.arpointcloudfindsurface.helpers.math.xyz
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue

class FeatureCompressor(
    private val maxIdentifierCount: Int,
    private val maxPointBinSize: Int
) {
    var zScore: Float = 2.0f

    private val identifierSet: MutableSet<Int> = mutableSetOf()
    private val identifierList: IntArrayFIFOQueue = IntArrayFIFOQueue()
    private val featureBins: MutableMap<Int, ArrayDeque<Vector4>> = mutableMapOf()
    private val pointList: MutableMap<Int, Vector3> = mutableMapOf()

    val points: Array<Vector3> get() = pointList.values.toTypedArray()

    var updated: Boolean = false

    fun clear() {
        identifierSet.clear()
        identifierList.clear()
        featureBins.clear()
        pointList.clear()
    }
    
    fun append(features: Array<Vector4>, identifiers: IntArray) {
        require(features.size == identifiers.size)

        for (i in 0 until features.size) {
            val feature = features[i]
            val id = identifiers[i]
            if (identifierSet.add(id)) {
                if (identifierSet.size > maxIdentifierCount) {
                    val removedID = identifierList.dequeueLastInt()
                    identifierSet.remove(removedID)
                    featureBins.remove(removedID)
                    pointList.remove(removedID)
                }
                identifierList.enqueueFirst(id)
            }

            val bin = featureBins.getOrPut(id) { ArrayDeque(maxPointBinSize) }
            bin.add(feature)
            if (bin.size > maxPointBinSize) {
                bin.removeFirst()
            }

            if (bin.size == 1) {
                pointList[id] = feature.xyz
            } else {
                pointList[id] = getZScoreFilteredMeanPoint(bin, zScore)
            }

            featureBins[id] = bin
        }
        updated = true
    }

    fun update() {
        for (id in identifierSet) {
            val bin = featureBins[id] ?: continue
            if (bin.size == 1) {
//                pointList[id] = bin.first().xyz
            } else {
                pointList[id] = getZScoreFilteredMeanPoint(bin, zScore)
            }
        }
    }
}

private fun getZScoreFilteredMeanPointWithoutWeights(features: ArrayDeque<Vector4>, zScore: Float): Vector3 {
    if (features.isEmpty()) return Vector3()
    val meanPoint = features.fold(Vector3()) { acc, feature ->
        acc + feature.xyz
    } / features.size.toFloat()

    val distanceSquared = features.map { feature ->
        distance2(feature.xyz, meanPoint)
    }

    val variance = distanceSquared.average().toFloat()
    val thresholdSquared = zScore * zScore * variance

    var count = 0
    var newMeanPoint = Vector3()
    for (i in 0 until features.size) {
        if (distanceSquared[i] > thresholdSquared) continue
        newMeanPoint += features[i].xyz
        count++
    }

    return if (count > 0) newMeanPoint / count.toFloat() else meanPoint
}

private fun getZScoreFilteredMeanPoint(features: ArrayDeque<Vector4>, zScore: Float): Vector3 {
    var totalWeight = 0f
    var weightedMeanPoint = Vector3()
    for (feature in features) {
        weightedMeanPoint += feature.xyz * feature.w
        totalWeight += feature.w
    }
    if (totalWeight <= 0f) return features.last().xyz
    weightedMeanPoint /= totalWeight

    val squaredDistances = features.map { feature ->
        distance2(feature.xyz, weightedMeanPoint)
    }

    val weightedVariance = features.indices.sumOf { i ->
        (squaredDistances[i] * features[i].w).toDouble()
    }.toFloat() / totalWeight

    val threshold = zScore * zScore * weightedVariance

    var filteredTotalWeight = 0f
    var filteredWeightedMeanPoint = Vector3()
    for (i in 0 until features.size) {
        if (squaredDistances[i] > threshold) continue
        val feature = features[i]
        filteredWeightedMeanPoint += feature.xyz * feature.w
        filteredTotalWeight += feature.w
    }

    return if (filteredTotalWeight > 0f) {
        filteredWeightedMeanPoint / filteredTotalWeight
    } else {
        weightedMeanPoint
    }
}
