package de.floorballcompanion.util

fun String?.isLiveStatus(): Boolean {
    val s = this?.lowercase() ?: return false
    return s in setOf("running", "ingame")
}
