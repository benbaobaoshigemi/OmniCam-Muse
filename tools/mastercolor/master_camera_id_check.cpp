#include <cassert>
#include <cstdint>

#include "../../app/src/main/cpp/master_camera_id.h"

int main() {
    const uint8_t main[] = {'2', 0};
    const uint8_t tele[] = {'3', 0};
    const uint8_t invalid[] = {'2', 'x'};
    assert(masterCameraId(main, sizeof(main), 0) == "2");
    assert(masterCameraId(tele, sizeof(tele), 3) == "3");
    assert(masterCameraId(nullptr, 0, 3) == "3");
    assert(masterCameraId(invalid, sizeof(invalid), 3) == "3");
}
