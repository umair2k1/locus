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

package com.locus.core.domain.notes

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer
import java.util.Date
import javax.inject.Inject

/**
 * Pure JVM [YamlCodec] backed by SnakeYAML 2.x.
 *
 * Uses [SafeConstructor] for safe deserialization preventing arbitrary code/class execution,
 * and [DumperOptions] configured for block style matching frontmatter conventions (no flow-style maps/lists).
 */
class SnakeYamlCodec
    @Inject
    constructor() : YamlCodec {
        private val yaml: Yaml by lazy {
            val loaderOptions = LoaderOptions()
            val dumperOptions =
                DumperOptions().apply {
                    defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                    isPrettyFlow = false
                    splitLines = false
                }
            val representer = Representer(dumperOptions)
            Yaml(SafeConstructor(loaderOptions), representer, dumperOptions, loaderOptions)
        }

        override fun decode(yamlText: String): Map<String, Any?> {
            if (yamlText.isBlank()) return emptyMap()
            val loaded = yaml.load<Any?>(yamlText)
            require(loaded == null || loaded is Map<*, *>) {
                "Expected YAML mapping but found: ${loaded?.let { it::class.java.simpleName }}"
            }
            return (loaded as? Map<*, *>).orEmpty().entries.associateTo(linkedMapOf()) { (k, v) ->
                k.toString() to sanitizeYamlValue(v)
            }
        }

        override fun encode(fields: Map<String, Any?>): String = yaml.dump(fields)

        private fun sanitizeYamlValue(value: Any?): Any? =
            when (value) {
                is Date -> value.toInstant().toString()
                is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to sanitizeYamlValue(v) }
                is List<*> -> value.map { sanitizeYamlValue(it) }
                else -> value
            }
    }
