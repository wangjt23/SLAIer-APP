package com.slai.campus.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字号阶梯。
 *
 * 只改了标题的几档：官方模板的标题偏紧、偏重，正文保持 Material 默认（中文正文再压字距会难读）。
 */
val SlaiTypography: Typography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.2.sp)
    )
}
