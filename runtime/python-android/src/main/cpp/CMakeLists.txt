# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only

cmake_minimum_required(VERSION 3.22.1)
project(mobileagent_python LANGUAGES C)

if(NOT DEFINED CPYTHON_RELEASE_DIR)
    message(FATAL_ERROR "CPYTHON_RELEASE_DIR is required; use the verified Python 3.14.7 Android package")
endif()

if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    set(CPYTHON_ARCH "aarch64")
    set(CPYTHON_TRIPLET "aarch64-linux-android")
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
    set(CPYTHON_ARCH "x86_64")
    set(CPYTHON_TRIPLET "x86_64-linux-android")
else()
    message(FATAL_ERROR "M6 supports only arm64-v8a and x86_64, got ${CMAKE_ANDROID_ARCH_ABI}")
endif()

set(CPYTHON_PREFIX "${CPYTHON_RELEASE_DIR}/extract-${CPYTHON_ARCH}/prefix")
set(CPYTHON_INCLUDE "${CPYTHON_PREFIX}/include/python3.14")
set(CPYTHON_LIBRARY "${CPYTHON_PREFIX}/lib/libpython3.14.so")

foreach(required_path IN ITEMS "${CPYTHON_INCLUDE}/Python.h" "${CPYTHON_LIBRARY}")
    if(NOT EXISTS "${required_path}")
        message(FATAL_ERROR "Verified CPython prefix is missing ${required_path}. Run the documented official-package preparation task.")
    endif()
endforeach()

add_library(cpython SHARED IMPORTED GLOBAL)
set_target_properties(cpython PROPERTIES
    IMPORTED_LOCATION "${CPYTHON_LIBRARY}"
    INTERFACE_INCLUDE_DIRECTORIES "${CPYTHON_INCLUDE}"
)

add_library(mobileagent_python SHARED python_runtime.c)
target_include_directories(mobileagent_python PRIVATE "${CPYTHON_INCLUDE}")
target_compile_features(mobileagent_python PRIVATE c_std_11)
target_compile_definitions(mobileagent_python PRIVATE
    PY_SSIZE_T_CLEAN
    MOBILEAGENT_PYTHON_VERSION="3.14"
)
target_compile_options(mobileagent_python PRIVATE -Wall -Wextra -Werror=return-type)
target_link_libraries(mobileagent_python PRIVATE cpython log)

# The official shared library keeps its own SONAME and links only against
# Android system libraries.  Keeping it as an imported target means Gradle
# packages the audited upstream binary; user skill archives never contribute
# native code to this target.
