package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.Moment
import me.rerere.rikkahub.data.repository.MomentAuthor
import me.rerere.rikkahub.data.repository.MomentRepository
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * 随记：用户和助手共用的小本子。两个人都能写、都能看，只能删自己写的。
 * 没有点赞、评论、封面这些社交的东西，也没有 AI 自动回复。
 * （数据还是存在原来的 moments 表里，所以以前发的朋友圈会作为旧随记显示。）
 * 样子：米白纸纹底、手写感的字、每张卡片角上轮流贴小草/小猫/花朵。
 * 字体可以在页面右上角的「Aa」里切换：家里的字体（霞鹜文楷）/ 系统字体。
 */
private val PaperBg = Color(0xFFF6F3EB)
private val PaperCard = Color(0xFFFCFAF5)
private val PaperText = Color(0xFF4A453D)
private val PaperDim = Color(0xFF9A948A)
private val PaperDot = Color(0xFFA5AE95)

private const val NOTES_PREFS = "notes_ui"
private const val KEY_HOME_FONT = "home_font"

private fun readHomeFont(context: Context): Boolean =
    runCatching { context.getSharedPreferences(NOTES_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HOME_FONT, true) }
        .getOrDefault(true)

private fun saveHomeFont(context: Context, home: Boolean) {
    runCatching {
        context.getSharedPreferences(NOTES_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HOME_FONT, home).apply()
    }
}

/** 小草、小猫、花朵 轮流贴在卡片右上角：图、宽、高（dp） */
private data class Deco(val res: Int, val w: Int, val h: Int)

private val DECOS = listOf(
    Deco(R.drawable.deco_grass, 42, 32),
    Deco(R.drawable.deco_cat, 30, 25),
    Deco(R.drawable.deco_flower, 28, 26),
)

