cmake_minimum_required(VERSION 3.22.1)

project("peerlinkprime")

set(CMAKE_CXX_STANDARD 17)

# ══════════════════════════════════════════════════════════════════
# SHIZUKU CMakeLists.txt links against:
#   target_link_libraries(libshizuku.so log cxx::cxx)
#
# cxx::cxx is org.lsposed.libcxx:libcxx — a prebuilt libc++ static lib.
# Shizuku uses it to get a consistent C++ runtime across ROM variants.
# It also happens to provide the <android/api-level.h> inline functions
# through the NDK headers it packages.
#
# YOUR OLD CMakeLists.txt had:
#   target_link_libraries(libpeerlinkstarter.so log)
#
# starter.cpp calls android_get_device_api_level() — a function declared
# as a static inline in <android/api-level.h> (NDK system header).
# Without that header included AND without the android system library linked,
# the function call resolves to an undefined symbol at link time.
#
# THE FIX: link the NDK "android" library. It provides android_get_device_api_level()
# and is the standard NDK way to use Android system APIs from native code.
# This is lighter than pulling in cxx::cxx (no additional AAR dependency needed).
# ══════════════════════════════════════════════════════════════════

add_compile_options(-Werror=format -fdata-sections -ffunction-sections -fno-exceptions -fno-rtti -fno-threadsafe-statics)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    message("Builing Release...")

    add_compile_options(-Os -flto -fvisibility=hidden -fvisibility-inlines-hidden)
    add_link_options(-flto -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--strip-all)
else ()
    message("Builing Debug...")

    add_definitions(-DDEBUG)
endif ()

add_library(peerlinkbackend SHARED
        peerlink_backend.cpp)

target_link_libraries(peerlinkbackend log android)

add_executable(libpeerlinkstarter.so
        starter.cpp
        misc.cpp
        selinux.cpp
        cgroup.cpp)

# "android" is the NDK system library that provides android_get_device_api_level().
# "log"     is the NDK logging library (__android_log_print used in logging.h).
# Shizuku also uses "log" — we match that exactly, plus add "android".
target_link_libraries(libpeerlinkstarter.so log android)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_custom_command(TARGET libpeerlinkstarter.so POST_BUILD
            COMMAND ${CMAKE_STRIP} --remove-section=.comment "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libpeerlinkstarter.so")
endif ()
