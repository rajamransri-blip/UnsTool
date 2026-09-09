cmake_minimum_required(VERSION 3.22.1)
project("upstool_native")

set(CMAKE_CXX_STANDARD 17)

add_library(upstool_native SHARED
    pak_engine.cpp
)

find_library(log-lib log)
find_package(ZLIB REQUIRED)

target_link_libraries(upstool_native
    ${log-lib}
    ZLIB::ZLIB
)
