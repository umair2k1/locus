# 🚨 MANDATORY AI INSTRUCTION: PHONE ADB DEPLOY, TEST & BUILD POLICY

> **CRITICAL RULES FOR ALL AI ASSISTANTS / AGENTS WORKING IN THIS REPOSITORY**
>
> 1. **MANDATORY ADB DEPLOYMENT & TESTING**: Whenever you finish building or modifying UI, navigation, database, or app code, ALWAYS deploy to the connected phone via ADB:
>    `sh ./tools/phone.sh deploy oss` (or `full`) OR:
>    `adb install -r app/build/outputs/apk/oss/debug/app-oss-debug.apk && adb shell am start -n com.locus.app/.MainActivity`
> 2. **ON-DEVICE RUNTIME VERIFICATION**: Use `adb logcat` or `adb shell dumpsys activity` to verify that the app launches and runs without crashes. Do not rely solely on unit tests when the phone is connected.
> 3. **OFFLOAD NATIVE C/C++ / LLAMA.CPP TASKS**: When working on native code (e.g. Prompt 24 `llama.cpp JNI`, embedding runners, or benchmarks), offload native compilation and benchmark execution directly to the phone via the SSH tunnel:
>    `sh ./tools/phone.sh ssh "<command>"`
>    or via `adb forward tcp:8022 tcp:8022` + `ssh -i ~/.ssh/id_turbotransfer -p 8022 localhost "<command>"`.
> 4. **ANDROID GRADLE BUILD PIPELINE**: AGP (Android Gradle Plugin) executes on the host PC (due to Google's bundled `aapt2` host requirements), and then immediately streams to the phone over ADB for installation and testing.
> 5. **MANDATORY CI PRE-PUSH QUALITY GATES**: The repository enforces GitHub Actions CI on every push (`.github/workflows/ci.yml`). NEVER push code that fails CI checks. Before pushing, ALWAYS run and ensure 100% success on:
>    - `sh ./gradlew spotlessCheck` (auto-format with `sh ./gradlew spotlessApply` first if needed)
>    - `sh ./scripts/import-hygiene.sh` (architecture boundary rules)
>    - `sh ./gradlew detekt` (static analysis, complexity, magic numbers, method length)
>    - `sh ./gradlew :app:assembleOssDebug :app:assembleFullDebug` (assemble both flavors)
>    - `sh ./gradlew test` (unit test suite across all modules)
>
> 7. **RELEASING TO GITHUB**: A GitHub Release (with installable APKs) is created ONLY by pushing a `v*` tag — a plain `git push` to `master` NEVER triggers a release. To cut a release:
>    ```bash
>    git tag v<X.Y.Z> && git push origin v<X.Y.Z>
>    ```
>    The tag push triggers `.github/workflows/release.yml`, which runs all CI gates, builds both APK flavors, and publishes the release automatically. NEVER assume a release was created from a commit push alone; verify at `github.com/umair2k1/locus/releases`.

---

## 🛠️ Command Reference for AI Agents

### 1. Check Device & Node Status
```bash
sh ./tools/phone.sh status
```

### 2. Deploy App to Phone
```bash
# Build and deploy oss debug flavor
sh ./tools/phone.sh deploy oss

# Build and deploy full debug flavor
sh ./tools/phone.sh deploy full
```

### 3. Run On-Device Connected Tests
```bash
sh ./tools/phone.sh test
# Or directly:
sh ./gradlew connectedOssDebugAndroidTest
```

### 4. Monitor App Logcat
```bash
sh ./tools/phone.sh logcat
```

### 5. Execute Commands on Phone (Termux Native Node)
```bash
sh ./tools/phone.sh ssh "uname -a && nproc"
sh ./tools/phone.sh ssh "clang --version"
```

### 6. Run CI Pre-Push Verification Suite
```bash
# 1. Formatting check (fix with spotlessApply)
sh ./gradlew spotlessApply && sh ./gradlew spotlessCheck

# 2. Architectural import boundary check
sh ./scripts/import-hygiene.sh

# 3. Static analysis
sh ./gradlew detekt

# 4. Assemble all flavors
sh ./gradlew :app:assembleOssDebug :app:assembleFullDebug

# 5. Unit tests
sh ./gradlew test
```

### 7. Commit & Push Prompt Deliverables
```bash
git add -A
git commit -m "<prompt-commit-message>"
git push origin master
```

### 8. Cut a GitHub Release
```bash
git tag v<X.Y.Z>
git push origin v<X.Y.Z>
# Release appears at github.com/umair2k1/locus/releases after CI completes (~5 min)
```
