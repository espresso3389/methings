cmake_minimum_required(VERSION 3.22.1)
project(pythonbridge)

add_library(pythonbridge SHARED
    python_bridge.cpp
)

add_library(usbiso SHARED
    usb_iso_bridge.cpp
)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(pythonbridge
    ${log-lib}
    ${dl-lib}
)

target_link_libraries(usbiso
    ${log-lib}
)
