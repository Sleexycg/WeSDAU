package com.sdau.campuskit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Color as AndroidColor
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** 自定义备注最多 12 个字。 */
private const val CUSTOM_NOTE_MAX_LENGTH = 12

/**
 * 按显示宽度截断：一个汉字（含全角字符）计 1，字母/数字等半角字符计 0.5，
 * 总宽超过 [maxHan] 时丢弃多余部分——超出字符直接输不进去。
 */
private fun limitByHanWidth(input: String, maxHan: Int): String {
    var width = 0f
    input.forEachIndexed { index, ch ->
        val w = if (ch.code > 0x2E7F) 1f else 0.5f
        if (width + w > maxHan) return input.substring(0, index)
        width += w
    }
    return input
}

/** Course details and editing form. */
internal class LiquidCourseDialogView(
    context: Context,
    private var pageSnapshot: Bitmap?,
    courseName: String,
    room: String,
    teacher: String,
    slotText: String,
    scheduleTitle: String = slotText,
    weeks: String,
    canEdit: Boolean,
    creating: Boolean = false,
    initialSlotCount: Int = 1,
    maxSlotCount: Int = 1,
    allowDurationEdit: Boolean = false,
    bagNote: String = "",
    examNote: String = "",
    customNote: String = "",
    canEditNotes: Boolean = false,
    onSave: (
        name: String,
        room: String,
        teacher: String,
        weeks: String,
        slotCount: Int
    ) -> Unit,
    onSaveNotes: (bag: String, exam: String, custom: String) -> Unit = { _, _, _ -> },
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    /** 保存备注后由宿主收起输入法。 */
    onKeyboardClose: () -> Unit = {}
) : FrameLayout(context) {
    private val hostImeVisible = mutableStateOf(false)
    // 输入法实时高度（px）：直接用系统分发的 inset 驱动卡片位移，键盘下落时卡片能同步跟随。
    private val hostImeHeightPx = mutableStateOf(0f)
    private val visibleWindowFrame = Rect()
    private val keyboardLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        updateImeVisibilityFromWindow()
    }

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isClickable = true
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val navigationInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime()) ||
                imeInsets.bottom > navigationInsets.bottom
            if (hostImeVisible.value != visible) hostImeVisible.value = visible
            val height = if (visible) imeInsets.bottom.toFloat() else 0f
            if (hostImeHeightPx.value != height) hostImeHeightPx.value = height
            insets
        }
        addView(
            composeHostView(context) {
                LiquidCourseDialog(
                    pageSnapshot = pageSnapshot,
                    initialCourseName = courseName,
                    initialRoom = room,
                    initialTeacher = teacher,
                    slotText = slotText,
                    scheduleTitle = scheduleTitle,
                    initialWeeks = weeks,
                    canEdit = canEdit,
                    creating = creating,
                    initialSlotCount = initialSlotCount,
                    maxSlotCount = maxSlotCount,
                    allowDurationEdit = allowDurationEdit,
                    bagNote = bagNote,
                    examNote = examNote,
                    customNote = customNote,
                    canEditNotes = canEditNotes,
                    hostImeVisible = hostImeVisible.value,
                    hostImeHeightPx = hostImeHeightPx.value,
                    onSave = onSave,
                    onSaveNotes = onSaveNotes,
                    onDelete = onDelete,
                    onDismiss = onDismiss,
                    onKeyboardClose = onKeyboardClose
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(keyboardLayoutListener)
        ViewCompat.requestApplyInsets(this)
        post(::updateImeVisibilityFromWindow)
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnGlobalLayoutListener(keyboardLayoutListener)
        }
        super.onDetachedFromWindow()
    }

    private fun updateImeVisibilityFromWindow() {
        if (!isAttachedToWindow) return
        getWindowVisibleDisplayFrame(visibleWindowFrame)
        val obscuredHeight = (rootView.height - visibleWindowFrame.bottom).coerceAtLeast(0)
        val threshold = (96f * resources.displayMetrics.density).roundToInt()
        val insets = ViewCompat.getRootWindowInsets(this)
        val visibleFromInsets = insets?.let {
            it.isVisible(WindowInsetsCompat.Type.ime()) ||
                it.getInsets(WindowInsetsCompat.Type.ime()).bottom >
                it.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        } == true
        val visible = visibleFromInsets || obscuredHeight > threshold
        if (hostImeVisible.value != visible) hostImeVisible.value = visible
        if (!visible && hostImeHeightPx.value != 0f) hostImeHeightPx.value = 0f
    }

    fun releaseSnapshot() {
        releaseDialogSnapshot(pageSnapshot) { pageSnapshot = null }
    }
}

