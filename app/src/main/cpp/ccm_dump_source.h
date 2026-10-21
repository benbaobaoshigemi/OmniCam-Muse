// Stock PMA110 ProProc prefixes, through WB and CCM.
#pragma once
static constexpr char kCcmDumpSource[] = R"muse_cl(
#define CL_KHR_FP16_ENABLE 1

/***********************************************************
** Copyright (C), 2024-2028, OPPO Mobile Comm Corp., Ltd.
** File: - base.cl
** Description:
** Version: 1.0
** Date : 2025-06-20
** Author: gaohang
**
** ---------------------Revision History: ---------------------
** <author>     <date>      <desc>
** gaohang      2025/06/20  initial version
****************************************************************/
#ifdef CL_KHR_FP16_ENABLE
    #pragma OPENCL EXTENSION  cl_khr_fp16 : enable
#endif

__constant sampler_t sampler_linear_unnorm      = CLK_FILTER_LINEAR | CLK_NORMALIZED_COORDS_FALSE | CLK_ADDRESS_CLAMP_TO_EDGE;
__constant sampler_t sampler_linear_norm        = CLK_FILTER_LINEAR | CLK_NORMALIZED_COORDS_TRUE  | CLK_ADDRESS_CLAMP_TO_EDGE;
__constant sampler_t sampler_linear_mirror_norm = CLK_FILTER_LINEAR | CLK_NORMALIZED_COORDS_TRUE  | CLK_ADDRESS_MIRRORED_REPEAT;

__constant sampler_t sampler_nearest_unnorm      = CLK_FILTER_NEAREST | CLK_NORMALIZED_COORDS_FALSE | CLK_ADDRESS_CLAMP_TO_EDGE;
__constant sampler_t sampler_nearest_norm        = CLK_FILTER_NEAREST | CLK_NORMALIZED_COORDS_TRUE  | CLK_ADDRESS_CLAMP_TO_EDGE;
__constant sampler_t sampler_nearest_mirror_norm = CLK_FILTER_NEAREST | CLK_NORMALIZED_COORDS_TRUE  | CLK_ADDRESS_MIRRORED_REPEAT;


inline float load1(const int idx, __global const float* src) {
    return src[idx];
}

inline uchar load1U8(const int idx, __global const uchar* src) {
    return src[idx];
}

inline void store1(float val, const int idx, __global float* out) {
    out[idx] = val;
}

inline void store1U8(uchar val, const int idx, __global uchar* out) {
    out[idx] = val;
}

inline float loadLoc1(const int idx, __local float* src) {
    return src[idx];
}

inline void storeLoc1(float val, const int idx, __local float* out) {
    out[idx] = val;
}

inline half load1F16(const int idx, __global const half* src) {
    return src[idx];
}

inline void store1F16(half val, const int idx, __global half* out) {
    out[idx] = val;
}

inline ushort load1U16(const int idx, __global const ushort* src) {
    return src[idx];
}

inline void store1U16(ushort val, const int idx, __global ushort* out) {
    out[idx] = val;
}

//artifactsGain
inline float computeScale(float inputSize, float outputSize, int alignCorners) {
    if(1 == alignCorners) {
        if (outputSize > 1.f) {
            return (float)(inputSize - 1.f) / (outputSize - 1.f);
        }
        return 0.f;
    }
    return (float)inputSize / outputSize;
}

inline float computeScaleIndex(float scale, float dstIndex, int alignCorners, int cubic) {
    if (1 == alignCorners) {
        return (float)scale * dstIndex;
    }
    float srcIndex = (float)scale * (dstIndex + 0.5f) - 0.5f;
    if (0 == cubic && srcIndex < 0.0f) {
        return 0.0f;
    }
    return srcIndex;
}

inline float4 getCubicUpsampleCoefficients(float t)
{
    float x1 = t;
    float x2 = 1.0f - t;
    float x1p1 = x1 + 1.0f;
    float x2p1 = x2 + 1.0f;

    float coeff0 = ((-0.75f * x1p1 + 3.75f) * x1p1 - 6.0f) * x1p1 + 3.0f;
    float coeff1 = ((1.25f * x1 - 2.25f) * x1) * x1 + 1.0f;
    float coeff2 = ((1.25f * x2 - 2.25f) * x2) * x2 + 1.0f;
    float coeff3 = ((-0.75f * x2p1 + 3.75f) * x2p1 - 6.0f) * x2p1 + 3.0f;

    return (float4)(coeff0, coeff1, coeff2, coeff3);
}

// idx:[min, max) mirror edge
inline int clampIndex(int idx, int min, int max) {
    int idxI = idx < min ? (-idx - 1) : ((idx < max) ? idx : (2 * max - 1 - idx));
    return clamp(idxI, min, max - 1);
}

inline int clampIndexReflect(int idx, int min, int max) {
    return idx < min ? clamp((int)abs(idx), min, max - 1) : ((idx < max) ? idx : clamp(2 * max - 2 - idx, min, max - 1));
}

inline int common_func_edge_clamp_repeat(int idx, int min, int max)
{
    return clamp(idx, min, max - 1);
}

inline float clampIndexF(float idx, float minV, float maxV) {
    float idxF = idx < minV ? -idx - 1 : ((idx < maxV) ? idx : 2 * maxV - 1 - idx);
    return clamp(idxF, minV, maxV - 1);
}

inline float clampEdgeF(float idx, float minV, float maxV) {
    float idxF = idx < minV ? -idx : ((idx < maxV) ? idx : 2 * maxV - idx);
    return clamp(idxF, minV, maxV - 1);
}

inline ushort common_func_min_u16x3(ushort3 input)
{
    ushort min_ab = (input.s0 < input.s1) ? input.s0 : input.s1;
    return (min_ab < input.s2) ? min_ab : input.s2;
}

inline ushort common_func_max_u16x3(ushort3 input)
{
    ushort max_ab = (input.s0 > input.s1) ? input.s0 : input.s1;
    return (max_ab > input.s2) ? max_ab : input.s2;
}

inline int boxfilterIdx(int2 pos, int w, int h)  {
    int x = pos.x;
    int y = pos.y;
    x = clampIndex(x, 0, w);
    y = clampIndex(y, 0, h);
    return mad24(y, w, x);
}

