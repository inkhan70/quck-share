package com.quickshare.core.util

/**
 * Extension-to-MIME mapping.
 *
 * `MimeTypeMap` is an Android API and is not available to the core module, so the mapping is done
 * here explicitly. Keeping it in the core means the same value is used for the wire manifest, the
 * SAF file-type hint and the UI icon, so a `.apk` is always described as an APK everywhere.
 */
object MimeTypes {

    const val DEFAULT = "application/octet-stream"

    private val BY_EXTENSION = mapOf(
        // Images
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "heic" to "image/heic", "heif" to "image/heif", "svg" to "image/svg+xml",
        // Video
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "avi" to "video/x-msvideo", "mov" to "video/quicktime", "3gp" to "video/3gpp",
        "m4v" to "video/x-m4v", "ts" to "video/mp2t",
        // Audio
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg",
        "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac",
        "opus" to "audio/opus", "mid" to "audio/midi",
        // Documents
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "rtf" to "application/rtf",
        "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv",
        "json" to "application/json", "xml" to "application/xml",
        "html" to "text/html", "htm" to "text/html", "css" to "text/css",
        "js" to "text/javascript", "kt" to "text/x-kotlin", "java" to "text/x-java",
        // Archives and packages
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "apk" to "application/vnd.android.package-archive",
        "apks" to "application/vnd.android.package-archive",
        "xapk" to "application/vnd.android.package-archive",
        "aab" to "application/x-authorware-bin",
        "jar" to "application/java-archive",
        "epub" to "application/epub+zip",
        // Contacts / calendars / misc
        "vcf" to "text/x-vcard", "ics" to "text/calendar",
        "ttf" to "font/ttf", "otf" to "font/otf", "woff" to "font/woff", "woff2" to "font/woff2",
    )

    /** Best-effort MIME type from a filename; never returns null. */
    fun fromFileName(name: String?): String {
        if (name.isNullOrBlank()) return DEFAULT
        val ext = SafePaths.extensionOf(name)
        if (ext.isEmpty()) return DEFAULT
        return BY_EXTENSION[ext] ?: DEFAULT
    }

    /** A coarse category, used to pick an icon without the UI needing its own table. */
    fun categoryOf(mime: String?, name: String? = null): FileCategory {
        val resolved = mime?.takeIf { it.isNotBlank() && it != DEFAULT } ?: fromFileName(name)
        return when {
            resolved.startsWith("image/") -> FileCategory.IMAGE
            resolved.startsWith("video/") -> FileCategory.VIDEO
            resolved.startsWith("audio/") -> FileCategory.AUDIO
            resolved == "application/vnd.android.package-archive" -> FileCategory.APK
            resolved.contains("zip") || resolved.contains("rar") ||
                resolved.contains("7z") || resolved.contains("tar") ||
                resolved.contains("gzip") || resolved.contains("compressed") -> FileCategory.ARCHIVE
            resolved == "application/pdf" -> FileCategory.PDF
            resolved.startsWith("text/") || resolved.contains("document") ||
                resolved.contains("spreadsheet") || resolved.contains("presentation") -> FileCategory.DOCUMENT
            else -> FileCategory.OTHER
        }
    }
}

/** Coarse file categories, so the UI can choose an icon without duplicating the MIME table. */
enum class FileCategory {
    IMAGE, VIDEO, AUDIO, DOCUMENT, PDF, ARCHIVE, APK, OTHER
}
