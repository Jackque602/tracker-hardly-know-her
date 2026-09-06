package dev.jackque.roamed.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jackque.roamed.appContainer
import dev.jackque.roamed.core.regions.RegionProgress
import dev.jackque.roamed.core.regions.RegionTally
import dev.jackque.roamed.core.stats.ExplorationStats
import dev.jackque.roamed.data.db.VisitedPlaceEntity

@Composable
fun StatsScreen() {
    val container = LocalContext.current.appContainer
    val viewModel: StatsViewModel = viewModel(factory = StatsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val summary = state.summary
    val regions = state.regions
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            HeadlineCard(
                percentOfLand = summary.percentOfLand,
                percentOfSurface = summary.percentOfSurface,
                areaSquareMeters = summary.areaSquareMeters,
            )
        }

        item {
            StatGrid(
                entries = buildList {
                    add("Squares uncovered" to summary.cellCount.toString())
                    add("Distance travelled" to ExplorationStats.formatDistance(summary.totalDistanceMeters))
                    add("This year" to ExplorationStats.formatDistance(summary.distanceThisYearMeters))
                    add("Days out and about" to summary.activeDays.toString())
                    if (regions != null) {
                        add("Continents" to outOf(regions.continents.size, regions.continentsInAtlas))
                        add("Countries" to outOf(regions.countries.size, regions.countriesInAtlas))
                        add("States and provinces" to regions.subdivisions.size.toString())
                    } else {
                        add("Countries" to summary.countryCount.toString())
                    }
                    add("Tracking since" to (summary.firstDate ?: "—"))
                },
            )
        }

        if (regions != null) {
            regionSection("Continents", regions.continents)
            regionSection("Countries", regions.countries)
            regionSection("States and provinces", regions.subdivisions)
        }

        if (summary.newCellsPerYear.isNotEmpty()) {
            item {
                SectionCard(title = "New ground each year") {
                    val busiest = summary.newCellsPerYear.maxOf { it.count }.coerceAtLeast(1)
                    summary.newCellsPerYear.forEach { year ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = year.year,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(56.dp),
                            )
                            LinearProgressIndicator(
                                progress = { year.count.toFloat() / busiest },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(10.dp),
                            )
                            Text(
                                text = year.count.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(72.dp),
                            )
                        }
                    }
                }
            }
        }

        if (summary.places.isNotEmpty()) {
            item {
                Text(
                    text = "Where you have been",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(summary.places, key = { it.countryCode + "/" + it.adminArea }) { place ->
                PlaceRow(place)
            }
        }

        item {
            Footnote(summary.rawFixCount, regions)
        }
    }
}

private fun outOf(visited: Int, total: Int): String =
    if (total > 0) "$visited of $total" else visited.toString()

/**
 * One ranked list of regions - continents, countries or states - as its own card.
 *
 * Long lists are cut short until asked, because a well-travelled map can name hundreds of states
 * and nobody opens this screen to scroll past all of them.
 */
private fun LazyListScope.regionSection(
    title: String,
    entries: List<RegionProgress>,
) {
    if (entries.isEmpty()) return
    item(key = "region-section-$title") {
        var expanded by rememberSaveable(title) { mutableStateOf(false) }
        val shown = if (expanded) entries else entries.take(COLLAPSED_ROWS)
        val leader = entries.first().exploredSquareMeters.coerceAtLeast(1.0)

        SectionCard(title = "$title · ${entries.size}") {
            Text(
                text = "Bars compare these against each other. The percentage is the real share " +
                    "of the place you have set foot in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            shown.forEach { entry -> RegionRow(entry, leader) }
            if (entries.size > COLLAPSED_ROWS) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show fewer" else "Show all ${entries.size}")
                }
            }
        }
    }
}

@Composable
private fun RegionRow(entry: RegionProgress, leaderSquareMeters: Double) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.region.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        entry.parentName,
                        "${ExplorationStats.formatArea(entry.exploredSquareMeters)} of " +
                            ExplorationStats.formatArea(entry.region.areaSquareMeters),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = ExplorationStats.formatPercent(entry.percentExplored),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.End,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (entry.exploredSquareMeters / leaderSquareMeters).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
        )
    }
}

@Composable
private fun Footnote(rawFixCount: Int, regions: RegionTally?) {
    val text = buildString {
        append(
            "Area counts every grid square you have been seen inside, and a square is about " +
                "300 m across at the equator - so a short walk still uncovers a whole one. " +
                "$rawFixCount raw fixes are stored for the trail and GPX export.",
        )
        if (regions != null) {
            append(
                "\n\nBorders are matched on a grid about 10 km across, so somewhere within a few " +
                    "kilometres of a border can be credited to the wrong side of it. The atlas " +
                    "counts territories and dependencies as their own countries, and states are " +
                    "whatever each country calls its first-level divisions - so a country may be " +
                    "split into fifty of them or into two hundred.",
            )
            if (regions.unplacedSquareMeters > 0.0) {
                append(
                    " ${ExplorationStats.formatArea(regions.unplacedSquareMeters)} of what you " +
                        "have uncovered fell outside every border, at sea or just off a coastline.",
                )
            }
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HeadlineCard(
    percentOfLand: Double,
    percentOfSurface: Double,
    areaSquareMeters: Double,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = ExplorationStats.formatPercent(percentOfLand),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "of Earth's land uncovered",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${ExplorationStats.formatArea(areaSquareMeters)} · " +
                    "${ExplorationStats.formatPercent(percentOfSurface)} of the whole planet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatGrid(entries: List<Pair<String, String>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            entries.forEachIndexed { index, (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(value, style = MaterialTheme.typography.titleSmall)
                }
                if (index != entries.lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun PlaceRow(place: VisitedPlaceEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (place.adminArea.isBlank()) place.countryName
            else "${place.adminArea}, ${place.countryName}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = place.countryCode,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** How many rows a region list shows before it needs asking. */
private const val COLLAPSED_ROWS = 8
