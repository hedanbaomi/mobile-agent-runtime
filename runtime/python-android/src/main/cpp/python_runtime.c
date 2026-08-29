// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

#include <Python.h>
#include <jni.h>

#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <unistd.h>

#define MOBILEAGENT_VERSION 1
#define MAX_CONTROL_FRAME (64 * 1024)
#define MAX_OUTPUT_BYTES (1024 * 1024)
#define MAX_STDLIB_BYTES (16 * 1024 * 1024)
#define MAX_BROKER_CHUNK_BYTES (48 * 1024)
#define MAX_BROKER_VALUE_BYTES (8 * 1024 * 1024)
#define MAX_BROKER_CHUNKS 1024
#define MAX_ERROR_BYTES (4 * 1024)
#define MAX_DIAGNOSTIC_BYTES 256
#define MAX_TOKEN_BYTES 256
#define MAX_IDENTIFIER_BYTES 128
#define CODE_ARCHIVE_READ_CHUNK (64 * 1024)
#define CHANNEL_NONCE_LENGTH 43
#define RESULT_INPUT_LIMIT -2
#define RESULT_OUTPUT_LIMIT -3

typedef struct {
    int package_fd;
    int stdlib_fd;
    int input_fd;
    int result_fd;
    int broker_request_fd;
    int broker_response_fd;
    int log_fd;
    int max_output_bytes;
    int max_log_bytes;
    int max_input_bytes;
    int max_broker_calls;
    int grant_revision;
    char invocation_id[MAX_IDENTIFIER_BYTES + 1];
    char run_id[MAX_IDENTIFIER_BYTES + 1];
    char package_hash[65];
    char one_time_token[MAX_TOKEN_BYTES + 1];
    char channel_nonce[CHANNEL_NONCE_LENGTH + 1];
    volatile sig_atomic_t cancelled;
    int broker_calls;
    int request_sequence;
    _Atomic(size_t) log_bytes;
    _Atomic int log_limit_exceeded;
    /* Strong reference acquired from the already loaded built-in _io module.
     * It is cached before any package code can mutate module attributes. */
    PyObject *code_bytes_io_type;
} RuntimeState;

/* The service accepts one invocation and exits, so keeping the state in
 * process storage also makes an in-flight Binder cancellation safe when the
 * JNI call is returning. */
static RuntimeState g_runtime_state;
static _Atomic(RuntimeState *) g_state = NULL;
/* Register the hook before CPython initialization, but do not ask the hook to
 * inspect Python objects until the interpreter has completed initialization.
 * CPython imports frozen/bootstrap modules during that interval and the
 * callback is not allowed to manufacture Python exceptions against a partial
 * interpreter.  No skill or package code runs before this flag is enabled. */
static _Atomic int g_audit_enabled = 0;

static RuntimeState *current_state(void) {
    return atomic_load_explicit(&g_state, memory_order_acquire);
}

static int diagnostic_token_char(unsigned char value, int allow_dot) {
    return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') ||
        (value >= '0' && value <= '9') || value == '_' || (allow_dot && value == '.');
}

static int copy_diagnostic_token(PyObject *value, char *destination, size_t capacity, int allow_dot) {
    if (value == NULL || destination == NULL || capacity < 2 || !PyUnicode_Check(value)) return 0;
    Py_ssize_t length = 0;
    const char *text = PyUnicode_AsUTF8AndSize(value, &length);
    if (text == NULL || length < 1 || (size_t)length >= capacity || (size_t)length > 128) {
        PyErr_Clear();
        return 0;
    }
    for (Py_ssize_t index = 0; index < length; index++) {
        if (!diagnostic_token_char((unsigned char)text[index], allow_dot)) return 0;
    }
    memcpy(destination, text, (size_t)length);
    destination[length] = '\0';
    return 1;
}

static void set_stage_diagnostic(char *destination, size_t capacity, const char *stage) {
    if (destination == NULL || capacity == 0) return;
    (void)snprintf(destination, capacity, "stage=%s", stage != NULL ? stage : "unknown");
}

/* Keep diagnostics useful for local verification without returning Python
 * messages, tracebacks, arguments, paths, tickets or nonce material. */
static void capture_python_diagnostic(char *destination, size_t capacity, const char *stage) {
    PyObject *exception_type = NULL;
    PyObject *exception_value = NULL;
    PyObject *traceback = NULL;
    char exception_name[64] = {0};
    char missing_module[129] = {0};

    set_stage_diagnostic(destination, capacity, stage);
    if (!PyErr_Occurred()) return;
    PyErr_Fetch(&exception_type, &exception_value, &traceback);

    PyObject *name = exception_type == NULL ? NULL : PyObject_GetAttrString(exception_type, "__name__");
    (void)copy_diagnostic_token(name, exception_name, sizeof(exception_name), 0);
    Py_XDECREF(name);
    if (exception_type != NULL && PyErr_GivenExceptionMatches(exception_type, PyExc_ModuleNotFoundError)) {
        PyObject *missing = exception_value == NULL ? NULL : PyObject_GetAttrString(exception_value, "name");
        (void)copy_diagnostic_token(missing, missing_module, sizeof(missing_module), 1);
        Py_XDECREF(missing);
    }
    if (exception_name[0] != '\0') {
        int written = snprintf(destination, capacity, "stage=%s exception=%s",
            stage != NULL ? stage : "unknown", exception_name);
        if (written >= 0 && (size_t)written < capacity && missing_module[0] != '\0') {
            (void)snprintf(destination + written, capacity - (size_t)written,
                " missing=%s", missing_module);
        }
    }
    Py_XDECREF(exception_type);
    Py_XDECREF(exception_value);
    Py_XDECREF(traceback);
    PyErr_Clear();
}

static int cancelled(void) {
    RuntimeState *state = current_state();
    return state != NULL && state->cancelled != 0;
}

static int write_all(int fd, const void *buffer, size_t length) {
    const unsigned char *cursor = (const unsigned char *)buffer;
    while (length > 0) {
        ssize_t written = write(fd, cursor, length);
        if (written < 0 && errno == EINTR) {
            continue;
        }
        if (written <= 0) {
            return -1;
        }
        cursor += (size_t)written;
        length -= (size_t)written;
    }
    return 0;
}

/* Count bytes at the producer before writing them.  This closes the race in
 * which a fast script writes past the host drain limit and returns a result
 * before the drain coroutine has observed those bytes.  Saturation prevents a
 * maliciously large stream from wrapping the counter back below the limit. */
static int account_log_bytes(RuntimeState *state, size_t length) {
    if (state == NULL) return 1;
    if (atomic_load_explicit(&state->log_limit_exceeded, memory_order_acquire)) return 2;
    size_t observed = atomic_load_explicit(&state->log_bytes, memory_order_relaxed);
    for (;;) {
        size_t next = observed;
        if (length > SIZE_MAX - observed) {
            next = SIZE_MAX;
        } else {
            next += length;
        }
        if (atomic_compare_exchange_weak_explicit(&state->log_bytes, &observed, next,
            memory_order_acq_rel, memory_order_relaxed)) {
            int exceeded = next > (size_t)state->max_log_bytes;
            if (exceeded) {
                int expected = 0;
                if (!atomic_compare_exchange_strong_explicit(&state->log_limit_exceeded, &expected, 1,
                    memory_order_acq_rel, memory_order_acquire)) {
                    return 2;
                }
                return 1;
            }
            return 0;
        }
    }
}

/* CPython may construct its standard streams with Android-specific buffering
 * that does not reliably target the inherited log pipe.  Keep the actual log
 * descriptor private to native code and expose only the two operations that
 * print() needs.  This object deliberately has no instance dictionary,
 * fileno, path, buffer or descriptor member visible to Python. */
typedef struct {
    PyObject_HEAD
    RuntimeState *state;
} PythonLogStreamObject;

static PyObject *python_log_stream_write(PythonLogStreamObject *self, PyObject *arguments) {
    PyObject *value = NULL;
    if (!PyArg_ParseTuple(arguments, "U:write", &value)) return NULL;
    if (self == NULL || self->state == NULL || self->state->log_fd < 0) {
        PyErr_SetString(PyExc_OSError, "Python log stream is unavailable");
        return NULL;
    }
    Py_ssize_t byte_length = 0;
    const char *utf8 = PyUnicode_AsUTF8AndSize(value, &byte_length);
    if (utf8 == NULL) return NULL;
    int log_write_state = account_log_bytes(self->state, (size_t)byte_length);
    if (log_write_state == 2) {
        PyErr_SetString(PyExc_OSError, "Python log output limit exceeded");
        return NULL;
    }
    if (byte_length > 0 && write_all(self->state->log_fd, utf8, (size_t)byte_length) != 0) {
        if (errno == 0) {
            PyErr_SetString(PyExc_OSError, "Python log stream is unavailable");
        } else {
            PyErr_SetFromErrno(PyExc_OSError);
        }
        return NULL;
    }
    if (log_write_state == 1) {
        PyErr_SetString(PyExc_OSError, "Python log output limit exceeded");
        return NULL;
    }
    return PyLong_FromSsize_t(PyUnicode_GetLength(value));
}

