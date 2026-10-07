#include <jni.h>
#include <android/log.h>
#include <mutex>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
#include <thread>
#include "llama.h"
#include "common.h"
#include "sampling.h"
#include "unicode.h"
#define TAG "LocusLlamaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ponytail: single resident model in static pointers, upgrade to instance registry if multi-model needed
enum class NativeModelKind {
    CHAT = 0,
    EMBEDDING = 1
};

struct LocusSamplingParams {
    double temperature = 0.7;
    double top_p = 0.9;
    int32_t max_tokens = 1024;
};

static std::mutex g_mutex;
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static NativeModelKind g_model_kind = NativeModelKind::CHAT;
static void internal_unload() {
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}
static void locus_llama_log_callback(ggml_log_level level, const char* text, void* /* user_data */) {
    if (text == nullptr) return;
    if (level == GGML_LOG_LEVEL_ERROR) {
        LOGE("%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        LOGI("[WARN] %s", text);
    } else if (level == GGML_LOG_LEVEL_INFO) {
        LOGI("%s", text);
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_locus_core_ai_llama_LlamaRuntime_nativeLoadModel(
    JNIEnv* env,
    jobject /* thiz */,
    jstring j_path,
    jint j_model_kind
) {
    if (j_path == nullptr) {
        return JNI_FALSE;
    }

    const char* path_chars = env->GetStringUTFChars(j_path, nullptr);
    if (path_chars == nullptr) {
        return JNI_FALSE;
    }
    std::string path(path_chars);
    env->ReleaseStringUTFChars(j_path, path_chars);

    std::lock_guard<std::mutex> lock(g_mutex);

    // Unload any previously loaded model (single-model-loaded-at-a-time contract)
    internal_unload();

    llama_backend_init();

    llama_log_set(locus_llama_log_callback, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU-first: M-1 requirement

    g_model = llama_model_load_from_file(path.c_str(), mparams);
    if (g_model == nullptr) {
        LOGE("Failed to load model from: %s", path.c_str());
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    g_model_kind = static_cast<NativeModelKind>(j_model_kind);

    uint32_t n_ctx_train = llama_model_n_ctx_train(g_model);
    uint32_t target_ctx = 2048;
    if (n_ctx_train > 0 && n_ctx_train < target_ctx) {
        target_ctx = n_ctx_train;
    }
    cparams.n_ctx = target_ctx;

    int hardware_threads = static_cast<int>(std::thread::hardware_concurrency());
    // On 8-core mobile SoCs (2 Prime + 6 Performance), 6 threads maximizes throughput without core contention
    cparams.n_threads = std::max(1, hardware_threads >= 8 ? 6 : (hardware_threads > 0 ? hardware_threads : 4));
    cparams.n_threads_batch = cparams.n_threads;

    if (g_model_kind == NativeModelKind::EMBEDDING) {
        cparams.embeddings = true;
        cparams.n_batch = std::min(target_ctx, 2048u);
        cparams.n_ubatch = cparams.n_batch;
    } else {
        cparams.embeddings = false;
        cparams.n_batch = std::min(target_ctx, 512u);
        cparams.n_ubatch = cparams.n_batch;
    }
    g_ctx = llama_init_from_model(g_model, cparams);
    if (g_ctx == nullptr) {
        LOGE("Failed to create llama context for model: %s", path.c_str());
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Successfully loaded model from: %s (kind=%d, n_ctx=%u, n_embd=%d)",
         path.c_str(), static_cast<int>(g_model_kind), target_ctx, llama_model_n_embd(g_model));
    return JNI_TRUE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_locus_core_ai_llama_LlamaRuntime_nativeEmbed(
    JNIEnv* env,
    jobject /* thiz */,
    jstring j_text
) {
    if (j_text == nullptr) {
        return nullptr;
    }

    const char* text_chars = env->GetStringUTFChars(j_text, nullptr);
    if (text_chars == nullptr) {
        return nullptr;
    }
    std::string text(text_chars);
    env->ReleaseStringUTFChars(j_text, text_chars);

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_model == nullptr || g_ctx == nullptr) {
        LOGE("Cannot embed: model or context not loaded");
        return nullptr;
    }

    if (g_model_kind != NativeModelKind::EMBEDDING) {
        LOGE("Cannot embed: model not loaded as EMBEDDING");
        return nullptr;
    }

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) {
        LOGE("Failed to get model vocab");
        return nullptr;
    }

    std::vector<llama_token> tokens = common_tokenize(vocab, text, true, false);
    if (tokens.empty()) {
        jfloatArray empty = env->NewFloatArray(0);
        return empty;
    }

    const int n_ctx = llama_n_ctx(g_ctx);
    if (static_cast<int>(tokens.size()) > n_ctx) {
        tokens.resize(n_ctx);
    }

    llama_batch batch = llama_batch_init(static_cast<int32_t>(tokens.size()), 0, 1);
    for (size_t i = 0; i < tokens.size(); i++) {
        common_batch_add(batch, tokens[i], static_cast<llama_pos>(i), { 0 }, true);
    }

    llama_memory_clear(llama_get_memory(g_ctx), true);

    if (llama_decode(g_ctx, batch) < 0) {
        LOGE("llama_decode failed");
        llama_batch_free(batch);
        return nullptr;
    }

    const int n_embd = llama_model_n_embd(g_model);
    const enum llama_pooling_type pooling_type = llama_pooling_type(g_ctx);

    const float* raw_embd = nullptr;
    if (pooling_type == LLAMA_POOLING_TYPE_NONE) {
        raw_embd = llama_get_embeddings_ith(g_ctx, -1);
    } else {
        raw_embd = llama_get_embeddings_seq(g_ctx, 0);
    }

    if (raw_embd == nullptr) {
        raw_embd = llama_get_embeddings(g_ctx);
    }

    if (raw_embd == nullptr || n_embd <= 0) {
        LOGE("Failed to extract embeddings");
        llama_batch_free(batch);
        return nullptr;
    }

    std::vector<float> normalized(n_embd);
    common_embd_normalize(raw_embd, normalized.data(), n_embd, 2);

    llama_batch_free(batch);

    jfloatArray result = env->NewFloatArray(n_embd);
    if (result == nullptr) {
        return nullptr;
    }
    env->SetFloatArrayRegion(result, 0, n_embd, normalized.data());
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_locus_core_ai_llama_LlamaRuntime_nativeGenerate(
    JNIEnv* env,
    jobject /* thiz */,
    jstring j_prompt,
    jdouble j_temperature,
    jdouble j_top_p,
    jint j_max_tokens,
    jobject j_callback
) {
    if (j_prompt == nullptr || j_callback == nullptr) {
        return JNI_FALSE;
    }

    const char* prompt_chars = env->GetStringUTFChars(j_prompt, nullptr);
    if (prompt_chars == nullptr) {
        return JNI_FALSE;
    }
    std::string prompt(prompt_chars);
    env->ReleaseStringUTFChars(j_prompt, prompt_chars);

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_model == nullptr || g_ctx == nullptr) {
        LOGE("Cannot generate text: model or context not loaded");
        return JNI_FALSE;
    }

    if (g_model_kind != NativeModelKind::CHAT) {
        LOGE("Cannot generate text: model not loaded as CHAT");
        return JNI_FALSE;
    }

    jclass callback_class = env->GetObjectClass(j_callback);
    if (callback_class == nullptr) {
        LOGE("Failed to get callback class");
        return JNI_FALSE;
    }

    jmethodID on_token_method = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)Z");
    if (on_token_method == nullptr) {
        LOGE("Failed to get onToken method ID");
        return JNI_FALSE;
    }

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) {
        LOGE("Failed to get model vocab");
        return JNI_FALSE;
    }

    LocusSamplingParams params{
        static_cast<double>(j_temperature),
        static_cast<double>(j_top_p),
        static_cast<int32_t>(j_max_tokens)
    };

    // Tokenize prompt. For generative chat: add_special = true, parse_special = true
    std::vector<llama_token> prompt_tokens = common_tokenize(vocab, prompt, true, true);
    if (prompt_tokens.empty()) {
        return JNI_TRUE;
    }

    const int n_ctx = llama_n_ctx(g_ctx);
    if (static_cast<int>(prompt_tokens.size()) >= n_ctx) {
        LOGE("Prompt too long for context size: %zu >= %d", prompt_tokens.size(), n_ctx);
        prompt_tokens.resize(std::max(1, n_ctx - 1));
    }

    // Clear KV cache / memory
    llama_memory_clear(llama_get_memory(g_ctx), true);

    const int n_batch = llama_n_batch(g_ctx);
    llama_batch batch = llama_batch_init(n_batch, 0, 1);

    // Decode prompt tokens
    for (size_t i = 0; i < prompt_tokens.size(); i++) {
        bool need_logits = (i == prompt_tokens.size() - 1);
        common_batch_add(batch, prompt_tokens[i], static_cast<llama_pos>(i), { 0 }, need_logits);
        if (batch.n_tokens == n_batch || i == prompt_tokens.size() - 1) {
            if (llama_decode(g_ctx, batch) != 0) {
                LOGE("llama_decode failed on prompt tokens");
                llama_batch_free(batch);
                return JNI_FALSE;
            }
            common_batch_clear(batch);
        }
    }

    // Initialize sampling
    common_params_sampling sparams;
    sparams.temp = static_cast<float>(params.temperature);
    sparams.top_p = static_cast<float>(params.top_p);
    sparams.seed = LLAMA_DEFAULT_SEED;

    struct common_sampler* sampler = common_sampler_init(g_model, sparams);
    if (sampler == nullptr) {
        LOGE("Failed to initialize common_sampler");
        llama_batch_free(batch);
        return JNI_FALSE;
    }

    // Accept prompt tokens into sampler for repetition penalty
    for (auto id : prompt_tokens) {
        common_sampler_accept(sampler, id, false);
    }

    int max_tokens = params.max_tokens;
    if (max_tokens <= 0) {
        max_tokens = 1024;
    }
    int n_past = static_cast<int>(prompt_tokens.size());
    if (n_past + max_tokens > n_ctx) {
        max_tokens = n_ctx - n_past;
    }

    std::string cached_token_chars;
    bool should_continue = true;

    for (int step = 0; step < max_tokens && should_continue; step++) {
        const llama_token token_id = common_sampler_sample(sampler, g_ctx, -1);
        common_sampler_accept(sampler, token_id, true);

        if (llama_vocab_is_eog(vocab, token_id)) {
            LOGI("Generation finished at EOG token %d (step %d)", token_id, step);
            break;
        }

        std::string piece = common_token_to_piece(vocab, token_id, false);
        cached_token_chars += piece;

        if (common_utf8_is_complete(cached_token_chars)) {
            jstring j_piece = env->NewStringUTF(cached_token_chars.c_str());
            if (j_piece != nullptr) {
                jboolean cb_result = env->CallBooleanMethod(j_callback, on_token_method, j_piece);
                env->DeleteLocalRef(j_piece);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    should_continue = false;
                } else if (cb_result == JNI_FALSE) {
                    should_continue = false;
                }
            }
            cached_token_chars.clear();
        }

        if (!should_continue || step + 1 >= max_tokens) {
            break;
        }

        common_batch_clear(batch);
        common_batch_add(batch, token_id, static_cast<llama_pos>(n_past), { 0 }, true);
        n_past++;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("llama_decode failed during generation step %d", step);
            break;
        }
    }

    if (!cached_token_chars.empty() && should_continue) {
        jstring j_piece = env->NewStringUTF(cached_token_chars.c_str());
        if (j_piece != nullptr) {
            env->CallBooleanMethod(j_callback, on_token_method, j_piece);
            env->DeleteLocalRef(j_piece);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
    }

    common_sampler_free(sampler);
    llama_batch_free(batch);

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_locus_core_ai_llama_LlamaRuntime_nativeUnload(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    internal_unload();
    LOGI("Model unloaded");
}

}
