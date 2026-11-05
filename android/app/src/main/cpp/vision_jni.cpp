// JNI-Brücke zum Bildmodell (llama.cpp mtmd): ein kleines Modell (z. B. SmolVLM-256M) beschreibt ein Bild als Text.
// Das Chat-Modell bleibt reiner Text; die Beschreibung geht als Text in den Chat.
//
// Wie in llama_jni.cpp gehen Texte als UTF-8-Bytes über die Grenze. Modell und mmproj-Datei werden über Pfade geladen
// (die App kopiert sie vorher in ihren privaten Speicher; sie sind klein).
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"

#define TAG "AiChatVision"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Vision {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    mtmd_context * mtmd = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_ctx = 0;
    int n_batch = 512;
    std::atomic<bool> cancel{false};
    std::string stats = "{}";
};

std::string g_error;
std::once_flag g_backend_once;

void log_callback(enum ggml_log_level level, const char * text, void *) {
    __android_log_write(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, TAG, text);
}

void init_backend_once() {
    std::call_once(g_backend_once, [] {
        llama_log_set(log_callback, nullptr);
        mtmd_log_set(log_callback, nullptr);
        llama_backend_init();
    });
}

std::string path_of(JNIEnv * env, jstring s) {
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

std::string bytes_to_string(JNIEnv * env, jbyteArray bytes) {
    if (!bytes) return {};
    const jsize len = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(len), '\0');
    env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray string_to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return out;
}

// Länge des Präfixes, das nicht mitten in einer Mehrbyte-Folge endet.
size_t utf8_complete_prefix(const std::string & s) {
    const size_t n = s.size();
    size_t i = n;
    for (int back = 0; i > 0 && back < 4; back++) {
        const unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) == 0x80) {
            i--;
            continue;
        }
        const size_t need = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
        return (n - (i - 1)) >= need ? n : i - 1;
    }
    return n;
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    char buf[256];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big(static_cast<size_t>(-n), '\0');
        n = llama_token_to_piece(vocab, token, big.data(), static_cast<int32_t>(big.size()), 0, false);
        return n > 0 ? big.substr(0, static_cast<size_t>(n)) : std::string();
    }
    return std::string(buf, static_cast<size_t>(n));
}

double ms_since(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
}

void release(Vision * v) {
    if (!v) return;
    if (v->mtmd) mtmd_free(v->mtmd);
    if (v->ctx) llama_free(v->ctx);
    if (v->model) llama_model_free(v->model);
    delete v;
}

Vision * vision_of(jlong handle) { return reinterpret_cast<Vision *>(handle); }

}  // namespace

