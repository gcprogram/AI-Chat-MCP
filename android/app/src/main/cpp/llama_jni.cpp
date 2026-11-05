// JNI-Brücke zu llama.cpp: Modell laden, Chat-Vorlage anwenden, Text erzeugen (Stück für Stück an Kotlin).
//
// Text geht als UTF-8-Bytes (byte[]) über die Grenze, nicht als String: JNI-Strings sind "Modified UTF-8" und
// verstümmeln Zeichen außerhalb der Basisebene (Emojis). Stücke werden nur an vollständigen Zeichengrenzen geliefert.
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <unistd.h>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "AiChatLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    FILE * file = nullptr;  // nur beim Laden über einen Dateideskriptor: muss offen bleiben, llama.cpp schließt es nicht
    const llama_vocab * vocab = nullptr;
    int n_ctx = 0;
    int n_batch = 512;
    std::vector<llama_token> cache;  // Tokens, die im KV-Cache stehen (für die Wiederverwendung des Anfangs)
    std::atomic<bool> cancel{false};
    std::string stats = "{}";
};

std::string g_error;
std::once_flag g_backend_once;

void log_callback(enum ggml_log_level level, const char * text, void *) {
    __android_log_write(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, TAG, text);
}

// Länge des Präfixes, das nicht mitten in einer Mehrbyte-Folge endet.
size_t utf8_complete_prefix(const std::string & s) {
    const size_t n = s.size();
    size_t i = n;
    for (int back = 0; i > 0 && back < 4; back++) {
        const unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) == 0x80) {  // Folgebyte: weiter zurück zum Startbyte
            i--;
            continue;
        }
        const size_t need = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
        return (n - (i - 1)) >= need ? n : i - 1;
    }
    return n;
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    int n = -llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, false, true);
    std::vector<llama_token> tokens(std::max(n, 0));
    int r = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), false, true);
    tokens.resize(r < 0 ? 0 : r);
    return tokens;
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

std::string to_string(JNIEnv * env, jbyteArray bytes) {
    if (!bytes) return {};
    const jsize len = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(len), '\0');
    env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return out;
}

Engine * engine_of(jlong handle) { return reinterpret_cast<Engine *>(handle); }

double ms_since(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
}

}  // namespace

extern "C" {

}  // extern "C"

namespace {

void init_backend_once() {
    std::call_once(g_backend_once, [] {
        llama_log_set(log_callback, nullptr);
        llama_backend_init();
    });
}

// Gemeinsamer Teil des Ladens: Kontext anlegen. Bei Fehler wird das Modell freigegeben (die FILE* schließt der Aufrufer).
jlong create_engine(llama_model * model, FILE * file, jint n_ctx, jint n_threads) {
    if (!model) {
        g_error = "Das Modell konnte nicht geladen werden (Datei lesbar? Format GGUF?)";
        return 0;
    }
    const int train_ctx = llama_model_n_ctx_train(model);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(train_ctx > 0 ? std::min<int>(n_ctx, train_ctx) : n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        g_error = "Der Kontext konnte nicht angelegt werden (zu wenig Arbeitsspeicher?)";
        return 0;
    }
    auto * e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->file = file;
    e->vocab = llama_model_get_vocab(model);
    e->n_ctx = static_cast<int>(llama_n_ctx(ctx));
    e->n_batch = static_cast<int>(cp.n_batch);
    LOGI("Modell geladen: n_ctx=%d, Threads=%d", e->n_ctx, n_threads);
    return reinterpret_cast<jlong>(e);
}

llama_model_params model_params() {
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_extra_bufts = false;  // kein Umpacken der Gewichte: das würde das Modell zusätzlich in den RAM kopieren
    return mp;
}

}  // namespace

