@file:OptIn(ExperimentalMaterial3Api::class)

package dev.offline.player

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import androidx.navigation.NavController
import androidx.navigation.compose.*
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ---------- ДАННЫЕ ---------- */

data class Song(
    val id: Long, val title: String, val artist: String, val album: String,
    val albumId: Long, val dur: Long, val uri: Uri, val path: String,
    val folder: String, val added: Long
) {
    val art: Uri? get() = if (albumId > 0)
        ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId) else null
}

class Pl(var name: String, val ids: MutableList<Long> = mutableStateListOf())

object Repo {
    private lateinit var ctx: Context
    val songs = mutableStateListOf<Song>()
    var loaded by mutableStateOf(false)
    fun init(c: Context) { ctx = c.applicationContext }

    suspend fun scan() = withContext(Dispatchers.IO) {
        val out = ArrayList<Song>()
        val p = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DATE_ADDED)
        runCatching {
            ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, p,
                "${MediaStore.Audio.Media.IS_MUSIC}!=0", null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(6) ?: continue
                    val f = File(path); val id = c.getLong(0)
                    out.add(Song(id,
                        c.getString(1)?.takeIf { it.isNotBlank() } ?: f.nameWithoutExtension,
                        c.getString(2)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist",
                        c.getString(3)?.takeIf { it.isNotBlank() } ?: "Unknown Album",
                        c.getLong(4), c.getLong(5),
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        path, f.parentFile?.name ?: "Music", c.getLong(7)))
                }
            }
        }
        withContext(Dispatchers.Main) { songs.clear(); songs.addAll(out); loaded = true }
    }

    fun byId(id: Long) = songs.firstOrNull { it.id == id }
    fun albums() = songs.groupBy { it.albumId }.values.sortedByDescending { g -> g.maxOf { it.added } }
    fun artists() = songs.groupBy { it.artist }.entries.sortedBy { it.key.lowercase() }
    fun folders() = songs.groupBy { it.folder }.entries.sortedBy { it.key.lowercase() }

    suspend fun genres(): List<Pair<Long, String>> = withContext(Dispatchers.IO) {
        val r = ArrayList<Pair<Long, String>>()
        runCatching {
            ctx.contentResolver.query(MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME), null, null,
                "${MediaStore.Audio.Genres.NAME} ASC")?.use { c ->
                while (c.moveToNext()) { val n = c.getString(1); if (!n.isNullOrBlank()) r.add(c.getLong(0) to n) }
            }
        }
        r
    }

    suspend fun genreSongs(gid: Long): List<Song> = withContext(Dispatchers.IO) {
        val ids = HashSet<Long>()
        runCatching {
            ctx.contentResolver.query(MediaStore.Audio.Genres.Members.getContentUri("external", gid),
                arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID), null, null, null)?.use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0))
            }
        }
        songs.filter { it.id in ids }
    }
}

object Store {
    private lateinit var sp: android.content.SharedPreferences
    val likes = mutableStateListOf<Long>()
    val pls = mutableStateListOf<Pl>()
    var listenMs by mutableStateOf(0L)
    var plays by mutableStateOf(0)
    var since by mutableStateOf(System.currentTimeMillis())
    private val lrc = HashMap<Long, String>()
    private var tick = 0

    fun init(c: Context) {
        sp = c.getSharedPreferences("store", Context.MODE_PRIVATE)
        sp.getString("likes", "")!!.split(",").forEach { it.toLongOrNull()?.let { l -> likes.add(l) } }
        runCatching {
            val a = JSONArray(sp.getString("pls", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val ids = mutableStateListOf<Long>()
                val arr = o.getJSONArray("ids")
                for (j in 0 until arr.length()) ids.add(arr.getLong(j))
                pls.add(Pl(o.getString("name"), ids))
            }
        }
        listenMs = sp.getLong("listenMs", 0); plays = sp.getInt("plays", 0)
        since = sp.getLong("since", System.currentTimeMillis())
        sp.edit().putLong("since", since).apply()
        sp.getString("lrc", "")!!.split("\n").forEach { line ->
            val k = line.substringBefore("|").toLongOrNull() ?: return@forEach
            lrc[k] = line.substringAfter("|")
        }
    }
    fun liked(id: Long) = id in likes
    fun like(id: Long) { if (!likes.remove(id)) likes.add(id); persist() }
    fun pl(n: String) = pls.firstOrNull { it.name == n }
    fun create(n: String): Boolean { if (n.isBlank() || pl(n) != null) return false; pls.add(Pl(n)); persist(); return true }
    fun delete(n: String) { pls.removeAll { it.name == n }; persist() }
    fun add(n: String, ids: List<Long>) { pl(n)?.let { p -> ids.forEach { if (it !in p.ids) p.ids.add(it) }; persist() } }
    fun rem(n: String, id: Long) { pl(n)?.let { it.ids.remove(id); persist() } }
    fun listened(ms: Long) { listenMs += ms; if (++tick >= 120) flush() }
    fun play() { plays++; persist() }
    fun flush() { tick = 0; sp.edit().putLong("listenMs", listenMs).putInt("plays", plays).apply() }
    fun lrcUri(id: Long) = lrc[id]
    fun setLrc(id: Long, u: String) { lrc[id] = u; persist() }
    fun persist() {
        val a = JSONArray()
        pls.forEach { p -> a.put(JSONObject().put("name", p.name).put("ids", JSONArray(p.ids.toList()))) }
        sp.edit().putString("likes", likes.joinToString(",")).putString("pls", a.toString())
            .putString("lrc", lrc.entries.joinToString("\n") { "${it.key}|${it.value}" })
            .putInt("plays", plays).putLong("listenMs", listenMs).apply()
    }
}

/* ---------- ПЛЕЕР ---------- */

object Hub {
    lateinit var player: ExoPlayer; private set
    private lateinit var ctx: Context
    private var ctrl: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    val cur = mutableStateOf<Song?>(null)
    val playing = mutableStateOf(false)
    val pos = mutableStateOf(0L)
    val dur = mutableStateOf(0L)
    val shuf = mutableStateOf(false)
    val auto = mutableStateOf(true)
    val qname = mutableStateOf("Local Music")
    val qver = mutableStateOf(0)
    val dev = mutableStateOf<AudioDeviceInfo?>(null)

