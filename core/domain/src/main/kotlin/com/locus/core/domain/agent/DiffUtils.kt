/*
 * Copyright 2026 Locus Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.locus.core.domain.agent

object DiffUtils {
    /**
     * Computes a unified-diff-style string comparing [oldText] with [newText] for [fileLabel].
     */
    @Suppress("ReturnCount")
    fun computeUnifiedDiff(
        fileLabel: String,
        oldText: String,
        newText: String,
    ): String {
        if (oldText == newText) {
            return "--- a/$fileLabel\n+++ b/$fileLabel\n@@ -1 +1 @@\n (no changes)"
        }

        val oldLines = if (oldText.isEmpty()) emptyList() else oldText.lines()
        val newLines = if (newText.isEmpty()) emptyList() else newText.lines()

        if (oldLines.isEmpty()) {
            val sb = StringBuilder()
            sb.appendLine("--- a/$fileLabel")
            sb.appendLine("+++ b/$fileLabel")
            sb.appendLine("@@ -0,0 +1,${newLines.size} @@")
            for (line in newLines) {
                sb.appendLine("+$line")
            }
            return sb.toString().trimEnd()
        }

        if (newLines.isEmpty()) {
            val sb = StringBuilder()
            sb.appendLine("--- a/$fileLabel")
            sb.appendLine("+++ b/$fileLabel")
            sb.appendLine("@@ -1,${oldLines.size} +0,0 @@")
            for (line in oldLines) {
                sb.appendLine("-$line")
            }
            return sb.toString().trimEnd()
        }

        return formatLcsDiff(fileLabel, oldLines, newLines)
    }

    private fun formatLcsDiff(
        fileLabel: String,
        oldLines: List<String>,
        newLines: List<String>,
    ): String {
        val n = oldLines.size
        val m = newLines.size
        val dp = Array(n + 1) { IntArray(m + 1) }

        for (i in 0 until n) {
            for (j in 0 until m) {
                if (oldLines[i] == newLines[j]) {
                    dp[i + 1][j + 1] = dp[i][j] + 1
                } else {
                    dp[i + 1][j + 1] = maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        val diffOps = mutableListOf<String>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1]) {
                diffOps.add(" " + oldLines[i - 1])
                i--
                j--
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                diffOps.add("+" + newLines[j - 1])
                j--
            } else if (i > 0) {
                diffOps.add("-" + oldLines[i - 1])
                i--
            }
        }
        diffOps.reverse()

        val sb = StringBuilder()
        sb.appendLine("--- a/$fileLabel")
        sb.appendLine("+++ b/$fileLabel")
        sb.appendLine("@@ -1,$n +1,$m @@")
        for (op in diffOps) {
            sb.appendLine(op)
        }
        return sb.toString().trimEnd()
    }
}
