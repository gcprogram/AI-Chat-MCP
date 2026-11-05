cmake_minimum_required(VERSION 3.22.1)
project(aichat_llama LANGUAGES C CXX)

# llama.cpp liegt (gitignored) in android/third_party/llama.cpp; die genaue Version steht in android/llama.cpp.version,
# geholt wird sie beim ersten Build per Gradle-Task fetchLlama.
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")

# Immer optimiert bauen: ein Debug-Build von ggml wäre um ein Vielfaches langsamer.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
add_compile_options(-O3 -DNDEBUG -fvisibility=hidden)

# Nur die Bibliothek, kein Server/Tools/Tests, keine Netzwerk-Abhängigkeiten.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
# Nur die Bild-Bibliothek (libmtmd) ohne den Rest von tools/: für das Bildmodell (Bilder beschreiben).
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

# ARM64 ohne Laufzeit-Erkennung: Armv8.2 mit Dot-Product und FP16 (Cortex-A78/X1 und neuer, z. B. Exynos 2100).
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama.cpp)

add_library(aichat_llama SHARED llama_jni.cpp vision_jni.cpp)
target_include_directories(aichat_llama PRIVATE ${LLAMA_DIR}/include ${LLAMA_DIR}/ggml/include ${LLAMA_DIR}/tools/mtmd)
target_link_libraries(aichat_llama PRIVATE mtmd llama ggml log android)
