package app.recly.windows.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** docs/09 "간격": multiples of four, with 8 / 16 / 24 as the rhythm. */
object Space {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 16.dp
    val l: Dp = 24.dp
    val xl: Dp = 32.dp
}

/**
 * docs/09 "접근성": whatever it draws, nothing you can click is smaller than this. A small glyph —
 * the connector's `+`, a square switch — keeps its size and grows a target around itself.
 */
val MinTouch: Dp = 44.dp

/** docs/09 "형태": 4 for a node, 8 for a card, 0 for a table row. Badges take half a node. */
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
 * docs/09 "고대비 모드": Windows publishes its high-contrast switch — AWT carries
 * `SPI_GETHIGHCONTRAST` as the `win.highContrast.on` desktop property — so the app follows the
 * system, which is the whole of the setting here.
 *
 * Null is "this machine does not say", which is every non-Windows host (the macOS development one
 * included) and any Windows toolkit that did not publish the property. [highContrastOf] is what
 * turns that into an answer.
 */
fun systemHighContrast(): Boolean? = runCatching {
    java.awt.Toolkit.getDefaultToolkit().getDesktopProperty(HIGH_CONTRAST_PROPERTY) as? Boolean
}.getOrNull()

/** A machine that did not say is a machine in normal contrast. */
fun highContrastOf(system: Boolean?): Boolean = system == true

/**
 * [systemHighContrast], kept current: AWT fires a property change when the user flips the Windows
 * setting, and a window that stays open has to follow it — with the in-app toggle gone, a stale
 * read would hold the wrong contrast until the window is recreated.
 */
@Composable
fun observeSystemHighContrast(): Boolean? {
    var value by remember { mutableStateOf(systemHighContrast()) }
    DisposableEffect(Unit) {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        val listener = java.beans.PropertyChangeListener { event -> value = event.newValue as? Boolean }
        toolkit.addPropertyChangeListener(HIGH_CONTRAST_PROPERTY, listener)
        onDispose { toolkit.removePropertyChangeListener(HIGH_CONTRAST_PROPERTY, listener) }
    }
    return value
}

private const val HIGH_CONTRAST_PROPERTY = "win.highContrast.on"

/** Read far more often than `MaterialTheme`, so both get a name of one word. */
val blueprint: BlueprintColors
    @Composable get() = LocalBlueprintColors.current

val mono: MonoType
    @Composable get() = LocalMonoType.current

/**
 * docs/09, applied to Compose Desktop. Material 3 stays underneath — its text fields, chips and
 * dropdown menus are the right components — but the scheme, the type and the shapes are ours, so
 * nothing arrives in Material's default purple or its pill corners.
 *
 * The type scale is interpolated from the width of *this window* ([fluidScale]), which is why the
 * theme measures rather than taking a number: a 640dp tray popup and a 1280dp editor are two sizes
 * of the same window on one screen, and a single app-wide scale would be wrong for one of them.
 *
 * [dark] defaults to the system's setting; the settings window passes an override over it.
 */
@Composable
fun ReclyDesktopTheme(
    dark: Boolean,
    highContrast: Boolean,
    content: @Composable () -> Unit,
) {
    val palette = remember(dark, highContrast) { blueprintColors(dark, highContrast) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = remember(maxWidth) { fluidScale(maxWidth.value) }
        val typography = remember(scale) { reclyTypography(scale) }
        val monospace = remember(scale) { monoType(scale) }
        CompositionLocalProvider(
            LocalBlueprintColors provides palette,
            LocalMonoType provides monospace,
        ) {
            MaterialTheme(
                colorScheme = palette.toColorScheme(),
                typography = typography,
                shapes = ReclyShapes,
                content = content,
            )
        }
    }
}

/**
 * docs/09 "간격": an 8dp dot grid at 6% behind the content — the visible grid the nodes sit on. Off
 * in high contrast, where a texture is noise.
 */
fun Modifier.dotGrid(palette: BlueprintColors): Modifier = this
    .background(palette.background)
    .then(
        if (palette.highContrast) {
            Modifier
        } else {
            Modifier.drawWithCache {
                // One 8dp tile, painted once and repeated. A window of dots is several thousand
                // circles and not one of them ever changes, so drawing them per frame would be
                // paying for the grid over and over.
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
        },
    )

/**
 * Material's scheme, derived. Only the roles this shell actually draws are mapped; the rest follow
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