inline float4 read_pixel_edge_f32_img(read_only image2d_t srcImg, int2 pos, int w, int h) {
    int x = pos.x;
    int y = pos.y;

    if(x < 0) {
        x = -(x);
    } else if(x >= w) {
        x = (w << 1) - 2 - x;
    }
    if(y < 0) {
        y = -(y);
    } else if(y >= h) {
        y = (h << 1) - 2 - y;
    }

    return read_imagef(srcImg, sampler_nearest_unnorm, (int2)(x, y));
}

inline float4 read_pixel_edge_f32_img_norm(read_only image2d_t srcImg, int2 pos, float2 posScale, int w, int h) {
    int x = pos.x;
    int y = pos.y;

    if(x < 0) {
        x = -(x);
    } else if(x >= w) {
        x = (w << 1) - 2 - x;
    }
    if(y < 0) {
        y = -(y);
    } else if(y >= h) {
        y = (h << 1) - 2 - y;
    }
    return read_imagef(srcImg, sampler_linear_norm, (float2)(x, y) * posScale);
}

inline float common_func_select_by_pattern_i32(int pattern, int i1, int i2, int i3, int i4)
{
    return (pattern == 0) ? i1 : (pattern == 1) ? i2 : (pattern == 2) ? i3 : i4;
}

inline float common_func_select_by_pattern_fp32(int pattern, float f1, float f2, float f3, float f4)
{
    return (pattern == 0) ? f1 : (pattern == 1) ? f2 : (pattern == 2) ? f3 : f4;
}

inline float2 common_func_select_by_pattern_fp32x2(int pattern, float2 f1, float2 f2, float2 f3, float2 f4)
{
    return (pattern == 0) ? f1 : (pattern == 1) ? f2 : (pattern == 2) ? f3 : f4;
}

inline float3 common_func_select_by_pattern_fp32x3(int pattern, float3 f1, float3 f2, float3 f3, float3 f4)
{
    return (pattern == 0) ? f1 : (pattern == 1) ? f2 : (pattern == 2) ? f3 : f4;
}

inline float4 common_func_select_by_pattern_fp32x4(int pattern, float4 f1, float4 f2, float4 f3, float4 f4)
{
    return (pattern == 0) ? f1 : (pattern == 1) ? f2 : (pattern == 2) ? f3 : f4;
}

#define COMMON_FUNC_RESIZE_BILINEAR_BUF(funcName, TYPE, VECTYPE, LOADF, CONVF, SCALE)                           \
inline VECTYPE funcName(__global TYPE* srcBuf, float srcX, float srcY, int cols, int rows, int srcStride) {     \
                                                                                                                \
    srcX = clampIndexF(srcX, 0, cols);                                                                          \
    srcY = clampIndexF(srcY, 0, rows);                                                                          \
    int srcX1 = (int)srcX;                                                                                      \
    int srcY1 = (int)srcY;                                                                                      \
    float2 frac = (float2)(srcX - srcX1, srcY - srcY1);                                                         \
                                                                                                                \
    int srcX2 = min(srcX1 + 1, cols - 1);                                                                       \
    int srcY2 = min(srcY1 + 1, rows - 1);                                                                       \
                                                                                                                \
    int idx00 = mad24(srcStride, srcY1, srcX1);                                                                 \
    int idx01 = mad24(srcStride, srcY1, srcX2);                                                                 \
    int idx10 = mad24(srcStride, srcY2, srcX1);                                                                 \
    int idx11 = mad24(srcStride, srcY2, srcX2);                                                                 \
                                                                                                                \
    VECTYPE vec00 = CONVF(LOADF(idx00, srcBuf)) * SCALE;                                                        \
    VECTYPE vec01 = CONVF(LOADF(idx01, srcBuf)) * SCALE;                                                        \
    VECTYPE vec10 = CONVF(LOADF(idx10, srcBuf)) * SCALE;                                                        \
    VECTYPE vec11 = CONVF(LOADF(idx11, srcBuf)) * SCALE;                                                        \
                                                                                                                \
    return (1.0f - frac.x) * (1.0f - frac.y) * vec00 +                                                          \
                    frac.x * (1.0f - frac.y) * vec01 +                                                          \
                    (1.0f - frac.x) * frac.y * vec10 +                                                          \
                    frac.x * frac.y * vec11;                                                                    \
}

#ifdef CL_KHR_FP16_ENABLE
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_f16_c1_buf, half, float, vload_half, , 1.f)
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_f16_c2_buf, half, float2, vload_half2, , 1.f)
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_f16_c3_buf, half, float3, vload_half3, , 1.f)
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_f16_c4_buf, half, float4, vload_half4, , 1.f)
#endif
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_u8_c1_buf, uchar, float, load1U8, convert_float, 1.f / 255)
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_u8_c2_buf, uchar, float2, vload2, convert_float2, 1.f / 255)
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_u8_c3_buf, uchar, float3, vload3, convert_float3, 1.f / 255)
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_u8_c4_buf, uchar, float4, vload4, convert_float4, 1.f / 255)
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_u16_c3_buf, ushort, float3, vload3, convert_float3, 1.f)
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_s16_c3_buf, short, float3, vload3, convert_float3, 1.f)
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_f32_c1_buf, float, float, load1, , 1.f)
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_f32_c2_buf, float, float2, vload2, , 1.f)
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_f32_c3_buf, float, float3, vload3, , 1.f)
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_f32_c4_buf, float, float4, vload4, , 1.f)
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_u16_c1_buf, ushort, float, load1U16, convert_float, 1.f)
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_u16_c2_buf, ushort, float2, vload2, convert_float2, 1.f)
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_u8_c1_buf_unnorm, uchar, float, load1U8, convert_float, 1.f)
COMMON_FUNC_RESIZE_BILINEAR_BUF(common_func_resize_bilinear_u8_c2_buf_unnorm, uchar, float2, vload2, convert_float2, 1.f)