    fun init(c: Context) {
        if (::player.isInitialized) return
        ctx = c.applicationContext
        player = ExoPlayer.Builder(ctx).setHandleAudioFocus(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { playing.value = p; if (!p) Store.flush() }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                cur.value = item?.mediaId?.toLongOrNull()?.let { Repo.byId(it) }
                dur.value = player.duration.coerceAtLeast(0); pos.value = 0; qver.value++
            }
            override fun onPlaybackStateChanged(state: Int) {
                dur.value = player.duration.coerceAtLeast(0)
                if (state == Player.STATE_ENDED) {
                    Store.play()
                    if (auto.value && Repo.songs.isNotEmpty()) {
                        player.addMediaItems(Repo.songs.shuffled().take(10).map { item(it) })
                        player.prepare(); player.play()
                    }
                }
            }
            override fun onShuffleModeEnabledChanged(e: Boolean) { shuf.value = e }
            override fun onTimelineChanged(t: Timeline, reason: Int) { qver.value++ }
        })
    }
    private fun item(s: Song) = MediaItem.Builder().setMediaId(s.id.toString()).setUri(s.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist)
            .setAlbumTitle(s.album).setArtworkUri(s.art).build()).build()
    private fun service() {
        if (ctrl == null) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java))
            ctrl = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync()
        }
    }
    fun play(list: List<Song>, i: Int, name: String, shuffle: Boolean = false) {
        if (list.isEmpty()) return
        service(); qname.value = name
        player.shuffleModeEnabled = shuffle
        player.setMediaItems(list.map { item(it) }, i.coerceIn(0, list.size - 1), 0)
        player.prepare(); player.play()
    }
    fun toggle() {
        service()
        if (player.isPlaying) player.pause()
        else { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
    }
    fun next() = player.seekToNextMediaItem()
    fun prev() = player.seekToPreviousMediaItem()
    fun playNext(s: Song) { service(); player.addMediaItem(player.currentMediaItemIndex + 1, item(s)) }
    fun enqueue(s: Song) { service(); player.addMediaItem(item(s)) }
    fun device(d: AudioDeviceInfo?) { dev.value = d; player.setPreferredAudioDevice(d) }
}

class PlaybackService : MediaSessionService() {
    private var s: MediaSession? = null
    override fun onGetSession(ci: MediaSession.ControllerInfo): MediaSession? {
        if (s == null) s = MediaSession.Builder(this, Hub.player).build()
        return s
    }
    override fun onDestroy() { s?.release(); s = null; super.onDestroy() }
}

/* ---------- ТЕМА / ОБЩЕЕ ---------- */

val BG = Color(0xFF000000); val S1 = Color(0xFF151515)
val S2 = Color(0xFF242424); val T2 = Color(0xFFB0B0B0)

@Composable fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        background = BG, surface = S1, surfaceVariant = S2, primary = Color.White,
        onPrimary = Color.Black, onBackground = Color.White, onSurface = Color.White,
        secondary = T2, onSurfaceVariant = T2), content = content)
}

fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
fun total(ms: Long): String { val m = ms / 60000; return if (m >= 60) "${m / 60}h ${m % 60}m" else "$m min" }

@Composable fun Art(u: Uri?, size: Int, r: Int = 8) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(r.dp)).background(S2), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, Modifier.size((size / 2).dp), tint = T2)
        AsyncImage(u, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}
