package de.floorballcompanion.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import de.floorballcompanion.R

private const val BASE_URL = "https://saisonmanager.de"

fun resolveLogoUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return if (url.startsWith("http")) url else "$BASE_URL$url"
}

@Composable
fun TeamLogo(
    logoUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    val resolved = resolveLogoUrl(logoUrl)
    if (resolved != null) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(resolved)
                .crossfade(true)
                .error(R.drawable.placeholder_logo)
                .fallback(R.drawable.placeholder_logo)
                .build(),
            contentDescription = contentDescription,
            modifier = modifier
                .size(size)
                .clip(CircleShape),
            contentScale = ContentScale.Fit,
        )
    } else {
        Image(
            painter = painterResource(R.drawable.placeholder_logo),
            contentDescription = contentDescription ?: "Kein Logo",
            modifier = modifier
                .size(size)
                .clip(CircleShape),
            contentScale = ContentScale.Fit,
        )
    }
}