private enum class CourseDialogIcon { EDIT, SAVE, DELETE }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiquidCourseDialog(
    pageSnapshot: Bitmap?,
    initialCourseName: String,
    initialRoom: String,
    initialTeacher: String,
    slotText: String,
    scheduleTitle: String,
    initialWeeks: String,
    canEdit: Boolean,
    creating: Boolean,
    initialSlotCount: Int,
    maxSlotCount: Int,
    allowDurationEdit: Boolean,
    bagNote: String,
    examNote: String,
    customNote: String,
    canEditNotes: Boolean,
    hostImeVisible: Boolean,
    hostImeHeightPx: Float,
    onSave: (
        name: String,
        room: String,
        teacher: String,
        weeks: String,
        slotCount: Int
    ) -> Unit,
    onSaveNotes: (bag: String, exam: String, custom: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    onKeyboardClose: () -> Unit
) {
    val themeColors = CampusComposeTheme.colors
    val contentColor = themeColors.primaryText
    val secondaryColor = contentColor.copy(alpha = 0.66f)
    val accentColor = themeColors.accent
    val containerColor = if (themeColors.isDark) {
        themeColors.glassStrongSurface
    } else {
        themeColors.glassSurface
    }
    val dimColor = themeColors.dialogScrim
    val snapshotImage = remember(pageSnapshot) { pageSnapshot?.asImageBitmap() }
    val backdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    var editing by remember(creating) { mutableStateOf(creating) }
    var courseName by remember(initialCourseName) { mutableStateOf(initialCourseName) }
    var room by remember(initialRoom) { mutableStateOf(initialRoom) }
    var teacher by remember(initialTeacher) { mutableStateOf(initialTeacher) }
    var weeks by remember(initialWeeks) { mutableStateOf(initialWeeks) }
    var slotCount by remember(initialSlotCount) { mutableStateOf(initialSlotCount.toString()) }
    // 备注编辑状态：详情页右下角入口进入编辑后逐项修改。
    var editingNotes by remember { mutableStateOf(false) }
    var bagNote by remember(bagNote) { mutableStateOf(bagNote) }
    var examNote by remember(examNote) { mutableStateOf(examNote) }
    var customNote by remember(customNote) { mutableStateOf(customNote) }
    val hasNotes = bagNote.isNotBlank() || examNote.isNotBlank() || customNote.isNotBlank()
    // 备注窗口标题用进入时的状态，避免编辑过程中清空导致标题来回跳。
    val initialHasNotes = remember {
        bagNote.isNotBlank() || examNote.isNotBlank() || customNote.isNotBlank()
    }
    val availableSlotCount = maxSlotCount.coerceAtLeast(1)
    val imeVisible = hostImeVisible || WindowInsets.isImeVisible
    var keyboardRaised by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        keyboardRaised = imeVisible
    }
    // 位置跟随：直接用系统 imePadding，窗口随输入法的出现/收起动画逐帧同步
    // 移动（同一套系统动画时序），不会出现弹簧滞后导致的"先卡一下再落下"。

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
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
            Box(Modifier.fillMaxSize().background(dimColor))
        }
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = null, indication = null) {
                    // 修改课程 / 修改备注时禁止点空白退出，只能通过右上角按钮；
                    // 浏览详情和新增课程仍可点空白关闭。
                    if (!editingNotes && (creating || !editing)) onDismiss()
                }
        )
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // 课程详情卡片：备注表单在同一张卡片内切换（与修改课程一致的动画），
            // 卡片尺寸变化由 animateContentSize 平滑过渡。
            Box(
                Modifier
                    .padding(horizontal = 28.dp)
                    .fillMaxWidth()
                    .widthIn(max = 372.dp)
                    .imePadding()
                    .clip(RoundedRectangle(28.dp))
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedRectangle(28.dp) },
                        effects = {
                            vibrancy()
                            colorControls(
                                brightness = if (themeColors.isDark) 0f else 0.14f,
                                saturation = if (themeColors.isDark) 0.54f else 0.80f
                            )
                            blur((if (themeColors.isDark) 8.dp else 18.dp).toPx())
                            lens(12.dp.toPx(), 24.dp.toPx(), depthEffect = true)
                        },
                        shadow = null,
                        highlight = {
                            Highlight.Default.copy(alpha = if (themeColors.isDark) 0.12f else 0.58f)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .clickable(interactionSource = null, indication = null, onClick = {})
                    .animateContentSize()
            ) {
            Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 20.dp, end = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    BasicText(
                        when {
                            // editingNotes 必须先于 editing 判断：备注窗口常从修改课程页进入，
                            // 此时 editing 仍为 true。
                            editingNotes -> if (initialHasNotes) "修改备注 · $scheduleTitle" else "设置备注 · $scheduleTitle"
                            creating -> "添加课程 · $scheduleTitle"
                            editing -> "修改课程 · $scheduleTitle"
                            else -> "课程详情"
                        },
                        style = TextStyle(secondaryColor, 12.sp, FontWeight.Medium)
                    )
                    BasicText(
                        courseName.ifBlank { if (creating) "新课程" else "未命名课程" },
                        modifier = Modifier.padding(top = 4.dp),
                        style = TextStyle(contentColor, 20.sp, FontWeight.SemiBold)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (editingNotes) {
                        // 清空备注：一键清空三个输入并保存。
                        CourseLiquidIconButton(
                            backdrop = backdrop,
                            icon = CourseDialogIcon.DELETE,
                            contentDescription = "清空备注",
                            onClick = {
                                bagNote = ""
                                examNote = ""
                                customNote = ""
                                onSaveNotes("", "", "")
                                keyboardRaised = false
                                onKeyboardClose()
                                // 保持 editingNotes 不变：弹窗整体关闭，
                                // 避免关闭动画期间闪现修改课程页。
                            }
                        )
                        CourseLiquidIconButton(
                            backdrop = backdrop,
                            icon = CourseDialogIcon.SAVE,
                            contentDescription = if (initialHasNotes) "保存备注" else "添加备注",
                            onClick = {
                                onSaveNotes(
                                    bagNote.trim(),
                                    examNote.trim(),
                                    customNote.trim()
                                )
                                keyboardRaised = false
                                onKeyboardClose()
                                // 同上：保持备注画面直到弹窗整体关闭。
                            }
                        )
                    }
                    if (!editing && !editingNotes && canEdit) {
                        CourseLiquidIconButton(
                            backdrop = backdrop,
                            icon = CourseDialogIcon.EDIT,
                            contentDescription = "修改课程",
                            onClick = { editing = true }
                        )
                        if (onDelete != null) {
                            CourseLiquidIconButton(
                                backdrop = backdrop,
                                icon = CourseDialogIcon.DELETE,
                                contentDescription = "删除课程",
                                onClick = onDelete
                            )
                        }
                    }
                    // 备注窗口从修改课程页进入时 editing 仍为 true，
                    // 需抑制修改课程的保存键，避免出现两个确认。
                    if (editing && !editingNotes) {
                        CourseLiquidIconButton(
                            backdrop = backdrop,
                            icon = CourseDialogIcon.SAVE,
                            contentDescription = if (creating) "添加课程" else "保存修改",
                            onClick = {
                                onSave(
                                    courseName,
                                    room,
                                    teacher,
                                    weeks,
                                    slotCount.toIntOrNull()
                                        ?.coerceIn(1, availableSlotCount)
                                        ?: 1
                                )
                            }
                        )
                    }
                }
            }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 22.dp)
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                if (editingNotes) {
                    // 备注表单：与修改课程共用同一张卡片、同一个 animateContentSize 动画，
                    // 保存由头部右上角的对号按钮完成。
                    CourseNotesEditor(
                        bagNote = bagNote,
                        examNote = examNote,
                        customNote = customNote,
                        keyboardRaised = keyboardRaised,
                        onBagChange = { bagNote = it },
                        onExamChange = { examNote = it },
                        onCustomChange = { customNote = limitByHanWidth(it, CUSTOM_NOTE_MAX_LENGTH) }
                    )
                } else if (editing) {
                    CourseLiquidTextField(
                        label = "课程名",
                        value = courseName,
                        keyboardAlreadyVisible = keyboardRaised,
                        onValueChange = { courseName = it }
                    )
                    CourseLiquidTextField(
                        label = "地点",
                        value = room,
                        keyboardAlreadyVisible = keyboardRaised,
                        onValueChange = { room = it }
                    )
                    CourseLiquidTextField(
                        label = "教师",
                        value = teacher,
                        keyboardAlreadyVisible = keyboardRaised,
                        onValueChange = { teacher = it }
                    )
                    CourseLiquidTextField(
                        label = "周数（如1-16；1，2，3；7，8，9，11-16）",
                        value = weeks,
                        keyboardAlreadyVisible = keyboardRaised,
                        onValueChange = { weeks = it }
                    )
                    if (creating || allowDurationEdit) {
                        CourseLiquidTextField(
                            label = "持续节数（1-$availableSlotCount）",
                            value = slotCount,
                            keyboardAlreadyVisible = keyboardRaised,
                            onValueChange = { input ->
                                slotCount = input.filter(Char::isDigit).take(2)
                            }
                        )
                    }
                    if (canEditNotes) {
                        // 修改界面单独的备注入口：点击后弹出独立备注窗口。
                        CourseNotesEntryButton(
                            backdrop = backdrop,
                            hasNotes = bagNote.isNotBlank() || examNote.isNotBlank() || customNote.isNotBlank(),
                            onClick = { editingNotes = true }
                        )
                    }
                } else {
                    CourseDetailLine(
                        label = "地点",
                        value = "@${room.ifBlank { "-" }}",
                        iconRes = R.drawable.ic_detail_location,
                        contentColor = contentColor,
                        secondaryColor = secondaryColor
                    )
                    CourseDetailLine(
                        label = "教师",
                        value = teacher.ifBlank { "-" },
                        iconRes = R.drawable.ic_detail_teacher,
                        contentColor = contentColor,
                        secondaryColor = secondaryColor
                    )
                    CourseDetailLine(
                        label = "节次",
                        value = slotText,
                        iconRes = R.drawable.ic_detail_time,
                        contentColor = contentColor,
                        secondaryColor = secondaryColor
                    )
                    CourseDetailLine(
                        label = "周数",
                        value = weeks.ifBlank { "-" },
                        iconRes = R.drawable.ic_detail_week,
                        contentColor = contentColor,
                        secondaryColor = secondaryColor
                    )
                }
            }
            }

            // 备注覆盖在卡片右下角空白处：不占内容行、不影响卡片大小。
            if (!editing && !editingNotes && canEditNotes && hasNotes) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomEnd)
                        .padding(start = 24.dp, end = 20.dp, top = 30.dp, bottom = 18.dp)
                        // 有备注时，点击备注区域直接进入备注编辑界面。
                        .clickable(interactionSource = null, indication = null) {
                            editingNotes = true
                        }
                ) {
                    Column(
                        Modifier.align(Alignment.BottomEnd),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        if (bagNote.isNotBlank()) {
                            BasicText(
                                "手机袋：$bagNote",
                                style = TextStyle(secondaryColor, 11.sp, FontWeight.Medium)
                            )
                        }
                        if (examNote.isNotBlank()) {
                            BasicText(
                                "考试时间：$examNote",
                                style = TextStyle(secondaryColor, 11.sp, FontWeight.Medium)
                            )
                        }
                        if (customNote.isNotBlank()) {
                            BasicText(
                                customNote,
                                style = TextStyle(secondaryColor, 11.sp, FontWeight.Medium)
                            )
                        }
                    }
                }
            }
            // 卡片 Box 闭合。
            }
        }
    }
}

