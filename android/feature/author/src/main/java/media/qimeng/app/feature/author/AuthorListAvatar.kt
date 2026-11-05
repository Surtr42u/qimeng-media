package media.qimeng.app.feature.author

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** LazyColumn移出屏幕即取消读取并释放图片状态，网络读取由ViewModel承接。 */
@Composable
internal fun AuthorListAvatar(authorId: String, name: String, loadAvatar: suspend (String) -> ByteArray?) {
    val image by produceState<ByteArray?>(null, authorId) { value = loadAvatar(authorId) }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(52.dp)) {
        if (image != null) {
            AsyncImage(model = image, contentDescription = "$name 的头像", contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(CircleShape))
        } else {
            val label = name.ifBlank { "作" }
            Box(contentAlignment = Alignment.Center) {
                Text(label.substring(0, label.offsetByCodePoints(0, 1)), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
