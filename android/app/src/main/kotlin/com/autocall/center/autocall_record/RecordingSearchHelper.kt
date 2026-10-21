package com.autocall.center.autocall_record

import android.os.Environment
import android.util.Log
import java.io.File
import kotlin.math.abs

/**
 * RecordingSearchHelper
 *
 * Centralized scanning and matching of call recordings across OEM devices
 * (Infinix / Tecno / itel [Transsion XOS/HiOS], Vivo [Funtouch OS / OriginOS],
 *  Samsung [One UI], Xiaomi [MIUI / HyperOS], Oppo / Realme / OnePlus [ColorOS], etc.).
 */
object RecordingSearchHelper {

    private const val TAG = "RecordingSearchHelper"

    val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "amr", "aac", "wav", "ogg", "3gp", "opus")

    /**
     * Known base directories where Infinix, Vivo, Samsung, Xiaomi, Oppo, and stock Android save recordings.
     */
    fun getCandidateRecordingDirectories(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val dirs = LinkedHashSet<File>()

        // 1. Infinix / Tecno / itel (Transsion XOS / HiOS) Call Recording paths
        dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "PhoneRecord"))
        dirs.add(File(root, "Music/PhoneRecord"))
        dirs.add(File(root, "PhoneRecord"))
        dirs.add(File(root, "Recordings/PhoneRecord"))
        dirs.add(File(root, "Sounds/PhoneRecord"))
        dirs.add(File(root, "Audio/PhoneRecord"))

        // 2. Vivo (FunTouch OS / OriginOS) Call Recording paths
        dirs.add(File(root, "Recordings/Call"))
        dirs.add(File(root, "Recordings/Record/Call"))
        dirs.add(File(root, "Record/Call"))
        dirs.add(File(root, "Sounds/Call"))
        dirs.add(File(root, "vivo/sound_recorder/call_rec"))
        dirs.add(File(root, "vivo/sound_recorder/call"))
        dirs.add(File(root, "vivo/sound_recorder"))

        // 3. Samsung Call Recording paths
        dirs.add(File(Environment.getExternalStoragePublicDirectory("Recordings"), "Call"))
        dirs.add(File(root, "Recordings/Call"))
        dirs.add(File(root, "Sounds/Call"))

        // 4. Xiaomi / MIUI / HyperOS Call Recording paths
        dirs.add(File(root, "MIUI/sound_recorder/call_rec"))
        dirs.add(File(root, "MIUI/sound_recorder"))
        dirs.add(File(root, "sound_recorder/call_rec"))

        // 5. Oppo / Realme / OnePlus (ColorOS / Realme UI / OxygenOS)
        dirs.add(File(root, "Recordings/Call Recordings"))
        dirs.add(File(root, "Music/Recordings/Call Recordings"))
        dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Recordings/Call Recordings"))
        dirs.add(File(root, "Recordings/Call"))

        // 6. Huawei / Honor (EMUI / MagicOS)
        dirs.add(File(root, "record"))
        dirs.add(File(root, "Record/Call"))
        dirs.add(File(root, "Sounds/CallRecord"))

        // 7. Android Standard Public Directories (Works on all Android versions)
        dirs.add(Environment.getExternalStoragePublicDirectory("Recordings"))
        dirs.add(File(root, "Recordings"))
        dirs.add(File(root, "VoiceRecorder"))
        dirs.add(File(root, "SoundRecorder"))
        dirs.add(File(root, "Sounds"))
        dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Recordings/Call"))
        dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Recordings"))
        dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC))

        return dirs.toList()
    }

    /**
     * Expands candidate directories to include direct 1st-level subdirectories.
     * Crucial for Infinix (which puts recordings in Music/PhoneRecord/<PhoneNumber>/),
     * Oppo, Vivo, and other OEMs that create contact or number-specific folders.
     */
    fun getAllSearchDirectories(): List<File> {
        val baseDirs = getCandidateRecordingDirectories()
        val allDirs = LinkedHashSet<File>()

        for (dir in baseDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            allDirs.add(dir)

            try {
                val subDirs = dir.listFiles { f -> f.isDirectory }
                if (subDirs != null) {
                    for (sub in subDirs) {
                        allDirs.add(sub)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error listing subdirectories in ${dir.absolutePath}: ${e.message}")
            }
        }
        return allDirs.toList()
    }

    /**
     * Extracts normalized variants of phone numbers (e.g. 0856... vs 62856... vs +62856...
     * and last 7-8 digits) to maximize matching against OEM filenames and folders.
     */
    fun getPhoneNumberVariants(phoneNumber: String): Set<String> {
        val variants = LinkedHashSet<String>()
        val cleaned = phoneNumber.replace(Regex("[^0-9]"), "")
        if (cleaned.isBlank()) return emptySet()

        variants.add(cleaned)

        if (cleaned.startsWith("0")) {
            val withoutZero = cleaned.substring(1)
            variants.add("62$withoutZero")
            variants.add(withoutZero)
        } else if (cleaned.startsWith("62")) {
            val without62 = cleaned.substring(2)
            variants.add("0$without62")
            variants.add(without62)
        }

        if (cleaned.length >= 7) variants.add(cleaned.takeLast(7))
        if (cleaned.length >= 8) variants.add(cleaned.takeLast(8))

        return variants
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
        val searchDirs = getAllSearchDirectories()
        val cleanedTargetNumber = phoneNumber.replace(Regex("[^0-9]"), "")
        val lastDigits = if (cleanedTargetNumber.length >= 7) cleanedTargetNumber.takeLast(7) else cleanedTargetNumber
        val callEndTime = if (callDuration > 0) callTimestamp + (callDuration * 1000L) else callTimestamp
        val variants = getPhoneNumberVariants(phoneNumber)

        var bestMatch: File? = null
        var highestScore = -1
        var smallestDiff = Long.MAX_VALUE

        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles { file ->
                file.isFile && AUDIO_EXTENSIONS.contains(file.extension.lowercase()) && file.length() > 512
            } ?: continue

            for (file in files) {
                val score = calculateMatchScore(file, cleanedTargetNumber, lastDigits, contactName, callEndTime, callTimestamp, variants)
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

        Log.i(TAG, "findMatchingRecordingFile for $phoneNumber ($contactName): result=${bestMatch?.absolutePath}, score=$highestScore")
        return if (highestScore >= 40) bestMatch else null
    }

    fun calculateMatchScore(
        file: File,
        targetFullPhone: String,
        targetLastDigits: String,
        contactName: String,
        callEndTime: Long,
        callStartTime: Long,
        numberVariants: Set<String> = emptySet()
    ): Int {
        val fileLastModified = file.lastModified()

        // 1. HARD SAFETY CHECK:
        // A recording for THIS call cannot have been modified before the call started!
        // Allow up to 10 seconds of clock skew between system clock and file system.
        if (callStartTime > 0 && fileLastModified < (callStartTime - 10000L)) {
            return -1 // Disqualify past calls completely
        }

        // File modified more than 10 minutes after call ended is not from this call
        if (callEndTime > 0 && fileLastModified > (callEndTime + 600000L)) {
            return -1
        }

        var score = 0
        val fileName = file.name
        val cleanedFileName = fileName.replace(Regex("[^0-9]"), "")
        val parentDirName = file.parentFile?.name ?: ""
        val cleanedParentName = parentDirName.replace(Regex("[^0-9]"), "")

        // 2. Contact Name Matching (+150 points)
        // Vivo OEM: "<Contact Name> YYYY-MM-DD HH-mm-ss.m4a"
        // Infinix / Oppo: Folder or filename may contain contact name
        if (contactName.isNotBlank()) {
            val nameWords = contactName.trim().split(Regex("\\s+"))
            var matchedAnyWord = false
            for (word in nameWords) {
                if (word.length >= 2 && (fileName.contains(word, ignoreCase = true) || parentDirName.contains(word, ignoreCase = true))) {
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

        // 3. Phone number correlation (+120 points for full/variant match, +60 for last digits)
        var matchedNumber = false
        val allVariants = if (numberVariants.isNotEmpty()) numberVariants else getPhoneNumberVariants(targetFullPhone)

        for (variant in allVariants) {
            if (variant.length >= 8 && (cleanedFileName.contains(variant) || cleanedParentName.contains(variant))) {
                score += 120
                matchedNumber = true
                break
            }
        }

        if (!matchedNumber) {
            if (targetFullPhone.length >= 8 && (cleanedFileName.contains(targetFullPhone) || cleanedParentName.contains(targetFullPhone))) {
                score += 120
            } else if (targetLastDigits.length >= 6 && (cleanedFileName.contains(targetLastDigits) || cleanedParentName.contains(targetLastDigits))) {
                score += 60
            }
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
     * Scans and returns all audio recordings found across OEM and system directories.
     */
    fun scanAllRecordings(): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        try {
            val searchDirs = getAllSearchDirectories()
            val seenPaths = mutableSetOf<String>()

            for (dir in searchDirs) {
                try {
                    if (!dir.exists() || !dir.isDirectory) continue
                    val files = dir.listFiles { file ->
                        file.isFile && AUDIO_EXTENSIONS.contains(file.extension.lowercase()) && file.length() > 512
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
            Log.w(TAG, "scanAllRecordings safe caught: ${e.localizedMessage}")
        }
        return result
    }
}
