// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

#include <jni.h>

#include <cstdint>
#include <exception>
#include <limits>
#include <memory>
#include <utility>

#include <usearch/index_dense.hpp>

namespace {

using Index = unum::usearch::index_dense_t;

void raise(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message);
}

Index* getIndex(JNIEnv* env, jlong pointer) {
    if (pointer == 0) {
        raise(env, "USearch index is closed");
        return nullptr;
    }
    return reinterpret_cast<Index*>(pointer);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_runtime_mobileagent_vector_NativeUsearchIndex_nativeCreate(
    JNIEnv* env, jclass, jint dimensions, jint capacity) {
    if (dimensions <= 0 || capacity <= 0) {
        raise(env, "USearch dimensions and capacity must be positive");
        return 0;
    }
    try {
        const auto metric = unum::usearch::metric_punned_t::builtin(
            static_cast<std::size_t>(dimensions),
            unum::usearch::metric_kind_t::cos_k,
            unum::usearch::scalar_kind_t::f32_k);
        auto result = Index::make(metric, unum::usearch::index_dense_config_t(16, 64, 64));
        if (!result) {
            const char* message = result.error.what();
            result.error.release();
            raise(env, message);
            return 0;
        }
        if (!result.index.try_reserve(unum::usearch::index_limits_t(
                static_cast<std::size_t>(capacity), 1))) {
            raise(env, "USearch index reservation failed");
            return 0;
        }
        return reinterpret_cast<jlong>(new Index(std::move(result.index)));
    } catch (const std::exception& error) {
        raise(env, error.what());
    } catch (...) {
        raise(env, "USearch index creation failed");
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_runtime_mobileagent_vector_NativeUsearchIndex_nativeAdd(
    JNIEnv* env, jclass, jlong pointer, jlong key, jfloatArray vector) {
    Index* index = getIndex(env, pointer);
    if (index == nullptr || vector == nullptr) return;
    const jsize length = env->GetArrayLength(vector);
    if (static_cast<std::size_t>(length) != index->dimensions()) {
        raise(env, "USearch vector dimensions differ from the index");
        return;
    }
    jfloat* values = env->GetFloatArrayElements(vector, nullptr);
    if (values == nullptr) return;
    try {
        auto result = index->add(static_cast<Index::vector_key_t>(key), values);
        if (!result) {
            const char* message = result.error.what();
            result.error.release();
            raise(env, message);
        }
    } catch (const std::exception& error) {
        raise(env, error.what());
    } catch (...) {
        raise(env, "USearch vector insertion failed");
    }
    env->ReleaseFloatArrayElements(vector, values, JNI_ABORT);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_runtime_mobileagent_vector_NativeUsearchIndex_nativeSearch(
    JNIEnv* env, jclass, jlong pointer, jfloatArray query, jint topK) {
    Index* index = getIndex(env, pointer);
    if (index == nullptr || query == nullptr) return nullptr;
    if (topK <= 0) return env->NewLongArray(0);
    const jsize length = env->GetArrayLength(query);
    if (static_cast<std::size_t>(length) != index->dimensions()) {
        raise(env, "USearch query dimensions differ from the index");
        return nullptr;
    }
    jfloat* values = env->GetFloatArrayElements(query, nullptr);
    if (values == nullptr) return nullptr;
    try {
        auto result = index->search(values, static_cast<std::size_t>(topK));
        env->ReleaseFloatArrayElements(query, values, JNI_ABORT);
        if (!result) {
            const char* message = result.error.what();
            result.error.release();
            raise(env, message);
            return nullptr;
        }
        const auto count = static_cast<jsize>(result.count);
        jlongArray output = env->NewLongArray(count);
        if (output == nullptr) return nullptr;
        std::unique_ptr<Index::vector_key_t[]> keys(new Index::vector_key_t[result.count]);
        result.dump_to(keys.get());
        env->SetLongArrayRegion(output, 0, count, reinterpret_cast<const jlong*>(keys.get()));
        return output;
    } catch (const std::exception& error) {
        env->ReleaseFloatArrayElements(query, values, JNI_ABORT);
        raise(env, error.what());
    } catch (...) {
        env->ReleaseFloatArrayElements(query, values, JNI_ABORT);
        raise(env, "USearch vector search failed");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_runtime_mobileagent_vector_NativeUsearchIndex_nativeClose(
    JNIEnv* env, jclass, jlong pointer) {
    if (pointer == 0) return;
    try {
        delete reinterpret_cast<Index*>(pointer);
    } catch (const std::exception& error) {
        raise(env, error.what());
    } catch (...) {
        raise(env, "USearch index close failed");
    }
}