extern "C" {

// Laden über einen Dateipfad.
JNIEXPORT jlong JNICALL Java_app_aichatmcp_local_LlamaNative_load(JNIEnv * env, jobject, jstring jpath, jint n_ctx,
                                                                  jint n_threads) {
    init_backend_once();
    const char * path = env->GetStringUTFChars(jpath, nullptr);
    std::string model_path(path);
    env->ReleaseStringUTFChars(jpath, path);
    try {
        return create_engine(llama_model_load_from_file(model_path.c_str(), model_params()), nullptr, n_ctx, n_threads);
    } catch (const std::exception & ex) {
        g_error = std::string("Fehler beim Laden: ") + ex.what();
        return 0;
    }
}

// Laden über einen schon geöffneten Dateideskriptor (Datei aus dem Auswahldialog): der Pfad wäre für die App nicht
// lesbar, der Deskriptor schon. Er wird dupliziert, die Kopie bleibt bis free() offen.
JNIEXPORT jlong JNICALL Java_app_aichatmcp_local_LlamaNative_loadFd(JNIEnv *, jobject, jint fd, jint n_ctx,
                                                                    jint n_threads) {
    init_backend_once();
    const int copy = dup(fd);
    FILE * file = copy >= 0 ? fdopen(copy, "rb") : nullptr;
    if (!file) {
        if (copy >= 0) close(copy);
        g_error = "Der Dateideskriptor des Modells ist nicht nutzbar";
        return 0;
    }
    try {
        llama_model * model = llama_model_load_from_file_ptr(file, model_params());
        const jlong handle = create_engine(model, file, n_ctx, n_threads);
        if (handle == 0) fclose(file);
        return handle;
    } catch (const std::exception & ex) {
        fclose(file);
        g_error = std::string("Fehler beim Laden: ") + ex.what();
        return 0;
    }
}

JNIEXPORT jstring JNICALL Java_app_aichatmcp_local_LlamaNative_lastError(JNIEnv * env, jobject) {
    return env->NewStringUTF(g_error.c_str());
}

JNIEXPORT void JNICALL Java_app_aichatmcp_local_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    Engine * e = engine_of(handle);
    if (!e) return;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    if (e->file) fclose(e->file);
    delete e;
}

JNIEXPORT void JNICALL Java_app_aichatmcp_local_LlamaNative_cancel(JNIEnv *, jobject, jlong handle) {
    if (Engine * e = engine_of(handle)) e->cancel = true;
}

JNIEXPORT jint JNICALL Java_app_aichatmcp_local_LlamaNative_contextSize(JNIEnv *, jobject, jlong handle) {
    Engine * e = engine_of(handle);
    return e ? e->n_ctx : 0;
}

JNIEXPORT jstring JNICALL Java_app_aichatmcp_local_LlamaNative_stats(JNIEnv * env, jobject, jlong handle) {
    Engine * e = engine_of(handle);
    return env->NewStringUTF(e ? e->stats.c_str() : "{}");
}

// Wendet die Chat-Vorlage des Modells an (aus der GGUF-Datei) und liefert den Prompt als UTF-8; null bei Fehler.
JNIEXPORT jbyteArray JNICALL Java_app_aichatmcp_local_LlamaNative_applyTemplate(JNIEnv * env, jobject, jlong handle,
                                                                               jobjectArray roles,
                                                                               jobjectArray contents) {
    Engine * e = engine_of(handle);
    if (!e) return nullptr;
    const jsize count = env->GetArrayLength(roles);
    std::vector<std::string> role_store(static_cast<size_t>(count)), content_store(static_cast<size_t>(count));
    size_t total = 0;
    for (jsize i = 0; i < count; i++) {
        auto jrole = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        const char * r = env->GetStringUTFChars(jrole, nullptr);
        role_store[i] = r;
        env->ReleaseStringUTFChars(jrole, r);
        env->DeleteLocalRef(jrole);
        auto jcontent = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
        content_store[i] = to_string(env, jcontent);
        env->DeleteLocalRef(jcontent);
        total += content_store[i].size();
    }
    std::vector<llama_chat_message> chat(static_cast<size_t>(count));
    for (jsize i = 0; i < count; i++) chat[i] = {role_store[i].c_str(), content_store[i].c_str()};

    const char * tmpl = llama_model_chat_template(e->model, nullptr);
    for (int attempt = 0; attempt < 2; attempt++) {
        const char * use = tmpl ? tmpl : "chatml";
        std::string out(total * 2 + 1024, '\0');
        int n = llama_chat_apply_template(use, chat.data(), chat.size(), true, out.data(), static_cast<int32_t>(out.size()));
        if (n > static_cast<int>(out.size())) {
            out.assign(static_cast<size_t>(n), '\0');
            n = llama_chat_apply_template(use, chat.data(), chat.size(), true, out.data(), static_cast<int32_t>(out.size()));
        }
        if (n >= 0) return to_bytes(env, out.substr(0, static_cast<size_t>(n)));
        LOGE("Chat-Vorlage nicht anwendbar, weiche auf chatml aus");
        tmpl = nullptr;  // zweiter Versuch mit chatml
    }
    return nullptr;
}

