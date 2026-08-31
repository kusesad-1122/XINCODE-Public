# XINCODE Responses API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every XINCODE call that selects `apiPathType = "responses"` use the OpenAI Responses API correctly while preserving existing provider protocols and keeping all credentials out of shipped artifacts.

**Architecture:** Add a pure `ResponsesProtocol` in the `provider` module for endpoint selection, request conversion, non-streaming extraction, and SSE aggregation. Route `OpenAiClient`, `AuxModels`, and `JudgeService` through that protocol; keep Chat Completions and Anthropic branches intact. Add configuration choices and protocol regression tests.

**Tech Stack:** Kotlin 1.9.24, Android/Gradle, OkHttp 4.12, `org.json`, JUnit 4, GitHub Actions, GitHub Release workflow.

## Global Constraints

- Never place a real API key in source, tests, logs, APKs, Git history, or GitHub Actions artifacts.
- Responses endpoint is `/v1/responses`, with exactly one `/v1` when the configured base already contains a version segment.
- Responses tools are flat function definitions and tool results use `function_call_output` with the model-provided `call_id`.
- `response.completed` is complete; `response.incomplete` is truncated; `response.failed` and `error` are failures.
- Existing Chat Completions, Anthropic, custom URL, and database migration behavior must remain compatible.
- Verification must include provider unit tests, Kotlin compilation, debug APK assembly, lint, and the repository CI result.

---

### Task 1: Add failing protocol contract tests

**Files:**
- Create: `provider/src/test/java/com/xincode/provider/ResponsesProtocolTest.kt`
- Modify: `provider/build.gradle.kts`

**Interfaces:**
- Tests target the public pure functions introduced in Task 2: `ResponsesProtocol.endpoint`, `buildRequest`, `extractResponse`, and `SseParser.accept`.

- [ ] **Step 1: Add provider test dependencies**

Add JUnit and JVM `org.json` to `provider/build.gradle.kts`:

```kotlin
testImplementation("junit:junit:4.13.2")
testImplementation("org.json:json:20231013")
```

- [ ] **Step 2: Write tests before production changes**

Cover these literal behaviors:

```kotlin
@Test fun endpointDoesNotDuplicateVersionSegment() {
    assertEquals("https://api.openai.com/v1/responses", ResponsesProtocol.endpoint("https://api.openai.com/v1"))
    assertEquals("https://api.openai.com/v1/responses", ResponsesProtocol.endpoint("https://api.openai.com"))
}
@Test fun requestUsesResponsesInputToolsAndTextFormat() { /* assert exact request JSON fields */ }
@Test fun responseExtractsOutputTextAndFunctionCalls() { /* assert text, function call, and usage */ }
@Test fun sseAggregatesTextFunctionArgumentsDoneAndUsage() { /* assert the documented event sequence */ }
@Test fun incompleteSseIsMarkedTruncated() { /* assert response.incomplete sets truncated */ }
```

Fixtures must include `response.output_item.added`, multiple `response.function_call_arguments.delta`, `response.function_call_arguments.done`, `response.output_item.done`, and `response.completed`/`response.incomplete`. Use a fake literal token such as `test-key-not-a-secret`; never use an actual credential.

- [ ] **Step 3: Run the focused test through CI and verify RED**

Because the local sandbox cannot download Gradle, commit only the test/dependency change to an `agent/responses-api` branch, open a draft PR against `main`, and inspect the provider test job. Expected result: compilation/test failure because `ResponsesProtocol` and `SseParser` do not exist yet. Do not proceed until the failure is caused by the missing implementation rather than workflow setup.

- [ ] **Step 4: Commit the red test**

```bash
git add provider/build.gradle.kts provider/src/test/java/com/xincode/provider/ResponsesProtocolTest.kt
git commit -m "test: define Responses API protocol contract"
```

---

### Task 2: Implement the pure Responses protocol layer

**Files:**
- Create: `provider/src/main/java/com/xincode/provider/ResponsesProtocol.kt`
- Create: `provider/src/main/java/com/xincode/provider/ResponsesStreamParser.kt`
- Test: `provider/src/test/java/com/xincode/provider/ResponsesProtocolTest.kt`

**Interfaces:**
- `ResponsesProtocol.endpoint(baseUrl: String): String`
- `ResponsesProtocol.buildRequest(model, messages, tools, maxOutputTokens, topP, responseFormat, thinkingEnabled, thinkingLevel): JSONObject`
- `ResponsesProtocol.extractResponse(body: JSONObject): ResponsesResult`
- `ResponsesStreamParser.accept(sseLine: String): Unit`
- `ResponsesStreamParser.result(): ResponsesResult`