@Composable fun ArtBig(u: Uri?, mod: Modifier = Modifier) {
    Box(mod.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(S2), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, Modifier.size(40.dp), tint = T2)
        AsyncImage(u, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}
@Composable fun Section(t: String) {
    Text(t, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp))
}
@Composable fun EmptyMsg() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text("На устройстве нет аудиофайлов", color = T2, textAlign = TextAlign.Center)
    }
}
@Composable fun PlayShuffle(play: () -> Unit, shuf: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(play, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
            Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(shuf, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(26.dp)) {
            Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle")
        }
    }
}
@Composable fun SongRow(s: Song, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Art(s.art, 46); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, maxLines = 1, fontSize = 15.sp)
            Text(s.artist, maxLines = 1, fontSize = 13.sp, color = T2)
        }
        Text(fmt(s.dur), fontSize = 12.sp, color = T2)
        SongMenu(s, onRemove)
    }
}
@Composable fun SongMenu(s: Song, onRemove: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    var dlg by remember { mutableStateOf(false) }
    IconButton({ open = true }) { Icon(Icons.Rounded.MoreVert, null, tint = T2) }
    DropdownMenu(open, { open = false }) {
        DropdownMenuItem({ Text("Воспроизвести следующей") }, { open = false; Hub.playNext(s) })
        DropdownMenuItem({ Text("В очередь") }, { open = false; Hub.enqueue(s) })
        DropdownMenuItem({ Text("В плейлист…") }, { open = false; dlg = true })
        DropdownMenuItem({ Text(if (Store.liked(s.id)) "Убрать из Liked" else "В Liked songs") }, { open = false; Store.like(s.id) })
        if (onRemove != null) DropdownMenuItem({ Text("Удалить из плейлиста") }, { open = false; onRemove() })
    }
    if (dlg) AddDlg(listOf(s.id)) { dlg = false }
}
@Composable fun AddDlg(ids: List<Long>, close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("В плейлист") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Liked songs", Modifier.fillMaxWidth().clickable { ids.forEach { if (!Store.liked(it)) Store.like(it) }; close() }.padding(vertical = 12.dp))
            Store.pls.forEach { p ->
                Text(p.name, Modifier.fillMaxWidth().clickable { Store.add(p.name, ids); close() }.padding(vertical = 12.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(name, { name = it }, placeholder = { Text("Новый плейлист") }, modifier = Modifier.weight(1f), singleLine = true)
                IconButton({ if (Store.create(name)) { Store.add(name, ids); close() } }) { Icon(Icons.Rounded.Add, null) }
            }
        }
    }, confirmButton = {})
}
@Composable fun NameDlg(title: String, close: () -> Unit, ok: (String) -> Unit) {
    var v by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { TextField(v, { v = it }, singleLine = true) },
        confirmButton = { TextButton({ ok(v) }) { Text("OK") } },
        dismissButton = { TextButton(close) { Text("Отмена") } })
}
@Composable fun IconRow(ic: ImageVector, title: String, sub: String?, onClick: () -> Unit, art: Uri? = null, round: Boolean = false) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (art != null || round) Art(art, 46, if (round) 23 else 8)
        else Box(Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(S2), contentAlignment = Alignment.Center) { Icon(ic, null, tint = Color.White) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, fontSize = 15.sp)
            if (sub != null) Text(sub, fontSize = 13.sp, color = T2, maxLines = 1)
        }
        Icon(Icons.Rounded.KeyboardArrowRight, null, tint = T2)
    }
}

/* ---------- НАВИГАЦИЯ ---------- */

object Perms {
    var ask: (() -> Unit)? = null
    fun audio(c: Context) = ContextCompat.checkSelfPermission(c,
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

class App : Application() {
    override fun onCreate() { super.onCreate(); Repo.init(this); Store.init(this); Hub.init(this) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        val l = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (Perms.audio(this)) lifecycleScope.launch { Repo.scan() }
        }
        Perms.ask = {
            l.launch(if (Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
        setContent { AppTheme { Root() } }
    }
}

@Composable fun Root() {
    val ctx = LocalContext.current
    val nav = rememberNavController()
    val back by nav.currentBackStackEntryAsState()
    val route = back?.destination?.route ?: "home"
    LaunchedEffect(Unit) { if (Perms.audio(ctx) && !Repo.loaded) Repo.scan() }
    LaunchedEffect(Unit) {
        while (true) {
            if (Hub.playing.value) { Hub.pos.value = Hub.player.currentPosition; Store.listened(500) }
            delay(500)
        }
    }
    if (!Repo.loaded) { PermScreen(); return }
    Scaffold(containerColor = BG, bottomBar = {
        if (route != "now") Column { MiniPlayer { nav.navigate("now") }; NavBar(route, nav) }
    }) { pad ->
        NavHost(nav, "home", Modifier.padding(pad)) {
            composable("home") { HomeScreen(nav) }
            composable("explore") { ExploreScreen(nav) }
            composable("library") { LibraryScreen(nav) }
            composable("search") { SearchScreen() }
            composable("local") { LocalScreen(nav) }
            composable("pl/{n}") { PlScreen(nav, it.arguments?.getString("n") ?: "") }
            composable("list/{t}/{k}") { ListScreen(nav, it.arguments?.getString("t") ?: "", it.arguments?.getString("k") ?: "") }
            composable("now") { NowScreen { nav.popBackStack() } }
        }
    }
}

@Composable fun PermScreen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(BG).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(72.dp), tint = Color.White)
            Spacer(Modifier.height(16.dp))
            Text("Нужен доступ к музыке", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Разреши чтение аудиофайлов — треки появятся автоматически.", color = T2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button({ if (Perms.audio(ctx)) scope.launch { Repo.scan() } else Perms.ask?.invoke() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Разрешить") }
        }
    }
}

@Composable fun MiniPlayer(open: () -> Unit) {
    val s = Hub.cur.value ?: return
    Surface(color = S2, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).clickable(onClick = open)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(s.art, 40); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, maxLines = 1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(s.artist, maxLines = 1, fontSize = 12.sp, color = T2)
            }
            IconButton({ Hub.toggle() }) { Icon(if (Hub.playing.value) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null) }
            IconButton({ Hub.next() }) { Icon(Icons.Rounded.SkipNext, null) }
        }
    }
}