@Composable
fun MomentsOverlay(
    visible: Boolean,
    assistantId: Uuid,
    assistant: Assistant,
    conversation: Conversation,
    assistantName: String,
    conversationSystemPrompt: String?,
    settings: Settings,
    vm: MomentsVM,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val timeline by remember(assistantId) {
        vm.observeTimeline(assistantId)
    }.collectAsStateWithLifecycle(emptyList())
    var composerVisible by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Moment?>(null) }
    var pendingEdit by remember { mutableStateOf<Moment?>(null) }
    var homeFont by remember { mutableStateOf(readHomeFont(context)) }
    val noteFont = remember(homeFont) {
        if (homeFont) FontFamily(Font(R.font.lxgw_wenkai)) else FontFamily.Default
    }
    val userName = settings.displaySetting.userNickname
        .ifBlank { stringResource(R.string.user_default_name) }
    val notes = remember(timeline) {
        timeline.map { it.moment }.sortedByDescending { it.createdAt }
    }
    val groups = remember(notes) { notes.groupBy { dayLabel(it.createdAt) } }
    val paperTile = ImageBitmap.imageResource(R.drawable.paper_tile)

    // 打开着的时候新来的也算看过了
    LaunchedEffect(assistantId, notes.size) {
        vm.markViewed(assistantId)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = PaperBg,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(
                            brush = ShaderBrush(
                                ImageShader(paperTile, TileMode.Repeated, TileMode.Repeated)
                            )
                        )
                    }
                    .safeDrawingPadding()
            ) {
                NotesTopBar(
                    noteFont = noteFont,
                    homeFont = homeFont,
                    onPickFont = { home ->
                        homeFont = home
                        saveHomeFont(context, home)
                    },
                    onClose = onDismiss,
                    onWrite = { composerVisible = true },
                )
                if (notes.isEmpty()) {
                    EmptyNotes(noteFont = noteFont, modifier = Modifier.fillMaxSize())
                } else {
                    // 装饰按屏幕上从上到下的顺序轮流贴：小草、小猫、花朵、小草……
                    val order = remember(notes) { notes.mapIndexed { i, n -> n.id to i }.toMap() }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        groups.forEach { (label, dayNotes) ->
                            item(key = "day-$label") {
                                DayHeader(label = label, noteFont = noteFont)
                            }
                            items(items = dayNotes, key = { it.id.toString() }) { note ->
                                val mine = note.author == MomentAuthor.USER
                                NoteCard(
                                    moment = note,
                                    authorName = if (mine) userName else assistantName,
                                    mine = mine,
                                    deco = DECOS[(order[note.id] ?: 0) % DECOS.size],
                                    noteFont = noteFont,
                                    onEdit = { pendingEdit = note },
                                    onDelete = { pendingDelete = note },
                                canDelete = true,
                                )
                            }
                        }
                        item {
                            Text(
                                text = "长按随记：她可以编辑或删除任何一条，Flow 只能删自己写的",
                                style = noteStyle(noteFont, 13, PaperDim),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp, bottom = 32.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (composerVisible) {
        NoteComposerDialog(
            noteFont = noteFont,
            onDismiss = { composerVisible = false },
            onSubmit = { content, imageUris ->
                vm.postUserMoment(assistantId, content, imageUris)
                composerVisible = false
            },
            saveImages = { uris ->
                vm.filesManager.createChatFilesByContents(uris)
                    .take(MomentRepository.MAX_IMAGES)
                    .map { it.toString() }
            }
        )
    }

    pendingEdit?.let { note ->
        NoteComposerDialog(
            noteFont = noteFont,
            title = "编辑随记",
            initialText = note.content,
            allowImages = false,
            onDismiss = { pendingEdit = null },
            onSubmit = { content, _ ->
                vm.editUserNote(note.id, content)
                pendingEdit = null
            },
            saveImages = { emptyList() },
        )
    }

    pendingDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = PaperCard,
            title = { Text("删掉这条随记？", style = noteStyle(noteFont, 20)) },
            text = { Text("删掉就找不回来了。", style = noteStyle(noteFont, 16)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAnyNote(assistantId, note.id)
                    pendingDelete = null
                }) { Text("删除", style = noteStyle(noteFont, 16)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消", style = noteStyle(noteFont, 16)) }
            },
        )
    }
}

private fun noteStyle(font: FontFamily, size: Int, color: Color = PaperText): TextStyle =
    TextStyle(fontFamily = font, fontSize = size.sp, lineHeight = (size * 1.6f).sp, color = color)

@Composable
private fun NotesTopBar(
    noteFont: FontFamily,
    homeFont: Boolean,
    onPickFont: (Boolean) -> Unit,
    onClose: () -> Unit,
    onWrite: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(HugeIcons.Cancel01, contentDescription = "关闭", tint = PaperText)
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "随记", style = noteStyle(noteFont, 32), fontWeight = FontWeight.Medium)
            Image(
                painter = painterResource(R.drawable.deco_swash),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .width(104.dp)
                    .height(14.dp),
            )
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Text(
                    text = "Aa",
                    style = noteStyle(noteFont, 17, PaperDim),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { menuOpen = true }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text((if (homeFont) "✓ " else "    ") + "家里的字体（霞鹜文楷）") },
                        onClick = { onPickFont(true); menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text((if (!homeFont) "✓ " else "    ") + "系统字体") },
                        onClick = { onPickFont(false); menuOpen = false },
                    )
                }
            }
            IconButton(onClick = onWrite) {
                Icon(HugeIcons.Add01, contentDescription = "写随记", tint = PaperText)
            }
        }
    }
}

@Composable
private fun DayHeader(label: String, noteFont: FontFamily) {
    Column(
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    ) {
        Text(text = label, style = noteStyle(noteFont, 21))
    }
}

@Composable
private fun EmptyNotes(noteFont: FontFamily, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Image(
            painter = painterResource(R.drawable.deco_cat),
            contentDescription = null,
            modifier = Modifier.size(width = 96.dp, height = 80.dp),
        )
        Text(text = "还没有随记", style = noteStyle(noteFont, 20))
        Text(
            text = "点右上角的＋，写下第一条。你们俩都能写。",
            style = noteStyle(noteFont, 15, PaperDim),
            textAlign = TextAlign.Center,
        )
    }
}

