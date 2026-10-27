package com.mamaika.songcatcher

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val APP_VERSION = "0.4"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current

    LaunchedEffect(vm.status) {
        if (vm.status != "Готов") Toast.makeText(ctx, vm.status, Toast.LENGTH_SHORT).show()
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) {
            vm.catchSong(Pair(res.resultCode, data))
        } else {
            vm.status = "Нужно разрешить запись звука телефона"
        }
    }

    fun startCapture() {
        if (vm.usePhoneAudio) {
            val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            vm.catchSong()
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCapture() else vm.status = "Нужен доступ к микрофону"
    }

    val onCatch = {
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startCapture() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    var screen by rememberSaveable { mutableStateOf("main") }
    BackHandler(enabled = screen != "main") { screen = "main" }

    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                when (screen) {
                    "settings" -> SettingsScreen(vm, onBack = { screen = "main" }, onHelp = { screen = "help" })
                    "help" -> HelpScreen(onBack = { screen = "main" })
                    else -> MainScreen(
                        vm,
                        onCatch = onCatch,
                        onSettings = { screen = "settings" },
                        onHelp = { screen = "help" }
                    )
                }
            }
        }
        PendingDialog(vm)
    }
}

// ============================ главный экран ============================

@Composable
fun MainScreen(vm: MainViewModel, onCatch: () -> Unit, onSettings: () -> Unit, onHelp: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var showManual by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Track?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("SongCatcher", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(6.dp))
            Text(APP_VERSION, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onHelp) { Text("?") }
            TextButton(onClick = onSettings) { Text("⚙") }
        }

        vm.report?.let { r ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        r,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 140.dp)
                            .verticalScroll(rememberScrollState())
                    )
                    Row {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(r)) }) { Text("Скопировать") }
                        TextButton(onClick = { vm.dismissReport() }) { Text("Закрыть") }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Button(
            onClick = onCatch,
            enabled = !vm.busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(if (vm.busy) "Работаю…" else "Распознать и скачать")
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SourceButton(!vm.usePhoneAudio, "Микрофон") { vm.updateUsePhoneAudio(false) }
            Spacer(Modifier.width(8.dp))
            SourceButton(vm.usePhoneAudio, "Звук телефона") { vm.updateUsePhoneAudio(true) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showManual = !showManual }) {
                Text(if (showManual) "Вручную ▴" else "Вручную ▾")
            }
        }

        if (showManual) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = vm.manualQuery,
                    onValueChange = { vm.manualQuery = it },
                    label = { Text("Название ролика или ссылка") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.downloadManual() },
                    enabled = !vm.busy && vm.manualQuery.isNotBlank()
                ) { Text("Найти") }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            vm.status,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (vm.busy) {
            Spacer(Modifier.height(4.dp))
            val p = vm.progress
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Треки (${vm.tracks.size})", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                showSearch = !showSearch
                if (!showSearch) vm.query = ""
            }) { Text(if (showSearch) "Закрыть поиск" else "Поиск") }
        }
        if (showSearch) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                label = { Text("Поиск по трекам") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
        }

        if (vm.tracks.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Пока пусто. Включи музыку и нажми кнопку или введи название ролика вручную.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(vm.visibleTracks, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        current = track.id == vm.currentId,
                        onPlay = { vm.play(track) },
                        onRename = { renaming = track },
                        onExport = { vm.export(track) },
                        onDelete = { vm.delete(track) }
                    )
                }
            }
        }

        vm.currentTrack?.let { PlayerBar(vm, it) }
    }

    renaming?.let { t ->
        RenameDialog(
            track = t,
            onDismiss = { renaming = null },
            onSave = { a, title ->
                vm.rename(t, a, title)
                renaming = null
            }
        )
    }
}

