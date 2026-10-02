// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

#define _POSIX_C_SOURCE 200809L

#include "sha256_fd.h"

#include <errno.h>
#include <stdint.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define SHA256_BLOCK_BYTES 64U
#define SHA256_DIGEST_BYTES 32U
#define SHA256_HEX_BYTES (SHA256_DIGEST_BYTES * 2U)

typedef struct {
    uint32_t state[8];
    uint64_t bit_count;
    unsigned char block[SHA256_BLOCK_BYTES];
    size_t block_length;
} Sha256Context;

static const uint32_t round_constants[64] = {
    0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U,
    0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
    0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U,
    0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
    0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU,
    0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
    0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U,
    0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
    0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U,
    0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
    0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U,
    0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
    0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U,
    0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
    0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U,
    0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U,
};

static uint32_t rotate_right(uint32_t value, unsigned int count) {
    return (value >> count) | (value << (32U - count));
}

static uint32_t read_be32(const unsigned char *bytes) {
    return ((uint32_t)bytes[0] << 24) | ((uint32_t)bytes[1] << 16) |
        ((uint32_t)bytes[2] << 8) | (uint32_t)bytes[3];
}

static void write_be32(unsigned char *bytes, uint32_t value) {
    bytes[0] = (unsigned char)(value >> 24);
    bytes[1] = (unsigned char)(value >> 16);
    bytes[2] = (unsigned char)(value >> 8);
    bytes[3] = (unsigned char)value;
}

static void sha256_transform(Sha256Context *context, const unsigned char block[SHA256_BLOCK_BYTES]) {
    uint32_t words[64];
    for (size_t index = 0; index < 16; index++) {
        words[index] = read_be32(block + index * 4U);
    }
    for (size_t index = 16; index < 64; index++) {
        uint32_t left = words[index - 15];
        uint32_t right = words[index - 2];
        uint32_t sigma0 = rotate_right(left, 7) ^ rotate_right(left, 18) ^ (left >> 3);
        uint32_t sigma1 = rotate_right(right, 17) ^ rotate_right(right, 19) ^ (right >> 10);
        words[index] = words[index - 16] + sigma0 + words[index - 7] + sigma1;
    }

    uint32_t a = context->state[0];
    uint32_t b = context->state[1];
    uint32_t c = context->state[2];
    uint32_t d = context->state[3];
    uint32_t e = context->state[4];
    uint32_t f = context->state[5];
    uint32_t g = context->state[6];
    uint32_t h = context->state[7];

    for (size_t index = 0; index < 64; index++) {
        uint32_t sum1 = rotate_right(e, 6) ^ rotate_right(e, 11) ^ rotate_right(e, 25);
        uint32_t choose = (e & f) ^ (~e & g);
        uint32_t temporary1 = h + sum1 + choose + round_constants[index] + words[index];
        uint32_t sum0 = rotate_right(a, 2) ^ rotate_right(a, 13) ^ rotate_right(a, 22);
        uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
        uint32_t temporary2 = sum0 + majority;
        h = g;
        g = f;
        f = e;
        e = d + temporary1;
        d = c;
        c = b;
        b = a;
        a = temporary1 + temporary2;
    }

    context->state[0] += a;
    context->state[1] += b;
    context->state[2] += c;
    context->state[3] += d;
    context->state[4] += e;
    context->state[5] += f;
    context->state[6] += g;
    context->state[7] += h;
}

static void sha256_init(Sha256Context *context) {
    static const uint32_t initial_state[8] = {
        0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U, 0xa54ff53aU,
        0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U,
    };
    memcpy(context->state, initial_state, sizeof(initial_state));
    context->bit_count = 0;
    context->block_length = 0;
}

static void sha256_update(Sha256Context *context, const unsigned char *bytes, size_t length) {
    context->bit_count += (uint64_t)length * 8U;
    while (length > 0) {
        size_t available = SHA256_BLOCK_BYTES - context->block_length;
        size_t count = length < available ? length : available;
        memcpy(context->block + context->block_length, bytes, count);
        context->block_length += count;
        bytes += count;
        length -= count;
        if (context->block_length == SHA256_BLOCK_BYTES) {
            sha256_transform(context, context->block);
            context->block_length = 0;
        }
    }
}

static void sha256_finish(Sha256Context *context, unsigned char digest[SHA256_DIGEST_BYTES]) {
    uint64_t original_bit_count = context->bit_count;
    context->block[context->block_length++] = 0x80U;
    if (context->block_length > 56U) {
        memset(context->block + context->block_length, 0, SHA256_BLOCK_BYTES - context->block_length);
        sha256_transform(context, context->block);
        context->block_length = 0;
    }
    memset(context->block + context->block_length, 0, 56U - context->block_length);
    for (size_t index = 0; index < 8; index++) {
        context->block[63U - index] = (unsigned char)(original_bit_count >> (index * 8U));
    }
    sha256_transform(context, context->block);
    for (size_t index = 0; index < 8; index++) {
        write_be32(digest + index * 4U, context->state[index]);
    }
}

static int lower_hex_nibble(char character) {
    if (character >= '0' && character <= '9') return character - '0';
    if (character >= 'a' && character <= 'f') return character - 'a' + 10;
    return -1;
}

int mobileagent_sha256_fd_matches(int descriptor, const char *expected_hex, size_t maximum_bytes) {
    if (descriptor < 0 || expected_hex == NULL || strlen(expected_hex) != SHA256_HEX_BYTES) return 0;

    unsigned char expected[SHA256_DIGEST_BYTES];
    for (size_t index = 0; index < SHA256_DIGEST_BYTES; index++) {
        int high = lower_hex_nibble(expected_hex[index * 2U]);
        int low = lower_hex_nibble(expected_hex[index * 2U + 1U]);
        if (high < 0 || low < 0) return 0;
        expected[index] = (unsigned char)((high << 4) | low);
    }

    struct stat descriptor_stat;
    if (fstat(descriptor, &descriptor_stat) != 0 || !S_ISREG(descriptor_stat.st_mode) ||
        descriptor_stat.st_size < 22 || descriptor_stat.st_size > (off_t)maximum_bytes) {
        return 0;
    }

    Sha256Context context;
    sha256_init(&context);
    unsigned char buffer[64U * 1024U];
    off_t offset = 0;
    while (offset < descriptor_stat.st_size) {
        off_t remaining = descriptor_stat.st_size - offset;
        size_t requested = remaining < (off_t)sizeof(buffer) ? (size_t)remaining : sizeof(buffer);
        ssize_t count;
        do {
            count = pread(descriptor, buffer, requested, offset);
        } while (count < 0 && errno == EINTR);
        if (count <= 0 || (size_t)count > requested) return 0;
        sha256_update(&context, buffer, (size_t)count);
        offset += (off_t)count;
    }

    unsigned char actual[SHA256_DIGEST_BYTES];
    sha256_finish(&context, actual);
    unsigned char difference = 0;
    for (size_t index = 0; index < SHA256_DIGEST_BYTES; index++) {
        difference |= actual[index] ^ expected[index];
    }
    return difference == 0;
}
