#include <jni.h>
#include <android/log.h>
#include "llama.h"
#include "ggml-backend.h"
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <stdexcept>
#include <cstdlib>

namespace {
struct Session {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    llama_sampler * sampler = nullptr;
    std::atomic<bool> cancelled{false};
    int generated = 0;
    ~Session() {
        if (sampler) llama_sampler_free(sampler);
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};
std::once_flag init_flag;
Session * get(jlong handle) {
    if (!handle) throw std::runtime_error("The offline model is not loaded.");
    return reinterpret_cast<Session *>(handle);
}
void fail(JNIEnv * env, const char * message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
std::string bytes(JNIEnv * env, jbyteArray data) {
    std::string out(env->GetArrayLength(data), '\0');
    env->GetByteArrayRegion(data, 0, out.size(), reinterpret_cast<jbyte *>(out.data()));
    return out;
}
bool abort_decode(void * data) { return static_cast<Session *>(data)->cancelled.load(); }

std::string text_of(JNIEnv * env, jstring value) {
    const char * raw = env->GetStringUTFChars(value, nullptr);
    std::string out(raw);
    env->ReleaseStringUTFChars(value, raw);
    return out;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hackathon_calico_coach_NativeCoach_nativeLoad(JNIEnv * env, jobject, jstring path, jstring library_dir) {
    try {
        // FastRPC loads libggml-htp-v<arch>.so from ADSP_LIBRARY_PATH, which must be set
        // before the Hexagon backend registers itself during llama_backend_init.
        std::string libraries = text_of(env, library_dir);
        static std::once_flag env_flag;
        std::call_once(env_flag, [&libraries] {
            setenv("ADSP_LIBRARY_PATH", libraries.c_str(), 1);
            setenv("GGML_HEXAGON_DEVICES", "HTP0", 0);
            setenv("GGML_HEXAGON_OPPOLL", "1", 0);
        });
        std::call_once(init_flag, [] {
            llama_log_set([](ggml_log_level level, const char * text, void *) {
                if (level == GGML_LOG_LEVEL_ERROR) __android_log_write(ANDROID_LOG_ERROR, "CalicoCoach", text);
                else if (level == GGML_LOG_LEVEL_WARN) __android_log_write(ANDROID_LOG_WARN, "CalicoCoach", text);
                else __android_log_write(ANDROID_LOG_DEBUG, "CalicoCoach", text);
            }, nullptr);
            llama_backend_init();
        });
        auto session = std::make_unique<Session>();
        std::string model_path = text_of(env, path);
        // Every layer runs on the NPU. There is no CPU fallback: a silent CPU run
        // would be about thirty times slower to the first token.
        ggml_backend_dev_t npu = ggml_backend_dev_by_name("HTP0");
        if (!npu) throw std::runtime_error("The Hexagon NPU is unavailable on this phone, so the offline coach cannot run.");
        __android_log_print(ANDROID_LOG_INFO, "CalicoCoach", "NPU device: %s", ggml_backend_dev_description(npu));
        static ggml_backend_dev_t devices[] = { npu, nullptr };
        auto mp = llama_model_default_params();
        mp.devices = devices;
        mp.n_gpu_layers = 99;
        session->model = llama_model_load_from_file(model_path.c_str(), mp);
        if (!session->model) throw std::runtime_error("Could not load the model. Re-import or download it again.");
        auto cp = llama_context_default_params();
        cp.n_ctx = 2048;
        cp.n_batch = 512;
        cp.n_ubatch = 512;
        cp.n_threads = 4;
        cp.n_threads_batch = 4;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
        cp.abort_callback = abort_decode;
        cp.abort_callback_data = session.get();
        session->context = llama_init_from_model(session->model, cp);
        if (!session->context) throw std::runtime_error("Not enough memory to start the offline coach.");
        session->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        // Deterministic decoding keeps reference-grounded coaching reproducible.
        llama_sampler_chain_add(session->sampler, llama_sampler_init_greedy());
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception & e) { fail(env, e.what()); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hackathon_calico_coach_NativeCoach_nativeStart(JNIEnv * env, jobject, jlong handle, jbyteArray input) {
    try {
        auto s = get(handle);
        s->cancelled = false;
        s->generated = 0;
        llama_memory_clear(llama_get_memory(s->context), true);
        llama_sampler_reset(s->sampler);
        auto prompt = bytes(env, input);
        auto vocab = llama_model_get_vocab(s->model);
        int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, false, true);
        if (count <= 0 || count > 1800) throw std::runtime_error("Message is too long. Try a shorter question or clear the conversation.");
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, false, true) < 0)
            throw std::runtime_error("Could not read this message.");
        for (int i = 0; i < count && !s->cancelled; i += 512) {
            auto batch = llama_batch_get_one(tokens.data() + i, std::min(512, count-i));
            if (llama_decode(s->context, batch) != 0 && !s->cancelled)
                throw std::runtime_error("The offline model could not process this message.");
        }
    } catch (const std::exception & e) { fail(env, e.what()); }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hackathon_calico_coach_NativeCoach_nativeNext(JNIEnv * env, jobject, jlong handle) {
    try {
        auto s = get(handle);
        if (s->cancelled || s->generated >= 192) return nullptr;
        const auto vocab = llama_model_get_vocab(s->model);
        auto token = llama_sampler_sample(s->sampler, s->context, -1);
        if (llama_vocab_is_eog(vocab, token)) return nullptr;
        std::vector<char> piece(128);
        int length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        if (length < 0) {
            piece.resize(-length);
            length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        }
        if (length < 0) throw std::runtime_error("Could not decode the response.");
        auto batch = llama_batch_get_one(&token, 1);
        if (llama_decode(s->context, batch) != 0 && !s->cancelled)
            throw std::runtime_error("The model stopped unexpectedly. Please retry.");
        ++s->generated;
        auto out = env->NewByteArray(length);
        env->SetByteArrayRegion(out, 0, length, reinterpret_cast<jbyte *>(piece.data()));
        return out;
    } catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hackathon_calico_coach_NativeCoach_nativeCancel(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Session *>(handle)->cancelled = true;
}
extern "C" JNIEXPORT void JNICALL
Java_com_hackathon_calico_coach_NativeCoach_nativeFree(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Session *>(handle);
}
