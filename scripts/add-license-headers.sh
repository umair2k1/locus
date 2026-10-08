#!/usr/bin/env bash
set -euo pipefail

# scripts/add-license-headers.sh: Idempotently adds Apache-2.0 license header
# to all source files across app, core/domain, core/data, and core/ai.
# Excludes third-party submodules (e.g. llama.cpp) and build directories.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

python3 - << 'EOF'
import os
import re

HEADER = """/*
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
"""

MODULES = ["app/src", "core/domain/src", "core/data/src", "core/ai/src"]
EXTENSIONS = {".kt", ".java"}

count = 0
for mod in MODULES:
    if not os.path.exists(mod):
        continue
    for root, dirs, files in os.walk(mod):
        # Exclude build and external third-party llama.cpp
        if "build" in root or "llama.cpp" in root:
            continue
        for f in files:
            ext = os.path.splitext(f)[1]
            if ext in EXTENSIONS:
                path = os.path.join(root, f)
                with open(path, "r", encoding="utf-8") as fp:
                    content = fp.read()

                if "Licensed under the Apache License" in content:
                    continue

                # Preserve EXPERIMENTAL banner if present
                if content.startswith("// EXPERIMENTAL"):
                    lines = content.splitlines(True)
                    first_line = lines[0]
                    rest = "".join(lines[1:])
                    new_content = first_line + "\n" + HEADER + "\n" + rest.lstrip("\n")
                else:
                    new_content = HEADER + "\n" + content.lstrip("\n")

                with open(path, "w", encoding="utf-8") as fp:
                    fp.write(new_content)
                count += 1

print(f"Added Apache-2.0 license header to {count} file(s).")
EOF
