package com.locus.core.domain.keep

import java.io.InputStream

data class KeepImportResult(
    val importedCount: Int,
    val skippedCount: Int,
    val errors: List<String> = emptyList(),
)

interface KeepImportRepository {
    suspend fun importFromZip(inputStream: InputStream): KeepImportResult
}
