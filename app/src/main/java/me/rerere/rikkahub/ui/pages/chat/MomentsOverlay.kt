package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
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
import androidx.compose.runtime.remember
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
 */
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

    val timeline by remember(assistantId) {
        vm.observeTimeline(assistantId)
    }.collectAsStateWithLifecycle(emptyList())
    var composerVisible by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Moment?>(null) }
    val userName = settings.displaySetting.userNickname
        .ifBlank { stringResource(R.string.user_default_name) }
    val notes = remember(timeline) {
        timeline.map { it.moment }.sortedByDescending { it.createdAt }
    }
    val groups = remember(notes) { notes.groupBy { dayLabel(it.createdAt) } }

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
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                NotesTopBar(onClose = onDismiss, onWrite = { composerVisible = true })
                if (notes.isEmpty()) {
                    EmptyNotes(modifier = Modifier.fillMaxSize())
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        groups.forEach { (label, dayNotes) ->
                            item(key = "day-$label") {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                                )
                            }
                            items(items = dayNotes, key = { it.id.toString() }) { note ->
                                val mine = note.author == MomentAuthor.USER
                                NoteCard(
                                    moment = note,
                                    authorName = if (mine) userName else assistantName,
                                    mine = mine,
                                    onDelete = { pendingDelete = note },
                                )
                            }
                        }
                        item { Spacer(modifier = Modifier.height(32.dp)) }
                    }
                }
            }
        }
    }

    if (composerVisible) {
        NoteComposerDialog(
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

    pendingDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删掉这条随记？") },
            text = { Text("删掉就找不回来了。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteUserNote(assistantId, note.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun NotesTopBar(onClose: () -> Unit, onWrite: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(HugeIcons.Cancel01, contentDescription = "关闭")
        }
        Text(
            text = "随记",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onWrite) {
            Icon(HugeIcons.Add01, contentDescription = "写随记")
        }
    }
}

@Composable
private fun EmptyNotes(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_notepad),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(52.dp),
        )
        Text(
            text = "还没有随记",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "点右上角的＋，写下第一条。你们俩都能写。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 一条随记。她的和助手的用两种柔和的底色区分；点一下展开/收起长文，只有自己写的能删。 */
@Composable
private fun NoteCard(
    moment: Moment,
    authorName: String,
    mine: Boolean,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val container = if (mine) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = authorName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = timeLabel(moment.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (mine) {
                    Text(
                        text = "删除",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onDelete)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (moment.content.isNotBlank()) {
                Text(
                    text = moment.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            NoteImages(moment.imageUris)
        }
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
    onDismiss: () -> Unit,
    onSubmit: (String, List<String>) -> Unit,
    saveImages: (List<Uri>) -> List<String>,
) {
    var text by remember { mutableStateOf("") }
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
        title = { Text("写随记") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                    placeholder = { Text("想到什么写什么…") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
                if (imageUris.isNotEmpty()) {
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
                TextButton(
                    onClick = { imagePicker.launch("image/*") },
                    enabled = imageUris.size < MomentRepository.MAX_IMAGES,
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("添加图片 ${imageUris.size}/${MomentRepository.MAX_IMAGES}")
                }
            }
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank() || imageUris.isNotEmpty(),
                onClick = { onSubmit(text, imageUris) }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun dayLabel(timestamp: Long): String =
    SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date(timestamp))

private fun timeLabel(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timestamp))
