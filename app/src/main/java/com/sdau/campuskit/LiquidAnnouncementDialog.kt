package com.sdau.campuskit

import android.content.Context
import android.graphics.Bitmap
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle

/** Startup announcement dialog backed by the same sampled glass used for updates. */
internal class LiquidAnnouncementDialogView(
    context: Context,
    private var pageSnapshot: Bitmap?,
    announcement: String,
    imageUrls: List<String> = emptyList(),
    preloadedImages: List<Bitmap?> = emptyList(),
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onOpenUrl: (String) -> Unit
) : FrameLayout(context) {
    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isClickable = true
        addView(
            composeHostView(context) {
                LiquidAnnouncementDialog(
                    pageSnapshot = pageSnapshot,
                    announcement = announcement,
                    imageUrls = imageUrls,
                    preloadedImages = preloadedImages,
                    onCancel = onCancel,
                    onConfirm = onConfirm,
                    onOpenUrl = onOpenUrl
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    fun releaseSnapshot() {
        releaseDialogSnapshot(pageSnapshot) { pageSnapshot = null }
    }
}

@Composable
private fun LiquidAnnouncementDialog(
    pageSnapshot: Bitmap?,
    announcement: String,
    imageUrls: List<String>,
    preloadedImages: List<Bitmap?>,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val themeColors = CampusComposeTheme.colors
    val contentColor = themeColors.primaryText
    val secondaryColor = contentColor.copy(alpha = 0.68f)
    val snapshotImage = remember(pageSnapshot) { pageSnapshot?.asImageBitmap() }
    val links = remember(announcement) { extractAnnouncementLinks(announcement) }
    val announcementContent = remember(announcement) { removeAnnouncementLinks(announcement) }
    val backdrop = rememberLayerBackdrop()
    // 公告配图：弹窗打开前已在后台预加载，多张图横向滑动切换，点击用浏览器打开大图。
    val announcementImages = remember(preloadedImages, imageUrls) {
        imageUrls.indices.mapNotNull { index -> preloadedImages.getOrNull(index) }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            if (snapshotImage != null) {
                Image(
                    bitmap = snapshotImage,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
            } else {
                Box(Modifier.fillMaxSize().background(themeColors.pageBackground))
            }
            Box(Modifier.fillMaxSize().background(themeColors.dialogScrim))
        }
        // Consume outside taps so the explicit Cancel/Confirm behavior remains predictable.
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) {})
        Column(
            Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedRectangle(48.dp) },
                    effects = {
                        colorControls(
                            brightness = if (themeColors.isDark) 0f else 0.08f,
                            saturation = if (themeColors.isDark) 0.54f else 1.35f
                        )
                        blur((if (themeColors.isDark) 8.dp else 12.dp).toPx())
                        lens(24.dp.toPx(), 48.dp.toPx(), depthEffect = true)
                    },
                    highlight = {
                        Highlight.Plain.copy(alpha = if (themeColors.isDark) 0.12f else 1f)
                    },
                    onDrawSurface = { drawRect(themeColors.glassSurface) }
                )
                .clickable(interactionSource = null, indication = null) {}
        ) {
            BasicText(
                text = "公告",
                modifier = Modifier.padding(28.dp, 24.dp, 28.dp, 10.dp),
                style = TextStyle(contentColor, 24.sp, FontWeight.Medium)
            )
            Column(
                Modifier
                    .padding(horizontal = 28.dp)
                    .heightIn(min = 72.dp, max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (announcementContent.isNotBlank()) {
                    BasicText(
                        text = announcementContent,
                        style = TextStyle(contentColor.copy(alpha = 0.82f), 15.sp, lineHeight = 23.sp)
                    )
                }
                if (announcementImages.isNotEmpty()) {
                    Column(Modifier.padding(top = 14.dp)) {
                        // 横向翻页切换（HorizontalPager 自带跟手手势与吸附动画）。
                        val pagerState = rememberPagerState { announcementImages.size }
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .pointerInput(Unit) {
                                    // 横向锁定的翻页手势：一旦判定横向（|dx|>|dy| 且超过
                                    // touchSlop），后续每一帧都消费事件，外层纵向滚动
                                    // 完全收不到，避免斜滑被误判成上下滚动。
                                    var horizontalLocked = false
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        horizontalLocked = false
                                        do {
                                            val event = awaitPointerEvent()
                                            val drag = event.changes.firstOrNull() ?: continue
                                            if (!horizontalLocked) {
                                                val dx = drag.position.x - down.position.x
                                                val dy = drag.position.y - down.position.y
                                                if (kotlin.math.abs(dx) > kotlin.math.abs(dy) &&
                                                    kotlin.math.abs(dx) > viewConfiguration.touchSlop
                                                ) {
                                                    horizontalLocked = true
                                                }
                                            }
                                            if (horizontalLocked) {
                                                event.changes.forEach { it.consume() }
                                            }
                                        } while (event.changes.any { it.pressed })
                                    }
                                }
                                .clip(RoundedRectangle(16.dp))
                        ) { page ->
                            val image = announcementImages[page]
                            val url = imageUrls.getOrNull(page)
                            Image(
                                bitmap = image.asImageBitmap(),
                                contentDescription = "公告图片 ${page + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable(
                                        interactionSource = null,
                                        indication = null,
                                        onClick = { url?.let(onOpenUrl) }
                                    )
                            )
                        }
                        if (announcementImages.size > 1) {
                            Row(
                                Modifier
                                    .padding(top = 8.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                repeat(announcementImages.size) { page ->
                                    val selected = page == pagerState.currentPage
                                    Box(
                                        Modifier
                                            .padding(horizontal = 4.dp)
                                            .size(if (selected) 8.dp else 6.dp)
                                            .clip(RoundedRectangle(8.dp))
                                            .background(
                                                if (selected) themeColors.accent
                                                else contentColor.copy(alpha = 0.25f)
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
                if (links.isNotEmpty()) {
                    BasicText(
                        text = "相关链接",
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                        style = TextStyle(secondaryColor, 13.sp, FontWeight.SemiBold)
                    )
                    links.forEach { url ->
                        BasicText(
                            text = url,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = null,
                                    indication = null,
                                    onClick = { onOpenUrl(url) }
                                )
                                .padding(vertical = 5.dp),
                            style = TextStyle(
                                color = themeColors.accent,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        )
                    }
                }
                // 取消/确认放在滚动内容最底部：长公告（多图/长文）时按钮随内容滚动，
                // 不再固定悬浮在弹窗底部；底部留足距离不贴边。
                Row(
                    Modifier
                        .padding(0.dp, 24.dp, 0.dp, 20.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QuietDialogAction(
                        label = "取消",
                        foreground = contentColor,
                        enabled = true,
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    )
                    CampusLiquidButton(
                        onClick = onConfirm,
                        backdrop = backdrop,
                        style = LiquidButtonStyle.TINTED,
                        enabled = true,
                        allowDragDeformation = false,
                        deformationHorizontalPadding = 0.dp,
                        deformationVerticalPadding = 0.dp,
                        modifier = Modifier.weight(1f),
                        height = 48.dp
                    ) {
                        BasicText("确认", style = TextStyle(Color.White, 16.sp))
                    }
                }
            }
        }
    }
}

private val announcementLinkPattern = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)

private fun extractAnnouncementLinks(text: String): List<String> =
    announcementLinkPattern
        .findAll(text)
        .map { it.value.trimEnd('.', ',', ';', ':', '，', '。', '；', '：', '！', '？', ')', ']', '}') }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()

private fun removeAnnouncementLinks(text: String): String =
    text.lines()
        .map { line ->
            announcementLinkPattern.replace(line, "")
                .trimEnd()
                .replace(Regex("^[\\s•·*+-]+$"), "")
        }
        .joinToString("\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
