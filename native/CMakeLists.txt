cmake_minimum_required(VERSION 3.22)
project(aria2engine C CXX)

# libaria2 and its static dependencies from build/build.sh, per ABI.
set(PREFIX "${ARIA2_PREFIX_ROOT}/${ANDROID_ABI}/prefix")
if(NOT EXISTS "${PREFIX}/lib/libaria2.a")
  message(FATAL_ERROR "no libaria2 for ${ANDROID_ABI} in ${PREFIX}: run build/build.sh ${ANDROID_ABI} out/${ANDROID_ABI} first")
endif()
foreach(lib aria2 ssl crypto expat)
  add_library(${lib}_static STATIC IMPORTED)
  set_target_properties(${lib}_static PROPERTIES IMPORTED_LOCATION "${PREFIX}/lib/lib${lib}.a")
endforeach()

# The sandbox library (bi0shacker001/sandbox), pinned as a submodule; only its static library.
set(SBX_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../vendor/sandbox ${CMAKE_CURRENT_BINARY_DIR}/sandbox)

# The engine process: libaria2 under the lockdown.
add_library(aria2engine SHARED engine/engine_jni.cc)
target_include_directories(aria2engine PRIVATE "${PREFIX}/include" ${CMAKE_CURRENT_SOURCE_DIR}/../vendor/sandbox/include)
target_compile_features(aria2engine PRIVATE cxx_std_11)
target_link_libraries(aria2engine aria2_static ssl_static crypto_static expat_static sbx z dl log)
target_link_options(aria2engine PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-z,max-page-size=16384")

# The host process: the broker only, no libaria2.
add_library(aria2broker SHARED broker/broker_jni.c)
target_include_directories(aria2broker PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../vendor/sandbox/include)
target_link_libraries(aria2broker sbx log)
target_link_options(aria2broker PRIVATE "-Wl,-z,max-page-size=16384")