static PyObject *python_log_stream_flush(PythonLogStreamObject *self, PyObject *arguments) {
    if (!PyArg_ParseTuple(arguments, ":flush")) return NULL;
    if (self == NULL || self->state == NULL || self->state->log_fd < 0) {
        PyErr_SetString(PyExc_OSError, "Python log stream is unavailable");
        return NULL;
    }
    Py_RETURN_NONE;
}

static PyMethodDef python_log_stream_methods[] = {
    {"write", (PyCFunction)python_log_stream_write, METH_VARARGS,
        "Write text to the native bounded log channel."},
    {"flush", (PyCFunction)python_log_stream_flush, METH_VARARGS,
        "Flush the native bounded log channel."},
    {NULL, NULL, 0, NULL},
};

static void python_log_stream_dealloc(PythonLogStreamObject *self) {
    PyObject_Free((void *)self);
}

static PyTypeObject python_log_stream_type = {
    PyVarObject_HEAD_INIT(NULL, 0)
    .tp_name = "_mobileagent.NativeLogStream",
    .tp_basicsize = sizeof(PythonLogStreamObject),
    .tp_flags = Py_TPFLAGS_DEFAULT,
    .tp_methods = python_log_stream_methods,
    .tp_dealloc = (destructor)python_log_stream_dealloc,
};

static int install_python_log_streams(RuntimeState *state,
    char *diagnostic, size_t diagnostic_capacity) {
    if (state == NULL || state->log_fd < 0) {
        set_stage_diagnostic(diagnostic, diagnostic_capacity, "python_log_stream");
        return -1;
    }
    if (PyType_Ready(&python_log_stream_type) != 0) {
        capture_python_diagnostic(diagnostic, diagnostic_capacity, "python_log_stream");
        return -1;
    }
    PythonLogStreamObject *stdout_stream = PyObject_New(
        PythonLogStreamObject, &python_log_stream_type);
    PythonLogStreamObject *stderr_stream = PyObject_New(
        PythonLogStreamObject, &python_log_stream_type);
    if (stdout_stream == NULL || stderr_stream == NULL) {
        Py_XDECREF(stdout_stream);
        Py_XDECREF(stderr_stream);
        capture_python_diagnostic(diagnostic, diagnostic_capacity, "python_log_stream");
        return -1;
    }
    stdout_stream->state = state;
    stderr_stream->state = state;
    PyObject *streams[] = {
        (PyObject *)stdout_stream,
        (PyObject *)stdout_stream,
        (PyObject *)stderr_stream,
        (PyObject *)stderr_stream,
    };
    const char *names[] = { "stdout", "__stdout__", "stderr", "__stderr__" };
    for (size_t index = 0; index < sizeof(names) / sizeof(names[0]); index++) {
        if (PySys_SetObject(names[index], streams[index]) != 0) {
            capture_python_diagnostic(diagnostic, diagnostic_capacity, "python_log_stream");
            Py_DECREF(stdout_stream);
            Py_DECREF(stderr_stream);
            return -1;
        }
    }
    Py_DECREF(stdout_stream);
    Py_DECREF(stderr_stream);
    return 0;
}

/* A bounded read that wakes for cancellation without using a process-wide
 * signal handler.  The service watchdog remains the hard upper bound. */
static int read_all_interruptible(int fd, void *buffer, size_t length) {
    unsigned char *cursor = (unsigned char *)buffer;
    while (length > 0) {
        if (cancelled()) {
            return -2;
        }
        struct pollfd descriptor = { .fd = fd, .events = POLLIN };
        int ready = poll(&descriptor, 1, 200);
        if (ready < 0 && errno == EINTR) {
            continue;
        }
        if (ready < 0) {
            return -1;
        }
        if (ready == 0) {
            continue;
        }
        ssize_t read_count = read(fd, cursor, length);
        if (read_count < 0 && errno == EINTR) {
            continue;
        }
        if (read_count <= 0) {
            return -1;
        }
        cursor += (size_t)read_count;
        length -= (size_t)read_count;
    }
    return 0;
}

static int write_frame(int fd, const unsigned char *payload, size_t length) {
    if (length > MAX_CONTROL_FRAME) {
        return -1;
    }
    unsigned char header[4] = {
        (unsigned char)((length >> 24) & 0xff),
        (unsigned char)((length >> 16) & 0xff),
        (unsigned char)((length >> 8) & 0xff),
        (unsigned char)(length & 0xff),
    };
    if (write_all(fd, header, sizeof(header)) != 0) {
        return -1;
    }
    return write_all(fd, payload, length);
}

static unsigned char *read_frame(int fd, size_t *length_out) {
    unsigned char header[4];
    int result = read_all_interruptible(fd, header, sizeof(header));
    if (result != 0) {
        return NULL;
    }
    size_t length = ((size_t)header[0] << 24) |
        ((size_t)header[1] << 16) |
        ((size_t)header[2] << 8) |
        (size_t)header[3];
    if (length > MAX_CONTROL_FRAME) {
        return NULL;
    }
    unsigned char *payload = (unsigned char *)calloc(length + 1, 1);
    if (payload == NULL) {
        return NULL;
    }
    if (length > 0 && read_all_interruptible(fd, payload, length) != 0) {
        free(payload);
        return NULL;
    }
    payload[length] = '\0';
    *length_out = length;
    return payload;
}

static int append_bytes(char *output, size_t capacity, size_t *cursor, const char *value, size_t length) {
    if (*cursor > capacity || length > capacity - *cursor) {
        return -1;
    }
    memcpy(output + *cursor, value, length);
    *cursor += length;
    return 0;
}

static int append_json_string(char *output, size_t capacity, size_t *cursor, const char *value) {
    if (append_bytes(output, capacity, cursor, "\"", 1) != 0) {
        return -1;
    }
    for (const unsigned char *p = (const unsigned char *)value; *p != '\0'; p++) {
        char escaped[7];
        size_t escaped_length = 0;
        switch (*p) {
            case '"': escaped[0] = '\\'; escaped[1] = '"'; escaped_length = 2; break;
            case '\\': escaped[0] = '\\'; escaped[1] = '\\'; escaped_length = 2; break;
            case '\b': escaped[0] = '\\'; escaped[1] = 'b'; escaped_length = 2; break;
            case '\f': escaped[0] = '\\'; escaped[1] = 'f'; escaped_length = 2; break;
            case '\n': escaped[0] = '\\'; escaped[1] = 'n'; escaped_length = 2; break;
            case '\r': escaped[0] = '\\'; escaped[1] = 'r'; escaped_length = 2; break;
            case '\t': escaped[0] = '\\'; escaped[1] = 't'; escaped_length = 2; break;
            default:
                if (*p < 0x20) {
                    (void)snprintf(escaped, sizeof(escaped), "\\u%04x", *p);
                    escaped_length = 6;
                } else {
                    escaped[0] = (char)*p;
                    escaped_length = 1;
                }
                break;
        }
        if (append_bytes(output, capacity, cursor, escaped, escaped_length) != 0) {
            return -1;
        }
    }
    return append_bytes(output, capacity, cursor, "\"", 1);
}

static int append_json_field_string(char *output, size_t capacity, size_t *cursor,
    const char *key, const char *value, int *first) {
    if (!*first && append_bytes(output, capacity, cursor, ",", 1) != 0) {
        return -1;
    }
    *first = 0;
    if (append_json_string(output, capacity, cursor, key) != 0 ||
        append_bytes(output, capacity, cursor, ":", 1) != 0 ||
        append_json_string(output, capacity, cursor, value) != 0) {
        return -1;
    }
    return 0;
}

static int append_json_field_int(char *output, size_t capacity, size_t *cursor,
    const char *key, int value, int *first) {
    char number[32];
    (void)snprintf(number, sizeof(number), "%d", value);
    if (!*first && append_bytes(output, capacity, cursor, ",", 1) != 0) {
        return -1;
    }
    *first = 0;
    return append_json_string(output, capacity, cursor, key) == 0 &&
        append_bytes(output, capacity, cursor, ":", 1) == 0 &&
        append_bytes(output, capacity, cursor, number, strlen(number)) == 0 ? 0 : -1;
}

static int valid_channel_nonce(const char *value) {
    if (value == NULL || strlen(value) != CHANNEL_NONCE_LENGTH) return 0;
    for (size_t i = 0; i < CHANNEL_NONCE_LENGTH; i++) {
        const unsigned char character = (unsigned char)value[i];
        if (!((character >= 'A' && character <= 'Z') ||
            (character >= 'a' && character <= 'z') ||
            (character >= '0' && character <= '9') || character == '_' || character == '-')) {
            return 0;
        }
    }
    return 1;
}

static void close_runtime_fds(RuntimeState *state) {
    if (state == NULL) return;
    int *descriptors[] = {
        &state->package_fd,
        &state->stdlib_fd,
        &state->input_fd,
        &state->result_fd,
        &state->broker_request_fd,
        &state->broker_response_fd,
        &state->log_fd,
    };
    for (size_t i = 0; i < sizeof(descriptors) / sizeof(descriptors[0]); i++) {
        if (*descriptors[i] >= 0) {
            (void)close(*descriptors[i]);
            *descriptors[i] = -1;
        }
    }
}

