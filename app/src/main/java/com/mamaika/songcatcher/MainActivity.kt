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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current

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

    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(16.dp)
            ) {
                Text("SongCatcher 0.3", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))

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
                                    .heightIn(max = 160.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                            Row {
                                TextButton(onClick = { clipboard.setText(AnnotatedString(r)) }) {
                                    Text("Скопировать")
                                }
                                TextButton(onClick = { vm.dismissReport() }) { Text("Закрыть") }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = vm.token,
                    onValueChange = vm::updateToken,
                    label = { Text("AudD API token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(if (vm.usePhoneAudio) "Источник: звук телефона" else "Источник: микрофон")
                    Switch(checked = vm.usePhoneAudio, onCheckedChange = vm::updateUsePhoneAudio)
                }

                Button(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            ctx, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) startCapture()
                        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    enabled = !vm.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                ) {
                    Text(if (vm.busy) "Работаю…" else "Распознать и скачать")
                }

                Spacer(Modifier.height(8.dp))
                Text(vm.status, style = MaterialTheme.typography.bodyMedium)

                if (vm.busy) {
                    Spacer(Modifier.height(8.dp))
                    val p = vm.progress
                    if (p != null) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.query = it },
                    label = { Text("Поиск по трекам") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(vm.visibleTracks, key = { it.id }) { track ->
                        val current = track.id == vm.currentId
                        val cover = remember(track.cover) {
                            if (track.cover.isNotEmpty())
                                BitmapFactory.decodeFile(track.cover)?.asImageBitmap()
                            else null
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.play(track) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(48.dp)) {
                                if (cover != null) {
                                    Image(
                                        bitmap = cover,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
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
                                Text(
                                    track.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = { vm.export(track) }) { Text("⤓") }
                            TextButton(onClick = { vm.delete(track) }) { Text("✕") }
                        }
                    }
                }

                vm.currentTrack?.let { t ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${t.artist} — ${t.title}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { vm.previous() }) { Text("◀◀") }
                        Button(onClick = { vm.togglePlay() }) {
                            Text(if (vm.isPlaying) "Пауза" else "Играть")
                        }
                        TextButton(onClick = { vm.next() }) { Text("▶▶") }
                        TextButton(onClick = { vm.toggleShuffle() }) {
                            Text(if (vm.shuffle) "Shuffle ✓" else "Shuffle")
                        }
                    }
                }
            }
        }
    }
}