@Composable fun NavBar(route: String, nav: NavController) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = S1, shape = RoundedCornerShape(30.dp), modifier = Modifier.weight(1f)) {
            Row(Modifier.padding(6.dp)) {
                NavItem("home", Icons.Rounded.Home, "Home", route, nav, Modifier.weight(1f))
                NavItem("explore", Icons.Rounded.GridView, "Explore", route, nav, Modifier.weight(1f))
                NavItem("library", Icons.Rounded.LibraryMusic, "Library", route, nav, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.width(8.dp))
        Surface(color = S1, shape = CircleShape) { IconButton({ nav.navigate("search") }) { Icon(Icons.Rounded.Search, null) } }
    }
}
@Composable fun NavItem(r: String, ic: ImageVector, label: String, route: String, nav: NavController, mod: Modifier) {
    val on = route == r
    Column(mod.clip(RoundedCornerShape(24.dp)).background(if (on) S2 else Color.Transparent)
        .clickable { if (!on) nav.navigate(r) { launchSingleTop = true; popUpTo("home") } }.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(ic, null, Modifier.size(22.dp), tint = if (on) Color.White else T2)
        Text(label, fontSize = 11.sp, color = if (on) Color.White else T2)
    }
}

/* ---------- ЭКРАНЫ ---------- */

