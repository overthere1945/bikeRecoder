package com.cowork.bikerecoder

object AppVersion {
    fun display(versionName: String): String {
        val base = versionName.substringBefore("-")
        return "V$base"
    }
}
