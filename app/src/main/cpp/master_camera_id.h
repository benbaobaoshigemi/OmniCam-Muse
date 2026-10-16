#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

inline std::string masterCameraId(const uint8_t *active, size_t count, int32_t frameId) {
    if (active && count > 1 && count <= 32 && active[count - 1] == 0) {
        bool numeric = true;
        for (size_t i = 0; i + 1 < count; ++i)
            numeric &= active[i] >= '0' && active[i] <= '9';
        if (numeric) return std::string(reinterpret_cast<const char *>(active), count - 1);
    }
    return std::to_string(frameId);
}