/** 一条随记：米白纸片卡、名字前一个小圆点、斜体时间；右上角贴一个小装饰。点一下展开/收起长文，只有自己写的能删。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    moment: Moment,
    authorName: String,
    mine: Boolean,
    deco: Deco,
    noteFont: FontFamily,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 3.dp,
                shape = shape,
                ambientColor = Color(0x33000000),
                spotColor = Color(0x33000000),
            )
            .clip(shape)
            .background(PaperCard)
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = if (mine || canDelete) ({ menuOpen = true }) else null,
            ),
    ) {
        Column(
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.padding(end = 42.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(PaperDot, CircleShape)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = authorName,
                    style = noteStyle(noteFont, 17),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = timeLabel(moment.createdAt),
                    style = noteStyle(noteFont, 14, PaperDim),
                    fontStyle = FontStyle.Italic,
                )
            }
            if (moment.content.isNotBlank()) {
                Text(
                    text = moment.content,
                    style = noteStyle(noteFont, 17),
                    maxLines = if (expanded) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            NoteImages(moment.imageUris)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (mine) {
                DropdownMenuItem(
                    text = { Text("编辑", style = noteStyle(noteFont, 16)) },
                    onClick = { menuOpen = false; onEdit() },
                )
            }
            if (mine || canDelete) {
                DropdownMenuItem(
                    text = { Text("删除", style = noteStyle(noteFont, 16)) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
        Image(
            painter = painterResource(deco.res),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 14.dp, top = 8.dp)
                .size(width = deco.w.dp, height = deco.h.dp),
        )
    }
}

@Composable
private fun NoteImages(imageUris: List<String>) {
    if (imageUris.isEmpty()) return
    val visibleUris = imageUris.take(MomentRepository.MAX_IMAGES)
    var showImagePreview by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    if (visibleUris.size == 1) {
        AsyncImage(
            model = visibleUris.first(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .heightIn(min = 140.dp, max = 260.dp)
                .clip(shape)
                .border(3.dp, Color.White, shape)
                .clickable { showImagePreview = true },
            contentScale = ContentScale.Crop,
        )
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = 3,
            modifier = Modifier.fillMaxWidth()
        ) {
            visibleUris.forEach { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(shape)
                        .border(3.dp, Color.White, shape)
                        .clickable { showImagePreview = true },
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
    if (showImagePreview) {
        ImagePreviewDialog(images = visibleUris) {
            showImagePreview = false
        }
    }
}

@Composable
private fun NoteComposerDialog(
    noteFont: FontFamily,
    title: String = "写随记",
    initialText: String = "",
    allowImages: Boolean = true,
    onDismiss: () -> Unit,
    onSubmit: (String, List<String>) -> Unit,
    saveImages: (List<Uri>) -> List<String>,
) {
    var text by remember { mutableStateOf(initialText) }
    var imageUris by remember { mutableStateOf(emptyList<String>()) }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            imageUris = (imageUris + saveImages(uris))
                .distinct()
                .take(MomentRepository.MAX_IMAGES)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PaperCard,
        title = { Text(title, style = noteStyle(noteFont, 22)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                    textStyle = noteStyle(noteFont, 17),
                    placeholder = { Text("想到什么写什么…", style = noteStyle(noteFont, 17, PaperDim)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
                if (allowImages && imageUris.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        imageUris.forEach { uri ->
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
                if (allowImages) TextButton(
                    onClick = { imagePicker.launch("image/*") },
                    enabled = imageUris.size < MomentRepository.MAX_IMAGES,
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("添加图片 ${imageUris.size}/${MomentRepository.MAX_IMAGES}", style = noteStyle(noteFont, 15, PaperText))
                }
            }
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank() || (allowImages && imageUris.isNotEmpty()),
                onClick = { onSubmit(text, imageUris) }
            ) {
                Text("保存", style = noteStyle(noteFont, 16, Color.White))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", style = noteStyle(noteFont, 16))
            }
        }
    )
}

private fun dayLabel(timestamp: Long): String =
    SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date(timestamp))

private fun timeLabel(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timestamp))