static int build_broker_request(char **payload_out, size_t *length_out,
    const char *request_id, const char *capability, const char *arguments_json) {
    RuntimeState *state = current_state();
    if (state == NULL) {
        return -1;
    }
    size_t capacity = MAX_CONTROL_FRAME;
    char *payload = (char *)calloc(capacity + 1, 1);
    if (payload == NULL) {
        return -1;
    }
    size_t cursor = 0;
    int first = 1;
    if (append_bytes(payload, capacity, &cursor, "{", 1) != 0 ||
        append_json_field_int(payload, capacity, &cursor, "version", MOBILEAGENT_VERSION, &first) != 0 ||
        append_json_field_string(payload, capacity, &cursor, "kind", "request", &first) != 0 ||
        append_json_field_string(payload, capacity, &cursor, "invocationId", state->invocation_id, &first) != 0 ||
        append_json_field_string(payload, capacity, &cursor, "runId", state->run_id, &first) != 0 ||
        append_json_field_string(payload, capacity, &cursor, "packageHash", state->package_hash, &first) != 0 ||
        append_json_field_int(payload, capacity, &cursor, "grantRevision", state->grant_revision, &first) != 0 ||
        append_json_field_string(payload, capacity, &cursor, "oneTimeToken", state->one_time_token, &first) != 0 ||
        append_json_field_string(payload, capacity, &cursor, "channelNonce", state->channel_nonce, &first) != 0 ||
        append_json_field_string(payload, capacity, &cursor, "requestId", request_id, &first) != 0 ||
        append_json_field_string(payload, capacity, &cursor, "capability", capability, &first) != 0 ||
        append_json_field_string(payload, capacity, &cursor, "argumentsJson", arguments_json, &first) != 0 ||
        append_bytes(payload, capacity, &cursor, "}", 1) != 0) {
        free(payload);
        return -1;
    }
    payload[cursor] = '\0';
    *payload_out = payload;
    *length_out = cursor;
    return 0;
}

static int hex_value(unsigned char value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
}

static int append_utf8(char *output, size_t capacity, size_t *cursor, unsigned int codepoint) {
    char encoded[4];
    size_t length;
    if (codepoint <= 0x7f) {
        encoded[0] = (char)codepoint;
        length = 1;
    } else if (codepoint <= 0x7ff) {
        encoded[0] = (char)(0xc0 | (codepoint >> 6));
        encoded[1] = (char)(0x80 | (codepoint & 0x3f));
        length = 2;
    } else if (codepoint <= 0xffff) {
        encoded[0] = (char)(0xe0 | (codepoint >> 12));
        encoded[1] = (char)(0x80 | ((codepoint >> 6) & 0x3f));
        encoded[2] = (char)(0x80 | (codepoint & 0x3f));
        length = 3;
    } else if (codepoint <= 0x10ffff) {
        encoded[0] = (char)(0xf0 | (codepoint >> 18));
        encoded[1] = (char)(0x80 | ((codepoint >> 12) & 0x3f));
        encoded[2] = (char)(0x80 | ((codepoint >> 6) & 0x3f));
        encoded[3] = (char)(0x80 | (codepoint & 0x3f));
        length = 4;
    } else {
        return -1;
    }
    return append_bytes(output, capacity, cursor, encoded, length);
}

static int decode_json_string(const char *start, const char *end, char *output,
    size_t capacity, size_t *length_out) {
    if (start == NULL || end == NULL || start >= end || *start != '"') {
        return -1;
    }
    size_t cursor = 0;
    const unsigned char *p = (const unsigned char *)start + 1;
    const unsigned char *limit = (const unsigned char *)end;
    while (p < limit) {
        if (*p == '"') {
            if (cursor >= capacity) return -1;
            output[cursor] = '\0';
            *length_out = cursor;
            return 0;
        }
        if (*p != '\\') {
            if (cursor + 1 >= capacity) return -1;
            output[cursor++] = (char)*p++;
            continue;
        }
        p++;
        if (p >= limit) return -1;
        if (*p != 'u' && cursor + 1 >= capacity) return -1;
        switch (*p) {
            case '"': output[cursor++] = '"'; p++; break;
            case '\\': output[cursor++] = '\\'; p++; break;
            case '/': output[cursor++] = '/'; p++; break;
            case 'b': output[cursor++] = '\b'; p++; break;
            case 'f': output[cursor++] = '\f'; p++; break;
            case 'n': output[cursor++] = '\n'; p++; break;
            case 'r': output[cursor++] = '\r'; p++; break;
            case 't': output[cursor++] = '\t'; p++; break;
            case 'u': {
                if ((size_t)(limit - p) < 5) return -1;
                int h0 = hex_value(p[1]);
                int h1 = hex_value(p[2]);
                int h2 = hex_value(p[3]);
                int h3 = hex_value(p[4]);
                if (h0 < 0 || h1 < 0 || h2 < 0 || h3 < 0) return -1;
                unsigned int codepoint = (unsigned int)((h0 << 12) | (h1 << 8) | (h2 << 4) | h3);
                if (append_utf8(output, capacity - 1, &cursor, codepoint) != 0) return -1;
                p += 5;
                break;
            }
            default: return -1;
        }
        if (cursor >= capacity) return -1;
    }
    return -1;
}

static int json_find_string(const unsigned char *payload, size_t payload_length,
    const char *key, char *output, size_t capacity) {
    char needle[96];
    int needle_length = snprintf(needle, sizeof(needle), "\"%s\"", key);
    if (needle_length <= 0 || (size_t)needle_length >= sizeof(needle)) return -1;
    for (size_t i = 0; i + (size_t)needle_length < payload_length; i++) {
        if (memcmp(payload + i, needle, (size_t)needle_length) != 0) continue;
        size_t cursor = i + (size_t)needle_length;
        while (cursor < payload_length && (payload[cursor] == ' ' || payload[cursor] == '\t' ||
            payload[cursor] == '\r' || payload[cursor] == '\n')) cursor++;
        if (cursor >= payload_length || payload[cursor] != ':') continue;
        cursor++;
        while (cursor < payload_length && (payload[cursor] == ' ' || payload[cursor] == '\t' ||
            payload[cursor] == '\r' || payload[cursor] == '\n')) cursor++;
        if (cursor >= payload_length) return -1;
        size_t decoded_length = 0;
        return decode_json_string((const char *)(payload + cursor),
            (const char *)(payload + payload_length), output, capacity, &decoded_length);
    }
    return -1;
}

static int json_find_int(const unsigned char *payload, size_t payload_length, const char *key, int *value_out) {
    char needle[96];
    int needle_length = snprintf(needle, sizeof(needle), "\"%s\"", key);
    if (needle_length <= 0 || (size_t)needle_length >= sizeof(needle)) return -1;
    for (size_t i = 0; i + (size_t)needle_length < payload_length; i++) {
        if (memcmp(payload + i, needle, (size_t)needle_length) != 0) continue;
        size_t cursor = i + (size_t)needle_length;
        while (cursor < payload_length && (payload[cursor] == ' ' || payload[cursor] == '\t' ||
            payload[cursor] == '\r' || payload[cursor] == '\n')) cursor++;
        if (cursor >= payload_length || payload[cursor++] != ':') continue;
        while (cursor < payload_length && (payload[cursor] == ' ' || payload[cursor] == '\t' ||
            payload[cursor] == '\r' || payload[cursor] == '\n')) cursor++;
        char *end = NULL;
        long parsed = strtol((const char *)(payload + cursor), &end, 10);
        if (end == (char *)(payload + cursor) || parsed < INT32_MIN || parsed > INT32_MAX) return -1;
        *value_out = (int)parsed;
        return 0;
    }
    return -1;
}

