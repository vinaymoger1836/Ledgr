package com.ledgr.app.ui.profile

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ledgr.app.R
import com.ledgr.app.ui.icons.CategoryIcons
import com.ledgr.app.ui.theme.LedgrTheme
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ───────────────────────────── colour helpers ─────────────────────────────

/** WCAG contrast ratio between two opaque colours. */
internal fun contrastRatio(a: Color, b: Color): Float {
    val l1 = a.luminance()
    val l2 = b.luminance()
    return (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f)
}

/** White or near-black, whichever reads better on [background] (>= 4.5:1 for every bank colour we ship). */
internal fun onColorFor(background: Color): Color {
    val light = Color.White
    val dark = Color(0xFF111111)
    return if (contrastRatio(light, background) >= contrastRatio(dark, background)) light else dark
}

// ───────────────────────────── layout pieces ─────────────────────────────

/**
 * A pushed screen: top bar with Back, optional actions and a snackbar host. The bottom bar is hidden on these
 * routes, so the navigation-bar inset is applied here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubScreenScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                title = { Text(text = title, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                actions = actions,
            )
        },
        snackbarHost = { if (snackbarHostState != null) SnackbarHost(snackbarHostState) },
        content = content,
    )
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { heading() },
    )
}

/** A tappable row that opens another screen: icon, title, one-line summary and a chevron. */
@Composable
internal fun NavRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Inline error text (also announced by TalkBack as part of the field). */
@Composable
internal fun ErrorText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

// ───────────────────────────── avatars and badges ─────────────────────────────

/** A category as a tinted circle holding its icon. Decorative: the category name is always shown beside it. */
@Composable
internal fun CategoryBadge(iconKey: String, colorIndex: Int, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val tone = LedgrTheme.categoryColors[colorIndex]
    Box(
        modifier = modifier
            .size(size)
            .background(tone.copy(alpha = 0.16f), CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = CategoryIcons.vector(iconKey),
            contentDescription = null,
            tint = tone,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/**
 * Merchant avatar (docs/08 §3): the user's picture, else the category icon on the category colour, else the
 * first letter on a colour derived from the name. Decorative; the merchant name is always next to it.
 */
@Composable
internal fun MerchantAvatar(
    name: String,
    nameColorIndex: Int,
    categoryIconKey: String?,
    categoryColorIndex: Int?,
    image: File?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val bitmap = rememberFileImage(image)
    when {
        bitmap != null -> Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .clearAndSetSemantics { },
        )
        categoryIconKey != null && categoryColorIndex != null ->
            CategoryBadge(categoryIconKey, categoryColorIndex, modifier, size)
        else -> {
            val tone = LedgrTheme.categoryColors[nameColorIndex]
            Box(
                modifier = modifier
                    .size(size)
                    .background(tone.copy(alpha = 0.2f), CircleShape)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Decodes a small saved picture off the main thread. Null while loading or if the file is missing. */
@Composable
internal fun rememberFileImage(file: File?): ImageBitmap? {
    val state by produceState<ImageBitmap?>(initialValue = null, file?.path) {
        value = if (file == null) {
            null
        } else {
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
        }
    }
    return state
}

// ───────────────────────────── pickers shared by categories and merchants ─────────────────────────────

/** The ten category colours as swatches. Selected = ring + check mark; every swatch is named for TalkBack. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorSwatchPicker(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val names = stringArrayResource(R.array.category_color_names)
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (index in 0 until LedgrTheme.categoryColors.size) {
            val tone = LedgrTheme.categoryColors[index]
            val isSelected = index == selected
            val name = names.getOrElse(index) { "" }
            val description = if (isSelected) stringResource(R.string.picker_selected, name) else name
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.RadioButton) { onSelect(index) }
                    .semantics {
                        contentDescription = description
                        this.selected = isSelected
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(tone, CircleShape)
                        .then(
                            if (isSelected) {
                                Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface), CircleShape)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = onColorFor(tone),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The category icon set as a grid. Selected = filled container + outline; every icon is named for TalkBack. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IconPicker(selectedKey: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (option in CategoryIcons.all) {
            val isSelected = option.key == selectedKey
            val name = stringResource(option.label)
            val description = if (isSelected) stringResource(R.string.picker_selected, name) else name
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        MaterialTheme.shapes.medium,
                    )
                    .then(
                        if (isSelected) {
                            Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), MaterialTheme.shapes.medium)
                        } else {
                            Modifier
                        },
                    )
                    .clickable(role = Role.RadioButton) { onSelect(option.key) }
                    .semantics {
                        contentDescription = description
                        this.selected = isSelected
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = option.vector,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