@Composable
private fun SourceButton(selected: Boolean, label: String, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    if (selected) {
        Button(onClick = onClick, contentPadding = pad, modifier = Modifier.height(36.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = pad, modifier = Modifier.height(36.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    current: Boolean,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val cover = remember(track.cover) {
        if (track.cover.isNotEmpty()) BitmapFactory.decodeFile(track.cover)?.asImageBitmap() else null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp)) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) { Text("♪") }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (track.artist.isNotBlank()) {
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Сохранить в Музыку") }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

// ============================ плеер ============================

private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Ctl(text: String, active: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}

@Composable
fun PlayerBar(vm: MainViewModel, t: Track) {
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(0f) }
    val dur = vm.duration
    val frac = if (dur > 0) (vm.position.toFloat() / dur).coerceIn(0f, 1f) else 0f

    Column(Modifier.fillMaxWidth()) {
        Text(
            if (t.artist.isBlank()) t.title else "${t.artist} — ${t.title}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Bold
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatTime(if (dragging) (drag * dur).toLong() else vm.position),
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = if (dragging) drag else frac,
                onValueChange = {
                    dragging = true
                    drag = it
                },
                onValueChangeFinished = {
                    if (dur > 0) vm.seekTo((drag * dur).toLong())
                    dragging = false
                },
                enabled = dur > 0,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            Text(formatTime(dur), style = MaterialTheme.typography.bodySmall)
        }
        val repeatLabel = when (vm.repeatMode) {
            Player.REPEAT_MODE_ALL -> "Повтор: все"
            Player.REPEAT_MODE_ONE -> "Повтор: 1"
            else -> "Повтор"
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Ctl("◀◀") { vm.previous() }
            Button(
                onClick = { vm.togglePlay() },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) { Text(if (vm.isPlaying) "Пауза" else "Играть") }
            Ctl("▶▶") { vm.next() }
            Ctl("Mix", active = vm.shuffle) { vm.toggleShuffle() }
            Ctl(repeatLabel, active = vm.repeatMode != Player.REPEAT_MODE_OFF) { vm.cycleRepeat() }
        }
    }
}

// ============================ диалоги ============================

@Composable
fun PendingDialog(vm: MainViewModel) {
    val p = vm.pending ?: return
    val c = p.candidates[vm.pendingIndex]
    var bmp by remember(c.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(c.id) {
        bmp = withContext(Dispatchers.IO) {
            Recognizer.fetchBytes(c.thumbUrl)?.let { b ->
                BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { vm.cancelPending() },
        title = { Text("Скачать это видео?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                ) {
                    val b = bmp
                    if (b != null) {
                        Image(
                            bitmap = b,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {
                            Box(contentAlignment = Alignment.Center) { Text("…") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(c.title, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val dur = if (c.durationSec > 0) " · " + formatTime(c.durationSec * 1000) else ""
                Text(
                    "${c.channel}$dur",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Вариант ${vm.pendingIndex + 1} из ${p.candidates.size}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.weight(1f))
                    if (p.candidates.size > 1) {
                        TextButton(onClick = { vm.nextCandidate() }) { Text("Другое видео") }
                    }
                }
                OutlinedTextField(
                    value = vm.editArtist,
                    onValueChange = { vm.editArtist = it },
                    label = { Text("Исполнитель") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = vm.editTitle,
                    onValueChange = { vm.editTitle = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = { vm.confirmPending() }) { Text("Скачать") } },
        dismissButton = { TextButton(onClick = { vm.cancelPending() }) { Text("Отмена") } }
    )
}

@Composable
fun RenameDialog(track: Track, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var artist by remember { mutableStateOf(track.artist) }
    var title by remember { mutableStateOf(track.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переименовать") },
        text = {
            Column {
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Исполнитель") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(artist, title) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

// ============================ настройки ============================

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onHelp: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Spacer(Modifier.weight(1f))
            Text("Настройки", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(12.dp))

        Text("Распознавание", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = vm.token,
            onValueChange = vm::updateToken,
            label = { Text("AudD токен (необязательно)") },
            supportingText = { Text("Сохраняется на телефоне. Используется, когда кончатся анонимные запросы (около 10 в день)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))
        Text("Скачивание", style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Показывать видео перед скачиванием")
                Text(
                    "Увидишь, что именно скачается, сможешь выбрать другое видео и поправить название",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = vm.confirmDownload, onCheckedChange = vm::updateConfirmDownload)
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onHelp) { Text("Справка и советы") }
        Spacer(Modifier.height(8.dp))
        Text("SongCatcher $APP_VERSION", style = MaterialTheme.typography.bodySmall)
    }
}

// ============================ справка ============================

private val HELP: List<Pair<String, String>> = listOf(
    "Быстрый старт" to
        "1. Включи музыку (на другом устройстве или на этом телефоне).\n" +
        "2. Выбери источник: «Микрофон» или «Звук телефона».\n" +
        "3. Нажми «Распознать и скачать». Приложение послушает 10-12 секунд, определит песню, " +
        "найдёт её на YouTube и предложит скачать.\n" +
        "4. Трек появится в списке, нажми на него, чтобы включить.",
    "Токен AudD" to
        "Токен нужен для распознавания. Бесплатный выдают при регистрации на dashboard.audd.io. " +
        "Вставь его в Настройках (⚙). Приложение сначала использует бесплатные анонимные запросы " +
        "(около 10 в день), а когда они кончатся, переключается на твой токен. " +
        "На ручной режим токен не влияет вообще.",
    "Микрофон или звук телефона" to
        "Микрофон слышит только то, что звучит вслух, поэтому музыка должна играть из колонки или другого устройства, " +
        "а телефон лежать рядом.\n" +
        "«Звук телефона» записывает то, что играет на самом телефоне (браузер, видео), без шума и даже в наушниках. " +
        "Нужен Android 10+, при каждой записи система спросит разрешение. Некоторые приложения запрещают " +
        "запись своего звука, тогда будет сообщение про тишину.",
    "Ручной режим" to
        "Нажми «Вручную», введи название ролика или вставь ссылку на YouTube. " +
        "Ссылка скачает ровно то видео, а название покажет лучшие результаты поиска. Распознавание не используется.",
    "Выбор видео и названия" to
        "Перед скачиванием показывается превью видео. Если это не то, нажми «Другое видео». " +
        "Исполнителя и название можно поправить до скачивания. Лишнее вроде (Official Video) " +
        "убирается само. Позже трек можно переименовать через меню ⋮. " +
        "Выбор видео можно отключить в Настройках, тогда будет скачиваться первый найденный вариант.",
    "Плеер" to
        "Внизу ползунок: можно перемотать на любое место. «Mix» включает перемешивание, " +
        "«Повтор» переключает: выкл, весь список, один трек. Музыка играет в фоне, в шторке есть кнопки.",
    "Музыка в других плеерах" to
        "В меню ⋮ у трека выбери «Сохранить в Музыку». Файл появится в папке Music/SongCatcher " +
        "(работает на Android 10+).",
    "Если музыка в фоне останавливается (MIUI)" to
        "Настройки, Приложения, SongCatcher, Контроль активности, «Нет ограничений». " +
        "Также включи автозапуск для приложения.",
    "Если что-то не работает" to
        "• «Не удалось распознать»: песни может не быть в базе, попробуй другой момент или звук погромче.\n" +
        "• Ошибка про отпечаток: запись слишком тихая или с паузами.\n" +
        "• Ошибка скачивания: YouTube мог поменяться. Закрой и открой приложение, оно при запуске обновляет загрузчик.\n" +
        "• После вылета при следующем запуске сверху появится красная плашка с причиной, её можно скопировать."
)

@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Spacer(Modifier.weight(1f))
            Text("Справка", style = MaterialTheme.typography.titleLarge)
        }
        HELP.forEach { (title, body) ->
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
    }
}