static int request_broker(const char *capability, const char *arguments_json,
    char **value_json_out, size_t *value_length_out, char *error_code, size_t error_code_capacity,
    char *error_message, size_t error_message_capacity) {
    RuntimeState *state = current_state();
    if (state == NULL || capability == NULL || arguments_json == NULL || cancelled()) return -1;
    if (state->broker_calls >= state->max_broker_calls) {
        (void)snprintf(error_code, error_code_capacity, "broker_limit");
        (void)snprintf(error_message, error_message_capacity, "Broker call limit exceeded");
        return -1;
    }
    state->broker_calls++;
    state->request_sequence++;
    char request_id[64];
    (void)snprintf(request_id, sizeof(request_id), "r%d", state->request_sequence);
    char *request_payload = NULL;
    size_t request_length = 0;
    if (build_broker_request(&request_payload, &request_length, request_id, capability, arguments_json) != 0 ||
        write_frame(state->broker_request_fd, (const unsigned char *)request_payload, request_length) != 0) {
        free(request_payload);
        (void)snprintf(error_code, error_code_capacity, "broker_unavailable");
        (void)snprintf(error_message, error_message_capacity, "Broker unavailable");
        return -1;
    }
    free(request_payload);

    size_t response_length = 0;
    unsigned char *response = read_frame(state->broker_response_fd, &response_length);
    if (response == NULL) {
        (void)snprintf(error_code, error_code_capacity, cancelled() ? "cancelled" : "broker_unavailable");
        (void)snprintf(error_message, error_message_capacity, cancelled() ? "Invocation cancelled" : "Broker unavailable");
        return -1;
    }
    char response_id[64] = {0};
    char status[32] = {0};
    char response_error_code[MAX_ERROR_BYTES + 1] = {0};
    char response_error_message[MAX_ERROR_BYTES + 1] = {0};
    int chunk_index = 0;
    int chunk_count = 1;
    int ok = json_find_string(response, response_length, "requestId", response_id, sizeof(response_id)) == 0 &&
        json_find_string(response, response_length, "status", status, sizeof(status)) == 0 &&
        json_find_int(response, response_length, "chunkIndex", &chunk_index) == 0 &&
        json_find_int(response, response_length, "chunkCount", &chunk_count) == 0 &&
        chunk_index == 0 && chunk_count >= 1 && chunk_count <= MAX_BROKER_CHUNKS &&
        strcmp(response_id, request_id) == 0;
    if (ok && strcmp(status, "OK") == 0) {
        char chunk[MAX_BROKER_CHUNK_BYTES + 1] = {0};
        size_t aggregate_length = 0;
        const int expected_chunk_count = chunk_count;
        size_t aggregate_capacity = (size_t)expected_chunk_count * MAX_BROKER_CHUNK_BYTES;
        if (aggregate_capacity > MAX_BROKER_VALUE_BYTES) aggregate_capacity = MAX_BROKER_VALUE_BYTES;
        char *aggregate = (char *)calloc(aggregate_capacity + 1, 1);
        if (aggregate == NULL) ok = 0;
        for (int expected_index = 0; ok && expected_index < expected_chunk_count; expected_index++) {
            if (expected_index > 0) {
                free(response);
                response_length = 0;
                response = read_frame(state->broker_response_fd, &response_length);
                if (response == NULL) { ok = 0; break; }
                memset(response_id, 0, sizeof(response_id));
                memset(status, 0, sizeof(status));
                chunk_index = -1;
                chunk_count = 0;
                ok = json_find_string(response, response_length, "requestId", response_id, sizeof(response_id)) == 0 &&
                    json_find_string(response, response_length, "status", status, sizeof(status)) == 0 &&
                    json_find_int(response, response_length, "chunkIndex", &chunk_index) == 0 &&
                    json_find_int(response, response_length, "chunkCount", &chunk_count) == 0 &&
                    chunk_index == expected_index && chunk_count == expected_chunk_count &&
                    strcmp(response_id, request_id) == 0 && strcmp(status, "OK") == 0;
            }
            if (!ok || json_find_string(response, response_length, "valueJson", chunk, sizeof(chunk)) != 0) {
                ok = 0;
                break;
            }
            size_t chunk_length = strlen(chunk);
            if (aggregate_length > MAX_BROKER_VALUE_BYTES - chunk_length ||
                aggregate_length + chunk_length > aggregate_capacity) {
                ok = 0;
                break;
            }
            memcpy(aggregate + aggregate_length, chunk, chunk_length);
            aggregate_length += chunk_length;
            chunk[0] = '\0';
        }
        free(response);
        if (ok) {
            aggregate[aggregate_length] = '\0';
            *value_json_out = aggregate;
            *value_length_out = aggregate_length;
            return 0;
        }
        free(aggregate);
    } else {
        (void)json_find_string(response, response_length, "errorCode", response_error_code, sizeof(response_error_code));
        (void)json_find_string(response, response_length, "errorMessage", response_error_message, sizeof(response_error_message));
        (void)snprintf(error_code, error_code_capacity, "%s", response_error_code[0] ? response_error_code : "denied");
        (void)snprintf(error_message, error_message_capacity, "%s", response_error_message[0] ? response_error_message : "Capability denied");
    }
    free(response);
    if (error_code[0] == '\0') (void)snprintf(error_code, error_code_capacity, "broker_protocol");
    if (error_message[0] == '\0') (void)snprintf(error_message, error_message_capacity, "Invalid broker response");
    return -1;
}

static PyObject *python_request(PyObject *module, PyObject *arguments) {
    const char *capability = NULL;
    const char *arguments_json = NULL;
    Py_ssize_t arguments_length = 0;
    if (!PyArg_ParseTuple(arguments, "ss#", &capability, &arguments_json, &arguments_length)) {
        return NULL;
    }
    if (current_state() == NULL || capability[0] == '\0' || strlen(capability) > 128 ||
        arguments_length < 0 || arguments_length > MAX_CONTROL_FRAME) {
        PyErr_SetString(PyExc_ValueError, "Invalid broker request");
        return NULL;
    }
    char *arguments_copy = (char *)calloc((size_t)arguments_length + 1, 1);
    if (arguments_copy == NULL) {
        return PyErr_NoMemory();
    }
    memcpy(arguments_copy, arguments_json, (size_t)arguments_length);
    char *value_json = NULL;
    size_t value_length = 0;
    char error_code[MAX_ERROR_BYTES + 1] = {0};
    char error_message[MAX_ERROR_BYTES + 1] = {0};
    int result;
    Py_BEGIN_ALLOW_THREADS
    result = request_broker(capability, arguments_copy, &value_json, &value_length,
        error_code, sizeof(error_code), error_message, sizeof(error_message));
    Py_END_ALLOW_THREADS
    free(arguments_copy);
    if (result != 0) {
        if (strcmp(error_code, "cancelled") == 0) {
            PyErr_SetString(PyExc_KeyboardInterrupt, "Invocation cancelled");
        } else {
            PyErr_Format(PyExc_PermissionError, "%s", error_message[0] ? error_message : "Capability denied");
        }
        return NULL;
    }
    PyObject *value = PyUnicode_DecodeUTF8(value_json, (Py_ssize_t)value_length, "strict");
    free(value_json);
    return value;
}

static PyMethodDef mobileagent_methods[] = {
    {"request", python_request, METH_VARARGS, "Request one host capability using the private Broker."},
    {NULL, NULL, 0, NULL},
};

static struct PyModuleDef mobileagent_module = {
    PyModuleDef_HEAD_INIT,
    "_mobileagent",
    "Private native bridge for the isolated MobileAgent Python worker.",
    -1,
    mobileagent_methods,
    NULL,
    NULL,
    NULL,
    NULL,
};

PyMODINIT_FUNC PyInit__mobileagent(void) {
    return PyModule_Create(&mobileagent_module);
}

static int is_safe_fd_path(const char *path, size_t path_length, RuntimeState *state) {
    if (path == NULL || state == NULL) return 0;
    char expected[64];
    const int descriptors[] = { state->package_fd, state->stdlib_fd, state->input_fd };
    for (size_t i = 0; i < sizeof(descriptors) / sizeof(descriptors[0]); i++) {
        (void)snprintf(expected, sizeof(expected), "/proc/self/fd/%d", descriptors[i]);
        size_t expected_length = strlen(expected);
        if (path_length == expected_length && memcmp(path, expected, expected_length) == 0) return 1;
    }
    return (path_length == strlen("/dev/urandom") && memcmp(path, "/dev/urandom", path_length) == 0) ||
        (path_length == strlen("/dev/null") && memcmp(path, "/dev/null", path_length) == 0);
}

static int audit_path_is_safe(PyObject *arguments, RuntimeState *state) {
    if (arguments == NULL || !PyTuple_Check(arguments) || PyTuple_Size(arguments) == 0) return 0;
    PyObject *first = PyTuple_GetItem(arguments, 0);
    const char *path = NULL;
    Py_ssize_t path_length = 0;
    if (PyUnicode_Check(first)) {
        path = PyUnicode_AsUTF8AndSize(first, &path_length);
        if (path == NULL) {
            PyErr_Clear();
            return 0;
        }
    } else if (PyBytes_Check(first)) {
        char *bytes_path = NULL;
        if (PyBytes_AsStringAndSize(first, &bytes_path, &path_length) != 0) {
            PyErr_Clear();
            return 0;
        }
        path = bytes_path;
    } else {
        return 0;
    }
    return path_length >= 0 && is_safe_fd_path(path, (size_t)path_length, state);
}

/* CPython's POSIX os.write/os.writev implementations do not raise an audit
 * event.  They write directly through the C runtime, so an audit hook alone
 * cannot stop a script from injecting bytes into a descriptor inherited by
 * the worker.  Replace the public raw-descriptor write entry points in both
 * aliases (posix and os) before package code is imported.  Native channel
 * writes continue to use write_all() below and never call these Python
 * objects. */
static PyObject *deny_python_descriptor_write(PyObject *module, PyObject *arguments) {
    (void)module;
    (void)arguments;
    PyErr_SetString(PyExc_PermissionError,
        "Raw descriptor writes are unavailable in isolated Python");
    return NULL;
}

static const char raw_descriptor_write_message[] =
    "Raw descriptor writes are unavailable in isolated Python";

static PyMethodDef raw_descriptor_write_methods[] = {
    {"write", (PyCFunction)deny_python_descriptor_write, METH_VARARGS,
        raw_descriptor_write_message},
    {"writev", (PyCFunction)deny_python_descriptor_write, METH_VARARGS,
        raw_descriptor_write_message},
    {"pwrite", (PyCFunction)deny_python_descriptor_write, METH_VARARGS,
        raw_descriptor_write_message},
    {"pwritev", (PyCFunction)deny_python_descriptor_write, METH_VARARGS,
        raw_descriptor_write_message},
    {"sendfile", (PyCFunction)deny_python_descriptor_write, METH_VARARGS,
        raw_descriptor_write_message},
    {"copy_file_range", (PyCFunction)deny_python_descriptor_write, METH_VARARGS,
        raw_descriptor_write_message},
    {"splice", (PyCFunction)deny_python_descriptor_write, METH_VARARGS,
        raw_descriptor_write_message},
    {"tee", (PyCFunction)deny_python_descriptor_write, METH_VARARGS,
        raw_descriptor_write_message},
};