#define COMMON_FUNC_RESIZE_BICUBIC_BUF(funcName, TYPE, VECTYPE, LOADF, CONVF)                                   \
inline VECTYPE funcName(__global TYPE* src, float srcX, float srcY, int srcW, int srcH, int stride)             \
{                                                                                                               \
    srcX = clampIndexF(srcX, 0, srcW);                                                                          \
    srcY = clampIndexF(srcY, 0, srcH);                                                                          \
    int srcX1 = (int)srcX;                                                                                      \
    int srcY1 = (int)srcY;                                                                                      \
    float2 frac = (float2)(srcX - srcX1, srcY - srcY1);                                                         \
                                                                                                                \
    float4 x_coeffs = getCubicUpsampleCoefficients(frac.x);                                                     \
    float4 y_coeffs = getCubicUpsampleCoefficients(frac.y);                                                     \
    float x0 = x_coeffs.s0, x1 = x_coeffs.s1, x2 = x_coeffs.s2, x3 = x_coeffs.s3;                               \
    float y0 = y_coeffs.s0, y1 = y_coeffs.s1, y2 = y_coeffs.s2, y3 = y_coeffs.s3;                               \
                                                                                                                \
    float c00 = y0 * x0, c01 = y0 * x1, c02 = y0 * x2, c03 = y0 * x3;                                           \
    float c10 = y1 * x0, c11 = y1 * x1, c12 = y1 * x2, c13 = y1 * x3;                                           \
    float c20 = y2 * x0, c21 = y2 * x1, c22 = y2 * x2, c23 = y2 * x3;                                           \
    float c30 = y3 * x0, c31 = y3 * x1, c32 = y3 * x2, c33 = y3 * x3;                                           \
                                                                                                                \
    int startY = srcY1 - 1;                                                                                     \
    int startX = srcX1 - 1;                                                                                     \
    int y0_idx = clamp(startY,     0, srcH - 1);                                                                \
    int y1_idx = clamp(startY + 1, 0, srcH - 1);                                                                \
    int y2_idx = clamp(startY + 2, 0, srcH - 1);                                                                \
    int y3_idx = clamp(startY + 3, 0, srcH - 1);                                                                \
    int x0_idx = clamp(startX,     0, srcW - 1);                                                                \
    int x1_idx = clamp(startX + 1, 0, srcW - 1);                                                                \
    int x2_idx = clamp(startX + 2, 0, srcW - 1);                                                                \
    int x3_idx = clamp(startX + 3, 0, srcW - 1);                                                                \
                                                                                                                \
    int rb0 = stride * y0_idx, rb1 = stride * y1_idx, rb2 = stride * y2_idx, rb3 = stride * y3_idx;             \
                                                                                                                \
    VECTYPE color = (VECTYPE)(0.f);                                                                             \
    color += CONVF(LOADF(rb0 + x0_idx, src)) * c00;                                                             \
    color += CONVF(LOADF(rb0 + x1_idx, src)) * c01;                                                             \
    color += CONVF(LOADF(rb0 + x2_idx, src)) * c02;                                                             \
    color += CONVF(LOADF(rb0 + x3_idx, src)) * c03;                                                             \
                                                                                                                \
    color += CONVF(LOADF(rb1 + x0_idx, src)) * c10;                                                             \
    color += CONVF(LOADF(rb1 + x1_idx, src)) * c11;                                                             \
    color += CONVF(LOADF(rb1 + x2_idx, src)) * c12;                                                             \
    color += CONVF(LOADF(rb1 + x3_idx, src)) * c13;                                                             \
                                                                                                                \
    color += CONVF(LOADF(rb2 + x0_idx, src)) * c20;                                                             \
    color += CONVF(LOADF(rb2 + x1_idx, src)) * c21;                                                             \
    color += CONVF(LOADF(rb2 + x2_idx, src)) * c22;                                                             \
    color += CONVF(LOADF(rb2 + x3_idx, src)) * c23;                                                             \
                                                                                                                \
    color += CONVF(LOADF(rb3 + x0_idx, src)) * c30;                                                             \
    color += CONVF(LOADF(rb3 + x1_idx, src)) * c31;                                                             \
    color += CONVF(LOADF(rb3 + x2_idx, src)) * c32;                                                             \
    color += CONVF(LOADF(rb3 + x3_idx, src)) * c33;                                                             \
                                                                                                                \
    return color;                                                                                               \
}
COMMON_FUNC_RESIZE_BICUBIC_BUF(common_func_resize_bicubic_u16_c1_buf, ushort, float, load1U16, convert_float)
COMMON_FUNC_RESIZE_BICUBIC_BUF(common_func_resize_bicubic_u16_c2_buf, ushort, float2, vload2, convert_float2)
COMMON_FUNC_RESIZE_BICUBIC_BUF(common_func_resize_bicubic_u8_c1_buf, uchar, float, load1U8, convert_float)
COMMON_FUNC_RESIZE_BICUBIC_BUF(common_func_resize_bicubic_u8_c2_buf, uchar, float2, vload2, convert_float2)
COMMON_FUNC_RESIZE_BICUBIC_BUF(common_func_resize_bicubic_fp32_c3_buf, float, float3, vload3, convert_float3)

#define COMMON_RESIZE_BILINEAR_DILATE_BUF(funcName, TYPE, VECTYPE, LOADF, CONVF, SCALE)                                \
inline VECTYPE funcName(__global TYPE* srcBuf, float srcX, float srcY, int cols, int rows, int srcStride, int dilate, int index) {     \
                                                                                                                \
    srcX = clampIndexF(srcX, 0, cols);                                                                          \
    srcY = clampIndexF(srcY, 0, rows);                                                                          \
    int srcX1 = (int)srcX;                                                                                      \
    int srcY1 = (int)srcY;                                                                                      \
    float2 frac = (float2)(srcX - srcX1, srcY - srcY1);                                                         \
                                                                                                                \
    int srcX2 = min(srcX1 + 1, cols - 1);                                                                       \
    int srcY2 = min(srcY1 + 1, rows - 1);                                                                       \
                                                                                                                \
    int idx00 = mad24(srcStride, srcY1, srcX1 * dilate + index);                                                                 \
    int idx01 = mad24(srcStride, srcY1, srcX2 * dilate + index);                                                                 \
    int idx10 = mad24(srcStride, srcY2, srcX1 * dilate + index);                                                                 \
    int idx11 = mad24(srcStride, srcY2, srcX2 * dilate + index);                                                                 \
                                                                                                                \
    VECTYPE vec00 = CONVF(LOADF(idx00, srcBuf)) * SCALE;                                                        \
    VECTYPE vec01 = CONVF(LOADF(idx01, srcBuf)) * SCALE;                                                        \
    VECTYPE vec10 = CONVF(LOADF(idx10, srcBuf)) * SCALE;                                                        \
    VECTYPE vec11 = CONVF(LOADF(idx11, srcBuf)) * SCALE;                                                        \
                                                                                                                \
    return (1.0f - frac.x) * (1.0f - frac.y) * vec00 +                                                          \
                    frac.x * (1.0f - frac.y) * vec01 +                                                          \
                    (1.0f - frac.x) * frac.y * vec10 +                                                          \
                    frac.x * frac.y * vec11;                                                                    \
}
COMMON_RESIZE_BILINEAR_DILATE_BUF(common_func_resize_bilinear_dilate_f32_c3_buf, float, float3, vload3, , 1.f)

