cmake_minimum_required(VERSION 3.22.1)

project("redboxpcemulator")

add_library(
        ${CMAKE_PROJECT_NAME}
        SHARED
        redboxpcemulator.cpp
        redbox_qemu.cpp
        redbox_qemu_executor.cpp
)

target_include_directories(
        ${CMAKE_PROJECT_NAME}
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(
        log-lib
        log
)

find_library(
        android-lib
        android
)

target_link_libraries(
        ${CMAKE_PROJECT_NAME}
        ${log-lib}
        ${android-lib}
        dl
)