// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <exception>
#include <limits>
#include <memory>
#include <thread>
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

/**
 * Releases a pinned jfloatArray exactly once, including when USearch or a
 * later allocation throws.  nativeSearch used to release the query elements
 * right after search() and then release the same pointer again from its catch
 * blocks; releasing a jfloat* twice is JNI undefined behaviour and aborts under
 * CheckJNI, which matters exactly when memory pressure makes those paths run.
 */
class ScopedFloatArray {
  public:
    ScopedFloatArray(JNIEnv* env, jfloatArray array, jfloat* values) noexcept
        : env_(env), array_(array), values_(values) {}
    ~ScopedFloatArray() noexcept { release(); }

    ScopedFloatArray(ScopedFloatArray const&) = delete;
    ScopedFloatArray& operator=(ScopedFloatArray const&) = delete;

    jfloat* get() const noexcept { return values_; }

    void release() noexcept {
        if (values_ != nullptr) {
            env_->ReleaseFloatArrayElements(array_, values_, JNI_ABORT);
            values_ = nullptr;
        }
    }

  private:
    JNIEnv* env_;
    jfloatArray array_;
    jfloat* values_;
};

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
                static_cast<std::size_t>(capacity),
                // threads=1 made concurrent search on a leased handle throw
                // IllegalStateException (CI job 101318610136,
                // VectorIndexCacheDeviceTest.concurrentSearchAndInvalidateNeverObservesClosedHandle).
                // Multiple retrieve() calls may search one cached index.
                std::max<std::size_t>(32, std::thread::hardware_concurrency())))) {
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
    ScopedFloatArray pinned(env, query, values);
    try {
        auto result = index->search(pinned.get(), static_cast<std::size_t>(topK));
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
        raise(env, error.what());
    } catch (...) {
        raise(env, "USearch vector search failed");
    }
    return nullptr;
}

/**
 * Persists the whole index (graph + vectors + u64 keys) to `path`.
 * USearch validates the format on load; the Kotlin wrapper additionally
 * persists the chunkId<->key mapping, which the binary format does not carry.
 */
extern "C" JNIEXPORT void JNICALL
Java_runtime_mobileagent_vector_NativeUsearchIndex_nativeSave(
    JNIEnv* env, jclass, jlong pointer, jstring path) {
    Index* index = getIndex(env, pointer);
    if (index == nullptr || path == nullptr) return;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return;
    try {
        auto result = index->save(chars);
        if (!result) {
            const char* message = result.error.what();
            result.error.release();
            raise(env, message);
        }
    } catch (const std::exception& error) {
        raise(env, error.what());
    } catch (...) {
        raise(env, "USearch index save failed");
    }
    env->ReleaseStringUTFChars(path, chars);
}

/**
 * Restores a new handle from a persisted index file.  Returns 0 and raises on
 * a truncated, foreign or otherwise invalid file: a partially loaded index is
 * never returned, so callers can only fall back to rebuilding from SQLite.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_runtime_mobileagent_vector_NativeUsearchIndex_nativeRestore(
    JNIEnv* env, jclass, jstring path) {
    if (path == nullptr) return 0;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return 0;
    jlong handle = 0;
    try {
        auto result = Index::make(chars, false);
        if (!result) {
            const char* message = result.error.what();
            result.error.release();
            raise(env, message);
        } else {
            handle = reinterpret_cast<jlong>(new Index(std::move(result.index)));
        }
    } catch (const std::exception& error) {
        raise(env, error.what());
    } catch (...) {
        raise(env, "USearch index restore failed");
    }
    env->ReleaseStringUTFChars(path, chars);
    return handle;
}

extern "C" JNIEXPORT jint JNICALL
Java_runtime_mobileagent_vector_NativeUsearchIndex_nativeSize(
    JNIEnv* env, jclass, jlong pointer) {
    Index* index = getIndex(env, pointer);
    if (index == nullptr) return -1;
    return static_cast<jint>(index->size());
}

extern "C" JNIEXPORT jint JNICALL
Java_runtime_mobileagent_vector_NativeUsearchIndex_nativeDimensions(
    JNIEnv* env, jclass, jlong pointer) {
    Index* index = getIndex(env, pointer);
    if (index == nullptr) return -1;
    return static_cast<jint>(index->dimensions());
}
extern "C" JNIEXPORT jboolean JNICALL
Java_runtime_mobileagent_vector_NativeUsearchIndex_nativeHasSequentialKeys(
    JNIEnv* env, jclass, jlong pointer, jint count) {
    Index* index = getIndex(env, pointer);
    if (index == nullptr || count < 0 || index->size() != static_cast<std::size_t>(count)) return JNI_FALSE;
    try {
        for (std::uint64_t key = 1; key <= static_cast<std::uint64_t>(count); ++key)
            if (!index->contains(key)) return JNI_FALSE;
        return JNI_TRUE;
    } catch (const std::exception& error) {
        raise(env, error.what());
    } catch (...) {
        raise(env, "USearch key validation failed");
    }
    return JNI_FALSE;
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
