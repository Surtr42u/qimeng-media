package media.qimeng.app.feature.author

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import media.qimeng.app.core.ui.component.QimengAuthorAvatar

/** Re-entering rows synchronously reuse the bounded VM cache. */
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
        QimengAuthorAvatar(name, state.bytes, state.loading, avatarKey)
    }
}