- [ ] **Step 1: Implement endpoint and input conversion**

Convert system messages into `instructions`, user/assistant messages into `input`, assistant tool calls into `function_call`, and tool rows into `function_call_output` using the existing tool call ID as `call_id`.

- [ ] **Step 2: Implement request conversion**

Build only Responses fields. Convert `{type:"function",function:{...}}` tools to flat functions. Convert nested Chat Completions `response_format.json_schema` to `text.format`. Map thinking levels to valid Responses reasoning effort values and omit Chat Completions-only fields.

- [ ] **Step 3: Implement non-streaming extraction**

Read text from `output_text` first, then walk `output[]` for `message.content[].text` and `function_call` items. Normalize usage to the existing `AgentStreamResult` fields while retaining detail objects.

- [ ] **Step 4: Implement streaming aggregation**

Parse `data:` SSE lines, ignore comments/blank lines, aggregate text and reasoning deltas, create tool-call entries from `response.output_item.added`, append argument deltas, accept `response.function_call_arguments.done` as a complete-argument fallback, and finalize on `response.output_item.done`.

- [ ] **Step 5: Run the focused test and verify GREEN**

Run the provider test in CI and confirm all protocol tests pass. Fix production code, not the fixture, if any assertion fails.

- [ ] **Step 6: Commit the protocol layer**

```bash
git add provider/src/main/java/com/xincode/provider/ResponsesProtocol.kt provider/src/main/java/com/xincode/provider/ResponsesStreamParser.kt provider/src/test/java/com/xincode/provider/ResponsesProtocolTest.kt
git commit -m "feat: add Responses API protocol layer"
```

---

### Task 3: Route OpenAiClient through the shared protocol

**Files:**
- Modify: `provider/src/main/java/com/xincode/provider/OpenAiClient.kt`
- Modify: `provider/src/main/java/com/xincode/provider/AgentTypes.kt` only if the normalized result needs a documented field
- Test: `provider/src/test/java/com/xincode/provider/ResponsesProtocolTest.kt`

- [ ] **Step 1: Replace the existing Responses request builder**

Use `ResponsesProtocol.buildRequest` in `agentStreamResponses`; remove duplicate local input/tool/format conversion after the shared functions are used.

- [ ] **Step 2: Correct streaming completion semantics**

Treat `response.completed` as `truncated=false`; treat `response.incomplete` as `truncated=true` while returning accumulated output and usage; propagate `response.failed` and `error` through `onError` with their server message.

- [ ] **Step 3: Add Responses support to `chat()`**

For `apiPathType == "responses"`, send a non-streaming Responses request and extract `output_text` or the first usable message text. Keep the old `choices[0].message.content` parser for other paths.

- [ ] **Step 4: Add Responses support to `chatStream()`**

For `apiPathType == "responses"`, use the shared SSE parser and invoke the existing callbacks. Keep current Chat Completions SSE behavior for other paths.

- [ ] **Step 5: Add extra-header handling and safe logging consistently**

Ensure Responses requests apply configured extra headers, never log Authorization or request bodies, and close OkHttp responses on every path.

- [ ] **Step 6: Run provider tests and compile**

Run `./gradlew :provider:testDebugUnitTest :provider:compileDebugKotlin` in CI. Expected result: green tests and provider compilation.

- [ ] **Step 7: Commit**

```bash
git add provider/src/main/java/com/xincode/provider/OpenAiClient.kt provider/src/main/java/com/xincode/provider/AgentTypes.kt
git commit -m "feat: route OpenAiClient calls through Responses API"
```

---

### Task 4: Adapt auxiliary model and Goal judge calls

**Files:**
- Modify: `app/src/main/java/com/xincode/app/AuxModels.kt`
- Modify: `provider/src/main/java/com/xincode/provider/JudgeService.kt`
- Modify: `app/src/main/java/com/xincode/app/XincodeApplication.kt`
- Modify: `app/src/main/java/com/xincode/app/GoalRunner.kt` only if constructor data must include protocol type
- Test: `provider/src/test/java/com/xincode/provider/ResponsesProtocolTest.kt`

- [ ] **Step 1: Preserve `apiPathType` when resolving function configurations**