static int install_raw_descriptor_write_guards_for_module(PyObject *module,
    char *diagnostic, size_t diagnostic_capacity) {
    if (module == NULL) return -1;
    for (size_t index = 0;
        index < sizeof(raw_descriptor_write_methods) / sizeof(raw_descriptor_write_methods[0]);
        index++) {
        const char *name = raw_descriptor_write_methods[index].ml_name;
        PyObject *existing = PyObject_GetAttrString(module, name);
        if (existing == NULL) {
            if (PyErr_ExceptionMatches(PyExc_AttributeError)) {
                PyErr_Clear();
                continue;
            }
            capture_python_diagnostic(diagnostic, diagnostic_capacity, "python_fd_write_guard");
            return -1;
        }
        int callable = PyCallable_Check(existing);
        Py_DECREF(existing);
        if (!callable) {
            set_stage_diagnostic(diagnostic, diagnostic_capacity, "python_fd_write_guard");
            return -1;
        }
        PyObject *replacement = PyCFunction_NewEx(&raw_descriptor_write_methods[index],
            module, NULL);
        if (replacement == NULL) {
            capture_python_diagnostic(diagnostic, diagnostic_capacity, "python_fd_write_guard");
            return -1;
        }
        int result = PyObject_SetAttrString(module, name, replacement);
        Py_DECREF(replacement);
        if (result != 0) {
            capture_python_diagnostic(diagnostic, diagnostic_capacity, "python_fd_write_guard");
            return -1;
        }
    }
    return 0;
}

static int install_raw_descriptor_write_guards(char *diagnostic, size_t diagnostic_capacity) {
    PyObject *posix = PyImport_ImportModule("posix");
    if (posix == NULL) {
        capture_python_diagnostic(diagnostic, diagnostic_capacity, "python_fd_write_guard");
        return -1;
    }
    if (install_raw_descriptor_write_guards_for_module(posix, diagnostic, diagnostic_capacity) != 0) {
        Py_DECREF(posix);
        return -1;
    }
    PyObject *os = PyImport_ImportModule("os");
    if (os == NULL) {
        capture_python_diagnostic(diagnostic, diagnostic_capacity, "python_fd_write_guard");
        Py_DECREF(posix);
        return -1;
    }
    int result = install_raw_descriptor_write_guards_for_module(os, diagnostic, diagnostic_capacity);
    Py_DECREF(os);
    Py_DECREF(posix);
    return result;
}

static int audit_hook(const char *event, PyObject *arguments, void *user_data) {
    (void)user_data;
    RuntimeState *state = current_state();
    if (state == NULL || event == NULL ||
        !atomic_load_explicit(&g_audit_enabled, memory_order_acquire)) return 0;
    /* v3.14.7 does not emit these events for the built-in POSIX methods, so
     * install_raw_descriptor_write_guards() is the effective enforcement.
     * Keep the event checks as defense in depth for explicit sys.audit calls
     * and future CPython builds that add the events. */
    if (strcmp(event, "os.write") == 0 || strcmp(event, "os.writev") == 0 ||
        strcmp(event, "os.pwrite") == 0 || strcmp(event, "os.pwritev") == 0 ||
        strcmp(event, "os.sendfile") == 0 || strcmp(event, "os.copy_file_range") == 0 ||
        strcmp(event, "os.splice") == 0 || strcmp(event, "os.tee") == 0) {
        PyErr_SetString(PyExc_PermissionError,
            "Raw descriptor writes are unavailable in isolated Python");
        return -1;
    }
    if (strcmp(event, "open") == 0 || strcmp(event, "os.open") == 0 ||
        strcmp(event, "os.stat") == 0 || strcmp(event, "os.listdir") == 0 ||
        strcmp(event, "os.scandir") == 0 || strcmp(event, "os.access") == 0 ||
        strcmp(event, "os.getcwd") == 0 || strcmp(event, "os.chdir") == 0 ||
        strcmp(event, "os.putenv") == 0 || strcmp(event, "os.unsetenv") == 0) {
        if (audit_path_is_safe(arguments, state)) return 0;
        PyErr_SetString(PyExc_PermissionError, "Python file access is unavailable");
        return -1;
    }
    if (strncmp(event, "socket", 6) == 0 || strncmp(event, "subprocess", 10) == 0 ||
        strcmp(event, "os.system") == 0 || strcmp(event, "os.exec") == 0 ||
        strcmp(event, "os.fork") == 0 || strncmp(event, "os.spawn", 8) == 0 ||
        strcmp(event, "os.posix_spawn") == 0 || strcmp(event, "os.posix_spawnp") == 0 ||
        strcmp(event, "ctypes.dlopen") == 0 || strcmp(event, "sys.remote_exec") == 0) {
        PyErr_SetString(PyExc_PermissionError, "Network and process capabilities are unavailable");
        return -1;
    }
    if (strcmp(event, "import") == 0 && arguments != NULL && PyTuple_Size(arguments) >= 2) {
        PyObject *origin = PyTuple_GetItem(arguments, 1);
        if (origin != Py_None && PyUnicode_Check(origin)) {
            const char *origin_text = PyUnicode_AsUTF8(origin);
            if (origin_text != NULL && strstr(origin_text, ".so") != NULL) {
                PyErr_SetString(PyExc_PermissionError, "Native skill extensions are unavailable");
                return -1;
            }
        }
    }
    return 0;
}

static void set_result_fd(RuntimeState *state, const char *status, const char *error_code,
    const char *error_message, const char *output, size_t output_length) {
    if (state == NULL || state->result_fd < 0) return;
    if (atomic_load_explicit(&state->log_limit_exceeded, memory_order_acquire)) {
        output = NULL;
        output_length = 0;
        status = "UNKNOWN_OUTCOME";
        error_code = "log_limit";
        error_message = "Python log output limit exceeded";
    } else if (output_length > (size_t)state->max_output_bytes || output_length > MAX_OUTPUT_BYTES) {
        output = "";
        output_length = 0;
        status = "FAILED";
        error_code = "output_limit";
        error_message = "Python output limit exceeded";
    }
    char header[MAX_CONTROL_FRAME];
    int header_length = snprintf(header, sizeof(header),
        "{\"version\":%d,\"kind\":\"result\",\"status\":\"%s\",\"outputBytes\":%zu,"
        "\"channelNonce\":\"%s\"",
        MOBILEAGENT_VERSION, status, output_length, state->channel_nonce);
    if (header_length < 0 || (size_t)header_length >= sizeof(header)) return;
    size_t cursor = (size_t)header_length;
    if (error_code != NULL) {
        if (append_bytes(header, sizeof(header), &cursor, ",\"errorCode\":", 13) != 0 ||
            append_json_string(header, sizeof(header), &cursor, error_code) != 0) return;
        if (error_message != NULL &&
            (append_bytes(header, sizeof(header), &cursor, ",\"errorMessage\":", 16) != 0 ||
                append_json_string(header, sizeof(header), &cursor, error_message) != 0)) return;
    }
    if (append_bytes(header, sizeof(header), &cursor, "}", 1) != 0) return;
    (void)write_frame(state->result_fd, (const unsigned char *)header, cursor);
    if (output_length > 0) (void)write_all(state->result_fd, output, output_length);
}

static int read_input(RuntimeState *state, char **input_out, size_t *length_out) {
    size_t capacity = (size_t)state->max_input_bytes;
    char *input = (char *)calloc(capacity + 1, 1);
    if (input == NULL) return -1;
    size_t cursor = 0;
    while (cursor < capacity) {
        if (cancelled()) {
            free(input);
            return -2;
        }
        struct pollfd descriptor = { .fd = state->input_fd, .events = POLLIN };
        int ready = poll(&descriptor, 1, 200);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) { free(input); return -1; }
        if (ready == 0) continue;
        ssize_t count = read(state->input_fd, input + cursor, capacity - cursor);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) { free(input); return -1; }
        if (count == 0) break;
        cursor += (size_t)count;
    }
    if (cursor == capacity) {
        while (true) {
            if (cancelled()) { free(input); return -2; }
            struct pollfd descriptor = { .fd = state->input_fd, .events = POLLIN };
            int ready = poll(&descriptor, 1, 200);
            if (ready < 0 && errno == EINTR) continue;
            if (ready < 0) { free(input); return -1; }
            if (ready == 0) continue;
            char extra;
            ssize_t count = read(state->input_fd, &extra, 1);
            if (count < 0 && errno == EINTR) continue;
            if (count > 0) { free(input); return RESULT_INPUT_LIMIT; }
            if (count == 0) break;
            free(input);
            return -1;
        }
    }
    input[cursor] = '\0';
    *input_out = input;
    *length_out = cursor;
    return 0;
}

static int append_wide_path(PyConfig *config, const char *path) {
    wchar_t wide_path[128];
    size_t converted = mbstowcs(wide_path, path, sizeof(wide_path) / sizeof(wide_path[0]) - 1);
    if (converted == (size_t)-1) return -1;
    wide_path[converted] = L'\0';
    PyStatus status = PyWideStringList_Append(&config->module_search_paths, wide_path);
    return PyStatus_Exception(status) ? -1 : 0;
}