inline ushort4 readPacked4Pixels(__global uchar* srcPacked)
{
    uchar4 highBytes = vload4(0, srcPacked);
    uchar lowByte = srcPacked[4];
    ushort4 unpackedPixels = (ushort4)lowByte;
    ushort4 shiftBit = (ushort4)(0, 2, 4, 6);
    ushort4 shiftFlag = (ushort4)0x03;
    unpackedPixels = (convert_ushort4(highBytes) << (ushort4)2) | (((ushort4)lowByte >> shiftBit) & shiftFlag);
    return unpackedPixels;
}

inline void writePacked4Pixels(__global uchar* dstPacked, ushort4 pixels)
{
    uchar4 highBytes = convert_uchar4(pixels >> (ushort4)2);
    vstore4(highBytes, 0, dstPacked);

    ushort4 shiftBit = (ushort4)(0, 2, 4, 6);
    ushort4 shiftFlag = (ushort4)0x03;
    shiftBit = (pixels & shiftFlag) << shiftBit;
    dstPacked[4] = shiftBit.s0 | shiftBit.s1 | shiftBit.s2 | shiftBit.s3;
}

inline float quadBayer4in1Packed(__global uchar* srcImage, int x1, int y1, int indx1, int srcStride)
{
    ushort4 color = 0.f;
    int idx = mad24(y1, srcStride, x1);
    uchar remain = srcImage[idx + 4];
    color.s01 = (convert_ushort2(vload2(0, srcImage + idx + indx1)) << (ushort)2) +
        (ushort2)((remain >> (indx1 << 1)) & 0x03, (remain >> ((indx1 + 1) << 1)) & 0x03);

    idx += srcStride;
    remain = srcImage[idx + 4];
    color.s23 = (convert_ushort2(vload2(0, srcImage + idx + indx1)) << (ushort)2) +
        (ushort2)((remain >> (indx1 << 1)) & 0x03, (remain >> ((indx1 + 1) << 1)) & 0x03);
    return dot(convert_float4(color), 0.25f);
}

inline ushort raw_readPixel_bayer_packed(__global uchar* packed, int x, int y, int srcStride, int idx, int is1xN)
{
    int srcIdx = is1xN ? mad24(srcStride, y, x) * 5 : mad24(srcStride, y, x);
    ushort res = (ushort)packed[srcIdx + idx] << 2;
    res = res | (ushort)((packed[srcIdx + 4] >> (idx << 1)) & 0x03);
    return res;
}

inline float raw_bilinear_resize_bayer_packed(__global ushort* srcImage, float x, float y, int srcWidth, int srcHeight, int srcStride,
    int idx, int startX, int startY)
{
    int y1 = floor(y);
    int x1 = floor(x);
    float h1lambda = y - y1;
    float w1lambda = x - x1;
    x1 = clamp(x1, 0, srcWidth - 1);
    y1 = clamp(y1, 0, srcHeight - 1);
    int x1p = clamp(x1 + 1, 0, srcWidth - 1);
    int y1p = clamp(y1 + 1, 0, srcHeight - 1);

    srcWidth = srcWidth << 1;
    srcHeight = srcHeight << 1;

    int colorX1 = (idx & 1) + ((x1 & 1) << 1) + startX;
    int x0 = (x1 << 1) + (idx & 1) + startX;
    if (x0 >= srcWidth) {
        x1 = ((srcWidth >> 2) - 1) * 5;
        colorX1 = 2;
    } else {
        x1 = ((x1 & 1) == 0 || startX == 0 || (idx & 1) == 0) ? (x1 >> 1) * 5 : ((x1 >> 1) + 1) * 5;
    }
    y1 = (y1 << 1) + (idx >> 1) + startY;
    y1 = y1 >= srcHeight ? srcHeight - 2 : y1;
    colorX1 = colorX1 >= 4 ? 0 : colorX1;

    int colorX1p = (idx & 1) + ((x1p & 1) << 1) + startX;
    x0 = (x1p << 1) + (idx & 1) + startX;
    if (x0 >= srcWidth) {
        x1p = ((srcWidth >> 2) - 1) * 5;
        colorX1p = 2;
    } else {
        x1p = ((x1p & 1) == 0 || startX == 0 || (idx & 1) == 0) ? (x1p >> 1) * 5 : ((x1p >> 1) + 1) * 5;
    }
    y1p = (y1p << 1) + (idx >> 1) + startY;
    y1p = y1p >= srcHeight ? srcHeight - 2 : y1p;
    colorX1p = colorX1p >= 4 ? 0 : colorX1p;
    __global uchar* ev0Image = (__global uchar*)srcImage;

    float color00 = (float)raw_readPixel_bayer_packed(ev0Image, x1, y1, srcStride, colorX1, 0);
    float color01 = (float)raw_readPixel_bayer_packed(ev0Image, x1p, y1, srcStride, colorX1p, 0);
    float color10 = (float)raw_readPixel_bayer_packed(ev0Image, x1, y1p, srcStride, colorX1, 0);
    float color11 = (float)raw_readPixel_bayer_packed(ev0Image, x1p, y1p, srcStride, colorX1p, 0);
    float v0 = mix(color00, color01, w1lambda);
    float v1 = mix(color10, color11, w1lambda);
    return mix(v0, v1, h1lambda);
}



/***********************************************************
** Copyright (C), 2024-2028, OPPO Mobile Comm Corp., Ltd.
** File: - proproc.cl
** Description:
** Version: 1.0
** Date : 2025-06-20
** Author: gaohang
**
** ---------------------Revision History: ---------------------
** <author>     <date>      <desc>
** gaohang      2025/06/20  initial version
****************************************************************/

