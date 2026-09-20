# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only

cmake_minimum_required(VERSION 3.22.1)
project(mobileagent_usearch_jni LANGUAGES CXX)

if(NOT USEARCH_ROOT OR NOT EXISTS "${USEARCH_ROOT}/include/usearch/index_dense.hpp")
    message(FATAL_ERROR "USEARCH_ROOT must point to the verified USearch v2.25.1 source tree")
endif()

add_library(usearch_jni SHARED UsearchJni.cpp)
target_compile_features(usearch_jni PRIVATE cxx_std_17)
# USearch is header-only, so its distance/search loops inherit this target's
# flags. Debug is also the native fallback for the local review APK. Keep -g
# and ordinary safety checks, but avoid minutes-long scalar index builds in
# those app variants. No fast-math or assertion changes; Release keeps its
# toolchain optimization settings.
target_compile_options(usearch_jni PRIVATE "$<$<CONFIG:Debug>:-O2>")
target_compile_definitions(
    usearch_jni PRIVATE
    USEARCH_USE_OPENMP=0
    USEARCH_USE_NUMKONG=0
)
target_include_directories(usearch_jni PRIVATE "${USEARCH_ROOT}/include")

find_library(log-lib log)
target_link_libraries(usearch_jni PRIVATE ${log-lib})
# 16 KB page-size readiness.  Google Play requires native libraries to be
# loadable on Android 15+ devices that run with 16 KB memory pages.  AGP already
# zipaligns the packaged .so entries to 16 KB, but the ELF program headers still
# described 4 KB pages, so the dynamic linker rejected the mapping.
#
# -z max-page-size widens PT_LOAD p_align from 4096 to 16384 and keeps
# p_vaddr - p_offset congruent modulo the page size.  It is not sufficient on
# its own: lld rounds the end of GNU_RELRO up to the *common* page size, so
# without -z common-page-size the GNU_RELRO segment still ends mid-page.  The
# loader then protects part of .data read-only and the library crashes on a
# 16 KB device even though every PT_LOAD looks correct.  Both options are
# additive; RELRO, BIND_NOW and the existing hardening flags stay in place.
target_link_options(usearch_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
