cmake_minimum_required(VERSION 3.22.1)
project(jiege_native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# One LSPosed native module (see assets/native_init). native_entry.cpp owns the
# LSPosed entry point and routes library-load callbacks to the feature files.
add_library(jiege_native SHARED
    native_entry.cpp
    gr_basictone.cpp
    pop_path_remap.cpp
    natural_mode.cpp
    unlock25mp.cpp
    face200m.cpp
    master_color.cpp
    master_core.cpp
    master_dt.cpp
    master_canon.cpp
    soft_light.cpp
    master_lut_slots.cpp)

target_compile_options(jiege_native PRIVATE -O2 -fvisibility=hidden)
target_link_libraries(jiege_native log dl camera2ndk android EGL GLESv3)
