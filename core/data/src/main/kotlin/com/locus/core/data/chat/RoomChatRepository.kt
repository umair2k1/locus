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

package com.locus.core.data.chat

import com.locus.core.domain.chat.ChatMessage
import com.locus.core.domain.chat.ChatRepository
import com.locus.core.domain.chat.ChatSession
import com.locus.core.domain.chat.CitedSource
import com.locus.core.domain.notes.UuidV7
import com.locus.core.domain.time.Clock
import com.locus.core.domain.time.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomChatRepository
    @Inject
    constructor(
        private val chatDao: ChatDao,
        private val clock: Clock,
        private val dispatchers: DispatcherProvider,
    ) : ChatRepository {
        override fun observeSessions(): Flow<List<ChatSession>> =
            chatDao
                .observeSessions()
                .map { list -> list.map { it.toDomain() } }
                .flowOn(dispatchers.io)

        override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
            chatDao
                .observeMessages(sessionId)
                .map { list -> list.map { it.toDomain() } }
                .flowOn(dispatchers.io)

        override suspend fun createSession(name: String): ChatSession =
            withContext(dispatchers.io) {
                val now = clock.now()
                val id = UuidV7.generate(clock)
                val entity =
                    ChatSessionEntity(
                        id = id,
                        name = name,
                        createdAt = now,
                        modifiedAt = now,
                    )
                chatDao.insertSession(entity)
                entity.toDomain()
            }

        override suspend fun appendMessage(
            sessionId: String,
            message: ChatMessage,
        ): Unit =
            withContext(dispatchers.io) {
                val messageId = if (message.id.isBlank()) UuidV7.generate(clock) else message.id
                val now = clock.now()
                val entity =
                    ChatMessageEntity(
                        id = messageId,
                        sessionId = sessionId,
                        role = message.role,
                        content = message.content,
                        citationsJson = encodeCitations(message.citations),
                        timestamp = message.timestamp,
                    )
                chatDao.appendMessageAndUpdateSession(entity, now)
            }

        private fun ChatSessionEntity.toDomain(): ChatSession =
            ChatSession(
                id = id,
                name = name,
                createdAt = createdAt,
                modifiedAt = modifiedAt,
            )

        private fun ChatMessageEntity.toDomain(): ChatMessage =
            ChatMessage(
                id = id,
                sessionId = sessionId,
                role = role,
                content = content,
                citations = decodeCitations(citationsJson),
                timestamp = timestamp,
            )

        private fun encodeCitations(citations: List<CitedSource>): String {
            if (citations.isEmpty()) return "[]"
            val array = JSONArray()
            for (source in citations) {
                val obj = JSONObject()
                obj.put("noteId", source.noteId)
                obj.put("noteTitle", source.noteTitle)
                val headingArray = JSONArray()
                for (heading in source.headingPath) {
                    headingArray.put(heading)
                }
                obj.put("headingPath", headingArray)
                array.put(obj)
            }
            return array.toString()
        }

        private fun decodeCitations(json: String): List<CitedSource> {
            if (json.isBlank() || json == "[]") return emptyList()
            return try {
                val array = JSONArray(json)
                List(array.length()) { index ->
                    val obj = array.getJSONObject(index)
                    val headingArray = obj.optJSONArray("headingPath")
                    val headingPath =
                        if (headingArray != null) {
                            List(headingArray.length()) { headingArray.getString(it) }
                        } else {
                            emptyList()
                        }
                    CitedSource(
                        noteId = obj.getString("noteId"),
                        noteTitle = obj.getString("noteTitle"),
                        headingPath = headingPath,
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