@Composable
private fun CourseNotesEntryButton(
    backdrop: com.kyant.backdrop.Backdrop,
    hasNotes: Boolean,
    onClick: () -> Unit
) {
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 1.02f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
        label = "courseNotesEntryScale"
    )
    val themeColors = CampusComposeTheme.colors
    val contentColor = themeColors.primaryText
    val shape = RoundedRectangle(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    colorControls(
                        brightness = if (themeColors.isDark) 0f else 0.14f,
                        saturation = if (themeColors.isDark) 0.54f else 0.84f
                    )
                    blur(8.dp.toPx())
                    lens(12.dp.toPx(), 24.dp.toPx())
                },
                highlight = {
                    Highlight.Default.copy(
                        alpha = interactiveHighlight.pressProgress *
                            if (themeColors.isDark) 0.18f else 0.68f
                    )
                },
                onDrawSurface = {
                    drawRect(
                        if (themeColors.isDark) themeColors.glassStrongSurface
                        else themeColors.glassSurface
                    )
                }
            )
            .border(
                1.dp,
                if (themeColors.isDark) Color.White.copy(alpha = 0.28f)
                else Color.White.copy(alpha = 0.82f),
                shape
            )
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .then(interactiveHighlight.modifier)
            .then(interactiveHighlight.gestureModifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            if (hasNotes) "修改备注" else "设置备注",
            modifier = Modifier.weight(1f),
            style = TextStyle(contentColor, 14.sp, FontWeight.Medium)
        )
        BasicText(
            if (hasNotes) "已设置" else "未设置",
            style = TextStyle(
                contentColor.copy(alpha = 0.55f), 12.sp, FontWeight.Medium
            )
        )
    }
}

