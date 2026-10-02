// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

#ifndef MOBILEAGENT_SHA256_FD_H
#define MOBILEAGENT_SHA256_FD_H

#include <stddef.h>

int mobileagent_sha256_fd_matches(int descriptor, const char *expected_hex, size_t maximum_bytes);

#endif