inline float3 ccmCal(float3 color, float3 ccmVec0, float3 ccmVec1, float3 ccmVec2, float rGain, float bGain, float awbMaxValue,
    float ispGain, float normVal)
{
    float3 awbGain = (float3)(rGain, 1.f, bGain);
    color = clamp(color * awbGain, 0.f, awbMaxValue * ispGain);
    color = color * normVal;

    float ccmR = dot(color, ccmVec0);
    float ccmG = dot(color, ccmVec1);
    float ccmB = dot(color, ccmVec2);
    return (float3)(ccmR, ccmG, ccmB);
}

inline float smoothstep_scalar(float edge0, float edge1, float x)
{
    float invedge1 = 1.f / edge1;
    float t = clamp((x - edge0) * invedge1, 0.f, 1.f);
    return t * t * (3.f - 2.f * t);
}

inline float proproc_compute_adjusted_noise_weight_fused_fast(float3 srcVec, float noiseWeight,
    float minGateEdge0, float minGateEdge1, float rgMaxGateEdge0, float rgMaxGateEdge1,
    float dipSpreadEdge0, float dipSpreadEdge1, float dipBrightEdge0, float dipBrightEdge1,
    float dipNotAllMidEdge0, float dipNotAllMidEdge1)
{
    // Ported from Python: compute_noise_weight_from_rgb_fused_fast + fused_fast_dip branch.
    // Thresholds in normalized channel domain; host (PostGenerateP010.cpp) precomputes edges with scale255.
    float r = srcVec.s0;
    float g = srcVec.s1;
    float b = srcVec.s2;

    float rgMax = fmax(r, g);
    float rgMin = fmin(r, g);
    float cMin = fmin(fmin(r, g), b);
    float cMax = fmax(fmax(r, g), b);
    float cMid = r + g + b - cMin - cMax;

    float ratioAvg = (r + g) / fmax(2.f * b, 1e-6f);
    float ratioMin = rgMin / fmax(b, 1e-6f);

    float minGate = 1.f - smoothstep_scalar(minGateEdge0, minGateEdge1, cMin);
    float ratioAvgGate = smoothstep_scalar(1.45f, 2.4f, ratioAvg);
    float ratioMinGate = smoothstep_scalar(0.8f, 1.25f, ratioMin);
    float rgMaxGate = smoothstep_scalar(rgMaxGateEdge0, rgMaxGateEdge1, rgMax);

    float attenuationBase = clamp(minGate * ratioAvgGate * ratioMinGate * rgMaxGate, 0.f, 1.f);

    float dipSpread = smoothstep_scalar(dipSpreadEdge0, dipSpreadEdge1, (cMid - cMin));
    float dipBright = smoothstep_scalar(dipBrightEdge0, dipBrightEdge1, cMax);
    float dipNotAllMid = 1.f - smoothstep_scalar(dipNotAllMidEdge0, dipNotAllMidEdge1, cMin);
    float attenuationDip = clamp(dipSpread * dipBright * dipNotAllMid, 0.f, 1.f);

    float attenuation = fmax(attenuationBase, attenuationDip);
    float minNoiseWeightScale = 0.0f;
    return noiseWeight * mix(1.f, minNoiseWeightScale, attenuation);
}

#define SMALL_GRAY(funcName, TYPE, VLOAD)                                                                                 \
inline float funcName(__global TYPE* srcImg, __global float* ccmTable, int2 coord, float grayScale,                       \
    float rGain, float bGain, float awbMaxValue, float ispGain, float normVal, int width, int height, int srcStride)      \
{                                                                                                                         \
    int w = coord.x;                                                                                                      \
    int h = coord.y;                                                                                                      \
                                                                                                                          \
    int y2Lo = h * grayScale;                                                                                             \
    int y2Hi = y2Lo + 1;                                                                                                  \
    int x2Lo = w * grayScale;                                                                                             \
    int x2Hi = (x2Lo + 1);                                                                                                \
                                                                                                                          \
    y2Lo = clampIndex(y2Lo, 0, height);                                                                                   \
    y2Hi = clampIndex(y2Hi, 0, height);                                                                                   \
    x2Lo = clampIndex(x2Lo, 0, width);                                                                                    \
    x2Hi = clampIndex(x2Hi, 0, width);                                                                                    \
                                                                                                                          \
    float3 sumColors = 0.f;                                                                                               \
    {                                                                                                                     \
        float3 ccmVec0 = vload3(0, ccmTable);                                                                             \
        float3 ccmVec1 = vload3(1, ccmTable);                                                                             \
        float3 ccmVec2 = vload3(2, ccmTable);                                                                             \
        {                                                                                                                 \
            float3 colorLo1 = VLOAD(mad24(srcStride, y2Lo, x2Lo), srcImg);/* check */                                     \
            colorLo1 = ccmCal(colorLo1, ccmVec0, ccmVec1, ccmVec2, rGain, bGain, awbMaxValue, ispGain, normVal);          \
            sumColors += colorLo1;                                                                                        \
        }                                                                                                                 \
        {                                                                                                                 \
            float3 colorLo2 = VLOAD(mad24(srcStride, y2Lo, x2Hi), srcImg);                                                \
            colorLo2 = ccmCal(colorLo2, ccmVec0, ccmVec1, ccmVec2, rGain, bGain, awbMaxValue, ispGain, normVal);          \
            sumColors += colorLo2;                                                                                        \
        }                                                                                                                 \
        {                                                                                                                 \
            float3 colorHi1 = VLOAD(mad24(srcStride, y2Hi, x2Lo), srcImg);                                                \
            colorHi1 = ccmCal(colorHi1, ccmVec0, ccmVec1, ccmVec2, rGain, bGain, awbMaxValue, ispGain, normVal);          \
            sumColors += colorHi1;                                                                                        \
        }                                                                                                                 \
        {                                                                                                                 \
            float3 colorHi2 = VLOAD(mad24(srcStride, y2Hi, x2Hi), srcImg);                                                \
            colorHi2 = ccmCal(colorHi2, ccmVec0, ccmVec1, ccmVec2, rGain, bGain, awbMaxValue, ispGain, normVal);          \
            sumColors += colorHi2;                                                                                        \
        }                                                                                                                 \
    }                                                                                                                     \
                                                                                                                          \
    float3 scales = (float3)(0.299f, 0.587f, 0.114f) * 0.25f;                                                             \
    return dot(scales, sumColors);                                                                                        \
}