@Composable
private fun CourseNotesEditor(
    bagNote: String,
    examNote: String,
    customNote: String,
    keyboardRaised: Boolean,
    onBagChange: (String) -> Unit,
    onExamChange: (String) -> Unit,
    onCustomChange: (String) -> Unit
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CourseLiquidTextField(
            label = "手机袋号码",
            value = bagNote,
            keyboardAlreadyVisible = keyboardRaised,
            onValueChange = onBagChange,
            fieldHeight = 68.dp
        )
        CourseLiquidTextField(
            label = "考试时间",
            value = examNote,
            keyboardAlreadyVisible = keyboardRaised,
            onValueChange = onExamChange,
            fieldHeight = 68.dp
        )
        CourseLiquidTextField(
            label = "自定义备注",
            value = customNote,
            keyboardAlreadyVisible = keyboardRaised,
            onValueChange = { input ->
                // 按显示宽度限制：最多 12 个汉字，字母/数字每 2 个算 1 个汉字，
                // 超出部分直接输不进去。
                onCustomChange(limitByHanWidth(input, CUSTOM_NOTE_MAX_LENGTH))
            },
            fieldHeight = 68.dp
        )
    }
}

@Composable
private fun CourseDetailLine(
    label: String,
    value: String,
    iconRes: Int,
    contentColor: Color,
    secondaryColor: Color
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(CampusComposeTheme.colors.accent)
        )
        BasicText(
            label,
            modifier = Modifier.padding(start = 10.dp).width(42.dp),
            style = TextStyle(secondaryColor, 12.sp, FontWeight.Bold)
        )
        BasicText(
            value,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
            style = TextStyle(contentColor, 15.sp, FontWeight.Medium)
        )
    }
}

