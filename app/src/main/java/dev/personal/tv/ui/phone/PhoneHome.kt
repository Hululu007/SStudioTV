package dev.personal.tv.ui.phone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.tv.data.Catalog
import dev.personal.tv.data.Progress
import dev.personal.tv.data.Store
import dev.personal.tv.data.Title
import dev.personal.tv.source.Sources
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.MenuItem
import dev.personal.tv.ui.components.Landscape
import dev.personal.tv.ui.screens.resumeTarget
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.launch

/** 海报网格的列：按屏幕宽度自动排，手机竖屏 3 列 */
val PosterCols = GridCells.Adaptive(minSize = 104.dp)

/** 网格里占满一整行的条目（标题、横滑的一排） */
fun LazyGridScope.full(key: Any, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

/** 首页：搜索框、继续观看、想看、豆瓣最近热门。下拉刷新热门 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneHome(goTab: (PTab) -> Unit) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val cw = Store.continueWatching()
    val wish = Store.wish.mapNotNull { Catalog.get(it) }
    val hot = Catalog.hot()
    val grid = rememberLazyGridState()
    PullToRefreshBox(
        isRefreshing = Catalog.loading && hot.isNotEmpty(),
        onRefresh = { scope.launch { Catalog.refreshHot() } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyVerticalGrid(
            PosterCols, state = grid, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = PC.Edge, end = PC.Edge, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            full("top") {
                Column(Modifier.statusBarsPadding().padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tx("SStudioTV", 22.sp, weight = FontWeight.ExtraBold)
                        Spacer(Modifier.weight(1f))
                        Tx(
                            if (Sources.empty) "未添加片源" else Sources.name, 12.sp, if (Sources.empty) Palette.Bad else Palette.T2,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))
                                .clickable { nav.openSources() }.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                    SearchBox(Modifier.padding(top = 12.dp)) { nav.openSearch() }
                }
            }
            if (Sources.empty) full("nosrc") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PC.Card).padding(16.dp)) {
                    Tx("还没有片源", 16.sp, weight = FontWeight.Bold)
                    Tx("SStudioTV 不自带片源。加一个影视仓 / TVBox 配置地址，就能找线路播放。", 12.5.sp, Palette.T2, lines = 3, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
                    BigButton(Modifier.padding(top = 12.dp), primary = true, height = 40.dp, onClick = { nav.openSources() }) { Tx("添加片源", 14.sp, it, FontWeight.SemiBold) }
                }
            }
            if (cw.isNotEmpty()) {
                full("cwh") { Section("继续观看", modifier = Modifier.bleed()) }
                full("cw") {
                    LazyRow(
                        Modifier.bleed(),
                        contentPadding = PaddingValues(horizontal = PC.Edge),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) { items(cw.take(20), key = { it.first.id }) { (t, p) -> ContinueCard(t, p) } }
                }
            }
            if (wish.isNotEmpty()) {
                full("wh") { Section("想看", "${wish.size} 部", Modifier.bleed(), "全部") { goTab(PTab.WISH) } }
                full("w") {
                    LazyRow(
                        Modifier.bleed(),
                        contentPadding = PaddingValues(horizontal = PC.Edge),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(wish.take(20), key = { it.id }) { t ->
                            PosterTile(t, Modifier.width(100.dp), onLong = { nav.posterMenu(t) }) { nav.openDetail(t.id) }
                        }
                    }
                }
            }
            full("hh") {
                Section(
                    "最近热门", when {
                        Catalog.loading && hot.isEmpty() -> "正在从豆瓣拉…"
                        Catalog.error != null && hot.isEmpty() -> Catalog.error
                        else -> "豆瓣 · 下拉刷新"
                    },
                    Modifier.bleed(), "找更多",
                ) { goTab(PTab.FIND) }
            }
            items(hot, key = { "h:" + it.id }) { t ->
                PosterTile(t, onLong = { nav.posterMenu(t) }) { nav.openDetail(t.id) }
            }
        }
    }
}

/** 假的搜索框：点了进搜索页 */
@Composable
fun SearchBox(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, Modifier.size(19.dp), tint = Palette.T3)
        Tx("搜片名、演员、拼音首字母", 14.sp, Palette.T3, modifier = Modifier.padding(start = 8.dp))
    }
}

/** 继续观看：横版剧照 + 进度条，点了直接接着放 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(t: Title, p: Progress) {
    val nav = LocalNav.current
    val newEps = Store.newEpisodes(t, p)
    Column(Modifier.width(220.dp)) {
        Box(
            Modifier.fillMaxWidth().height(124.dp).clip(RoundedCornerShape(10.dp))
                .combinedClickable(
                    onClick = { resumeTarget(t, p)?.let { nav.play(t.id, it.ep, it.name) } ?: nav.openDetail(t.id) },
                    onLongClick = {
                        nav.showMenu(
                            t, listOf(
                                MenuItem("打开详情") { nav.openDetail(t.id) },
                                MenuItem("找类似") { nav.openDetail(t.id, similar = true) },
                                MenuItem("移出继续观看") { Store.progress.remove(t.id); nav.toast("已移出，观看记录不会删除") },
                            ),
                        )
                    },
                ),
        ) {
            Landscape(t, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().drawBehind { drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))) })
            if (newEps > 0) {
                Tx(
                    "更新 $newEps 集", 10.5.sp, Color.White, FontWeight.Bold,
                    modifier = Modifier.padding(6.dp).background(Palette.Bad, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                Tx(
                    if (t.isEpisodic) p.epText(t.name) + if (!p.finishedEp) " · 剩 ${p.remainingMin} 分钟" else " · 看完了" else if (p.finishedEp) "看完了" else "剩 ${p.remainingMin} 分钟",
                    11.sp, Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                )
                ThinProgress(p.fraction)
            }
        }
        Tx(t.name, 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
    }
}

/** 想看 */
@Composable
fun PhoneWish() {
    val nav = LocalNav.current
    val titles = Store.wish.mapNotNull { Catalog.get(it) }
    LazyVerticalGrid(
        PosterCols, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = PC.Edge, end = PC.Edge, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        full("top") {
            Column(Modifier.statusBarsPadding().padding(top = 8.dp)) {
                Tx("想看", 22.sp, weight = FontWeight.ExtraBold)
                Tx("${titles.size} 部 · 只存在本机，不关联豆瓣账号 · 长按海报移出", 12.sp, Palette.T3, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (titles.isEmpty()) full("empty") { Empty("还没有想看的", "在详情页点「想看」，或者长按任何海报选「加入想看」") }
        items(titles, key = { it.id }) { t -> PosterTile(t, onLong = { nav.posterMenu(t) }) { nav.openDetail(t.id) } }
    }
}

/** 占满屏幕的居中提示 */
@Composable
fun CenterNote(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Tx(text, 14.sp, Palette.T3) }
