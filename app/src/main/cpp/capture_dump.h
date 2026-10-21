#pragma once

#include <cstddef>
#include <string>
#include "native_hooks.h"

bool captureDumpRaw();
bool captureDumpCcm();
bool captureDumpHdrt();
std::string captureDumpDirectory(const char *stage);
bool captureDumpWrite(const std::string &path, const void *data, size_t size);
void onLibraryLoadedForCaptureDump(HookFunction hook, const char *name, void *handle);
struct CaptureKernelArg { size_t size; const void *value; };
struct CaptureKernelArgs { const CaptureKernelArg *begin; const CaptureKernelArg *end; const CaptureKernelArg *capacity; };
void installCcmDump(HookFunction hook, void *handle);
void captureRawKernel(void *engine, const CaptureKernelArgs &args, unsigned dim, const size_t *offset, const size_t *global);
void installHdrtDump(HookFunction hook, const char *name, void *handle);
