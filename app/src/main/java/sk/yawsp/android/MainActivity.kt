package sk.yawsp.android

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import sk.yawsp.android.model.*
import sk.yawsp.android.network.WebshareRepository

val BgDark = Color(0xFF0E1521)
val CardDark = Color(0xFF172131)
val CardBorder = Color(0xFF283548)
val CyanLogo = Color(0xFF00C2FF)
val VipGreen = Color(0xFF4CAF50)
val BtnBlue = Color(0xFF2E6FD8)
val BtnTeal = Color(0xFF178D86)
val DownloadSeasonGreen = Color(0xFF2E8B57)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = BgDark) {
                    YawspApp()
                }
            }
        }
    }
}

sealed class Screen {
    object Home : Screen()
    data class SearchResults(val query: String, val sort: String = "", val category: String = "video") : Screen()
    data class Player(val title: String, val streamUrl: String, val subtitleUrls: List<String>) : Screen()
    object Bookmarks : Screen()
    object Settings : Screen()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YawspApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("yawsp_prefs", Context.MODE_PRIVATE) }
    val repo = remember {
        WebshareRepository().apply {
            setToken(prefs.getString("token", "") ?: "")
        }
    }
    val scope = rememberCoroutineScope()

    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    val screenStack = remember { mutableStateListOf<Screen>() }

    var userStatus by remember { mutableStateOf(UserStatus()) }
    var searchText by remember { mutableStateOf("") }
    var bookmarks by remember { mutableStateOf<List<WebshareFile>>(emptyList()) }

    var showSearchResults by remember { mutableStateOf<List<TvShow>>(emptyList()) }
    var selectedShow by remember { mutableStateOf<TvShow?>(null) }
    var selectedShowSeasons by remember { mutableStateOf<List<TvSeason>>(emptyList()) }
    var isLoadingShow by remember { mutableStateOf(false) }
    var showErrorMsg by remember { mutableStateOf<String?>(null) }
    var archiveChooserSeason by remember { mutableStateOf<TvSeason?>(null) }

    fun navigateTo(screen: Screen) {
        screenStack.add(currentScreen)
        currentScreen = screen
    }

    fun navigateBack() {
        if (screenStack.isNotEmpty()) {
            currentScreen = screenStack.removeAt(screenStack.lastIndex)
        }
    }

    BackHandler(enabled = screenStack.isNotEmpty()) {
        navigateBack()
    }

    LaunchedEffect(Unit) {
        val savedUser = prefs.getString("username", "") ?: ""
        if (repo.getToken().isNotBlank()) {
            userStatus = repo.checkUserStatus(savedUser)
        }
    }

    fun enqueueAndroidDownload(url: String, filename: String, subFolder: String? = null) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(filename)
                .setDescription("YAWsP Webshare sťahovanie")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .addRequestHeader("Cookie", "wst=${repo.getToken()}")
            val destPath = if (subFolder != null) "YAWsP/$subFolder/$filename" else "YAWsP/$filename"
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, destPath)
            dm.enqueue(req)
            Toast.makeText(context, "Sťahovanie spustené: $filename", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Chyba sťahovania: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun playFile(file: WebshareFile) {
        if (file.isArchive) {
            Toast.makeText(context, "Archív RAR/ZIP nie je možné prehrať priamo, klikni na Stiahnuť.", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            Toast.makeText(context, "Načítavam stream a titulky...", Toast.LENGTH_SHORT).show()
            val link = repo.getFileLink(file.ident, "video_stream")
            if (link != null) {
                val subs = repo.findSubtitlesForVideo(file.name)
                navigateTo(Screen.Player(file.name, link, subs))
            } else {
                Toast.makeText(context, "Súbor nie je dostupný alebo vypršalo prihlásenie", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun downloadFile(file: WebshareFile, subFolder: String? = null) {
        scope.launch {
            val link = repo.getFileLink(file.ident, "file_download")
            if (link != null) {
                enqueueAndroidDownload(link, file.name, subFolder)
            } else {
                Toast.makeText(context, "Nepodarilo sa získať odkaz na stiahnutie", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (archiveChooserSeason != null && selectedShow != null) {
        val season = archiveChooserSeason!!
        AlertDialog(
            onDismissRequest = { archiveChooserSeason = null },
            containerColor = CardDark,
            title = {
                Text(
                    "Vyber RAR archív – Séria ${"%02d".format(season.number)}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(season.archiveFiles) { arch ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = BgDark),
                            border = BorderStroke(1.dp, CardBorder),
                            modifier = Modifier.fillMaxWidth().clickable {
                                downloadFile(arch, selectedShow!!.name)
                                archiveChooserSeason = null
                            }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(arch.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${arch.formattedSize}  •  ${arch.badges.joinToString(" ")}",
                                    color = CyanLogo,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { archiveChooserSeason = null }) {
                    Text("Zrušiť", color = Color(0xFF94A3B8))
                }
            }
        )
    }

    when (val screen = currentScreen) {
        is Screen.Player -> {
            ExoPlayerScreen(
                title = screen.title,
                streamUrl = screen.streamUrl,
                subtitleUrls = screen.subtitleUrls,
                onBack = { navigateBack() }
            )
        }

        else -> {
            Scaffold(
                containerColor = BgDark,
                topBar = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgDark)
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (screenStack.isNotEmpty()) {
                                    IconButton(
                                        onClick = { navigateBack() },
                                        modifier = Modifier.size(32.dp).padding(end = 6.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Späť", tint = Color.White)
                                    }
                                }
                                Text(
                                    text = "YAWsP",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = CyanLogo
                                )
                                Spacer(Modifier.width(10.dp))
                                if (userStatus.isLoggedIn) {
                                    val badgeText = if (userStatus.isVip) "VIP ${userStatus.vipDays}d" else "FREE"
                                    val badgeColor = if (userStatus.isVip) VipGreen else Color(0xFFF59E0B)
                                    Text(
                                        text = badgeText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier
                                            .background(badgeColor, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { navigateTo(Screen.Bookmarks) },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Favorite,
                                            contentDescription = "Záložky",
                                            tint = Color(0xFFE53935),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    IconButton(
                                        onClick = { navigateTo(Screen.Settings) },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Settings,
                                            contentDescription = "Nastavenia",
                                            tint = Color(0xFFCBD5E1),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "v0.5.1",
                                    fontSize = 10.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    when (screen) {
                        is Screen.Home -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (!userStatus.isLoggedIn) {
                                    item {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)),
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                navigateTo(Screen.Settings)
                                            }
                                        ) {
                                            Text(
                                                "Nie ste prihlásený do Webshare. Ťuknite sem pre prihlásenie.",
                                                modifier = Modifier.padding(12.dp),
                                                color = Color.White,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }

                                item {
                                    Column {
                                        OutlinedTextField(
                                            value = searchText,
                                            onValueChange = { searchText = it },
                                            placeholder = { Text("Názov filmu alebo seriálu...", color = Color(0xFF64748B)) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(10.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = CyanLogo,
                                                unfocusedBorderColor = CardBorder,
                                                focusedContainerColor = CardDark,
                                                unfocusedContainerColor = CardDark,
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White
                                            ),
                                            modifier = Modifier.fillMaxWidth(),
                                            trailingIcon = {
                                                if (searchText.isNotEmpty()) {
                                                    IconButton(onClick = {
                                                        searchText = ""
                                                        selectedShow = null
                                                        showSearchResults = emptyList()
                                                        showErrorMsg = null
                                                    }) {
                                                        Icon(Icons.Default.Close, contentDescription = "Vymazať", tint = Color(0xFF94A3B8))
                                                    }
                                                }
                                            }
                                        )

                                        Spacer(Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    if (searchText.isNotBlank()) {
                                                        navigateTo(Screen.SearchResults(searchText.trim(), category = "video"))
                                                    } else {
                                                        Toast.makeText(context, "Zadaj názov filmu alebo epizódy", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = BtnBlue),
                                                modifier = Modifier.weight(1f).height(44.dp)
                                            ) {
                                                Text("Hľadať súbory", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            }

                                            Button(
                                                onClick = {
                                                    val q = searchText.trim()
                                                    if (q.isEmpty()) {
                                                        Toast.makeText(context, "Zadaj názov seriálu", Toast.LENGTH_SHORT).show()
                                                        return@Button
                                                    }
                                                    scope.launch {
                                                        isLoadingShow = true
                                                        showErrorMsg = null
                                                        selectedShow = null
                                                        selectedShowSeasons = emptyList()
                                                        val found = repo.searchTvShows(q)
                                                        showSearchResults = found
                                                        if (found.isNotEmpty()) {
                                                            val first = found.first()
                                                            selectedShow = first
                                                            selectedShowSeasons = repo.getShowSeasonsWithArchives(first.id, first.name)
                                                        } else {
                                                            showErrorMsg = "Seriál \"$q\" sa nenašiel. Skús originálny názov."
                                                        }
                                                        isLoadingShow = false
                                                    }
                                                },
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = BtnTeal),
                                                modifier = Modifier.weight(1f).height(44.dp)
                                            ) {
                                                Text("Seriály (Série)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            }
                                        }
                                    }
                                }

                                if (isLoadingShow) {
                                    item {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                CircularProgressIndicator(color = CyanLogo)
                                                Spacer(Modifier.height(8.dp))
                                                Text("Hľadám kompletné série v RAR...", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }

                                if (showErrorMsg != null) {
                                    item {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = CardDark),
                                            border = BorderStroke(1.dp, CardBorder),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(14.dp)) {
                                                Text(showErrorMsg!!, color = Color(0xFFCBD5E1), fontSize = 13.sp)
                                                Spacer(Modifier.height(8.dp))
                                                Button(
                                                    onClick = { navigateTo(Screen.SearchResults(searchText.trim(), category = "archives")) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = BtnBlue)
                                                ) {
                                                    Text("Hľadať RAR archívy priamo na Webshare")
                                                }
                                            }
                                        }
                                    }
                                }

                                if (selectedShow != null) {
                                    val show = selectedShow!!
                                    item {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = CardDark),
                                            border = BorderStroke(1.dp, CardBorder),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(modifier = Modifier.padding(12.dp)) {
                                                if (show.imageUrl != null) {
                                                    AsyncImage(
                                                        model = show.imageUrl,
                                                        contentDescription = show.name,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier
                                                            .width(96.dp)
                                                            .height(136.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                    )
                                                    Spacer(Modifier.width(12.dp))
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = show.name,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 18.sp,
                                                        color = Color.White
                                                    )
                                                    Spacer(Modifier.height(4.dp))
                                                    Text(
                                                        text = "${show.year} • ${show.seasonsCount} sérií (${show.totalEpisodes} ep.)",
                                                        color = CyanLogo,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontSize = 13.sp
                                                    )
                                                    Spacer(Modifier.height(6.dp))
                                                    Text(
                                                        text = show.summary,
                                                        maxLines = 4,
                                                        overflow = TextOverflow.Ellipsis,
                                                        fontSize = 12.sp,
                                                        lineHeight = 16.sp,
                                                        color = Color(0xFFCBD5E1)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (showSearchResults.size > 1) {
                                        item {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                showSearchResults.take(3).forEach { alt ->
                                                    FilterChip(
                                                        selected = alt.id == show.id,
                                                        onClick = {
                                                            scope.launch {
                                                                isLoadingShow = true
                                                                selectedShow = alt
                                                                selectedShowSeasons = repo.getShowSeasonsWithArchives(alt.id, alt.name)
                                                                isLoadingShow = false
                                                            }
                                                        },
                                                        label = { Text("${alt.name} (${alt.year})", maxLines = 1) }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (selectedShowSeasons.isNotEmpty()) {
                                        item {
                                            Text(
                                                text = "Kompletné série (RAR / ZIP archívy)",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 17.sp,
                                                color = Color.White,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }

                                        items(selectedShowSeasons) { season ->
                                            val hasRar = season.archiveFiles.isNotEmpty()
                                            val bestRar = season.archiveFiles.firstOrNull()

                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = CardDark),
                                                border = BorderStroke(1.dp, CardBorder),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                        Text(
                                                            text = "Séria ${"%02d".format(season.number)} (${season.episodeOrder} epizód)",
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 15.sp,
                                                            color = Color.White
                                                        )
                                                        Spacer(Modifier.height(3.dp))
                                                        if (hasRar && bestRar != null) {
                                                            Text(
                                                                text = "RAR: ${bestRar.name} (${bestRar.formattedSize})",
                                                                fontSize = 11.sp,
                                                                maxLines = 2,
                                                                overflow = TextOverflow.Ellipsis,
                                                                color = CyanLogo
                                                            )
                                                            if (season.archiveFiles.size > 1) {
                                                                Text(
                                                                    text = "+ ďalších ${season.archiveFiles.size - 1} RAR verzií",
                                                                    fontSize = 11.sp,
                                                                    color = Color(0xFF94A3B8)
                                                                )
                                                            }
                                                        } else {
                                                            Text(
                                                                text = "Kompletný RAR archív sa nenašiel",
                                                                fontSize = 12.sp,
                                                                color = Color(0xFF64748B)
                                                            )
                                                        }
                                                    }

                                                    if (hasRar && bestRar != null) {
                                                        Button(
                                                            onClick = {
                                                                if (season.archiveFiles.size == 1) {
                                                                    downloadFile(bestRar, show.name)
                                                                } else {
                                                                    archiveChooserSeason = season
                                                                }
                                                            },
                                                            shape = RoundedCornerShape(8.dp),
                                                            colors = ButtonDefaults.buttonColors(containerColor = DownloadSeasonGreen),
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                                        ) {
                                                            Text(
                                                                text = "Stiahnuť komplet\ncelú sériu (RAR)",
                                                                fontSize = 11.sp,
                                                                lineHeight = 13.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = Color.White
                                                            )
                                                        }
                                                    } else {
                                                        OutlinedButton(
                                                            onClick = {
                                                                navigateTo(
                                                                    Screen.SearchResults(
                                                                        "${show.name} S${"%02d".format(season.number)}",
                                                                        category = "archives"
                                                                    )
                                                                )
                                                            },
                                                            shape = RoundedCornerShape(8.dp),
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                                        ) {
                                                            Text("Hľadať RAR", fontSize = 11.sp, color = Color(0xFFCBD5E1))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else if (!isLoadingShow) {
                                    item {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "Rýchle kategórie",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 15.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                    item {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = CardDark),
                                            border = BorderStroke(1.dp, CardBorder),
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                navigateTo(Screen.SearchResults("", sort = "recent", category = "video"))
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(14.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.NewReleases, contentDescription = null, tint = CyanLogo)
                                                Spacer(Modifier.width(12.dp))
                                                Text("Najnovšie videá na Webshare", color = Color.White, fontWeight = FontWeight.Medium)
                                            }
                                        }
                                    }
                                    item {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = CardDark),
                                            border = BorderStroke(1.dp, CardBorder),
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                navigateTo(Screen.SearchResults("", sort = "largest", category = "video"))
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(14.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.HighQuality, contentDescription = null, tint = Color(0xFFA855F7))
                                                Spacer(Modifier.width(12.dp))
                                                Text("Najväčšie súbory (4K / BluRay)", color = Color.White, fontWeight = FontWeight.Medium)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        is Screen.SearchResults -> {
                            var files by remember { mutableStateOf<List<WebshareFile>>(emptyList()) }
                            var loading by remember { mutableStateOf(true) }
                            var currentSort by remember { mutableStateOf(screen.sort) }

                            LaunchedEffect(screen.query, currentSort, screen.category) {
                                loading = true
                                files = repo.searchFiles(screen.query, category = screen.category, sort = currentSort)
                                loading = false
                            }

                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = currentSort == "",
                                        onClick = { currentSort = "" },
                                        label = { Text("Predvolené") }
                                    )
                                    FilterChip(
                                        selected = currentSort == "recent",
                                        onClick = { currentSort = "recent" },
                                        label = { Text("Najnovšie") }
                                    )
                                    FilterChip(
                                        selected = currentSort == "largest",
                                        onClick = { currentSort = "largest" },
                                        label = { Text("Najväčšie") }
                                    )
                                }

                                if (loading) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = CyanLogo)
                                    }
                                } else {
                                    LazyColumn(
                                        contentPadding = PaddingValues(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(files) { file ->
                                            FileItemCard(
                                                file = file,
                                                isBookmarked = bookmarks.any { it.ident == file.ident },
                                                onPlay = { playFile(file) },
                                                onDownload = { downloadFile(file) },
                                                onToggleBookmark = {
                                                    bookmarks = if (bookmarks.any { it.ident == file.ident }) {
                                                        bookmarks.filterNot { it.ident == file.ident }
                                                    } else {
                                                        bookmarks + file
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        is Screen.Bookmarks -> {
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item {
                                    Text("Obľúbené súbory", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                                }
                                items(bookmarks) { file ->
                                    FileItemCard(
                                        file = file,
                                        isBookmarked = true,
                                        onPlay = { playFile(file) },
                                        onDownload = { downloadFile(file) },
                                        onToggleBookmark = {
                                            bookmarks = bookmarks.filterNot { it.ident == file.ident }
                                        }
                                    )
                                }
                            }
                        }

                        is Screen.Settings -> {
                            var userInput by remember { mutableStateOf(prefs.getString("username", "") ?: "") }
                            var passInput by remember { mutableStateOf("") }
                            var statusMsg by remember { mutableStateOf("") }

                            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                                Text("Prihlásenie do Webshare.cz", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = userInput,
                                    onValueChange = { userInput = it },
                                    label = { Text("Používateľské meno alebo e-mail") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = passInput,
                                    onValueChange = { passInput = it },
                                    label = { Text("Heslo") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        scope.launch {
                                            statusMsg = "Prihlasujem..."
                                            val res = repo.login(userInput.trim(), passInput)
                                            res.onSuccess { newToken ->
                                                prefs.edit()
                                                    .putString("username", userInput.trim())
                                                    .putString("token", newToken)
                                                    .apply()
                                                userStatus = repo.checkUserStatus(userInput.trim())
                                                statusMsg = "Úspešne prihlásené!"
                                            }.onFailure { err ->
                                                statusMsg = "Chyba: ${err.message}"
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = BtnBlue),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Prihlásiť sa a uložiť")
                                }
                                if (statusMsg.isNotBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(statusMsg, color = CyanLogo)
                                }
                            }
                        }

                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
fun FileItemCard(
    file: WebshareFile,
    isBookmarked: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onToggleBookmark: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth().clickable { onPlay() }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (file.badges.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    file.badges.forEach { badge ->
                        val bg = when (badge) {
                            "RAR/ZIP" -> Color(0xFFF59E0B)
                            "4K" -> Color(0xFF06B6D4)
                            "1080p" -> Color(0xFF3B82F6)
                            "CZ", "SK", "CZ/SK" -> Color(0xFF22C55E)
                            "TIT" -> Color(0xFFEAB308)
                            "HDR" -> Color(0xFFD946EF)
                            else -> Color(0xFFF97316)
                        }
                        Text(
                            text = badge,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier.background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Text(file.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${file.formattedSize}  •  +${file.positiveVotes}/-${file.negativeVotes}",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
                Row {
                    IconButton(onClick = onToggleBookmark, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (isBookmarked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Záložka",
                            tint = if (isBookmarked) Color(0xFFF43F5E) else Color.Gray
                        )
                    }
                    IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Download, contentDescription = "Stiahnuť", tint = CyanLogo)
                    }
                    if (!file.isArchive) {
                        IconButton(onClick = onPlay, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Prehrať", tint = VipGreen)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExoPlayerScreen(
    title: String,
    streamUrl: String,
    subtitleUrls: List<String>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val subConfigs = subtitleUrls.mapIndexed { idx, subUrl ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage("sk")
                    .setLabel("Webshare Titulky ${idx + 1}")
                    .build()
            }
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(streamUrl))
                .setSubtitleConfigurations(subConfigs)
                .build()
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        IconButton(
            onClick = {
                exoPlayer.release()
                onBack()
            },
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Späť", tint = Color.White)
        }
    }
}
