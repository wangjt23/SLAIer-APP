package com.slai.campus.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slai.campus.R
import com.slai.campus.ui.theme.BrandPalette
import com.slai.campus.ui.theme.LocalDarkTheme

/**
 * 学院 logo。
 *
 * 官方 logo 是**黑字**（`theme/slai_logo_black.png`），直接在深色底上会看不见，
 * 所以打包了两份：`slai_logo_on_light` 用原图，`slai_logo_on_dark` 把文字部分改成白色、
 * 保留洋红色的环形标志。两份都是从同一张原图生成的，不是重画的。
 */
@Composable
fun SlaiLogo(
    modifier: Modifier = Modifier,
    /**
     * 背景是不是深色。
     *
     * `null` = 跟随系统主题。注意别把这个参数理解成"用哪一份图" —— 图名说的是**它适合放在什么底色上**：
     * `slai_logo_on_dark` 是白字（放深色底），`slai_logo_on_light` 是黑字（放浅色底）。
     * 之前这里写反了，深色蒙版上压了一份黑字 logo，几乎看不见。
     */
    onDarkBackground: Boolean? = null
) {
    // 用应用当前的主题，而不是系统主题：设置里可以强制浅色/深色，
    // 系统是深色但应用被强制成浅色时，读系统值会挑到白字 logo，压在浅色背景上等于隐形。
    val useWhiteText = onDarkBackground ?: LocalDarkTheme.current
    Image(
        painter = painterResource(
            if (useWhiteText) R.drawable.slai_logo_on_dark else R.drawable.slai_logo_on_light
        ),
        contentDescription = stringResource(R.string.hero_logo_cd),
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}

/**
 * 首页顶部的主视觉：学院实景照片 + 品牌色渐变蒙版 + logo。
 *
 * 照片是 `theme/slai_campus_pic.jpg`（原文 6676×4461），这里缩到 1600 宽再打进包里 ——
 * 手机上一个头部卡片用不到 6676px，原图会让 APK 白胖 2 MB。
 *
 * 蒙版用主色 `#881E55` 到透明的竖向渐变，而不是纯黑：既能压住照片保证文字可读，
 * 又让整张卡片落在品牌色系里。
 */
@Composable
fun CampusHero(
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(184.dp)
            .clip(RoundedCornerShape(20.dp))
    ) {
        Image(
            painter = painterResource(R.drawable.slai_campus),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            /*
             * 取偏上的部分。原图中间那条横带正好是建筑自己的招牌（"深圳河套学院 /
             * Shenzhen Loop Area Institute"），默认居中裁切会让它恰好落在我们 logo 的位置，
             * 两个 logo 叠在一起。往上偏一点，画面变成干净的幕墙格栅，纯粹当纹理用。
             */
            alignment = Alignment.TopCenter,
            modifier = Modifier.matchParentSize()
        )

        /*
         * 蒙版做成"上透下实"：上半张留给照片本身（建筑夜景），下半张压成接近实色的品牌面板，
         * 文字压在上面。原来是均匀的半透明，结果照片里建筑自己的招牌和我们的 logo 叠在一起，
         * 同一张卡片上出现了三次学院名字。
         */
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.34f to BrandPalette.Magenta900.copy(alpha = 0.40f),
                        0.58f to BrandPalette.Magenta900.copy(alpha = 0.92f),
                        0.78f to BrandPalette.Magenta900.copy(alpha = 0.97f),
                        1.00f to Color(0xFF3A0E24).copy(alpha = 0.99f)
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SlaiLogo(
                modifier = Modifier.height(30.dp),
                // 蒙版永远是深色的，与系统主题无关，所以这里必须固定用白字那份。
                onDarkBackground = true
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.88f)
            )
        }
    }
}