@Composable fun HomeScreen(nav: NavController) {
    val songs = Repo.songs
    if (songs.isEmpty()) { EmptyMsg(); return }
    val quick = remember(songs.size) { songs.shuffled().take(6) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.GraphicEq, null, tint = Color.White); Spacer(Modifier.width(10.dp))
                Text("Offline Player", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        item { Section("Quick picks") }
        items(quick.size) { i -> SongRow(quick[i]) { Hub.play(quick, i, "Quick picks") } }
        item { Section("Albums & singles") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val al = Repo.albums().take(12)
                items(al.size) { i ->
                    val g = al[i]
                    Column(Modifier.width(140.dp).clickable { nav.navigate("list/album/${g[0].albumId}") }) {
                        Art(g[0].art, 140)
                        Text(g[0].album, maxLines = 1, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        Text(g[0].artist, maxLines = 1, fontSize = 12.sp, color = T2)
                    }
                }
            }
        }
        item { Section("Artists") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val ar = Repo.artists().take(12)
                items(ar.size) { i ->
                    val e = ar[i]
                    Column(Modifier.width(110.dp).clickable { nav.navigate("list/artist/${Uri.encode(e.key)}") },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Art(e.value[0].art, 110, 55)
                        Text(e.key, maxLines = 1, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        item { Section("Recently added") }
        val rec = songs.take(8)
        items(rec.size) { i -> SongRow(rec[i]) { Hub.play(songs.toList(), i, "Local Music") } }
    }
}

@Composable fun ExploreScreen(nav: NavController) {
    val genres by produceState<List<Pair<Long, String>>>(emptyList()) { value = Repo.genres() }
    val pal = listOf(
        listOf(Color(0xFF6C7BD6), Color(0xFF3B4A9E)), listOf(Color(0xFFE8C33D), Color(0xFFA8801B)),
        listOf(Color(0xFF7FC98A), Color(0xFF3E8A50)), listOf(Color(0xFFB58BD6), Color(0xFF6E4A9E)),
        listOf(Color(0xFFE0605E), Color(0xFF9E2F2F)), listOf(Color(0xFF57B6C2), Color(0xFF2A7681)),
        listOf(Color(0xFFE08A4B), Color(0xFF9E5620)), listOf(Color(0xFF8E9AA6), Color(0xFF4E5A66)))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Text("Explore", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GenreTile("Все треки", pal[0], Modifier.weight(1f)) { nav.navigate("local") }
                GenreTile("Недавние", pal[2], Modifier.weight(1f)) { nav.navigate("list/recent/all") }
            }
        }
        item { Section("Genres") }
        val rows = genres.chunked(2)
        items(rows.size) { i ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rows[i].forEachIndexed { j, g ->
                    GenreTile(g.second, pal[(i * 2 + j) % pal.size], Modifier.weight(1f)) { nav.navigate("list/genre/${g.first}") }
                }
                if (rows[i].size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (genres.isEmpty()) item { Text("Жанры не найдены в локальных файлах", color = T2, modifier = Modifier.padding(16.dp)) }
    }
}
@Composable fun GenreTile(t: String, c: List<Color>, mod: Modifier, onClick: () -> Unit) {
    Box(mod.height(90.dp).clip(RoundedCornerShape(12.dp)).background(Brush.horizontalGradient(c)).clickable(onClick = onClick)) {
        Text(t, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(12.dp))
    }
}

@Composable fun LibraryScreen(nav: NavController) {
    val ctx = LocalContext.current
    var newDlg by remember { mutableStateOf(false) }
    val imp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) importM3u(ctx, u) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Text("Library", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
        item { StatsCard() }
        item { IconRow(Icons.Rounded.Download, "Downloads", "${Repo.songs.count { it.path.contains("/Download", true) }} songs") { nav.navigate("list/downloads/all") } }
        item { IconRow(Icons.Rounded.Folder, "Local Music", "${Repo.songs.size} tracks · ${total(Repo.songs.sumOf { it.dur })}") { nav.navigate("local") } }
        item { Section("Playlists") }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionCard("New playlist", Icons.Rounded.Add, Modifier.weight(1f)) { newDlg = true }
                ActionCard("Import .m3u", Icons.Rounded.PlaylistAdd, Modifier.weight(1f)) { imp.launch(arrayOf("*/*")) }
            }
        }
        item { IconRow(Icons.Rounded.Favorite, "Liked songs", "playlist · ${Store.likes.size} songs") { nav.navigate("pl/__liked__") } }
        items(Store.pls.size) { i ->
            val p = Store.pls[i]
            IconRow(Icons.Rounded.QueueMusic, p.name, "playlist · ${p.ids.size} songs") { nav.navigate("pl/${Uri.encode(p.name)}") }
        }
    }
    if (newDlg) NameDlg("Новый плейлист", { newDlg = false }) { n -> if (Store.create(n)) newDlg = false }
}
@Composable fun StatsCard() {
    val df = remember { SimpleDateFormat("MM/yy", Locale.US) }
    val year = remember { SimpleDateFormat("yyyy", Locale.US).format(Date()) }
    Box(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(16.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF5B2A86), Color(0xFFA8443C), Color(0xFFC9822D)))).padding(20.dp)) {
        Column {
            Text("YOUR LISTENING EXPERIENCE", fontSize = 11.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(14.dp))
            Text("${Store.listenMs / 60000}", fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text("MINUTES LISTENED", fontSize = 11.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(18.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    Text("OFFLINE LISTENER", fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("${Store.plays} plays · $year", fontSize = 12.sp, color = Color(0xFFE8E8E8))
                }
                Column {
                    Text("MEMBER SINCE", fontSize = 10.sp, color = Color(0xFFE8E8E8))
                    Text(df.format(Date(Store.since)), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
@Composable fun ActionCard(t: String, ic: ImageVector, mod: Modifier, onClick: () -> Unit) {
    Column(mod) {
        Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).background(S1).clickable(onClick = onClick),
            contentAlignment = Alignment.Center) { Icon(ic, null, Modifier.size(36.dp), tint = Color.White) }
        Text(t, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    }
}
fun importM3u(ctx: Context, u: Uri) {
    var name = "Imported"
    runCatching {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0).substringBeforeLast('.')
        }
    }
    val ids = mutableListOf<Long>()
    runCatching {
        ctx.contentResolver.openInputStream(u)?.bufferedReader()?.forEachLine { raw ->
            val l = raw.trim()
            if (l.isNotEmpty() && !l.startsWith("#")) {
                val s = Repo.songs.firstOrNull { it.path.endsWith(l) || it.title.equals(l, true) || File(l).nameWithoutExtension.equals(it.title, true) }
                if (s != null && s.id !in ids) ids.add(s.id)
            }
        }
    }
    var n = name; var k = 2
    while (Store.pl(n) != null) { n = "$name ($k)"; k++ }
    Store.create(n); Store.add(n, ids)
    Toast.makeText(ctx, "Добавлено треков: ${ids.size}", Toast.LENGTH_SHORT).show()
}

@Composable fun SearchScreen() {
    var q by remember { mutableStateOf("") }
    val res = remember(q) {
        if (q.isBlank()) emptyList() else Repo.songs.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }.take(60)
    }
    Column(Modifier.fillMaxSize()) {
        TextField(q, { q = it }, Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Треки, исполнители, альбомы") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(24.dp))
        LazyColumn { items(res.size) { i -> SongRow(res[i]) { Hub.play(res, i, "Search") } } }
    }
}

@Composable fun LocalScreen(nav: NavController) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Songs", "Artists", "Albums", "Playlists", "Folders")
    val songs = Repo.songs
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)).background(S1), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Folder, null, Modifier.size(72.dp), tint = T2)
                }
                Spacer(Modifier.height(14.dp))
                Text("Local Music", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Audio files on device", color = T2, fontSize = 14.sp)
                Text("${songs.size} Tracks · ${total(songs.sumOf { it.dur })}", color = T2, fontSize = 13.sp)
            }
        }
        item { PlayShuffle({ Hub.play(songs.toList(), 0, "Local Music") }, { Hub.play(songs.toList(), 0, "Local Music", true) }) }
        item {
            ScrollableTabRow(selectedTabIndex = tab, containerColor = BG, edgePadding = 12.dp) {
                tabs.forEachIndexed { i, t -> Tab(text = { Text(t) }, selected = tab == i, onClick = { tab = i }) }
            }
        }
        when (tab) {
            0 -> items(songs.size) { i -> SongRow(songs[i]) { Hub.play(songs.toList(), i, "Local Music") } }
            1 -> { val a = Repo.artists(); items(a.size) { i ->
                IconRow(Icons.Rounded.Person, a[i].key, "${a[i].value.size} songs",
                    { nav.navigate("list/artist/${Uri.encode(a[i].key)}") }, a[i].value[0].art, true) } }
            2 -> { val rows = Repo.albums().chunked(2); items(rows.size) { i ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rows[i].forEach { g ->
                        Column(Modifier.weight(1f).clickable { nav.navigate("list/album/${g[0].albumId}") }) {
                            ArtBig(g[0].art, Modifier.fillMaxWidth())
                            Text(g[0].album, maxLines = 1, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            Text(g[0].artist, maxLines = 1, fontSize = 12.sp, color = T2)
                        }
                    }
                    if (rows[i].size == 1) Spacer(Modifier.weight(1f))
                } } }
            3 -> {
                item { IconRow(Icons.Rounded.Favorite, "Liked songs", "${Store.likes.size} songs") { nav.navigate("pl/__liked__") } }
                items(Store.pls.size) { i -> IconRow(Icons.Rounded.QueueMusic, Store.pls[i].name, "${Store.pls[i].ids.size} songs") { nav.navigate("pl/${Uri.encode(Store.pls[i].name)}") } }
            }
            else -> { val f = Repo.folders(); items(f.size) { i ->
                IconRow(Icons.Rounded.Folder, f[i].key, "${f[i].value.size} songs") { nav.navigate("list/folder/${Uri.encode(f[i].key)}") } } }
        }
    }
}

