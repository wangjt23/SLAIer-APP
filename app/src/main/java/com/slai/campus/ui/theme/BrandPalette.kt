package com.slai.campus.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 深圳河套学院（SLAI）品牌色。
 *
 * 色值全部取自官方 PPT 模板 `theme/河套学院PPT模板0709.pptx`（26 页里实际用到的颜色，
 * 按出现频次统计），而不是我凭感觉调的：
 *
 * | 色值 | 出现次数 | 用途 |
 * |---|---|---|
 * | `#881E55` | 38 | 主色（深洋红/勃艮第）—— 模板上的标题与色块 |
 * | `#D14AA4` | 18 | 主色亮阶 |
 * | `#D82E98` | 3（渐变色标） | 强调色，logo 渐变的高饱和端 |
 * | `#E27DBE` | 22 | 主色浅阶（浅背景块） |
 * | `#29AAE3` | 51 | 次色（青蓝），模板里出现最多，用于对比信息 |
 * | `#5E173E` / `#310D21` | 5 / 1 | 最深的一档，深色模式的容器色 |
 * | `#262626` / `#595959` | 28 / 2 | 正文灰阶 |
 *
 * logo 本身的主体（左侧环形标志）采样得到 `#B05070` / `#902060`，与上表同族，
 * 所以整套配色和 logo 是自洽的。
 */
object BrandPalette {

    // 主色：深洋红
    val Magenta900 = Color(0xFF310D21)
    val Magenta800 = Color(0xFF4A1331)
    val Magenta700 = Color(0xFF5E173E)
    val Magenta600 = Color(0xFF881E55)   // 模板主色
    val Magenta500 = Color(0xFF9B0059)
    val Magenta400 = Color(0xFFC9379B)
    val Magenta300 = Color(0xFFD14AA4)
    val Magenta200 = Color(0xFFD82E98)
    val Magenta100 = Color(0xFFE27DBE)
    val Magenta50 = Color(0xFFF7DCEB)

    // 次色：青蓝
    val Cyan600 = Color(0xFF1478A6)
    val Cyan500 = Color(0xFF29AAE3)      // 模板次色
    val Cyan200 = Color(0xFF9BD8F2)
    val Cyan50 = Color(0xFFDCF1FB)

    // 中性
    val Ink = Color(0xFF17161A)
    val Ink800 = Color(0xFF262626)
    val Grey600 = Color(0xFF595959)
    val Grey400 = Color(0xFF9A9A9E)
    val Grey300 = Color(0xFFBFBFBF)
    val Grey200 = Color(0xFFE6E6E6)
    val Grey100 = Color(0xFFF0F0F0)
    val Grey50 = Color(0xFFF2F2F2)

    /** 深色模式下的"表面"，带一点勃艮第底色，而不是纯灰。 */
    val SurfaceDark = Color(0xFF1A1216)
    val SurfaceContainerDark = Color(0xFF241A1F)
    val SurfaceContainerHighDark = Color(0xFF2E2228)
}