SMALL_GRAY(small_gray_f32, float, vload3)
SMALL_GRAY(small_gray_f16, half, vload_half3)

#define PROPROC_GEN_GRAY(funcName, TYPE, GRAYFUNC)                                                                                                  \
__kernel void funcName(__global TYPE* srcImg, __global float* ccmTable, __global ushort* dstImg,                                                    \
    int left, int top, int cropWidth, int cropHeight, int interpWidth, int interpHeight, int align, float scale,                                    \
    float normVal, float bgain, float rgain, float awbMaxValue, float scaleVal, float ispGain, float ispGainInv,                                    \
    int grayScale, int srcWidth, int srcHeight, int srcStride)                                                                                      \
{                                                                                                                                                   \
    int x = get_global_id(0);                                                                                                                       \
    int y = get_global_id(1);                                                                                                                       \
    int cols = get_global_size(0);  /* grayWidth  */                                                                                                \
    int rows = get_global_size(1);  /* grayHeight */                                                                                                \
    int idx = mad24(cols, y, x);                                                                                                                    \
                                                                                                                                                    \
    float wScale = computeScale(cropWidth, interpWidth, align);                                                                                     \
    float hScale = computeScale(cropHeight, interpHeight, align);                                                                                   \
                                                                                                                                                    \
    float h2 = y;                                                                                                                                   \
    float w2 = x;                                                                                                                                   \
                                                                                                                                                    \
    float h1r = computeScaleIndex(hScale, h2, align, 0);                                                                                            \
    float h1 = floor(h1r);                                                                                                                          \
    int h1p = (h1 < cropHeight - 1.0f) ? 1 : 0;                                                                                                     \
    float h1lambda = h1r - h1;                                                                                                                      \
                                                                                                                                                    \
    float w1r = computeScaleIndex(wScale, w2, align, 0);                                                                                            \
    float w1 = floor(w1r);                                                                                                                          \
    int w1p = (w1 < cropWidth - 1.0f) ? 1 : 0;                                                                                                      \
    float w1lambda = w1r - w1;                                                                                                                      \
                                                                                                                                                    \
    w1 += left;                                                                                                                                     \
    h1 += top;                                                                                                                                      \
                                                                                                                                                    \
    int2 coord1 = (int2)((int)w1,       ((int)h1 + 0));                                                                                             \
    int2 coord2 = (int2)((int)w1 + w1p, ((int)h1 + 0));                                                                                             \
    int2 coord3 = (int2)((int)w1,       ((int)h1 + h1p));                                                                                           \
                                                                                            \
    int2 coord4 = (int2)((int)w1 + w1p, ((int)h1 + h1p));                                                                                           \
    float v0 = 0.f;                                                                                                                                 \
    float v1 = 0.f;                                                                                                                                 \
    {                                                                                                                                               \
        float color1 = GRAYFUNC(srcImg, ccmTable, coord1, grayScale, rgain, bgain, awbMaxValue, ispGain, normVal, srcWidth, srcHeight, srcStride);  \
        float color2 = GRAYFUNC(srcImg, ccmTable, coord2, grayScale, rgain, bgain, awbMaxValue, ispGain, normVal, srcWidth, srcHeight, srcStride);  \
        v0 = mix(color1, color2, w1lambda);                                                                                                         \
    }                                                                                                                                               \
    {                                                                                                                                               \
        float color1 = GRAYFUNC(srcImg, ccmTable, coord3, grayScale, rgain, bgain, awbMaxValue, ispGain, normVal, srcWidth, srcHeight, srcStride);  \
        float color2 = GRAYFUNC(srcImg, ccmTable, coord4, grayScale, rgain, bgain, awbMaxValue, ispGain, normVal, srcWidth, srcHeight, srcStride);  \
        v1 = mix(color1, color2, w1lambda);                                                                                                         \
    }                                                                                                                                               \
                                                                                                                                                    \
    float result = mix(v0, v1, h1lambda);                                                                                                           \
    dstImg[idx] = (ushort)clamp((int)(result * scaleVal * ispGainInv), 0, 65535);                                                                   \
}

PROPROC_GEN_GRAY(proproc_gen_gray_u16_buf_fp32, float, small_gray_f32)
PROPROC_GEN_GRAY(proproc_gen_gray_u16_buf_fp16, half, small_gray_f16)


inline float3 proproc_addnoise_func(float3 srcVec, float2 normCoords, int2 outSize,
    float3 shotVec, float3 readVec, __global float* noiseMap, int2 noiseSize, float noiseWeight,
    float avgScale, float randomNum)
{
    float3 noiseVec = mad(shotVec, srcVec, readVec);

    int2 dstCoords = convert_int2(normCoords * convert_float2(outSize - 1) + (float2).5f);
    int2 noiseCoords = (int2)(dstCoords.s0 % noiseSize.s0, dstCoords.s1 % noiseSize.s1);

    float3 gaussianVec = vload3(mad24(noiseCoords.s1, noiseSize.s0, noiseCoords.s0), noiseMap) * randomNum;
    gaussianVec = clamp(gaussianVec * sqrt(noiseVec), -1.f, 1.f);
    gaussianVec = (float3)dot(gaussianVec, avgScale);

    return clamp(mad(gaussianVec, (float3)noiseWeight, srcVec), 0.f, 1.f);
}

inline float3 proproc_addnoise_weight_func(float3 srcVec, float2 normCoords, int2 outSize,
    float3 shotVec, float3 readVec, __global float* noiseMap, int2 noiseSize, float noiseWeight, float noiseThres,
    float3 rgb2yuvParsVec, float avgScale, float randomNum)
{

    float weight = dot(rgb2yuvParsVec, srcVec) * noiseThres;
    weight = clamp(weight, 0.f, 1.f) * noiseWeight;

    return proproc_addnoise_func(srcVec, normCoords, outSize, shotVec, readVec, noiseMap, noiseSize, weight, avgScale, randomNum);
}