static int set_config_path(PyConfig *config, wchar_t **target, const char *path) {
    wchar_t wide_path[128];
    size_t converted = mbstowcs(wide_path, path, sizeof(wide_path) / sizeof(wide_path[0]) - 1);
    if (converted == (size_t)-1) return -1;
    wide_path[converted] = L'\0';
    PyStatus status = PyConfig_SetString(config, target, wide_path);
    return PyStatus_Exception(status) ? -1 : 0;
}

static const char *diagnostic_errno_name(int error_number) {
    switch (error_number) {
    case EACCES: return "eacces";
    case EBADF: return "ebadf";
    case EINVAL: return "einval";
    case EIO: return "eio";
    case ENOENT: return "enoent";
    case ENOTDIR: return "enotdir";
    case ELOOP: return "eloop";
    default: return "other";
    }
}

static int zip_magic_is_valid(const unsigned char *magic, size_t length) {
    if (magic == NULL || length < 4) return 0;
    return (magic[0] == 'P' && magic[1] == 'K' &&
        ((magic[2] == 3 && magic[3] == 4) ||
         (magic[2] == 5 && magic[3] == 6) ||
         (magic[2] == 7 && magic[3] == 8)));
}

static void set_code_archive_error(PyObject *exception) {
    if (!PyErr_Occurred()) {
        PyErr_SetString(exception, "Python code archive unavailable");
    }
}

static int code_archive_fd_for_path(PyObject *path, RuntimeState *state,
    int *fd_out, size_t *maximum_size_out) {
    if (path == NULL || state == NULL || fd_out == NULL || maximum_size_out == NULL ||
        !PyUnicode_Check(path)) {
        set_code_archive_error(PyExc_PermissionError);
        return -1;
    }
    Py_ssize_t path_length = 0;
    const char *path_text = PyUnicode_AsUTF8AndSize(path, &path_length);
    if (path_text == NULL || path_length < 0) {
        PyErr_Clear();
        set_code_archive_error(PyExc_PermissionError);
        return -1;
    }

    const int descriptors[] = { state->stdlib_fd, state->package_fd };
    const size_t limits[] = { MAX_STDLIB_BYTES, 32U * 1024U * 1024U };
    for (size_t index = 0; index < sizeof(descriptors) / sizeof(descriptors[0]); index++) {
        char expected[64];
        int expected_length = snprintf(expected, sizeof(expected), "/proc/self/fd/%d", descriptors[index]);
        if (expected_length >= 0 && (size_t)expected_length == (size_t)path_length &&
            memcmp(path_text, expected, (size_t)expected_length) == 0) {
            *fd_out = descriptors[index];
            *maximum_size_out = limits[index];
            return 0;
        }
    }
    set_code_archive_error(PyExc_PermissionError);
    return -1;
}

static PyObject *read_code_archive(int fd, size_t maximum_size) {
    struct stat descriptor_stat;
    if (fstat(fd, &descriptor_stat) != 0 || !S_ISREG(descriptor_stat.st_mode) ||
        descriptor_stat.st_size < 22 || descriptor_stat.st_size > (off_t)maximum_size ||
        descriptor_stat.st_size > (off_t)PY_SSIZE_T_MAX) {
        set_code_archive_error(PyExc_OSError);
        return NULL;
    }

    const size_t archive_size = (size_t)descriptor_stat.st_size;
    PyObject *archive = PyBytes_FromStringAndSize(NULL, (Py_ssize_t)archive_size);
    if (archive == NULL) return NULL;
    char *destination = PyBytes_AsString(archive);
    if (destination == NULL) {
        Py_DECREF(archive);
        set_code_archive_error(PyExc_OSError);
        return NULL;
    }
    size_t offset = 0;
    while (offset < archive_size) {
        size_t remaining = archive_size - offset;
        size_t chunk_size = remaining > CODE_ARCHIVE_READ_CHUNK ?
            CODE_ARCHIVE_READ_CHUNK : remaining;
        ssize_t read_count = pread(fd, destination + offset, chunk_size, (off_t)offset);
        if (read_count < 0 && errno == EINTR) continue;
        if (read_count <= 0) {
            Py_DECREF(archive);
            set_code_archive_error(PyExc_OSError);
            return NULL;
        }
        offset += (size_t)read_count;
    }

    struct stat final_stat;
    if (fstat(fd, &final_stat) != 0 || final_stat.st_size != descriptor_stat.st_size ||
        !zip_magic_is_valid((const unsigned char *)destination, archive_size)) {
        Py_DECREF(archive);
        set_code_archive_error(PyExc_OSError);
        return NULL;
    }
    return archive;
}

static PyObject *code_bytes_io_factory(RuntimeState *state) {
    if (state == NULL) {
        set_code_archive_error(PyExc_RuntimeError);
        return NULL;
    }
    if (state->code_bytes_io_type != NULL) {
        Py_INCREF(state->code_bytes_io_type);
        return state->code_bytes_io_type;
    }

    /* _io.open_code() is the caller of this hook, so _io is already loaded.
     * Look it up without importing a new module, as required for an import
     * hook that may run during interpreter initialization. */
    PyObject *module_name = PyUnicode_FromString("_io");
    if (module_name == NULL) return NULL;
    PyObject *io_module = PyImport_GetModule(module_name);
    Py_DECREF(module_name);
    if (io_module == NULL) {
        PyErr_Clear();
        set_code_archive_error(PyExc_ImportError);
        return NULL;
    }
    if (!PyModule_Check(io_module)) {
        Py_DECREF(io_module);
        set_code_archive_error(PyExc_ImportError);
        return NULL;
    }
    PyObject *factory = PyObject_GetAttrString(io_module, "BytesIO");
    Py_DECREF(io_module);
    if (factory == NULL) return NULL;
    if (!PyType_Check(factory)) {
        Py_DECREF(factory);
        set_code_archive_error(PyExc_ImportError);
        return NULL;
    }
    state->code_bytes_io_type = factory;
    Py_INCREF(factory);
    return factory;
}

static PyObject *open_code_hook(PyObject *path, void *user_data) {
    RuntimeState *state = (RuntimeState *)user_data;
    int fd = -1;
    size_t maximum_size = 0;
    if (code_archive_fd_for_path(path, state, &fd, &maximum_size) != 0) return NULL;

    PyObject *archive = read_code_archive(fd, maximum_size);
    if (archive == NULL) return NULL;
    PyObject *factory = code_bytes_io_factory(state);
    if (factory == NULL) {
        Py_DECREF(archive);
        return NULL;
    }
    /* BytesIO owns a private copy: its writable in-memory cursor cannot write
     * back to the read-only Binder descriptor or reach any IPC channel. */
    PyObject *stream = PyObject_CallOneArg(factory, archive);
    Py_DECREF(factory);
    Py_DECREF(archive);
    if (stream == NULL && !PyErr_Occurred()) {
        set_code_archive_error(PyExc_OSError);
    }
    return stream;
}

static void clear_code_bytes_io_type(RuntimeState *state) {
    if (state == NULL || state->code_bytes_io_type == NULL) return;
    Py_CLEAR(state->code_bytes_io_type);
}

static int validate_stdlib_descriptor(int fd, char *diagnostic, size_t diagnostic_capacity) {
    struct stat descriptor_stat;
    if (fstat(fd, &descriptor_stat) != 0) {
        (void)snprintf(diagnostic, diagnostic_capacity, "stage=stdlib_fd_fstat errno=%s",
            diagnostic_errno_name(errno));
        return -1;
    }
    if (!S_ISREG(descriptor_stat.st_mode) || descriptor_stat.st_size < 22 ||
        descriptor_stat.st_size > MAX_STDLIB_BYTES) {
        set_stage_diagnostic(diagnostic, diagnostic_capacity, "stdlib_fd_shape");
        return -1;
    }
    off_t current_offset = lseek(fd, 0, SEEK_CUR);
    if (current_offset < 0) {
        (void)snprintf(diagnostic, diagnostic_capacity, "stage=stdlib_fd_offset errno=%s",
            diagnostic_errno_name(errno));
        return -1;
    }
    (void)current_offset;
    unsigned char magic[4] = {0};
    if (pread(fd, magic, sizeof(magic), 0) != (ssize_t)sizeof(magic) ||
        !zip_magic_is_valid(magic, sizeof(magic))) {
        set_stage_diagnostic(diagnostic, diagnostic_capacity, "stdlib_fd_magic");
        return -1;
    }
    /* Android isolated processes may fstat/pread a Binder-passed descriptor
     * while SELinux denies reopening its /proc/self/fd path.  Do not make
     * path reopening a prerequisite: open_code_hook consumes this descriptor
     * directly with bounded pread and never exposes the path to Python. */
    return 0;
}