@Composable fun ListScreen(nav: NavController, type: String, key: String) {
    var title by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<Song>>(emptyList()) }
    val genre by produceState<List<Song>?>(null, type, key) { value = if (type == "genre") Repo.genreSongs(key.toLongOrNull() ?: 0) else null }
    LaunchedEffect(type, key, genre, Repo.songs.size) {
        when (type) {
            "album" -> { list = Repo.songs.filter { it.albumId.toString() == key }; title = list.firstOrNull()?.album ?: "Album" }
            "artist" -> { list = Repo.songs.filter { it.artist == key }; title = key }
            "folder" -> { list = Repo.songs.filter { it.folder == key }; title = key }
            "recent" -> { list = Repo.songs.take(40); title = "Recently added" }
            "downloads" -> { list = Repo.songs.filter { it.path.contains("/Download", true) }; title = "Downloads" }
            "genre" -> { list = genre ?: emptyList(); title = "Genre" }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            IconButton({ nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, null) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Art(list.firstOrNull()?.art, 180)
                Spacer(Modifier.height(12.dp))
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center)
                Text("${list.size} Tracks · ${total(list.sumOf { it.dur })}", color = T2, fontSize = 13.sp)
            }
        }
        item { PlayShuffle({ Hub.play(list, 0, title) }, { Hub.play(list, 0, title, true) }) }
        items(list.size) { i -> SongRow(list[i]) { Hub.play(list, i, title) } }
    }
}

@Composable fun PlScreen(nav: NavController, name: String) {
    val liked = name == "__liked__"
    val title = if (liked) "Liked songs" else name
    val ids = if (liked) Store.likes else (Store.pl(name)?.ids ?: mutableStateListOf())
    val list = ids.mapNotNull { Repo.byId(it) }
    var ren by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            IconButton({ nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, null) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)).background(S1), contentAlignment = Alignment.Center) {
                    Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.QueueMusic, null, Modifier.size(72.dp), tint = Color.White)
                }
                Spacer(Modifier.height(12.dp))
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Playlist · ${list.size} Tracks · ${total(list.sumOf { it.dur })}", color = T2, fontSize = 13.sp)
            }
        }
        item { PlayShuffle({ Hub.play(list, 0, title) }, { Hub.play(list, 0, title, true) }) }
        if (!liked) item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ pick = true }) { Text("Добавить") }
                TextButton({ ren = true }) { Text("Переименовать") }
                TextButton({ Store.delete(name); nav.popBackStack() }) { Text("Удалить", color = Color(0xFFE0605E)) }
            }
        }
        items(list.size) { i ->
            SongRow(list[i], { Hub.play(list, i, title) }) { if (liked) Store.like(list[i].id) else Store.rem(name, list[i].id) }
        }
    }
    if (pick) PickDlg({ pick = false }) { sel -> Store.add(name, sel); pick = false }
    if (ren) NameDlg("Переименовать", { ren = false }) { n -> Store.pl(name)?.let { it.name = n; Store.persist() }; ren = false }
}
@Composable fun PickDlg(close: () -> Unit, ok: (List<Long>) -> Unit) {
    val sel = remember { mutableStateListOf<Long>() }
    AlertDialog(onDismissRequest = close, title = { Text("Добавить треки") }, text = {
        LazyColumn(Modifier.height(400.dp)) {
            items(Repo.songs.size) { i ->
                val s = Repo.songs[i]
                Row(Modifier.fillMaxWidth().clickable { if (!sel.remove(s.id)) sel.add(s.id) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(sel.contains(s.id), { if (!sel.remove(s.id)) sel.add(s.id) })
                    Spacer(Modifier.width(8.dp))
                    Column { Text(s.title, maxLines = 1, fontSize = 14.sp); Text(s.artist, maxLines = 1, fontSize = 12.sp, color = T2) }
                }
            }
        }
    }, confirmButton = { TextButton({ ok(sel.toList()) }) { Text("Добавить (${sel.size})") } },
       dismissButton = { TextButton(close) { Text("Отмена") } })
}

/* ---------- ЭКРАН ПЛЕЕРА ---------- */

@Composable fun NowScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val s = Hub.cur.value
    var sheet by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var addDlg by remember { mutableStateOf(false) }
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var vol by remember { mutableStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    val lrc by produceState<Lrc?>(Lrc.LOADING, s?.id) { value = loadLrc(ctx, s) }

    Box(Modifier.fillMaxSize().background(BG)) {
        AsyncImage(s?.art, null, Modifier.matchParentSize().scale(1.4f).blur(60.dp), contentScale = ContentScale.Crop)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x55000000), Color(0xCC000000), Color(0xF5000000)))))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(back) { Icon(Icons.Rounded.KeyboardArrowDown, null) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PLAYING FROM", fontSize = 10.sp, color = T2, letterSpacing = 1.sp)
                    Text(Hub.qname.value, fontSize = 13.sp, maxLines = 1)
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, null) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("В плейлист…") }, { menu = false; addDlg = true })
                        DropdownMenuItem({ Text("В очередь") }, { menu = false; s?.let { Hub.enqueue(it) } })
                    }
                }
            }
            if (s == null) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text("Ничего не играет", color = T2) }
            } else {
                Spacer(Modifier.height(8.dp))
                ArtBig(s.art, Modifier.fillMaxWidth(0.75f).align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(s.artist, fontSize = 14.sp, color = T2, maxLines = 1)
                    }
                    IconButton({ Store.like(s.id) }) {
                        Icon(if (Store.liked(s.id)) Icons.Rounded.Star else Icons.Rounded.StarBorder, null,
                            tint = if (Store.liked(s.id)) Color.White else T2)
                    }
                }
                Spacer(Modifier.height(10.dp))
                val line = lrc?.synced?.lastOrNull { it.t <= Hub.pos.value }?.text
                Text(line ?: if (lrc === Lrc.LOADING) "Checking the lyric sheet" else "Lyrics not available",
                    fontSize = 13.sp, color = T2, maxLines = 1, modifier = Modifier.clickable { sheet = "lrc" })
                Spacer(Modifier.height(6.dp))
                Slider(Hub.pos.value.toFloat(), { Hub.pos.value = it.toLong(); Hub.player.seekTo(it.toLong()) },
                    valueRange = 0f..(Hub.dur.value.coerceAtLeast(1)).toFloat())
                Row {
                    Text(fmt(Hub.pos.value), fontSize = 11.sp, color = T2)
                    Spacer(Modifier.weight(1f))
                    Text("-" + fmt(Hub.dur.value - Hub.pos.value), fontSize = 11.sp, color = T2)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ Hub.prev() }) { Icon(Icons.Rounded.SkipPrevious, null, Modifier.size(38.dp)) }
                    IconButton({ Hub.toggle() }) { Icon(if (Hub.playing.value) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(58.dp)) }
                    IconButton({ Hub.next() }) { Icon(Icons.Rounded.SkipNext, null, Modifier.size(38.dp)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.VolumeDown, null, Modifier.size(18.dp), tint = T2)
                    Slider(vol.toFloat(), { vol = it.toInt(); am.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0) },
                        valueRange = 0f..maxVol.toFloat().coerceAtLeast(1f), modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.VolumeUp, null, Modifier.size(18.dp), tint = T2)
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ sheet = "lrc" }) { Icon(Icons.Rounded.FormatQuote, null) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton({ sheet = "out" }) { Icon(Icons.Rounded.Headset, null) }
                        Text(outName(ctx), fontSize = 11.sp, color = T2)
                    }
                    IconButton({ sheet = "q" }) { Icon(Icons.Rounded.QueueMusic, null) }
                }
            }
        }
    }
    when (sheet) {
        "q" -> QueueSheet { sheet = "" }
        "lrc" -> LrcSheet(s, lrc) { sheet = "" }
        "out" -> OutSheet { sheet = "" }
    }
    if (addDlg && s != null) AddDlg(listOf(s.id)) { addDlg = false }
}

