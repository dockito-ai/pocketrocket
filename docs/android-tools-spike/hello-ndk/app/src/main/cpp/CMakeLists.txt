cmake_minimum_required(VERSION 3.22.1)
project(hellondk C CXX)

# Third-party C library fetched from source (criterion d). Pinned to a commit and checked by hash.
include(FetchContent)
FetchContent_Declare(tinyexpr
    URL https://github.com/codeplea/tinyexpr/archive/ba2e32bbcc2d1e644a7e132d24c2f542821cabe0.zip
    URL_HASH SHA256=49e9c3121c558c63f28876cb4cfae35f5bf34b33c9d4ef0a201cca982d21ab90
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_GetProperties(tinyexpr)
if(NOT tinyexpr_POPULATED)
    FetchContent_Populate(tinyexpr)
endif()
add_library(tinyexpr STATIC ${tinyexpr_SOURCE_DIR}/tinyexpr.c)
target_include_directories(tinyexpr PUBLIC ${tinyexpr_SOURCE_DIR})

add_library(hellondk SHARED hello.cpp)
find_library(log-lib log)
target_link_libraries(hellondk PRIVATE tinyexpr ${log-lib} m)
