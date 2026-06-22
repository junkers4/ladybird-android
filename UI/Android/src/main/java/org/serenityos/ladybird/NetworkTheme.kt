package org.serenityos.ladybird

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes

/**
 * Visual identity of a [NetworkMode]: the accent that recolors the app bar /
 * URL bar / status bar, and the logo shown in the toolbar switcher. Keeping
 * this in one place means adding a future mode only touches this table.
 */
enum class NetworkTheme(
    val mode: NetworkMode,
    @ColorRes val barColor: Int,
    @ColorRes val fieldColor: Int,
    @ColorRes val onColor: Int,
    @DrawableRes val icon: Int,
) {
    Normal(
        NetworkMode.Normal,
        R.color.ladybird_surface,
        R.color.ladybird_field_surface,
        R.color.ladybird_on_surface,
        R.drawable.ic_globe,
    ),
    Tor(
        NetworkMode.Tor,
        R.color.net_tor,
        R.color.net_tor_dark,
        R.color.net_tor_on,
        R.drawable.ic_shield,
    ),
    I2P(
        NetworkMode.I2P,
        R.color.net_i2p,
        R.color.net_i2p_dark,
        R.color.net_i2p_on,
        R.drawable.ic_lock,
    );

    /** All compartment icons are plain monochrome glyphs, tinted to the bar colour. */
    val tintIcon: Boolean get() = true

    companion object {
        fun of(mode: NetworkMode): NetworkTheme = entries.first { it.mode == mode }
    }
}
