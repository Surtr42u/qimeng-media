package media.qimeng.app.feature.author

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/** 行重新进入视口时同步使用有界缓存，按服务/刷新代次/作者重建状态。 */
@Composable
internal fun AuthorListAvatar(
    authorId: String,
    name: String,
    avatarKey: String,
    initialAvatar: AuthorAvatarState,
    loadAvatar: suspend (String) -> AuthorAvatarState,
) {
    key(avatarKey) {
        val state by produceState(initialAvatar, avatarKey) { value = loadAvatar(authorId) }
        val context = LocalContext.current
        val request = remember(state.bytes, avatarKey, context) {
            ImageRequest.Builder(context).data(state.bytes).memoryCacheKey(avatarKey).build()
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(48.dp)) {
            if (state.bytes != null) {
                AsyncImage(model = request, contentDescription = "$name 的头像", contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(CircleShape))
            } else if (!state.loading) {
                val label = name.ifBlank { "作" }
                Box(contentAlignment = Alignment.Center) {
                    Text(label.substring(0, label.offsetByCodePoints(0, 1)), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}
