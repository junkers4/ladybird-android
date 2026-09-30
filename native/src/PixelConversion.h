/*
 * Copyright (c) 2026, the ladybird-android contributors.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#pragma once

// Pixel conversion used when presenting frames (requirement ARCH-003). Kept free of Ladybird headers
// so native/tests/PixelConversionTest.cpp can test it with a plain host compiler.

#include <stddef.h>
#include <stdint.h>
#include <string.h>

namespace LadybirdAndroid {

// Ladybird bitmaps store 0xAARRGGBB words (BGRA in memory); Android windows want RGBA in memory.
// Frames are presented opaque.
inline uint32_t bgra_to_opaque_rgba(uint32_t pixel)
{
    return 0xFF000000u | ((pixel & 0x00FF0000u) >> 16) | (pixel & 0x0000FF00u) | ((pixel & 0x000000FFu) << 16);
}

// Copies `copy_width` source pixels into `destination` (converting if `swizzle`) and fills the rest of
// the `destination_width` row with `background`.
inline void convert_row(uint32_t* destination, int destination_width, uint32_t const* source, int copy_width, bool swizzle, uint32_t background)
{
    int x = 0;
    if (source && copy_width > 0) {
        if (copy_width > destination_width)
            copy_width = destination_width;
        if (swizzle) {
            for (; x < copy_width; ++x)
                destination[x] = bgra_to_opaque_rgba(source[x]);
        } else {
            memcpy(destination, source, static_cast<size_t>(copy_width) * sizeof(uint32_t));
            x = copy_width;
        }
    }
    for (; x < destination_width; ++x)
        destination[x] = background;
}

}