inline float3 capture_linear_pixel(__global half* src, int x, int y, int3 srcSize,
    float3 rgb2yuvParsVec0, float3 rgb2yuvParsVec1, float3 rgb2yuvParsVec2,
    uchar addNoiseEnable, __global float* noiseMap, int2 noiseSize, float3 shotVec, float3 readVec, float noiseWeight, float noiseThres,
    float3 awbGain, float awbMaxValue, float ispGain, int luxIndex,
    float3 ccmVec0, float3 ccmVec1, float3 ccmVec2, __global float* gammaTable, float gammaMaxScale,
    float maxValue, float randomNum, int2 dstSize, float outScaleW, float outScaleH, int offsetX, int offsetY, float whiteLevel,
    float minGateEdge0, float minGateEdge1, float rgMaxGateEdge0, float rgMaxGateEdge1,
    float dipSpreadEdge0, float dipSpreadEdge1, float dipBrightEdge0, float dipBrightEdge1,
    float dipNotAllMidEdge0, float dipNotAllMidEdge1)
{
    float2 dstCoordnorm = ((float2)(x, y) + 0.5f) / (float2)(dstSize.s0, dstSize.s1);
    float2 srcCoordnorm = dstCoordnorm * (float2)(outScaleW, outScaleH);
    float2 srcCoord = srcCoordnorm * (float2)(srcSize.s0, srcSize.s1) + (float2)(offsetX, offsetY);
    float3 srcVec = common_func_resize_bilinear_f16_c3_buf(src, srcCoord.x, srcCoord.y, srcSize.s0, srcSize.s1, srcSize.s2);

    float invMaxValue = 1.f / maxValue;
    srcVec = srcVec * invMaxValue;
    srcVec = clamp(srcVec, 0.f, 1.f);

    // add noise
    if (addNoiseEnable == 1) {
        float2 normCoords = (float2)(x, y) / convert_float2(srcSize.s01);
        srcVec = proproc_addnoise_weight_func(srcVec, normCoords, dstSize,
            shotVec, readVec, noiseMap, noiseSize, noiseWeight, noiseThres, rgb2yuvParsVec0, 1.f / 3, randomNum);
    }

    // awb
    srcVec = srcVec * maxValue * ispGain;
    srcVec = clamp(srcVec * awbGain, 0.f, awbMaxValue);
    srcVec = clamp(srcVec * invMaxValue, 0.f, 1.f);

    // ccm
    float ccmR = clamp(dot(srcVec, ccmVec0), 0.f, 1.f);
    float ccmG = clamp(dot(srcVec, ccmVec1), 0.f, 1.f);
    float ccmB = clamp(dot(srcVec, ccmVec2), 0.f, 1.f);
    float3 ccmVec = (float3)(ccmR, ccmG, ccmB);
    return ccmVec;
}

inline float3 capture_linear3f_pixel(__global half* src, int x, int y, int3 srcSize,
    float3 rgb2yuvParsVec0, float3 rgb2yuvParsVec1, float3 rgb2yuvParsVec2,
    uchar addNoiseEnable, __global float* noiseMap, int2 noiseSize, float3 shotVec, float3 readVec, float noiseWeight, float noiseThres,
    float3 awbGain, float awbMaxValue, float ispGain, int luxIndex,
    float3 ccmVec0, float3 ccmVec1, float3 ccmVec2, __global ushort* gammaTable, float gammaMaxScale,
    float maxValue, float randomNum, int2 dstSize, float outScaleW, float outScaleH, int offsetX, int offsetY, float whiteLevel,
    float minGateEdge0, float minGateEdge1, float rgMaxGateEdge0, float rgMaxGateEdge1,
    float dipSpreadEdge0, float dipSpreadEdge1, float dipBrightEdge0, float dipBrightEdge1,
    float dipNotAllMidEdge0, float dipNotAllMidEdge1)
{
    float2 dstCoordnorm = ((float2)(x, y) + 0.5f) / (float2)(dstSize.s0, dstSize.s1);
    float2 srcCoordnorm = dstCoordnorm * (float2)(outScaleW, outScaleH);
    float2 srcCoord = srcCoordnorm * (float2)(srcSize.s0, srcSize.s1) + (float2)(offsetX, offsetY);
    float3 srcVec = common_func_resize_bilinear_f16_c3_buf(src, srcCoord.x, srcCoord.y, srcSize.s0, srcSize.s1, srcSize.s2);

    float invMaxValue = 1.f / maxValue;
    srcVec = srcVec * invMaxValue;
    srcVec = clamp(srcVec, 0.f, 1.f);

    float adjustedNoiseWeight = proproc_compute_adjusted_noise_weight_fused_fast(srcVec, noiseWeight,
        minGateEdge0, minGateEdge1, rgMaxGateEdge0, rgMaxGateEdge1,
        dipSpreadEdge0, dipSpreadEdge1, dipBrightEdge0, dipBrightEdge1,
        dipNotAllMidEdge0, dipNotAllMidEdge1);

    // add noise need
    if (addNoiseEnable == 1) {
        float2 normCoords = (float2)(x, y) / convert_float2(srcSize.s01);
        srcVec = proproc_addnoise_weight_func(srcVec, normCoords, dstSize,
            shotVec, readVec, noiseMap, noiseSize, adjustedNoiseWeight, noiseThres, rgb2yuvParsVec0, 1.f / 3, randomNum);
    }

    // awb need
    srcVec = srcVec * maxValue * ispGain;
    srcVec = clamp(srcVec * awbGain, 0.f, awbMaxValue);
    srcVec = clamp(srcVec * invMaxValue, 0.f, 1.f);

    // ccm need
    float ccmR = clamp(dot(srcVec, ccmVec0), 0.f, 1.f);
    float ccmG = clamp(dot(srcVec, ccmVec1), 0.f, 1.f);
    float ccmB = clamp(dot(srcVec, ccmVec2), 0.f, 1.f);
    float3 ccmVec = (float3)(ccmR, ccmG, ccmB);
    return ccmVec;
}

