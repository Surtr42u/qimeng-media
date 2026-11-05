package media.qimeng.app.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/** Rendering is shared by author management and the detail sheet. IO stays in VMs. */
@Composable
fun QimengAuthorAvatar(name: String, bytes: ByteArray?, loading: Boolean, cacheKey: String, size: Dp = 48.dp) {
    key(cacheKey) {
        val context = LocalContext.current
        val request = remember(bytes, cacheKey, context) {
            ImageRequest.Builder(context).data(bytes).memoryCacheKey(cacheKey).build()
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(size)) {
            if (bytes != null) {
                AsyncImage(model = request, contentDescription = "$name 的头像", contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size).clip(CircleShape))
            } else if (!loading) {
                val label = name.ifBlank { "作" }
                Box(contentAlignment = Alignment.Center) {
                    Text(label.substring(0, label.offsetByCodePoints(0, 1)), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}
