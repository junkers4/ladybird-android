/*
 * Copyright (c) 2026, the ladybird-android contributors.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

// Built and run by tools/tests/test_native.py. Verifies: ARCH-003

#include "../src/PixelConversion.h"
#include <stdio.h>

static int failures = 0;

#define EXPECT_EQ(a, b)                                                                       \
    do {                                                                                      \
        unsigned long long va = (a), vb = (b);                                                \
        if (va != vb) {                                                                       \
            fprintf(stderr, "%s:%d: %s == %s failed (%llx != %llx)\n", __FILE__, __LINE__, #a, #b, va, vb); \
            ++failures;                                                                       \
        }                                                                                     \
    } while (0)

int main()
{
    using namespace LadybirdAndroid;

    // Red, green, blue in Ladybird's 0xAARRGGBB -> RGBA bytes = 0xAABBGGRR words.
    EXPECT_EQ(bgra_to_opaque_rgba(0xFFFF0000u), 0xFF0000FFu);
    EXPECT_EQ(bgra_to_opaque_rgba(0xFF00FF00u), 0xFF00FF00u);
    EXPECT_EQ(bgra_to_opaque_rgba(0xFF0000FFu), 0xFFFF0000u);
    // Alpha is forced opaque.
    EXPECT_EQ(bgra_to_opaque_rgba(0x00123456u), 0xFF563412u);

    uint32_t source[3] = { 0xFFFF0000u, 0xFF00FF00u, 0xFF0000FFu };
    uint32_t destination[5] = { 0, 0, 0, 0, 0 };
    convert_row(destination, 5, source, 3, true, 0xFFFFFFFFu);
    EXPECT_EQ(destination[0], 0xFF0000FFu);
    EXPECT_EQ(destination[2], 0xFFFF0000u);
    EXPECT_EQ(destination[3], 0xFFFFFFFFu);
    EXPECT_EQ(destination[4], 0xFFFFFFFFu);

    // Copy width is clamped to the destination; no swizzle copies verbatim.
    uint32_t narrow[2] = { 0, 0 };
    convert_row(narrow, 2, source, 3, false, 0);
    EXPECT_EQ(narrow[0], 0xFFFF0000u);
    EXPECT_EQ(narrow[1], 0xFF00FF00u);

    // No frame yet: the whole row is background.
    uint32_t empty[2] = { 1, 1 };
    convert_row(empty, 2, nullptr, 0, true, 0xFF121212u);
    EXPECT_EQ(empty[0], 0xFF121212u);
    EXPECT_EQ(empty[1], 0xFF121212u);

    if (failures == 0)
        puts("PixelConversionTest: OK");
    return failures == 0 ? 0 : 1;
}