fun outName(ctx: Context): String {
    Hub.dev.value?.let { return it.productName.toString() }
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val d = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
    return d?.productName?.toString() ?: "This phone"
}

@Composable fun QueueSheet(close: () -> Unit) {
    val ver = Hub.qver.value
    val p = Hub.player
    val shuffle = Hub.shuf.value
    val order = remember(ver, shuffle) {
        val t = p.currentTimeline; val res = ArrayList<Int>()
        if (!t.isEmpty) {
            var i = t.getNextWindowIndex(p.currentMediaItemIndex, Player.REPEAT_MODE_OFF, shuffle)
            while (i != C.INDEX_UNSET && res.size < 500) { res.add(i); i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle) }
        }
        res
    }
    val rowH = with(LocalDensity.current) { 64.dp.toPx() }
    ModalBottomSheet(onDismissRequest = close, containerColor = S1) {
        Column(Modifier.fillMaxWidth().height(560.dp).padding(horizontal = 16.dp)) {
            Text("Queue", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Now playing", fontSize = 12.sp, color = T2)
            Hub.cur.value?.let { QRow(it, null, null) {} }
            Spacer(Modifier.height(8.dp))
            Text("Next from: ${Hub.qname.value}", fontSize = 12.sp, color = T2)
            LazyColumn(Modifier.weight(1f)) {
                items(order.size) { i ->
                    val mi = order[i]
                    val s = p.getMediaItemAt(mi).mediaId.toLongOrNull()?.let { Repo.byId(it) } ?: return@items
                    var dy by remember(mi) { mutableStateOf(0f) }
                    QRow(s, Modifier.pointerInput(mi, order.size) {
                        detectDragGesturesAfterLongPress(onDragEnd = { dy = 0f }, onDragCancel = { dy = 0f }) { ch, a ->
                            ch.consume(); dy += a.y
                            val steps = (dy / rowH).toInt()
                            if (steps != 0) {
                                val to = order[(i + steps).coerceIn(0, order.size - 1)]
                                p.moveMediaItem(mi, to); dy -= steps * rowH
                            }
                        }
                    }, { p.removeMediaItem(mi) }) { p.seekTo(mi, 0); p.play() }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton({ p.shuffleModeEnabled = !shuffle }) { Icon(Icons.Rounded.Shuffle, null, tint = if (shuffle) Color.White else T2) }
                IconButton({ p.repeatMode = when (p.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF } }) {
                    Icon(if (p.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, null,
                        tint = if (p.repeatMode != Player.REPEAT_MODE_OFF) Color.White else T2)
                }
                IconButton({ Hub.auto.value = !Hub.auto.value }) { Icon(Icons.Rounded.AllInclusive, null, tint = if (Hub.auto.value) Color.White else T2) }
            }
            Text("AutoPlay — похожая музыка продолжит играть", fontSize = 11.sp, color = T2, modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}
@Composable fun QRow(s: Song, drag: Modifier?, remove: (() -> Unit)?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        if (drag != null) Icon(Icons.Rounded.DragHandle, null, Modifier.size(22.dp).then(drag), tint = T2)
        Spacer(Modifier.width(8.dp)); Art(s.art, 40); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { Text(s.title, maxLines = 1, fontSize = 14.sp); Text(s.artist, maxLines = 1, fontSize = 12.sp, color = T2) }
        if (remove != null) IconButton(remove) { Icon(Icons.Rounded.Close, null, Modifier.size(18.dp), tint = T2) }
    }
}

@Composable fun OutSheet(close: () -> Unit) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val devs = remember { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
        it.isSink && it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && it.type != AudioDeviceInfo.TYPE_TELEPHONY } }
    var vol by remember { mutableStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    val f = Hub.player.audioFormat
    ModalBottomSheet(onDismissRequest = close, containerColor = S1) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Audio output", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            OutRow(Icons.Rounded.Phone, "This phone", null, Hub.dev.value == null) { Hub.device(null) }
            devs.forEach { d ->
                OutRow(Icons.Rounded.Headset, d.productName.toString(),
                    if (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) "Bluetooth" else "Устройство",
                    Hub.dev.value?.id == d.id) { Hub.device(d) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Icon(Icons.Rounded.VolumeDown, null, Modifier.size(18.dp), tint = T2)
                Slider(vol.toFloat(), { vol = it.toInt(); am.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0) },
                    valueRange = 0f..max.toFloat().coerceAtLeast(1f), modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.VolumeUp, null, Modifier.size(18.dp), tint = T2)
            }
            val sub = if (f != null && f.sampleRate > 0) "PCM · ${f.sampleRate / 1000.0} kHz" else "16-bit PCM · 44.1 kHz"
            OutRow(Icons.Rounded.GraphicEq, "Audio Pipeline", sub, false) {}
            Spacer(Modifier.height(24.dp))
        }
    }
}
@Composable fun OutRow(ic: ImageVector, t: String, sub: String?, sel: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (sel) S2 else Color.Transparent)
        .clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ic, null, tint = Color.White); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(t, fontSize = 15.sp); if (sub != null) Text(sub, fontSize = 12.sp, color = T2) }
        if (sel) Icon(Icons.Rounded.Check, null, tint = Color.White)
    }
}

