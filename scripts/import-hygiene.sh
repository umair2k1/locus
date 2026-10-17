#!/usr/bin/env bash
set -euo pipefail

# scripts/import-hygiene.sh: Enforces architectural boundary import rules.
# (a) core/domain/src must not import android or androidx
# (b) app presentation packages (/ui/ or /viewmodel/) must not import core.data or core.ai outside /di/
# (c) app/src/main must not import flavor-specific com.locus.app.full.*
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

EXIT_CODE=0

echo "=== Running import hygiene checks ==="

# (a) Grep every .kt file under core/domain/src for ^import android or ^import androidx
DOMAIN_SRC="core/domain/src"
if [ -d "$DOMAIN_SRC" ]; then
    echo "Checking :core:domain for forbidden Android imports..."
    OFFENDING_FILES=()
    while IFS= read -r file; do
        [ -z "$file" ] && continue
        if grep -nE "^import (android|androidx)" "$file" >/dev/null 2>&1; then
            echo "  VIOLATION in $file:"
            grep -nE "^import (android|androidx)" "$file" | sed 's/^/    /'
            OFFENDING_FILES+=("$file")
        fi
    done < <(find "$DOMAIN_SRC" -type f -name "*.kt" 2>/dev/null || true)

    if [ ${#OFFENDING_FILES[@]} -gt 0 ]; then
        echo "ERROR: core/domain must be compile-enforced Android-free (found ${#OFFENDING_FILES[@]} offending file(s))."
        EXIT_CODE=1
    fi
fi

# (b) Grep every .kt file under app/src/main/kotlin/com/locus/app whose path contains /ui/ or /viewmodel/
# for import com.locus.core.data. or import com.locus.core.ai. outside /di/
APP_SRC="app/src/main/kotlin/com/locus/app"
if [ -d "$APP_SRC" ]; then
    echo "Checking app presentation layer (ui/viewmodel) for forbidden direct data/ai imports..."
    OFFENDING_FILES=()
    while IFS= read -r file; do
        [ -z "$file" ] && continue
        norm_file="${file//\\//}"
        if [[ "$norm_file" =~ (/ui/|/viewmodel/) ]] && [[ ! "$norm_file" =~ /di/ ]]; then
            if grep -nE "import com\.locus\.core\.(data|ai)\." "$file" >/dev/null 2>&1; then
                echo "  VIOLATION in $file:"
                grep -nE "import com\.locus\.core\.(data|ai)\." "$file" | sed 's/^/    /'
                OFFENDING_FILES+=("$file")
            fi
        fi
    done < <(find "$APP_SRC" -type f -name "*.kt" 2>/dev/null || true)

    if [ ${#OFFENDING_FILES[@]} -gt 0 ]; then
        echo "ERROR: app presentation packages (/ui/, /viewmodel/) must not import core.data or core.ai outside /di/ (found ${#OFFENDING_FILES[@]} offending file(s))."
        EXIT_CODE=1
    fi
fi

# (c) Grep every file under app/src/main for import com.locus.app.full.
MAIN_SRC="app/src/main"
if [ -d "$MAIN_SRC" ]; then
    echo "Checking app/src/main for forbidden flavor-specific imports (app.full.*)..."
    OFFENDING_FILES=()
    while IFS= read -r file; do
        [ -z "$file" ] && continue
        if grep -nE "import com\.locus\.app\.full\." "$file" >/dev/null 2>&1; then
            echo "  VIOLATION in $file:"
            grep -nE "import com\.locus\.app\.full\." "$file" | sed 's/^/    /'
            OFFENDING_FILES+=("$file")
        fi
    done < <(find "$MAIN_SRC" -type f \( -name "*.kt" -o -name "*.java" \) 2>/dev/null || true)

    if [ ${#OFFENDING_FILES[@]} -gt 0 ]; then
        echo "ERROR: app/src/main must not import flavor-specific com.locus.app.full.* (found ${#OFFENDING_FILES[@]} offending file(s))."
        EXIT_CODE=1
    fi
fi

if [ $EXIT_CODE -ne 0 ]; then
    echo "=== Import hygiene check FAILED ==="
    exit $EXIT_CODE
fi

echo "=== Import hygiene check PASSED ==="
exit 0