static int append_package_path(const char *package_path, char *diagnostic, size_t diagnostic_capacity) {
    PyObject *sys_path = PySys_GetObject("path");
    if (sys_path == NULL || !PyList_Check(sys_path)) {
        set_stage_diagnostic(diagnostic, diagnostic_capacity, "python_package_path");
        return -1;
    }
    PyObject *path = PyUnicode_FromString(package_path);
    if (path == NULL) {
        capture_python_diagnostic(diagnostic, diagnostic_capacity, "python_package_path");
        return -1;
    }
    int result = PyList_Append(sys_path, path);
    Py_DECREF(path);
    if (result != 0) {
        capture_python_diagnostic(diagnostic, diagnostic_capacity, "python_package_path");
        return -1;
    }
    return 0;
}

static int copy_status_identifier(const char *source, char *destination, size_t capacity) {
    if (source == NULL || destination == NULL || capacity < 2) return 0;
    size_t length = 0;
    while (source[length] != '\0' && length < 96 && length + 1 < capacity) length++;
    if (length == 0 || source[length] != '\0') return 0;
    for (size_t index = 0; index < length; index++) {
        const unsigned char value = (unsigned char)source[index];
        if (!diagnostic_token_char(value, 1)) return 0;
    }
    memcpy(destination, source, length);
    destination[length] = '\0';
    return 1;
}

static const char *status_message_category(const char *message) {
    if (message == NULL) return "unknown";
    if (strcmp(message, "failed to get the Python codec of the filesystem encoding") == 0 ||
        strcmp(message, "cannot initialize filesystem codec") == 0) {
        return "filesystem_codec";
    }
    if (strcmp(message, "Failed to import encodings module") == 0) return "encodings";
    if (strcmp(message, "failed to initialize importlib") == 0) return "importlib";
    if (strcmp(message, "failed to initialize sys streams") == 0 ||
        strcmp(message, "failed to initialize stream") == 0 ||
        strcmp(message, "failed to get the Python codec name of the stdio encoding") == 0) {
        return "std_streams";
    }
    if (strcmp(message, "Failed to import the site module") == 0) return "site";
    if (strcmp(message, "failed to initialize Android streams") == 0) return "android_streams";
    return "unknown";
}

/* PyStatus.err_msg is normally a CPython constant, but keep the diagnostic
 * channel safe if a future initialization path includes data.  A detail may
 * contain only short printable words; paths, separators, control/non-ASCII
 * bytes, and opaque long tokens are intentionally omitted. */
static int copy_status_message_detail(const char *source, char *destination, size_t capacity) {
    if (source == NULL || destination == NULL || capacity < 2) return 0;
    size_t length = 0;
    size_t token_length = 0;
    while (source[length] != '\0') {
        const unsigned char value = (unsigned char)source[length];
        if (value > 0x7f || value < 0x20 || value == '/' || value == '\\' ||
            value == ':' || value == '=' || value == '"' || value == '<' || value == '>') {
            return 0;
        }
        const int word_char = diagnostic_token_char(value, 1);
        if (word_char) {
            token_length++;
            if (token_length > 32) return 0;
        } else {
            token_length = 0;
            if (value != ' ' && value != ',' && value != '\'' && value != '-' && value != '(' && value != ')') {
                return 0;
            }
        }
        length++;
        if (length >= 128 || length + 1 >= capacity) return 0;
    }
    if (length == 0) return 0;
    memcpy(destination, source, length);
    destination[length] = '\0';
    return 1;
}

static void capture_status_diagnostic(char *destination, size_t capacity, const char *stage,
    PyStatus status) {
    set_stage_diagnostic(destination, capacity, stage);
    if (capacity < 2) return;
    char function_name[97] = {0};
    if (!copy_status_identifier(status.func, function_name, sizeof(function_name))) {
        (void)snprintf(function_name, sizeof(function_name), "unknown");
    }
    const char *status_type = PyStatus_IsError(status) ? "error" :
        (PyStatus_IsExit(status) ? "exit" : "unknown");
    const char *message_category = status_message_category(status.err_msg);
    char message_detail[128] = {0};
    if (copy_status_message_detail(status.err_msg, message_detail, sizeof(message_detail))) {
        int written = snprintf(destination, capacity,
            "stage=%s status_type=%s exitcode=%d func=%s msg=%s detail=%s",
            stage != NULL ? stage : "unknown", status_type, status.exitcode,
            function_name, message_category, message_detail);
        if (written >= 0 && (size_t)written < capacity) return;
    }
    (void)snprintf(destination, capacity,
        "stage=%s status_type=%s exitcode=%d func=%s msg=%s",
        stage != NULL ? stage : "unknown", status_type, status.exitcode,
        function_name, message_category);
}

static int pending_interrupt(void *argument) {
    (void)argument;
    PyErr_SetInterrupt();
    return 0;
}

static int initialize_python(RuntimeState *state, char *diagnostic, size_t diagnostic_capacity) {
    atomic_store_explicit(&g_audit_enabled, 0, memory_order_release);
    if (validate_stdlib_descriptor(state->stdlib_fd, diagnostic, diagnostic_capacity) != 0) return -1;
    if (PyImport_AppendInittab("_mobileagent", &PyInit__mobileagent) == -1) {
        set_stage_diagnostic(diagnostic, diagnostic_capacity, "python_init_registration");
        return -1;
    }
    if (PySys_AddAuditHook(audit_hook, NULL) != 0) {
        set_stage_diagnostic(diagnostic, diagnostic_capacity, "python_audit_hook");
        return -1;
    }

    PyConfig config;
    PyConfig_InitIsolatedConfig(&config);
    config.isolated = 1;
    config.use_environment = 0;
    config.install_signal_handlers = 0;
    config.site_import = 0;
    config.user_site_directory = 0;
    config.write_bytecode = 0;
    config.safe_path = 1;
    config.parse_argv = 0;
    config.module_search_paths_set = 1;
    char stdlib_path[64];
    char package_path[64];
    (void)snprintf(stdlib_path, sizeof(stdlib_path), "/proc/self/fd/%d", state->stdlib_fd);
    (void)snprintf(package_path, sizeof(package_path), "/proc/self/fd/%d", state->package_fd);
    char *argv[] = { (char *)"mobileagent-python", NULL };
    PyStatus status = PyConfig_SetBytesArgv(&config, 1, argv);
    if (PyStatus_Exception(status) || set_config_path(&config, &config.home, stdlib_path) != 0 ||
        set_config_path(&config, &config.stdlib_dir, stdlib_path) != 0 ||
        append_wide_path(&config, stdlib_path) != 0) {
        set_stage_diagnostic(diagnostic, diagnostic_capacity, "python_init_config");
        PyConfig_Clear(&config);
        return -1;
    }
    if (PyFile_SetOpenCodeHook(open_code_hook, state) != 0) {
        set_stage_diagnostic(diagnostic, diagnostic_capacity, "python_open_code_hook");
        PyConfig_Clear(&config);
        return -1;
    }
    status = Py_InitializeFromConfig(&config);
    if (PyStatus_Exception(status)) {
        /* Capture before Clear: CPython may return an err_msg owned by the
         * temporary configuration on some initialization paths. */
        capture_status_diagnostic(diagnostic, diagnostic_capacity, "python_initialize", status);
        PyConfig_Clear(&config);
        return -1;
    }
    /* The hook was registered before initialization, satisfying the native
     * hook ordering requirement.  Policy enforcement starts only after the
     * complete interpreter exists and before any skill/package import. */
    atomic_store_explicit(&g_audit_enabled, 1, memory_order_release);
    PyConfig_Clear(&config);
    if (append_package_path(package_path, diagnostic, diagnostic_capacity) != 0) {
        atomic_store_explicit(&g_audit_enabled, 0, memory_order_release);
        return -1;
    }
    if (install_raw_descriptor_write_guards(diagnostic, diagnostic_capacity) != 0) {
        atomic_store_explicit(&g_audit_enabled, 0, memory_order_release);
        return -1;
    }
    if (install_python_log_streams(state, diagnostic, diagnostic_capacity) != 0) {
        atomic_store_explicit(&g_audit_enabled, 0, memory_order_release);
        return -1;
    }
    return 0;
}