__kernel void capture_linear_rgb(__global half* src, int srcW, int srcH, int srcStride, __global float* pars,                                
    __local float* cache, uchar addNoiseEnable, __global float* noiseMap, int noiseW, int noiseH, float noiseWeight,            
    float noiseThres, float redGain, float blueGain, float awbMaxValue, float ispGain, int luxIndex, __global float* ccmTable,  
    __global float* gammaTable, float gammaMaxScale, float maxValue, float randomNum, int dstStride, int dstScanLine,       
    float outScaleW, float outScaleH, int offsetX, int offsetY, __global float* dst, int dstOffsetY, int dstHeight,            
    float whiteLevel, int lsb2msb, float minGateEdge0, float minGateEdge1, float rgMaxGateEdge0, float rgMaxGateEdge1,          
    float dipSpreadEdge0, float dipSpreadEdge1, float dipBrightEdge0, float dipBrightEdge1,                                     
    float dipNotAllMidEdge0, float dipNotAllMidEdge1)                                                                           
{                                                                                                                               
    int srcX = get_global_id(0);                                                                                                
    int srcY = get_global_id(1) + dstOffsetY;                                                                                   
                                                                                                                                
    int dstW = get_global_size(0);                                                                                              
    int dstH = dstHeight;                                                                                                       
                                                                                                                                
    int3 srcSize = (int3)(srcW, srcH, srcStride);                                                                               
    int2 dstSize = (int2)(dstW, dstH);                                                                                          
    int2 noiseSize = (int2)(noiseW, noiseH);                                                                                    
                                                                                                                                
    float3 rgb2yuvParsVec0 = vload3(0, pars);                                                                                   
    float3 rgb2yuvParsVec1 = vload3(1, pars);                                                                                   
    float3 rgb2yuvParsVec2 = vload3(2, pars);                                                                                   
                                                                                                                                
    float3 awbGain = (float3)(redGain, 1.f, blueGain);                                                                          
    float3 ccmVec0 = vload3(0, ccmTable);                                                                                       
    float3 ccmVec1 = vload3(1, ccmTable);                                                                                       
    float3 ccmVec2 = vload3(2, ccmTable);                                                                                       
                                                                                                                                
    float3 shotVec = vload3(0, pars + 9);                                                                                       
    float3 readVec = vload3(0, pars + 9 + 3);                                                                                   
                                                                                                                                
    float3 yuvVec00 = capture_linear_pixel(src, srcX, srcY, srcSize, rgb2yuvParsVec0, rgb2yuvParsVec1, rgb2yuvParsVec2,                     
        addNoiseEnable, noiseMap, noiseSize, shotVec, readVec, noiseWeight, noiseThres,                                         
        awbGain, awbMaxValue, ispGain, luxIndex, ccmVec0, ccmVec1, ccmVec2,                                                     
        gammaTable, gammaMaxScale, maxValue, randomNum, dstSize, outScaleW,                                                     
        outScaleH, offsetX, offsetY, whiteLevel, minGateEdge0, minGateEdge1, rgMaxGateEdge0, rgMaxGateEdge1,                    
        dipSpreadEdge0, dipSpreadEdge1, dipBrightEdge0, dipBrightEdge1, dipNotAllMidEdge0, dipNotAllMidEdge1);                  
    vstore3(yuvVec00, ((int)get_global_id(1) - (int)get_global_offset(1)) * dstW + srcX, dst);
}

__kernel void capture_linear3f_rgb(__global half* src, int srcW, int srcH, int srcStride, __global float* pars,                                
    __local float* cache, uchar addNoiseEnable, __global float* noiseMap, int noiseW, int noiseH, float noiseWeight,            
    float noiseThres, float redGain, float blueGain, float awbMaxValue, float ispGain, int luxIndex, __global float* ccmTable,  
    __global ushort* gammaTable, float gammaMaxScale, float maxValue, float randomNum, int dstStride, int dstScanLine,       
    float outScaleW, float outScaleH, int offsetX, int offsetY, __global float* dst, int dstOffsetY, int dstHeight,            
    float whiteLevel, int lsb2msb, float minGateEdge0, float minGateEdge1, float rgMaxGateEdge0, float rgMaxGateEdge1,          
    float dipSpreadEdge0, float dipSpreadEdge1, float dipBrightEdge0, float dipBrightEdge1,                                     
    float dipNotAllMidEdge0, float dipNotAllMidEdge1)                                                                           
{                                                                                                                               
    int srcX = get_global_id(0);                                                                                                
    int srcY = get_global_id(1) + dstOffsetY;                                                                                   
                                                                                                                                
    int dstW = get_global_size(0);                                                                                              
    int dstH = dstHeight;                                                                                                       
                                                                                                                                
    int3 srcSize = (int3)(srcW, srcH, srcStride);                                                                               
    int2 dstSize = (int2)(dstW, dstH);                                                                                          
    int2 noiseSize = (int2)(noiseW, noiseH);                                                                                    
                                                                                                                                
    float3 rgb2yuvParsVec0 = vload3(0, pars);                                                                                   
    float3 rgb2yuvParsVec1 = vload3(1, pars);                                                                                   
    float3 rgb2yuvParsVec2 = vload3(2, pars);                                                                                   
                                                                                                                                
    float3 awbGain = (float3)(redGain, 1.f, blueGain);                                                                          
    float3 ccmVec0 = vload3(0, ccmTable);                                                                                       
    float3 ccmVec1 = vload3(1, ccmTable);                                                                                       
    float3 ccmVec2 = vload3(2, ccmTable);                                                                                       
                                                                                                                                
    float3 shotVec = vload3(0, pars + 9);                                                                                       
    float3 readVec = vload3(0, pars + 9 + 3);                                                                                   
                                                                                                                                
    float3 yuvVec00 = capture_linear3f_pixel(src, srcX, srcY, srcSize, rgb2yuvParsVec0, rgb2yuvParsVec1, rgb2yuvParsVec2,                     
        addNoiseEnable, noiseMap, noiseSize, shotVec, readVec, noiseWeight, noiseThres,                                         
        awbGain, awbMaxValue, ispGain, luxIndex, ccmVec0, ccmVec1, ccmVec2,                                                     
        gammaTable, gammaMaxScale, maxValue, randomNum, dstSize, outScaleW,                                                     
        outScaleH, offsetX, offsetY, whiteLevel, minGateEdge0, minGateEdge1, rgMaxGateEdge0, rgMaxGateEdge1,                    
        dipSpreadEdge0, dipSpreadEdge1, dipBrightEdge0, dipBrightEdge1, dipNotAllMidEdge0, dipNotAllMidEdge1);                  
    vstore3(yuvVec00, ((int)get_global_id(1) - (int)get_global_offset(1)) * dstW + srcX, dst);
}

)muse_cl";
