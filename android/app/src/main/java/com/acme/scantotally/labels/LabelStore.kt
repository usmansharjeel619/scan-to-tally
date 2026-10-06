package com.acme.scantotally.labels

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Separate from stock/history resets. A saved label's identity never changes. */
class LabelStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun recent(company: String): List<LabelBatch> = synchronized(lock) {
        directory.listFiles { f -> f.extension == "json" }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { json.decodeFromString<LabelBatch>(it.readText()) }
            .filter { it.company == company }.sortedByDescending { it.createdAt }.take(100)
    }

    fun save(batch: LabelBatch) = synchronized(lock) {
        require(batch.id.matches(Regex("[a-f0-9-]{36}")))
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create label storage." }
        val target = File(directory, "${batch.id}.json")
        val temp = File.createTempFile("label-", ".tmp", directory)
        try {
            FileOutputStream(temp).use {
                it.write(json.encodeToString(batch).toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    companion object { private val lock = Any() }
}
