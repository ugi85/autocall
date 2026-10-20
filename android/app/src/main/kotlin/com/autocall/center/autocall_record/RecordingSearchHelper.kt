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
        dirs.add(File(Environment.getExternalStoragePublicDirectory("Recordings"), "Call"))

        // 3. Xiaomi / MIUI / HyperOS Call Recording paths
        dirs.add(File(root, "MIUI/sound_recorder/call_rec"))
        dirs.add(File(root, "MIUI/sound_recorder"))

        // 4. Android Standard Public Directories (Works on all Android versions)
        dirs.add(Environment.getExternalStoragePublicDirectory("Recordings"))
        dirs.add(File(root, "Recordings"))
        dirs.add(File(root, "VoiceRecorder"))
        dirs.add(File(root, "SoundRecorder"))
        dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Recordings/Call"))
        dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Recordings"))

        return dirs
    }

    /**
     * Matches a recording file based on phone number, contact name, and timestamp.
     */
    fun findMatchingRecordingFile(
        phoneNumber: String,
        contactName: String = "",
        callTimestamp: Long,
        callDuration: Long
    ): File? {
        val searchDirs = getCandidateRecordingDirectories()
        val cleanedTargetNumber = phoneNumber.replace(Regex("[^0-9]"), "")
        val lastDigits = if (cleanedTargetNumber.length >= 7) cleanedTargetNumber.takeLast(7) else cleanedTargetNumber
        val callEndTime = if (callDuration > 0) callTimestamp + (callDuration * 1000L) else callTimestamp

        var bestMatch: File? = null
        var highestScore = -1
        var smallestDiff = Long.MAX_VALUE

        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles { file ->
                file.isFile && AUDIO_EXTENSIONS.contains(file.extension.lowercase()) && file.length() > 512
            } ?: continue

            for (file in files) {
                val score = calculateMatchScore(file, cleanedTargetNumber, lastDigits, contactName, callEndTime, callTimestamp)
                if (score <= 0) continue

                val fileLastModified = file.lastModified()
                val diffFromEnd = abs(fileLastModified - callEndTime)

                if (score > highestScore || (score == highestScore && diffFromEnd < smallestDiff)) {
                    highestScore = score
                    smallestDiff = diffFromEnd
                    bestMatch = file
                }
            }
        }

        return if (highestScore >= 40) bestMatch else null
    }

    fun calculateMatchScore(
        file: File,
        targetFullPhone: String,
        targetLastDigits: String,
        contactName: String,
        callEndTime: Long,
        callStartTime: Long
    ): Int {
        val fileLastModified = file.lastModified()

        // 1. HARD SAFETY CHECK:
        // A recording for THIS call cannot have been modified before the call started!
        // Allow at most 4 seconds of clock skew. If file is older, it belongs to a PREVIOUS call!
        if (fileLastModified < (callStartTime - 4000L)) {
            return -1 // Disqualify past calls completely
        }

        var score = 0
        val fileName = file.name
        val cleanedFileName = fileName.replace(Regex("[^0-9]"), "")

        // 2. Contact Name Matching (+150 points)
        // Vivo OEM recorder names files: "<Contact Name> YYYY-MM-DD HH-mm-ss.m4a"
        if (contactName.isNotBlank()) {
            val nameWords = contactName.trim().split(Regex("\\s+"))
            var matchedAnyWord = false
            for (word in nameWords) {
                if (word.length >= 2 && fileName.contains(word, ignoreCase = true)) {
                    score += 150
                    matchedAnyWord = true
                    break
                }
            }
            // If contactName is provided, but the filename clearly belongs to another known contact name, penalize
            if (!matchedAnyWord && (fileName.startsWith("Bunda", true) || fileName.startsWith("Alya", true))) {
                score -= 100
            }
        }

        // 3. Phone number correlation (+100 points for full, +60 for last 7 digits)
        if (targetFullPhone.length >= 8 && cleanedFileName.contains(targetFullPhone)) {
            score += 100
        } else if (targetLastDigits.length >= 6 && cleanedFileName.contains(targetLastDigits)) {
            score += 60
        }

        // 4. Proximity calculation based on Call End Time (+5 to +60 points)
        val diffFromEnd = abs(fileLastModified - callEndTime)

        when {
            diffFromEnd <= 10_000L -> score += 60
            diffFromEnd <= 20_000L -> score += 45
            diffFromEnd <= 40_000L -> score += 30
            diffFromEnd <= 75_000L -> score += 15
            diffFromEnd <= 120_000L -> score += 5
            else -> score -= 30
        }

        return score
    }

    /**
     * Scans and returns all audio recordings found in the OEM directories.
     */
    fun scanAllRecordings(): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        try {
            val searchDirs = getCandidateRecordingDirectories()
            val seenPaths = mutableSetOf<String>()

            for (dir in searchDirs) {
                try {
                    if (!dir.exists() || !dir.isDirectory) continue
                    val files = dir.listFiles { file ->
                        file.isFile && AUDIO_EXTENSIONS.contains(file.extension.lowercase()) && file.length() > 1024
                    } ?: continue

                    for (file in files) {
                        try {
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
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }

            // Sort descending by last modified date safely
            result.sortByDescending { (it["lastModified"] as? Long) ?: 0L }
        } catch (e: Throwable) {
            android.util.Log.w("RecordingSearchHelper", "scanAllRecordings safe caught: ${e.localizedMessage}")
        }
        return result
    }
}
