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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jackque.roamed.appContainer
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
                entries = listOf(
                    "Squares uncovered" to summary.cellCount.toString(),
                    "Distance travelled" to ExplorationStats.formatDistance(summary.totalDistanceMeters),
                    "This year" to ExplorationStats.formatDistance(summary.distanceThisYearMeters),
                    "Days out and about" to summary.activeDays.toString(),
                    "Countries" to summary.countryCount.toString(),
                    "Tracking since" to (summary.firstDate ?: "—"),
                ),
            )
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
            Text(
                text = "Area counts every grid square you have been seen inside, and a square is " +
                    "about 300 m across at the equator - so a short walk still uncovers a whole " +
                    "one. ${summary.rawFixCount} raw fixes are stored for the trail and GPX export.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
