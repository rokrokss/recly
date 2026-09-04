package app.recly.android.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.recly.android.settings.AppTheme

/** docs/09 "간격": multiples of four, with 8 / 16 / 24 as the rhythm. */
object Space {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 16.dp
    val l: Dp = 24.dp
    val xl: Dp = 32.dp
}

/**
 * docs/09 "접근성": whatever it draws, nothing you can tap is smaller than this. A small glyph — the
 * connector's `+`, a square switch — keeps its size and grows a target around itself.
 */
val MinTouch: Dp = 44.dp

/** docs/09 "형태": 4 for a node, 8 for a card, 0 for a table row. Badges and chips take half a node. */
object Radius {
    val node: Dp = 4.dp
    val card: Dp = 8.dp
    val badge: Dp = 2.dp
}

val ReclyShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.badge),
    small = RoundedCornerShape(Radius.node),
    medium = RoundedCornerShape(Radius.card),
    large = RoundedCornerShape(Radius.card),
    extraLarge = RoundedCornerShape(Radius.card),
)

/** The table row shape of docs/09 — square, because a ledger is a table. */
val RowShape = RectangleShape

val LocalBlueprintColors: ProvidableCompositionLocal<BlueprintColors> =
    staticCompositionLocalOf { BlueprintLight }

val LocalMonoType: ProvidableCompositionLocal<MonoType> =
    staticCompositionLocalOf { monoType(1f) }

/**
 * True when the system says so ([Settings.Global.ANIMATOR_DURATION_SCALE] of zero, which is what
 * "remove animations" sets). Not static: the user can flip that setting while the screen is up.
 */
val LocalReduceMotion: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/** Read far more often than `MaterialTheme`, so both get a name of one word. */
val blueprint: BlueprintColors
    @Composable get() = LocalBlueprintColors.current

val mono: MonoType
    @Composable get() = LocalMonoType.current

/**
 * docs/09, applied. Material 3 stays underneath — its switches, text fields, dialogs and menus are
 * the right components and the platform's own — but the scheme, the type and the shapes are ours,
 * so nothing arrives in Material's default purple or its pill corners.
 *
 * Everything the theme varies on is read from the system: dark mode, the font scale (`sp` carries
 * it) and reduce motion (docs/09 "접근성"). Dark is the one of them the user may say otherwise
 * about — [theme] is the setting's override, and [AppTheme.SYSTEM] is the system's own answer.
 */
@Composable
fun ReclyTheme(
    theme: AppTheme = AppTheme.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = theme.isDark(isSystemInDarkTheme())
    val blueprint = remember(dark) { blueprintColors(dark) }
    val widthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val scale = remember(widthDp) { fluidScale(widthDp) }
    val typography = remember(scale) { reclyTypography(scale) }
    val monospace = remember(scale) { monoType(scale) }
    val reduceMotion = observeSystemReduceMotion()

    CompositionLocalProvider(
        LocalBlueprintColors provides blueprint,
        LocalMonoType provides monospace,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = blueprint.toColorScheme(),
            typography = typography,
            shapes = ReclyShapes,
            content = content,
        )
    }
}

/**
 * The system's own "remove animations" switch. `LocalAccessibilityManager` reports touch
 * exploration, not animation scale, so the setting is read where the platform keeps it — and
 * watched there, because the user can flip it in Settings while this app is only paused, and a
 * value read once would then be wrong until the process is recreated.
 */
@Composable
private fun observeSystemReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val lifecycleOwner = LocalLifecycleOwner.current
    var reduce by remember(resolver) { mutableStateOf(systemReduceMotion(animatorScale(resolver))) }
    DisposableEffect(resolver, lifecycleOwner) {
        val reread = { reduce = systemReduceMotion(animatorScale(resolver)) }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = reread()
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        // The observer covers the app being alive; a resume covers everything else, including a
        // build that does not notify on this row.
        val onResume = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reread()
        }
        lifecycleOwner.lifecycle.addObserver(onResume)
        reread()
        onDispose {
            resolver.unregisterContentObserver(observer)
            lifecycleOwner.lifecycle.removeObserver(onResume)
        }
    }
    return reduce
}

private fun animatorScale(resolver: ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/** "Remove animations" is an animator duration scale of zero; anything else animates. */
fun systemReduceMotion(scale: Float): Boolean = scale == 0f

/**
 * docs/09 "간격": an 8dp dot grid at 6% behind the content — the visible grid the nodes sit on.
 */
fun Modifier.dotGrid(palette: BlueprintColors): Modifier = this
    .background(palette.background)
    .drawWithCache {
        // One 8dp tile, painted once and repeated. A screen of dots is several thousand circles
        // and not one of them ever changes, so drawing them per frame would be paying for the
        // grid over and over.
        val step = 8.dp.roundToPx().coerceAtLeast(1)
        val tile = ImageBitmap(step, step)
        CanvasDrawScope().draw(
            density = this,
            layoutDirection = layoutDirection,
            canvas = Canvas(tile),
            size = Size(step.toFloat(), step.toFloat()),
        ) {
            drawCircle(
                color = palette.text.copy(alpha = 0.06f),
                radius = 0.8.dp.toPx(),
                center = Offset(step / 2f, step / 2f),
            )
        }
        val brush = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
        onDrawBehind { drawRect(brush) }
    }

/**
 * Material's scheme, derived. Only the roles this app actually draws are mapped; the rest follow
 * from them so a component nobody expected still lands in the palette.
 */
private fun BlueprintColors.toColorScheme() = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = accent,
    onPrimaryContainer = onAccent,
    secondary = textMuted,
    onSecondary = surface,
    // A selected `FilterChip` is a container, and Material's default one is a lavender that has
    // nothing to do with this palette: a chosen chip is the accent on the surface, outlined.
    secondaryContainer = surface,
    onSecondaryContainer = accent,
    background = background,
    onBackground = text,
    surface = surface,
    onSurface = text,
    surfaceVariant = background,
    onSurfaceVariant = textMuted,
    surfaceContainer = surface,
    surfaceContainerHigh = surface,
    surfaceContainerHighest = surface,
    surfaceContainerLow = background,
    surfaceContainerLowest = background,
    error = danger,
    onError = onDanger,
    errorContainer = surface,
    onErrorContainer = danger,
    tertiary = warningInk,
    onTertiary = surface,
    outline = grid,
    outlineVariant = grid,
    scrim = text,
)
