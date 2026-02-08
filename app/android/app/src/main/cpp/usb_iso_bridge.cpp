#include <jni.h>
#include <android/log.h>

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <vector>

#include <fcntl.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <linux/usbdevice_fs.h>

#define LOG_TAG "UsbIsoBridgeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void append_u32_le(std::vector<uint8_t> &out, uint32_t v) {
    out.push_back(static_cast<uint8_t>(v & 0xFFu));
    out.push_back(static_cast<uint8_t>((v >> 8) & 0xFFu));
    out.push_back(static_cast<uint8_t>((v >> 16) & 0xFFu));
    out.push_back(static_cast<uint8_t>((v >> 24) & 0xFFu));
}

static void append_i32_le(std::vector<uint8_t> &out, int32_t v) {
    append_u32_le(out, static_cast<uint32_t>(v));
}

static void throw_io(JNIEnv *env, const char *msg) {
    jclass ex = env->FindClass("java/io/IOException");
    if (!ex) return;
    env->ThrowNew(ex, msg ? msg : "IOException");
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_jp_espresso3389_methings_service_UsbIsoBridge_isochIn(
    JNIEnv *env,
    jclass,
    jint fd,
    jint endpoint_address,
    jint packet_size,
    jint num_packets,
    jint timeout_ms) {
    const int in_fd = static_cast<int>(fd);
    const int ep = static_cast<int>(endpoint_address);
    const int psize = static_cast<int>(packet_size);
    const int npk = static_cast<int>(num_packets);
    const int timeout = static_cast<int>(timeout_ms);

    if (in_fd < 0) {
        throw_io(env, "invalid_fd");
        return nullptr;
    }
    if (ep < 0 || ep > 255) {
        throw_io(env, "invalid_endpoint");
        return nullptr;
    }
    if (psize <= 0 || psize > (1024 * 1024)) {
        throw_io(env, "invalid_packet_size");
        return nullptr;
    }
    if (npk <= 0 || npk > 1024) {
        throw_io(env, "invalid_num_packets");
        return nullptr;
    }

    const size_t total_len = static_cast<size_t>(psize) * static_cast<size_t>(npk);
    std::vector<uint8_t> buffer(total_len);

    const size_t urb_size = sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc) * static_cast<size_t>(npk);
    auto *urb = reinterpret_cast<usbdevfs_urb *>(std::calloc(1, urb_size));
    if (!urb) {
        throw_io(env, "alloc_urb_failed");
        return nullptr;
    }

    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = static_cast<unsigned char>(ep);
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->buffer = buffer.data();
    urb->buffer_length = static_cast<int>(buffer.size());
    urb->number_of_packets = npk;
    for (int i = 0; i < npk; i++) {
        urb->iso_frame_desc[i].length = psize;
        urb->iso_frame_desc[i].actual_length = 0;
        urb->iso_frame_desc[i].status = 0;
    }

    if (ioctl(in_fd, USBDEVFS_SUBMITURB, urb) != 0) {
        const int e = errno;
        std::free(urb);
        char msg[128];
        std::snprintf(msg, sizeof(msg), "submit_urb_failed_errno_%d", e);
        LOGE("USBDEVFS_SUBMITURB failed errno=%d (%s)", e, std::strerror(e));
        throw_io(env, msg);
        return nullptr;
    }

    bool completed = false;
    const int step_ms = 5;
    int waited_ms = 0;
    while (waited_ms <= timeout) {
        void *reaped = nullptr;
        if (ioctl(in_fd, USBDEVFS_REAPURBNDELAY, &reaped) == 0) {
            if (reaped == urb) {
                completed = true;
                break;
            }
            // Unexpected urb pointer; ignore but warn.
            LOGW("REAPURBNDELAY returned unexpected urb=%p expected=%p", reaped, urb);
        } else {
            if (errno != EAGAIN) {
                LOGW("REAPURBNDELAY errno=%d (%s)", errno, std::strerror(errno));
            }
        }
        // Light sleep; we can't reliably poll(2) the device node for completion.
        usleep(static_cast<useconds_t>(step_ms * 1000));
        waited_ms += step_ms;
    }

    if (!completed) {
        // Best-effort cancel.
        (void)ioctl(in_fd, USBDEVFS_DISCARDURB, urb);
        std::free(urb);
        throw_io(env, "iso_transfer_timeout");
        return nullptr;
    }

    // Build output blob:
    // u32 magic "KISO" (0x4F53494B), u32 num_packets, u32 payload_len,
    // then num_packets * (i32 status, i32 actual_len), then payload bytes concatenated.
    std::vector<uint8_t> payload;
    payload.reserve(total_len);
    for (int i = 0; i < npk; i++) {
        const int actual = urb->iso_frame_desc[i].actual_length;
        if (actual > 0) {
            const size_t offset = static_cast<size_t>(i) * static_cast<size_t>(psize);
            const size_t end = offset + static_cast<size_t>(actual);
            if (end <= buffer.size()) {
                payload.insert(payload.end(), buffer.begin() + static_cast<long>(offset), buffer.begin() + static_cast<long>(end));
            }
        }
    }

    std::vector<uint8_t> out;
    out.reserve(12 + static_cast<size_t>(npk) * 8 + payload.size());
    append_u32_le(out, 0x4F53494Bu);  // "KISO"
    append_u32_le(out, static_cast<uint32_t>(npk));
    append_u32_le(out, static_cast<uint32_t>(payload.size()));
    for (int i = 0; i < npk; i++) {
        append_i32_le(out, static_cast<int32_t>(urb->iso_frame_desc[i].status));
        append_i32_le(out, static_cast<int32_t>(urb->iso_frame_desc[i].actual_length));
    }
    out.insert(out.end(), payload.begin(), payload.end());

    std::free(urb);

    jbyteArray j_out = env->NewByteArray(static_cast<jsize>(out.size()));
    if (!j_out) {
        throw_io(env, "alloc_bytearray_failed");
        return nullptr;
    }
    env->SetByteArrayRegion(j_out, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return j_out;
}

// methings-only JNI exports.