/* ---------- ТЕКСТЫ .LRC ---------- */

data class LrcLine(val t: Long, val text: String)
class Lrc(val synced: List<LrcLine>?, val plain: String?) { companion object { val LOADING = Lrc(null, null) } }

suspend fun loadLrc(ctx: Context, s: Song?): Lrc? = withContext(Dispatchers.IO) {
    if (s == null) return@withContext null
    val txt = runCatching {
        val u = Store.lrcUri(s.id)
        if (u != null) ctx.contentResolver.openInputStream(Uri.parse(u))!!.bufferedReader().readText()
        else { val f = File(s.path.substringBeforeLast('.') + ".lrc"); if (f.canRead()) f.readText() else null }
    }.getOrNull() ?: return@withContext null
    val re = Regex("\\[(\\d{1,2}):(\\d{2}(?:\\.\\d{1,3})?)]")
    val lines = ArrayList<LrcLine>(); val plain = StringBuilder()
    txt.lines().forEach { l ->
        val ms = re.findAll(l).toList(); val t = l.replace(re, "").trim()
        if (ms.isEmpty()) { if (t.isNotBlank() && !t.startsWith("[")) plain.appendLine(t) }
        else ms.forEach { m -> lines.add(LrcLine(m.groupValues[1].toLong() * 60000 + (m.groupValues[2].toDouble() * 1000).toLong(), t)) }
    }
    Lrc(lines.sortedBy { it.t }.takeIf { it.isNotEmpty() }, plain.toString().trim().takeIf { it.isNotBlank() })
}

@Composable fun LrcSheet(s: Song?, lrc: Lrc?, close: () -> Unit) {
    val ctx = LocalContext.current
    var reload by remember { mutableStateOf(0) }
    val data by produceState<Lrc?>(lrc, s?.id, reload) { value = loadLrc(ctx, s) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null && s != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Store.setLrc(s.id, u.toString()); reload++
        }
    }
    val ls = rememberLazyListState()
    val idx = data?.synced?.indexOfLast { it.t <= Hub.pos.value } ?: -1
    LaunchedEffect(idx) { if (idx >= 0) ls.animateScrollToItem(idx) }
    ModalBottomSheet(onDismissRequest = close, containerColor = S1) {
        Column(Modifier.fillMaxWidth().height(420.dp).padding(16.dp)) {
            Text(s?.title ?: "", fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            when {
                data?.synced != null -> LazyColumn(state = ls, modifier = Modifier.weight(1f)) {
                    items(data!!.synced!!.size) { i ->
                        val l = data!!.synced!![i]
                        Text(l.text.ifBlank { "…" }, fontSize = if (i == idx) 20.sp else 16.sp,
                            fontWeight = if (i == idx) FontWeight.Bold else FontWeight.Normal,
                            color = if (i == idx) Color.White else T2, modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
                data?.plain != null -> Text(data!!.plain!!, fontSize = 16.sp,
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()))
                else -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No lyrics for this track", color = T2)
                    Spacer(Modifier.height(12.dp))
                    TextButton({ pick.launch(arrayOf("*/*")) }) { Text("Добавить .lrc файл") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
