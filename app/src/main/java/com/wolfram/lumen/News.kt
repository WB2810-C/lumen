package com.wolfram.lumen

import android.util.Xml
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/* ---------- VRT NWS (official Atom feed: headlines and summaries only, no ads) ---------- */

@Immutable
data class NewsItem(
    val title: String,
    val summary: String,
    val category: String,
    val published: Long,
    val image: String?,
)

@Immutable
data class NewsState(val items: List<NewsItem> = emptyList(), val updatedAt: Long = 0L, val error: Throwable? = null)

suspend fun fetchVrtNews(): List<NewsItem> {
    val xml = Net.get(LumenConfig.NEWS_FEED_URL)
    val parser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(StringReader(xml))
    }
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val items = mutableListOf<NewsItem>()
    var inEntry = false
    var title = ""
    var summary = ""
    var category = ""
    var published = 0L
    var image: String? = null
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "entry" -> {
                    inEntry = true
                    title = ""; summary = ""; category = ""; published = 0L; image = null
                }
                "title" -> if (inEntry) title = parser.nextText().trim()
                "summary" -> if (inEntry) summary = parser.nextText().trim()
                "nstag" -> if (inEntry) category = parser.nextText().trim()
                "published" -> if (inEntry) published = runCatching { iso.parse(parser.nextText().take(19))?.time ?: 0L }.getOrDefault(0L)
                "link" -> if (inEntry && parser.getAttributeValue(null, "rel") == "enclosure") {
                    image = parser.getAttributeValue(null, "href")
                }
            }
            XmlPullParser.END_TAG -> if (parser.name == "entry" && inEntry) {
                inEntry = false
                if (title.isNotBlank()) items.add(NewsItem(title, summary.stripHtml(), category, published, image))
            }
        }
        event = parser.next()
    }
    return items.sortedByDescending { it.published }.take(30)
}

private fun String.stripHtml(): String =
    replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()

fun clockTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

/* ---------- The headline strip on the home screen ---------- */

@Composable
fun NewsStrip(news: NewsState, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalLumenTheme.current
    if (news.items.isEmpty()) {
        // Say why the headlines aren't there, instead of leaving an empty gap.
        val error = news.error ?: return
        Text(
            text = stringResource(R.string.news_failed, netReason(LocalContext.current, error)),
            color = theme.content.copy(alpha = 0.6f),
            fontSize = 9.sp,
            modifier = modifier.padding(vertical = 8.dp),
        )
        return
    }
    val shown = news.items.take(5)
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(shown) {
        while (true) {
            delay(8_000)
            index = (index + 1) % shown.size
        }
    }
    var focused by remember { mutableStateOf(false) }
    val item = shown[index.coerceIn(0, shown.lastIndex)]
    Row(
        modifier = modifier
            .height(30.dp)
            .glass(RoundedCornerShape(50), fill = if (focused) theme.glassFill + 0.25f else theme.glassFill, edge = if (focused) theme.ring else theme.glassEdge)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.play(Sound.FOCUS)
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                Sounds.play(Sound.SELECT)
                onOpen(index)
            }
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.news_source),
            color = theme.onContent,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(theme.content)
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(clockTime(item.published), color = theme.content.copy(alpha = 0.7f), fontSize = 9.sp)
        Spacer(Modifier.width(8.dp))
        AnimatedContent(
            targetState = item,
            transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
            label = "headline",
            modifier = Modifier.weight(1f),
        ) { shownItem ->
            Text(
                text = shownItem.title,
                color = theme.content,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.padding(horizontal = 7.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            shown.indices.forEach { i ->
                Box(
                    Modifier
                        .height(3.5.dp)
                        .width(if (i == index) 9.dp else 3.5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(theme.content.copy(alpha = if (i == index) 0.9f else 0.3f)),
                )
            }
        }
    }
}

/* ---------- The News page ---------- */

@Composable
fun NewsPage(news: NewsState, startAt: Int?) {
    val theme = LocalLumenTheme.current
    var open by remember(startAt) { mutableStateOf(startAt) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(news.items.isNotEmpty(), open) {
        if (open == null && news.items.isNotEmpty()) {
            delay(60)
            runCatching { firstFocus.requestFocus() }
        }
    }
    androidx.activity.compose.BackHandler(enabled = open != null) {
        Sounds.play(Sound.BACK)
        open = null
    }

    val detail = open?.let { news.items.getOrNull(it) }
    if (detail != null) {
        NewsDetail(detail)
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 10.dp, end = 34.dp, top = 24.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.news_source), color = theme.content, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = when {
                        news.updatedAt > 0 -> stringResource(R.string.news_updated, clockTime(news.updatedAt))
                        news.error != null -> stringResource(R.string.news_failed, netReason(LocalContext.current, news.error))
                        else -> stringResource(R.string.news_loading)
                    },
                    color = theme.content.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
        }
        itemsIndexed(news.items, key = { i, item -> "$i-${item.title}" }) { i, item ->
            NewsCard(
                item = item,
                big = i < 3,
                onClick = { open = i },
                modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}

@Composable
private fun NewsCard(item: NewsItem, big: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalLumenTheme.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(150), label = "newsScale")
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (focused) theme.tileFillFocused else theme.tileFill)
            .border(if (focused) 2.dp else 1.dp, if (focused) theme.ring else theme.line, shape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.play(Sound.FOCUS)
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                Sounds.play(Sound.SELECT)
                onClick()
            },
    ) {
        if (big) {
            val image by rememberRemoteImage(item.image)
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Brush.linearGradient(listOf(theme.main, theme.third))),
            ) {
                image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
        Column(Modifier.padding(12.dp)) {
            if (item.category.isNotBlank()) {
                Text(item.category.uppercase(), color = theme.content.copy(alpha = 0.65f), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = item.title,
                color = theme.content,
                fontSize = if (big) 14.sp else 12.sp,
                lineHeight = if (big) 18.sp else 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(clockTime(item.published), color = theme.content.copy(alpha = 0.6f), fontSize = 9.sp)
        }
    }
}

/** One article: photo, headline and VRT's summary, right inside Lumen. */
@Composable
private fun NewsDetail(item: NewsItem) {
    val theme = LocalLumenTheme.current
    val image by rememberRemoteImage(item.image, maxSide = 1280)
    val scroll = rememberScrollState()
    Row(
        Modifier
            .fillMaxSize()
            .padding(top = 24.dp, end = 34.dp, bottom = 24.dp, start = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(theme.main, theme.third))),
        ) {
            image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scroll),
        ) {
            Text("${item.category.uppercase()} · ${clockTime(item.published)}", color = theme.content.copy(alpha = 0.65f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            Text(item.title, color = theme.content, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Text(item.summary, color = theme.content.copy(alpha = 0.88f), fontSize = 15.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.news_back), color = theme.content.copy(alpha = 0.6f), fontSize = 10.sp)
        }
    }
}