@Composable
private fun CourseLiquidTextField(
    label: String,
    value: String,
    keyboardAlreadyVisible: Boolean,
    onValueChange: (String) -> Unit,
    fieldHeight: Dp = 58.dp
) {
    val themeColors = CampusComposeTheme.colors
    val contentColor = themeColors.primaryText
    val fieldShape = RoundedCornerShape(16.dp)
    // 备注输入框略高，输入区域更宽松；课程编辑沿用默认高度。
    val inputHeight = (fieldHeight - 30.dp).coerceAtLeast(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .height(fieldHeight)
            // The dialog shell has already sampled and blurred the page. Sampling the
            // root backdrop again here would reveal a clearer copy of the original
            // timetable inside every field, so fields only tint the blurred shell.
            .clip(fieldShape)
            .background(themeColors.glassSubtleSurface, fieldShape)
            .border(1.dp, themeColors.glassOutline, fieldShape)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        BasicText(label, style = TextStyle(contentColor.copy(alpha = 0.62f), 10.sp, FontWeight.Medium))
        AndroidView(
            factory = { context ->
                CourseEditText(context).apply {
                    setTextColor(AndroidColor.rgb(23, 25, 35))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setSingleLine(true)
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                    background = null
                    setPadding(0, 0, 0, 0)
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                }
            },
            update = { field ->
                field.setTextColor(
                    if (themeColors.isDark) AndroidColor.rgb(243, 245, 248)
                    else AndroidColor.rgb(23, 25, 35)
                )
                field.onCourseTextChanged = onValueChange
                field.updateCourseText(value)
                // When the IME is already on screen, changing fields must only move
                // the input connection. Requesting showSoftInput again makes several
                // OEM keyboards replay their complete entrance animation.
                field.showSoftInputOnFocus = !keyboardAlreadyVisible
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(inputHeight)
                .padding(top = 1.dp)
        )
    }
}

private class CourseEditText(context: Context) : EditText(context) {
    var onCourseTextChanged: (String) -> Unit = {}
    private var applyingExternalText = false

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!applyingExternalText) onCourseTextChanged(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    fun updateCourseText(value: String) {
        if (text?.toString() == value) return
        applyingExternalText = true
        setText(value)
        setSelection(value.length)
        applyingExternalText = false
    }
}

@Composable
private fun CourseLiquidIconButton(
    backdrop: com.kyant.backdrop.Backdrop,
    icon: CourseDialogIcon,
    contentDescription: String,
    onClick: () -> Unit
) {
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 1.07f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
        label = "courseDialogIconScale"
    )
    val themeColors = CampusComposeTheme.colors
    val accentColor = if (icon == CourseDialogIcon.DELETE) Color(0xFFF05252) else themeColors.accent
    Box(
        Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    colorControls(
                        brightness = if (themeColors.isDark) 0f else 0.14f,
                        saturation = if (themeColors.isDark) 0.54f else 0.84f
                    )
                    blur(8.dp.toPx())
                    lens(12.dp.toPx(), 24.dp.toPx())
                },
                highlight = {
                    Highlight.Default.copy(
                        alpha = interactiveHighlight.pressProgress *
                            if (themeColors.isDark) 0.18f else 0.68f
                    )
                },
                onDrawSurface = {
                    drawRect(
                        if (themeColors.isDark) themeColors.glassStrongSurface
                        else themeColors.glassSurface
                    )
                }
            )
            .then(
                if (themeColors.isDark) {
                    Modifier.border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.28f),
                        shape = CircleShape
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.82f),
                        shape = CircleShape
                    )
                }
            )
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .then(interactiveHighlight.modifier)
            .then(interactiveHighlight.gestureModifier)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        when (icon) {
            CourseDialogIcon.EDIT -> Image(
                painter = painterResource(R.drawable.ic_edit),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(accentColor)
            )
            CourseDialogIcon.SAVE -> Image(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(accentColor)
            )
            CourseDialogIcon.DELETE -> Canvas(Modifier.size(20.dp)) {
                val stroke = 2.dp.toPx()
                drawLine(accentColor, Offset(size.width * .25f, size.height * .28f), Offset(size.width * .75f, size.height * .28f), stroke, StrokeCap.Round)
                drawLine(accentColor, Offset(size.width * .40f, size.height * .18f), Offset(size.width * .60f, size.height * .18f), stroke, StrokeCap.Round)
                drawLine(accentColor, Offset(size.width * .31f, size.height * .38f), Offset(size.width * .36f, size.height * .82f), stroke, StrokeCap.Round)
                drawLine(accentColor, Offset(size.width * .69f, size.height * .38f), Offset(size.width * .64f, size.height * .82f), stroke, StrokeCap.Round)
                drawLine(accentColor, Offset(size.width * .36f, size.height * .82f), Offset(size.width * .64f, size.height * .82f), stroke, StrokeCap.Round)
                drawLine(accentColor, Offset(size.width * .44f, size.height * .43f), Offset(size.width * .44f, size.height * .70f), stroke * .8f, StrokeCap.Round)
                drawLine(accentColor, Offset(size.width * .56f, size.height * .43f), Offset(size.width * .56f, size.height * .70f), stroke * .8f, StrokeCap.Round)
            }
        }
    }
}