Extend auxiliary/judge resolved configuration to carry `openai`, `responses`, `anthropic`, or `custom`, and use the stored value when the call originates from a provider configuration.

- [ ] **Step 2: Route auxiliary text calls**

Use Responses request/response conversion when the resolved type is `responses`; retain Chat Completions for the default `openai` value.

- [ ] **Step 3: Route Goal judge calls**

Add an `apiPathType` constructor parameter defaulting to `openai`; use `ResponsesProtocol` for Responses judge requests and extract the judge text from `output_text` before parsing JSON.

- [ ] **Step 4: Update judge construction**

Pass `cfg.apiPathType` from `XincodeApplication.buildGoalJudge()` so function-model assignments actually select the configured protocol.

- [ ] **Step 5: Run app/provider unit tests and compile**

Run `./gradlew testDebugUnitTest compileDebugKotlin` in CI. Confirm existing tests still pass and no API key is used.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/xincode/app/AuxModels.kt provider/src/main/java/com/xincode/provider/JudgeService.kt app/src/main/java/com/xincode/app/XincodeApplication.kt app/src/main/java/com/xincode/app/GoalRunner.kt
git commit -m "feat: adapt auxiliary and judge calls to Responses"
```

---

### Task 5: Expose Responses configuration and update documentation

**Files:**
- Modify: `app/src/main/java/com/xincode/app/SupplierConfigScreen.kt`
- Modify: `app/src/main/java/com/xincode/app/ModelMarketScreen.kt`
- Modify: `provider/src/main/java/com/xincode/provider/ProviderProfiles.kt`
- Modify: `README.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add Responses selector**

Add a visible `responses` option labeled `OpenAI Responses (/v1/responses)` to the custom API path dropdown and preserve it during edit/save.

- [ ] **Step 2: Add an OpenAI Responses preset/profile**

Use the endpoint normalizer consistently, set `apiPathType = "responses"`, and use the existing documented `gpt-4o` default model.

- [ ] **Step 3: Document setup and credential boundary**

Document that users enter their own API key in the app's encrypted local configuration; no project API key is bundled. Document the Responses endpoint and supported features.

- [ ] **Step 4: Run lint/compile checks**

Run `./gradlew lintDebug compileDebugKotlin` in CI and fix any Compose/Kotlin errors.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/xincode/app/SupplierConfigScreen.kt app/src/main/java/com/xincode/app/ModelMarketScreen.kt provider/src/main/java/com/xincode/provider/ProviderProfiles.kt README.md CHANGELOG.md
git commit -m "feat: expose OpenAI Responses configuration"
```

---

### Task 6: Full verification, publish, and formal release

**Files:**
- Modify: `.github/workflows/build.yml` only if CI needs a deterministic Java/Gradle or release fix
- Create: no secret-bearing files

- [ ] **Step 1: Inspect the final diff and secret scan**

Run:

```bash
git status --short
git diff --check
rg -n --hidden -g '!*.png' -g '!*.jpg' -g '!*.jks' -g '!build/**' '(sk-[A-Za-z0-9_-]{20,}|OPENAI_API_KEY[[:space:]]*=|ghp_[A-Za-z0-9]{20,})' .
```

The scan must return no real credential. `test-key-not-a-secret` is allowed only in test fixtures.

- [ ] **Step 2: Push and run the complete CI**

Push the reviewed branch, ensure the draft PR triggers `XINCODE CI`, and wait for `compileDebugKotlin`, `assembleDebug`, `lintDebug`, and `testDebugUnitTest` to pass. Download the debug APK artifact only to verify that CI produced it; do not add it to git.

- [ ] **Step 3: Merge into `main`**

After the full CI run is green, merge the PR using the GitHub connector. Confirm `main` points at the merged commit.

- [ ] **Step 4: Create the next release tag**

Read the current version from `app/build.gradle.kts`, increment `versionCode` and patch the version name from `1.18` to `1.19`, commit that version bump, rerun CI, then create and push tag `v1.19` only after the main build is green.

- [ ] **Step 5: Verify release workflow**

Wait for the tag-triggered release job. Confirm the signed `app-release.apk` exists in the GitHub Release and that the workflow did not expose any secret in logs or artifacts.

- [ ] **Step 6: Final evidence report**

Report the merged commit, tag, CI run, release URL, APK asset name, test/build commands, and explicit statement that no OpenAI API key was bundled. If any check fails, report the failing job and continue fixing rather than claiming completion.
