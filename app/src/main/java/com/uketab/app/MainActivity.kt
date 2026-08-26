package com.uketab.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { UkeTabApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UkeTabApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var score by remember { mutableStateOf<Score?>(null) }
    var tuning by remember { mutableStateOf(Tuning.HIGH_G) }
    var capo by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("MusicXML 파일을 열거나 악보 사진을 찍어보세요.") }
    var busy by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("http://192.168.0.10:8000") }
    var showSettings by remember { mutableStateOf(false) }
    var photoFile by remember { mutableStateOf<File?>(null) }

    val tabText = remember(score, tuning, capo) {
        score?.let { TabConverter.render(it, TabConverter.convert(it, tuning, capo), tuning) }
    }

    fun loadFromUri(uri: Uri) {
        scope.launch {
            busy = true; status = "악보 파일 읽는 중..."
            try {
                val s = withContext(Dispatchers.IO) {
                    val name = queryName(ctx, uri)
                    ctx.contentResolver.openInputStream(uri)!!.use { MusicXmlParser.parse(it, name) }
                }
                score = s; status = "${s.title} — 음표 ${s.notes.size}개"
            } catch (e: Exception) { status = "읽기 실패: ${e.message}" }
            busy = false
        }
    }

    fun recognizePhoto(file: File) {
        scope.launch {
            busy = true; status = "사진을 서버로 보내 인식 중... (수십 초 걸릴 수 있음)"
            try {
                val s = withContext(Dispatchers.IO) {
                    val xml = OmrClient(serverUrl).recognize(file)
                    MusicXmlParser.parse(xml.byteInputStream(), "recognized.musicxml")
                }
                score = s; status = "인식 완료 — 음표 ${s.notes.size}개 (결과를 꼭 확인하세요)"
            } catch (e: Exception) { status = "인식 실패: ${e.message}" }
            busy = false
        }
    }

    fun transcribeAudio(file: File) {
        scope.launch {
            busy = true; status = "음원을 서버로 보내 멜로디 추출 중... (곡 길이에 따라 1~3분)"
            try {
                val s = withContext(Dispatchers.IO) {
                    val xml = OmrClient(serverUrl).transcribe(file)
                    MusicXmlParser.parse(xml.byteInputStream(), "audio.musicxml")
                }
                score = s; status = "추출 완료 — 음표 ${s.notes.size}개 (자동 채보라 오류가 있을 수 있어요)"
            } catch (e: Exception) { status = "추출 실패: ${e.message}" }
            busy = false
        }
    }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val name = queryName(ctx, it)
            val f = File(ctx.cacheDir, "photos/$name").apply { parentFile?.mkdirs() }
            ctx.contentResolver.openInputStream(it)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            transcribeAudio(f)
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadFromUri(it) }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val f = File(ctx.cacheDir, "photos/picked.jpg").apply { parentFile?.mkdirs() }
            ctx.contentResolver.openInputStream(it)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            recognizePhoto(f)
        }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoFile?.let { recognizePhoto(it) }
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val f = File(ctx.cacheDir, "photos/shot.jpg").apply { parentFile?.mkdirs() }
            photoFile = f
            takePhoto.launch(FileProvider.getUriForFile(ctx, "com.uketab.app.fileprovider", f))
        } else status = "카메라 권한이 필요합니다."
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("UkeTab 🎵") }, actions = {
            TextButton(onClick = { showSettings = true }) { Text("설정") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp).fillMaxSize()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openFile.launch(arrayOf("*/*")) }, enabled = !busy) { Text("파일 열기") }
                Button(onClick = { askCamera.launch(Manifest.permission.CAMERA) }, enabled = !busy) { Text("사진 찍기") }
                OutlinedButton(onClick = { pickImage.launch("image/*") }, enabled = !busy) { Text("갤러리") }
            }
            Spacer(Modifier.height(6.dp))
            Button(onClick = { pickAudio.launch("audio/*") }, enabled = !busy) { Text("🎧 MP3 음원에서 타브 만들기") }
            Spacer(Modifier.height(8.dp))
            TuningSelector(tuning) { tuning = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("카포: $capo")
                Slider(value = capo.toFloat(), onValueChange = { capo = it.toInt() }, valueRange = 0f..7f, steps = 6,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (tabText != null) {
                Row {
                    OutlinedButton(onClick = {
                        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, tabText) }
                        ctx.startActivity(Intent.createChooser(i, "타브 공유"))
                    }) { Text("공유 / 저장") }
                }
                SelectionContainer {
                    Text(
                        tabText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                    )
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(onDismissRequest = { showSettings = false },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("확인") } },
            title = { Text("OMR 서버 주소") },
            text = {
                Column {
                    Text("악보 사진 인식과 MP3 채보는 server/omr_server.py 를 실행한 PC 주소가 필요합니다.")
                    OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it }, singleLine = true)
                }
            })
    }
}

@Composable
fun TuningSelector(current: Tuning, onSelect: (Tuning) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Tuning.values().forEach { t ->
            FilterChip(selected = t == current, onClick = { onSelect(t) }, label = { Text(t.name.replace('_', '-')) })
        }
    }
}

private fun queryName(ctx: android.content.Context, uri: Uri): String {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
    }
    return uri.lastPathSegment ?: "score.xml"
}
