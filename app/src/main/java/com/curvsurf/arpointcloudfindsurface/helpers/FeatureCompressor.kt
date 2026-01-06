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
    var zScore: Float = 1.0f

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
    val meanPoint = features.reduce { acc, feature ->
        acc + feature
    }.xyz / features.size.toFloat()

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

    return newMeanPoint / count.toFloat()
}

private fun getZScoreFilteredMeanPoint(features: ArrayDeque<Vector4>, zScore: Float): Vector3 {
    var totalWeight = 0f
    var weightedMeanPoint = Vector3()
    for (feature in features) {
        weightedMeanPoint += feature.xyz * feature.w
        totalWeight += feature.w
    }
    weightedMeanPoint /= totalWeight

    val weightedSquaredDistances = features.map { feature ->
        distance2(feature.xyz, weightedMeanPoint) * feature.w
    }

    val variance = weightedSquaredDistances.sum() / totalWeight
    val threshold = zScore * zScore * variance

    var filteredTotalWeight = 0f
    var filteredWeightedMeanPoint = Vector3()
    for (i in 0 until features.size) {
        if (weightedSquaredDistances[i] > threshold) continue
        val feature = features[i]
        filteredWeightedMeanPoint += feature.xyz * feature.w
        filteredTotalWeight += feature.w
    }

    return filteredWeightedMeanPoint / filteredTotalWeight
}