extern "C" {

// Lädt Sprachteil (GGUF) und Bildteil (mmproj-GGUF). 0 bei Fehler (Text über lastError()).
JNIEXPORT jlong JNICALL Java_app_aichatmcp_local_VisionNative_load(JNIEnv * env, jobject, jstring jmodel, jstring jmmproj,
                                                                   jint n_threads) {
    init_backend_once();
    const std::string model_path = path_of(env, jmodel);
    const std::string mmproj_path = path_of(env, jmmproj);
    try {
        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.use_extra_bufts = false;
        llama_model * model = llama_model_load_from_file(model_path.c_str(), mp);
        if (!model) {
            g_error = "Das Bildmodell konnte nicht geladen werden (Datei lesbar? Format GGUF?)";
            return 0;
        }
        auto * v = new Vision();
        v->model = model;
        v->vocab = llama_model_get_vocab(model);

        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = 2048;
        cp.n_batch = static_cast<uint32_t>(v->n_batch);
        cp.n_ubatch = static_cast<uint32_t>(v->n_batch);
        cp.n_threads = n_threads;
        cp.n_threads_batch = n_threads;
        v->ctx = llama_init_from_model(model, cp);
        if (!v->ctx) {
            g_error = "Der Kontext des Bildmodells konnte nicht angelegt werden (zu wenig Arbeitsspeicher?)";
            release(v);
            return 0;
        }
        v->n_ctx = static_cast<int>(llama_n_ctx(v->ctx));

        mtmd_context_params params = mtmd_context_params_default();
        params.use_gpu = false;
        params.n_threads = n_threads;
        params.print_timings = false;
        params.warmup = false;  // spart einen Probelauf; der erste echte Lauf lädt ohnehin alles
        v->mtmd = mtmd_init_from_file(mmproj_path.c_str(), model, params);
        if (!v->mtmd) {
            g_error = "Die Bild-Hilfsdatei (mmproj) passt nicht zum Bildmodell oder ist nicht lesbar";
            release(v);
            return 0;
        }
        if (!mtmd_support_vision(v->mtmd)) {
            g_error = "Diese mmproj-Datei kann keine Bilder verarbeiten";
            release(v);
            return 0;
        }
        LOGI("Bildmodell geladen: n_ctx=%d, Threads=%d", v->n_ctx, n_threads);
        return reinterpret_cast<jlong>(v);
    } catch (const std::exception & ex) {
        g_error = std::string("Fehler beim Laden des Bildmodells: ") + ex.what();
        return 0;
    }
}

JNIEXPORT jstring JNICALL Java_app_aichatmcp_local_VisionNative_lastError(JNIEnv * env, jobject) {
    return env->NewStringUTF(g_error.c_str());
}

JNIEXPORT void JNICALL Java_app_aichatmcp_local_VisionNative_free(JNIEnv *, jobject, jlong handle) {
    release(vision_of(handle));
}

JNIEXPORT void JNICALL Java_app_aichatmcp_local_VisionNative_cancel(JNIEnv *, jobject, jlong handle) {
    if (Vision * v = vision_of(handle)) v->cancel = true;
}

JNIEXPORT jstring JNICALL Java_app_aichatmcp_local_VisionNative_stats(JNIEnv * env, jobject, jlong handle) {
    Vision * v = vision_of(handle);
    return env->NewStringUTF(v ? v->stats.c_str() : "{}");
}

// Beschreibt ein Bild (Bytes einer JPEG/PNG-Datei) nach der Anweisung [prompt]; die Stücke gehen an [sink].
// Rückgabe: 0 Ende des Modells, 1 Token-Limit, 2 abgebrochen, 3 Kontext voll, -1 Fehler (Text über lastError()).
JNIEXPORT jint JNICALL Java_app_aichatmcp_local_VisionNative_describe(JNIEnv * env, jobject, jlong handle,
                                                                      jbyteArray jimage, jbyteArray jprompt,
                                                                      jint max_tokens, jobject sink) {
    Vision * v = vision_of(handle);
    if (!v) {
        g_error = "Kein Bildmodell geladen";
        return -1;
    }
    v->cancel = false;
    jclass sink_class = env->GetObjectClass(sink);
    jmethodID on_piece = env->GetMethodID(sink_class, "onPiece", "([B)Z");
    if (!on_piece) {
        g_error = "Interner Fehler: Ausgabe-Schnittstelle fehlt";
        return -1;
    }
    const auto t_start = std::chrono::steady_clock::now();

    // Bild dekodieren (stb_image: JPEG, PNG, BMP, GIF)
    const std::string image = bytes_to_string(env, jimage);
    mtmd_helper_bitmap_wrapper wrapper = mtmd_helper_bitmap_init_from_buf(
        v->mtmd, reinterpret_cast<const unsigned char *>(image.data()), image.size(), false, mtmd_helper_init_opt_default());
    if (!wrapper.bitmap) {
        g_error = "Das Bild konnte nicht gelesen werden (Format?)";
        return -1;
    }

    // Anweisung mit Bildmarke in die Chat-Vorlage des Modells packen
    const std::string content = std::string(mtmd_default_marker()) + "\n" + bytes_to_string(env, jprompt);
    llama_chat_message message = {"user", content.c_str()};
    const char * tmpl = llama_model_chat_template(v->model, nullptr);
    std::string formatted(content.size() * 2 + 1024, '\0');
    int n = llama_chat_apply_template(tmpl ? tmpl : "chatml", &message, 1, true, formatted.data(),
                                      static_cast<int32_t>(formatted.size()));
    if (n > static_cast<int>(formatted.size())) {
        formatted.assign(static_cast<size_t>(n), '\0');
        n = llama_chat_apply_template(tmpl ? tmpl : "chatml", &message, 1, true, formatted.data(),
                                      static_cast<int32_t>(formatted.size()));
    }
    if (n < 0) {
        mtmd_bitmap_free(wrapper.bitmap);
        g_error = "Die Chat-Vorlage des Bildmodells lässt sich nicht anwenden";
        return -1;
    }
    formatted.resize(static_cast<size_t>(n));

    mtmd_input_text text = {formatted.c_str(), formatted.size(), /*add_special=*/true, /*parse_special=*/true};
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const mtmd_bitmap * bitmaps[1] = {wrapper.bitmap};
    const int32_t tokenized = mtmd_tokenize(v->mtmd, chunks, &text, bitmaps, 1);
    mtmd_bitmap_free(wrapper.bitmap);
    if (tokenized != 0) {
        mtmd_input_chunks_free(chunks);
        g_error = "Das Bild konnte nicht in Tokens umgewandelt werden (Code " + std::to_string(tokenized) + ")";
        return -1;
    }
    const size_t prompt_tokens = mtmd_helper_get_n_tokens(chunks);
    if (static_cast<int>(prompt_tokens) + 8 >= v->n_ctx) {
        mtmd_input_chunks_free(chunks);
        g_error = "Das Bild braucht zu viele Tokens (" + std::to_string(prompt_tokens) + " von " + std::to_string(v->n_ctx) + ")";
        return -1;
    }

    // Jedes Bild beginnt von vorn: kein Verlauf, der Zwischenspeicher wird geleert.
    llama_memory_clear(llama_get_memory(v->ctx), true);
    llama_pos n_past = 0;
    if (v->cancel) {
        mtmd_input_chunks_free(chunks);
        return 2;
    }
    if (mtmd_helper_eval_chunks(v->mtmd, v->ctx, chunks, 0, 0, v->n_batch, true, &n_past) != 0) {
        mtmd_input_chunks_free(chunks);
        g_error = "Das Bild konnte nicht verarbeitet werden";
        return -1;
    }
    mtmd_input_chunks_free(chunks);
    const double prompt_ms = ms_since(t_start);

    // Greedy mit leichter Wiederholungsbremse: Beschreibungen sollen sachlich und stabil sein.
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(v->vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    int result = 1;
    int generated = 0;
    std::string pending;
    const auto t_gen = std::chrono::steady_clock::now();
    while (generated < max_tokens) {
        if (v->cancel) {
            result = 2;
            break;
        }
        const llama_token token = llama_sampler_sample(smpl, v->ctx, -1);
        if (llama_vocab_is_eog(v->vocab, token)) {
            result = 0;
            break;
        }
        pending += token_piece(v->vocab, token);
        generated++;
        const size_t complete = utf8_complete_prefix(pending);
        if (complete > 0) {
            jbyteArray bytes = string_to_bytes(env, pending.substr(0, complete));
            const jboolean carry_on = env->CallBooleanMethod(sink, on_piece, bytes);
            env->DeleteLocalRef(bytes);
            pending.erase(0, complete);
            if (env->ExceptionCheck() || !carry_on) {
                result = 2;
                break;
            }
        }
        if (n_past + 1 >= v->n_ctx) {
            result = 3;
            break;
        }
        llama_token next = token;
        if (llama_decode(v->ctx, llama_batch_get_one(&next, 1)) != 0) {
            g_error = "Die Berechnung ist fehlgeschlagen";
            result = -1;
            break;
        }
        n_past++;
    }
    llama_sampler_free(smpl);

    char stats[256];
    std::snprintf(stats, sizeof(stats), "{\"prompt\":%d,\"gen\":%d,\"prompt_ms\":%.0f,\"gen_ms\":%.0f}",
                  static_cast<int>(prompt_tokens), generated, prompt_ms, ms_since(t_gen));
    v->stats = stats;
    LOGI("%s", stats);
    return result;
}

}  // extern "C"