// Erzeugt Text. Rückgabe: 0 Ende des Modells, 1 Token-Limit, 2 abgebrochen (Kotlin oder cancel()), 3 Kontext voll,
// -1 Fehler (Text über lastError()).
JNIEXPORT jint JNICALL Java_app_aichatmcp_local_LlamaNative_generate(
    JNIEnv * env, jobject, jlong handle, jbyteArray jprompt, jint max_tokens, jfloat temperature, jfloat top_p,
    jfloat repeat_penalty, jfloat freq_penalty, jfloat presence_penalty, jint seed, jobject sink) {
    Engine * e = engine_of(handle);
    if (!e) {
        g_error = "Kein Modell geladen";
        return -1;
    }
    e->cancel = false;
    jclass sink_class = env->GetObjectClass(sink);
    jmethodID on_piece = env->GetMethodID(sink_class, "onPiece", "([B)Z");
    if (!on_piece) {
        g_error = "Interner Fehler: Ausgabe-Schnittstelle fehlt";
        return -1;
    }

    const auto t_start = std::chrono::steady_clock::now();
    std::vector<llama_token> tokens = tokenize(e->vocab, to_string(env, jprompt));
    const llama_token bos = llama_vocab_bos(e->vocab);
    if (llama_vocab_get_add_bos(e->vocab) && bos != LLAMA_TOKEN_NULL && (tokens.empty() || tokens[0] != bos)) {
        tokens.insert(tokens.begin(), bos);
    }
    if (tokens.empty() || static_cast<int>(tokens.size()) + 8 >= e->n_ctx) {
        g_error = "Der Prompt passt nicht in den Kontext (" + std::to_string(tokens.size()) + " von " +
                  std::to_string(e->n_ctx) + " Tokens)";
        return -1;
    }

    // Anfang des vorigen Prompts wiederverwenden: der Verlauf wird bei jeder Anfrage komplett neu gesendet, nur das
    // Neue muss berechnet werden. Das letzte Token wird immer neu berechnet (für die Wahrscheinlichkeiten).
    size_t keep = 0;
    while (keep < tokens.size() && keep < e->cache.size() && tokens[keep] == e->cache[keep]) keep++;
    if (keep == tokens.size()) keep--;
    llama_memory_t mem = llama_get_memory(e->ctx);
    if (keep < e->cache.size()) {
        if (keep == 0 || !llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(keep), -1)) {
            llama_memory_clear(mem, true);
            keep = 0;
        }
    }
    e->cache.resize(keep);
    const int cached = static_cast<int>(keep);

    for (size_t i = keep; i < tokens.size();) {
        if (e->cancel) return 2;
        const int n = static_cast<int>(std::min<size_t>(static_cast<size_t>(e->n_batch), tokens.size() - i));
        if (llama_decode(e->ctx, llama_batch_get_one(&tokens[i], n)) != 0) {
            e->cache.clear();
            llama_memory_clear(mem, true);
            g_error = "Der Prompt konnte nicht verarbeitet werden";
            return -1;
        }
        e->cache.insert(e->cache.end(), tokens.begin() + static_cast<long>(i), tokens.begin() + static_cast<long>(i) + n);
        i += static_cast<size_t>(n);
    }
    const double prompt_ms = ms_since(t_start);

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (repeat_penalty != 1.0f || freq_penalty != 0.0f || presence_penalty != 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(e->vocab), 64, repeat_penalty,
                                                                   freq_penalty, presence_penalty));
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    int result = 1;
    int generated = 0;
    std::string pending;  // noch unvollständige Zeichen am Ende
    const auto t_gen = std::chrono::steady_clock::now();
    while (generated < max_tokens) {
        if (e->cancel) {
            result = 2;
            break;
        }
        const llama_token token = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, token)) {
            result = 0;
            break;
        }
        pending += token_piece(e->vocab, token);
        generated++;
        const size_t complete = utf8_complete_prefix(pending);
        if (complete > 0) {
            jbyteArray bytes = to_bytes(env, pending.substr(0, complete));
            const jboolean carry_on = env->CallBooleanMethod(sink, on_piece, bytes);
            env->DeleteLocalRef(bytes);
            pending.erase(0, complete);
            if (env->ExceptionCheck() || !carry_on) {
                result = 2;
                break;
            }
        }
        if (static_cast<int>(e->cache.size()) + 1 >= e->n_ctx) {
            result = 3;
            break;
        }
        llama_token next = token;
        if (llama_decode(e->ctx, llama_batch_get_one(&next, 1)) != 0) {
            g_error = "Die Berechnung ist fehlgeschlagen";
            result = -1;
            break;
        }
        e->cache.push_back(token);
    }
    llama_sampler_free(smpl);

    char stats[256];
    std::snprintf(stats, sizeof(stats),
                  "{\"prompt\":%d,\"cached\":%d,\"gen\":%d,\"prompt_ms\":%.0f,\"gen_ms\":%.0f}",
                  static_cast<int>(tokens.size()), cached, generated, prompt_ms, ms_since(t_gen));
    e->stats = stats;
    LOGI("%s", stats);
    return result;
}

}  // extern "C"
