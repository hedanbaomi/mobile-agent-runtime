# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only

cmake_minimum_required(VERSION 3.22.1)
project(mar_root_supervisor LANGUAGES C)

add_library(mar_root_supervisor SHARED mar_root_supervisor.c mar_root_file_ops.c)
target_compile_features(mar_root_supervisor PRIVATE c_std_11)
target_compile_options(mar_root_supervisor PRIVATE -Wall -Wextra -Werror=return-type)
target_link_libraries(mar_root_supervisor PRIVATE log)

# 16 KB page-size readiness for Android 15+ devices.
target_link_options(mar_root_supervisor PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
