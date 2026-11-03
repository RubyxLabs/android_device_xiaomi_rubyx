// SPDX-License-Identifier: Apache-2.0
#define LOG_TAG "audio.primary.rubyx"

#include <hardware/audio.h>
#include <log/log.h>

#include <cerrno>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include <unordered_map>

namespace {
using OpenOutput = decltype(audio_hw_device::open_output_stream);
using CloseDevice = decltype(hw_device_t::close);
struct DeviceHooks {
    OpenOutput openOutput;
    CloseDevice close;
};
std::mutex gMutex;
std::unordered_map<audio_hw_device*, DeviceHooks> gDevices;
std::once_flag gLoadOnce;
hw_module_t* gStockModule = nullptr;

int openOutput(audio_hw_device* device, audio_io_handle_t handle, audio_devices_t devices,
               audio_output_flags_t flags, audio_config* config,
               audio_stream_out** stream, const char* address) {
    if (!stream) return -EINVAL;
    *stream = nullptr;
    OpenOutput original;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        auto entry = gDevices.find(device);
        if (entry == gDevices.end()) return -ENODEV;
        original = entry->second.openOutput;
    }
    // HIDL 7.0 encodes SPATIALIZER as FAST | DEEP_BUFFER. The stock MTK
    // implementation cannot select an ADSP task for that combined flag.
    // Keep the framework spatializer output and use the supported FAST sink.
    constexpr unsigned int spatializerFlags = AUDIO_OUTPUT_FLAG_FAST | AUDIO_OUTPUT_FLAG_DEEP_BUFFER;
    if (static_cast<unsigned int>(flags) == spatializerFlags) {
        ALOGI("Using FAST playback for the software spatializer");
        flags = AUDIO_OUTPUT_FLAG_FAST;
    }
    return original(device, handle, devices, flags, config, stream, address);
}

int closeDevice(hw_device_t* common) {
    auto* device = reinterpret_cast<audio_hw_device*>(common);
    CloseDevice original;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        auto entry = gDevices.find(device);
        if (entry == gDevices.end()) return -ENODEV;
        original = entry->second.close;
        device->open_output_stream = entry->second.openOutput;
        common->close = original;
        gDevices.erase(entry);
    }
    return original(common);
}

int openDevice(const hw_module_t*, const char* name, hw_device_t** result) {
    if (!name || !result || strcmp(name, AUDIO_HARDWARE_INTERFACE) != 0) return -EINVAL;
    *result = nullptr;
    std::call_once(gLoadOnce, [] {
        // Keep the stock module loaded for the lifetime of its devices and streams.
        void* library = dlopen("/vendor/lib/hw/audio.primary.mt6877.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) {
            ALOGE("Cannot load stock audio module: %s", dlerror());
            return;
        }
        gStockModule = static_cast<hw_module_t*>(dlsym(library, HAL_MODULE_INFO_SYM_AS_STR));
        if (!gStockModule || !gStockModule->methods || !gStockModule->methods->open ||
            !gStockModule->id || strcmp(gStockModule->id, AUDIO_HARDWARE_MODULE_ID) != 0) {
            ALOGE("Invalid stock audio module");
            gStockModule = nullptr;
            dlclose(library);
        } else {
            gStockModule->dso = library;
        }
    });
    if (!gStockModule) return -ENODEV;
    int status = gStockModule->methods->open(gStockModule, name, result);
    if (status != 0) return status;
    if (!*result) return -ENODEV;
    auto* device = reinterpret_cast<audio_hw_device*>(*result);
    if (!device->open_output_stream || !device->common.close) {
        if (device->common.close) device->common.close(*result);
        *result = nullptr;
        return -ENODEV;
    }
    std::lock_guard<std::mutex> lock(gMutex);
    if (gDevices.find(device) == gDevices.end()) {
        gDevices.emplace(device, DeviceHooks{device->open_output_stream, device->common.close});
        device->open_output_stream = openOutput;
        device->common.close = closeDevice;
    }
    return 0;
}

hw_module_methods_t gMethods = {.open = openDevice};
}  // namespace

extern "C" {
__attribute__((visibility("default"))) audio_module HAL_MODULE_INFO_SYM = {
        .common = {
                .tag = HARDWARE_MODULE_TAG,
                .module_api_version = AUDIO_MODULE_API_VERSION_0_1,
                .hal_api_version = HARDWARE_HAL_API_VERSION,
                .id = AUDIO_HARDWARE_MODULE_ID,
                .name = "Ruby audio compatibility module",
                .author = "RubyxLabs",
                .methods = &gMethods,
        },
};
}
