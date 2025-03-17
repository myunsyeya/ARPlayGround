LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE    := yuv-to-rgb
LOCAL_SRC_FILES := YuvToRgbConverter.cpp
LOCAL_LDLIBS    := -llog -ljnigraphics
LOCAL_CPPFLAGS  := -std=c++11

include $(BUILD_SHARED_LIBRARY) 