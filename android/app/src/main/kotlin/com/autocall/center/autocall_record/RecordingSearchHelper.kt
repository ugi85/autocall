package com.autocall.center.autocall_record

import android.os.Environment
import java.io.File
import kotlin.math.abs

/**
 * RecordingSearchHelper
 *
 * Centralized scanning and matching of call recordings across OEM devices
 * (Vivo / FunTouch OS, Samsung, Xiaomi / MIUI, etc.).
 */
object RecordingSearchHelper {

    val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "amr", "aac", "wav", "ogg", "3gp")

    /**
     * Known directories where Vivo, Samsung, Xiaomi, and stock Android save recordings.
     */
    fun getCandidateRecordingDirectories(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val dirs = mutableListOf<File>()

        // 1. Vivo (FunTouch OS / OriginOS) Call Recording paths
        dirs.add(File(root, "Recordings/Call"))
        dirs.add(File(root, "Recordings/Record/Call"))
        dirs.add(File(root, "Record/Call"))
        dirs.add(File(root, "Sounds/Call"))
        dirs.add(File(root, "vivo/sound_recorder/call_rec"))
        dirs.add(File(root, "vivo/sound_recorder/call"))
        dirs.add(File(root, "vivo/sound_recorder"))

        // 2. Samsung Call Recording paths
        dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS), "Call"))

        // 3. Xiaomi / MIUI / HyperOS Call Recording paths
        dirs.add(File(root, "MIUI/sound_recorder/call_rec"))
        dirs.add(File(root, "MIUI/sound_recorder"))

        // 4. Android 10+ Standard Public Directories
        dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS))
        dirs.add(File(root, "Recordings"))
        dirs.add(File(root, "VoiceRecorder"))
        dirs.add(File(root, "SoundRecorder"))
        dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Recordings/Call"))
        dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Recordings"))

        return dirs
    }

    /**
     * Matches a recording file based on phone number and timestamp proximity.
     */
    fun findMatchingRecordingFile(
        phoneNumber: String,
        callTimestamp: Long,
        callDuration: Long
    ): File? {
        val searchDirs = getCandidateRecordingDirectories()
        val cleanedTargetNumber = phoneNumber.replace(Regex("[^0-9]"), "")
        val lastDigits = if (cleanedTargetNumber.length >= 7) cleanedTargetNumber.takeLast(7) else cleanedTargetNumber
        val callEndTime = if (callDuration > 0) callTimestamp + (callDuration * 1000L) else callTimestamp

        var bestMatch: File? = null
        var highestScore = -1

        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles { file ->
                file.isFile && AUDIO_EXTENSIONS.contains(file.extension.lowercase()) && file.length() > 512
            } ?: continue

            for (file in files) {
                val score = calculateMatchScore(file, lastDigits, callEndTime, callTimestamp)
                if (score > highestScore) {
                    highestScore = score
                    bestMatch = file
                }
            }
        }

        return if (highestScore >= 35) bestMatch else null
    }

    fun calculateMatchScore(
        file: File,
        targetLastDigits: String,
        callEndTime: Long,
        callStartTime: Long
    ): Int {
        var score = 0
        val fileName = file.name
        val cleanedFileName = fileName.replace(Regex("[^0-9]"), "")

        // 1. Phone number correlation (+60 points)
        if (targetLastDigits.isNotEmpty() && cleanedFileName.contains(targetLastDigits)) {
            score += 60
        }

        // 2. Proximity calculation (+5 to +50 points)
        val fileLastModified = file.lastModified()
        val diffFromEnd = abs(fileLastModified - callEndTime)
        val diffFromStart = abs(fileLastModified - callStartTime)
        val minDiff = minOf(diffFromEnd, diffFromStart)

        when {
            minDiff <= 30_000L -> score += 50
            minDiff <= 60_000L -> score += 40
            minDiff <= 120_000L -> score += 30
            minDiff <= 300_000L -> score += 15
            minDiff <= 600_000L -> score += 5
        }

        return score
    }

    /**
     * Scans and returns all audio recordings found in the OEM directories.
     */
    fun scanAllRecordings(): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        val searchDirs = getCandidateRecordingDirectories()
        val seenPaths = mutableSetOf<String>()

        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles { file ->
                file.isFile && AUDIO_EXTENSIONS.contains(file.extension.lowercase()) && file.length() > 1024
            } ?: continue

            for (file in files) {
                if (seenPaths.add(file.absolutePath)) {
                    result.add(
                        mapOf(
                            "name" to file.name,
                            "path" to file.absolutePath,
                            "size" to file.length(),
                            "lastModified" to file.lastModified()
                        )
                    )
                }
            }
        }

        // Sort descending by last modified date
        result.sortByDescending { it["lastModified"] as Long }
        return result
    }
}