static int invoke_entrypoint(RuntimeState *state, const char *entrypoint, const char *input,
    size_t input_length, char **output_out, size_t *output_length_out,
    char *diagnostic, size_t diagnostic_capacity) {
    const char *failure_stage = "entrypoint";
    const char *colon = strchr(entrypoint, ':');
    if (colon == NULL || colon == entrypoint || colon[1] == '\0') {
        set_stage_diagnostic(diagnostic, diagnostic_capacity, failure_stage);
        return -1;
    }
    size_t module_length = (size_t)(colon - entrypoint);
    if (module_length > 256) {
        set_stage_diagnostic(diagnostic, diagnostic_capacity, failure_stage);
        return -1;
    }
    char module_name[257];
    memcpy(module_name, entrypoint, module_length);
    module_name[module_length] = '\0';
    const char *function_name = colon + 1;
    PyObject *json_module = NULL;
    PyObject *input_text = NULL;
    PyObject *loads = NULL;
    PyObject *input_object = NULL;
    PyObject *module = NULL;
    PyObject *callable = NULL;
    PyObject *result = NULL;
    PyObject *dumps = NULL;
    PyObject *result_text = NULL;

    failure_stage = "bootstrap_import";
    json_module = PyImport_ImportModule("json");
    failure_stage = "input_decode";
    input_text = PyUnicode_DecodeUTF8(input, (Py_ssize_t)input_length, "strict");
    if (json_module == NULL || input_text == NULL) goto fail;
    failure_stage = "input_parse";
    loads = PyObject_GetAttrString(json_module, "loads");
    input_object = loads == NULL ? NULL : PyObject_CallOneArg(loads, input_text);
    Py_XDECREF(loads);
    loads = NULL;
    Py_DECREF(input_text);
    input_text = NULL;
    if (input_object == NULL) goto fail;
    failure_stage = "skill_import";
    module = PyImport_ImportModule(module_name);
    failure_stage = "skill_lookup";
    callable = module == NULL ? NULL : PyObject_GetAttrString(module, function_name);
    if (callable == NULL || !PyCallable_Check(callable)) {
        goto fail;
    }
    failure_stage = "skill_call";
    result = PyObject_CallOneArg(callable, input_object);
    Py_DECREF(callable);
    callable = NULL;
    Py_DECREF(module);
    module = NULL;
    Py_DECREF(input_object);
    input_object = NULL;
    if (result == NULL) goto fail;
    failure_stage = "result_serialize";
    dumps = PyObject_GetAttrString(json_module, "dumps");
    result_text = dumps == NULL ? NULL : PyObject_CallOneArg(dumps, result);
    Py_XDECREF(dumps);
    dumps = NULL;
    Py_DECREF(result);
    result = NULL;
    if (result_text == NULL || !PyUnicode_Check(result_text)) {
        goto fail;
    }
    failure_stage = "result_encode";
    Py_ssize_t output_length = 0;
    const char *output = PyUnicode_AsUTF8AndSize(result_text, &output_length);
    if (output == NULL || output_length < 0 || output_length > state->max_output_bytes) {
        Py_DECREF(result_text);
        PyErr_Clear();
        return RESULT_OUTPUT_LIMIT;
    }
    char *copy = (char *)malloc((size_t)output_length + 1);
    if (copy == NULL) {
        Py_DECREF(result_text);
        return -1;
    }
    memcpy(copy, output, (size_t)output_length);
    copy[output_length] = '\0';
    Py_DECREF(result_text);
    Py_DECREF(json_module);
    *output_out = copy;
    *output_length_out = (size_t)output_length;
    return 0;

fail:
    capture_python_diagnostic(diagnostic, diagnostic_capacity, failure_stage);
    Py_XDECREF(loads);
    Py_XDECREF(input_text);
    Py_XDECREF(input_object);
    Py_XDECREF(callable);
    Py_XDECREF(module);
    Py_XDECREF(result);
    Py_XDECREF(dumps);
    Py_XDECREF(result_text);
    Py_XDECREF(json_module);
    return -1;
}

JNIEXPORT jint JNICALL
Java_runtime_mobileagent_python_PythonNative_nativeRun(
    JNIEnv *env, jobject object, jstring invocation_id, jstring run_id, jstring package_hash,
    jint grant_revision, jstring one_time_token, jstring channel_nonce, jstring entrypoint, jint timeout_ms,
    jint max_output_bytes, jint max_log_bytes, jint max_input_bytes, jint max_broker_calls,
    jint package_fd, jint stdlib_fd, jint input_fd, jint result_fd, jint broker_request_fd,
    jint broker_response_fd, jint log_fd) {
    (void)object;
    if (current_state() != NULL) return -1;
    RuntimeState *state = &g_runtime_state;
    memset(state, 0, sizeof(*state));
    atomic_init(&state->log_bytes, 0);
    atomic_init(&state->log_limit_exceeded, 0);
    state->package_fd = package_fd;
    state->stdlib_fd = stdlib_fd;
    state->input_fd = input_fd;
    state->result_fd = result_fd;
    state->broker_request_fd = broker_request_fd;
    state->broker_response_fd = broker_response_fd;
    state->log_fd = log_fd;
    state->max_output_bytes = max_output_bytes;
    state->max_log_bytes = max_log_bytes;
    state->max_input_bytes = max_input_bytes;
    state->max_broker_calls = max_broker_calls;
    state->grant_revision = grant_revision;

    if (timeout_ms < 1 || timeout_ms > 30 * 1000 ||
        max_output_bytes < 1 || max_output_bytes > MAX_OUTPUT_BYTES ||
        max_log_bytes < 1 || max_log_bytes > 512 * 1024 ||
        max_input_bytes < 1 || max_input_bytes > 256 * 1024 ||
        max_broker_calls < 1 || max_broker_calls > 20 ||
        package_fd < 0 || stdlib_fd < 0 || input_fd < 0 || result_fd < 0 ||
        broker_request_fd < 0 || broker_response_fd < 0 || log_fd < 0) {
        close_runtime_fds(state);
        return -1;
    }

    const jstring string_objects[] = {
        invocation_id,
        run_id,
        package_hash,
        one_time_token,
        channel_nonce,
        entrypoint,
    };
    const char *strings[] = {
        (*env)->GetStringUTFChars(env, invocation_id, NULL),
        (*env)->GetStringUTFChars(env, run_id, NULL),
        (*env)->GetStringUTFChars(env, package_hash, NULL),
        (*env)->GetStringUTFChars(env, one_time_token, NULL),
        (*env)->GetStringUTFChars(env, channel_nonce, NULL),
        (*env)->GetStringUTFChars(env, entrypoint, NULL),
    };
    if (strings[0] == NULL || strings[1] == NULL || strings[2] == NULL || strings[3] == NULL ||
        strings[4] == NULL || strings[5] == NULL ||
        strlen(strings[0]) > MAX_IDENTIFIER_BYTES || strlen(strings[1]) > MAX_IDENTIFIER_BYTES ||
        strlen(strings[2]) != 64 || strlen(strings[3]) > MAX_TOKEN_BYTES ||
        !valid_channel_nonce(strings[4])) {
        for (size_t i = 0; i < sizeof(strings) / sizeof(strings[0]); i++) {
            if (strings[i] != NULL) (*env)->ReleaseStringUTFChars(env, string_objects[i], strings[i]);
        }
        close_runtime_fds(state);
        return -1;
    }
    (void)snprintf(state->invocation_id, sizeof(state->invocation_id), "%s", strings[0]);
    (void)snprintf(state->run_id, sizeof(state->run_id), "%s", strings[1]);
    (void)snprintf(state->package_hash, sizeof(state->package_hash), "%s", strings[2]);
    (void)snprintf(state->one_time_token, sizeof(state->one_time_token), "%s", strings[3]);
    (void)snprintf(state->channel_nonce, sizeof(state->channel_nonce), "%s", strings[4]);
    const char *entrypoint_text = strings[5];
    atomic_store_explicit(&g_state, state, memory_order_release);

    if (log_fd >= 0) {
        (void)dup2(log_fd, STDOUT_FILENO);
        (void)dup2(log_fd, STDERR_FILENO);
    }
    struct rlimit cpu_limit;
    cpu_limit.rlim_cur = (rlim_t)(timeout_ms / 1000 + 2);
    cpu_limit.rlim_max = cpu_limit.rlim_cur;
    (void)setrlimit(RLIMIT_CPU, &cpu_limit);

    char *input = NULL;
    size_t input_length = 0;
    char *output = NULL;
    size_t output_length = 0;
    char diagnostic[MAX_DIAGNOSTIC_BYTES] = {0};
    set_stage_diagnostic(diagnostic, sizeof(diagnostic), "input_read");
    int result_code = read_input(state, &input, &input_length);
    if (result_code == 0 && !cancelled()) {
        if (initialize_python(state, diagnostic, sizeof(diagnostic)) != 0) {
            result_code = -1;
        } else {
            result_code = invoke_entrypoint(state, entrypoint_text, input, input_length,
                &output, &output_length, diagnostic, sizeof(diagnostic));
        }
    }
    if (Py_IsInitialized()) {
        clear_code_bytes_io_type(state);
        (void)Py_FinalizeEx();
    }
    if (cancelled()) {
        set_result_fd(state, "CANCELLED", "cancelled", "Invocation cancelled", NULL, 0);
    } else if (result_code == RESULT_INPUT_LIMIT) {
        set_result_fd(state, "FAILED", "input_limit", "Python input limit exceeded", NULL, 0);
    } else if (result_code == RESULT_OUTPUT_LIMIT) {
        set_result_fd(state, "FAILED", "output_limit", "Python output limit exceeded", NULL, 0);
    } else if (result_code != 0) {
        set_result_fd(state, "FAILED", "python_error", diagnostic[0] != '\0' ? diagnostic : "stage=unknown", NULL, 0);
    } else {
        set_result_fd(state, "SUCCEEDED", NULL, NULL, output, output_length);
    }
    free(input);
    free(output);
    close_runtime_fds(state);
    for (size_t i = 0; i < sizeof(strings) / sizeof(strings[0]); i++) {
        if (strings[i] != NULL) (*env)->ReleaseStringUTFChars(env, string_objects[i], strings[i]);
    }
    atomic_store_explicit(&g_state, NULL, memory_order_release);
    return result_code;
}

JNIEXPORT void JNICALL
Java_runtime_mobileagent_python_PythonNative_nativeCancel(JNIEnv *env, jobject object) {
    (void)env;
    (void)object;
    RuntimeState *state = current_state();
    if (state != NULL) {
        state->cancelled = 1;
        (void)Py_AddPendingCall(pending_interrupt, NULL);
    }
}
