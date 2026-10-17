package com.codeair.mhe.operator.guard

object StaleTargetPolicy {
    private val generationPrefix = Regex("^g(\\d+):")

    fun isStale(targetId: String, currentGeneration: Long): Boolean {
        val parsed = generationOf(targetId) ?: return true
        return parsed != currentGeneration
    }

    fun generationOf(targetId: String): Long? {
        return generationPrefix.find(targetId)?.groupValues?.get(1)?.toLongOrNull()
    }
}
