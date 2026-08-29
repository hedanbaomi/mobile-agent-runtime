# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only

cmake_minimum_required(VERSION 3.22.1)
project(mobileagent_usearch_jni LANGUAGES CXX)

if(NOT USEARCH_ROOT OR NOT EXISTS "${USEARCH_ROOT}/include/usearch/index_dense.hpp")
    message(FATAL_ERROR "USEARCH_ROOT must point to the verified USearch v2.25.1 source tree")
endif()

add_library(usearch_jni SHARED UsearchJni.cpp)
target_compile_features(usearch_jni PRIVATE cxx_std_17)
target_compile_definitions(
    usearch_jni PRIVATE
    USEARCH_USE_OPENMP=0
    USEARCH_USE_NUMKONG=0
)
target_include_directories(usearch_jni PRIVATE "${USEARCH_ROOT}/include")

find_library(log-lib log)
target_link_libraries(usearch_jni PRIVATE ${log-lib})
