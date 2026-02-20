cmake_minimum_required(VERSION 3.22.1)
project(methings_native)

add_library(usbiso SHARED
    usb_iso_bridge.cpp
)

find_library(log-lib log)

target_link_libraries(usbiso
    ${log-lib}
)
