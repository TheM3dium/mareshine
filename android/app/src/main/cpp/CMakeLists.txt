cmake_minimum_required(VERSION 3.22)
project(mareshine_native C CXX)

# The game (libsms.so), its loader (libsmsbootstrap.so) and SDL2 come from the
# repository's own CMakeLists.txt, as chasem-dev/sms-pc-port builds them for
# Android (docs/ANDROID.md); SDL's source is the 2.32.10 release in build/deps.
get_filename_component(SMS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(SMS_GX_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(SMS_SDL2_SOURCE "${SMS_ROOT}/build/deps/SDL2-2.32.10" CACHE PATH "" FORCE)
add_subdirectory("${SMS_ROOT}" sms_core)
