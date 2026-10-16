#pragma once

#include <array>
#include <cstddef>
#include <string>
#include "master_core.h"

// Inline-hook function handed to native modules by LSPosed.
using HookFunction = int (*)(void *target, void *replacement, void **backup);

// Installs a hook on an exported symbol and logs a miss under the given tag.
void hookExport(HookFunction hook, void *handle, const char *symbol, void *replacement,
                void **backup, const char *tag);

// POP port: redirect POP retro settings and Meishe LUT reads to the app-private copies.
void installPopPathRemap(HookFunction hook);

// Natural photo mode (natural_mode.cpp): the edited tuning json and the softening, both per photo.
void onLibraryLoadedForNatural(const char *name);
// Returns the path to open instead of the tuning json, or null for every other path.
const char *naturalRemapPath(const char *path, char *buf, size_t n);

// Normal-mode 25MP unlock (unlock25mp.cpp): serves the relaxed config copies while the switch is on.
const char *unlock25mpRemapPath(const char *path, char *buf, size_t n);

// 哈苏超清 200MP without the big-face gate (face200m.cpp): serves the decision copy while the switch is on.
const char *face200mRemapPath(const char *path, char *buf, size_t n);

// Soft light replacement (soft_light.cpp): called for every library the camera process loads.
void onLibraryLoadedForSoftLight(HookFunction hook, const char *name, void *handle);

// GR port: called for every library the camera process loads.
void onLibraryLoadedForGr(HookFunction hook, const char *name, void *handle);
// True while BasicTone processes a master capture (set by the GR port's processCore hook).
bool basicToneMasterFrame();

// Master LUT slots (master_lut_slots.cpp): called for every library the camera process loads.
void onLibraryLoadedForLutSlots(HookFunction hook, const char *name, void *handle);

// Master colour (master_color.cpp).
extern void *gAlgoInterfaceHandle;  // libAlgoInterface handle (its deps include libcamera_metadata)
struct MasterColorCapture {
    int cct = 0;
    std::array<float, 9> ccm{};
    std::string cameraId;    // physical camera of the frame
    std::string lens;        // its scene front stage profile
    std::array<float, 4> param{NAN, NAN, NAN, NAN};  // look parameters (mastercolor::Key::param)
    int luxIndex = -1;
    bool luxBlend = false;
    std::string look = "hncs";
    float exposureEv = 0;
    float toneWeight = 1;    // blend mode weights 0..1; 1 / 1 = replacement
    float colourWeight = 1;
    std::array<float, 3> density{1, 1, 1};
};
// Master colour selected in the module UI: false = stock master LMT, else look = hncs / lumix_* / film_*,
// with the exposure compensation (EV) on top of the engine's default exposure and, in blend mode, how much of
// the look replaces the stock tone and colour (0..1, 1 / 1 = plain replacement).
bool masterColorLook(std::string &look, float &exposureEv, float &toneWeight, float &colourWeight, std::array<float, 3> &density, std::array<float, 4> &param);
bool masterColorCaptureInfo(void *frame, int cctFromParams, MasterColorCapture &out);
std::string masterColorCaptureDir(const MasterColorCapture &capture, int width, int height);
void masterColorPreparePath(const char *path);
const char *masterColorRemapPath(const char *path, char *buf, size_t n);
