import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.Adler32
import java.util.zip.CRC32
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element

// The launcher icon as PNGs, drawn from the brand's adaptive icon (res/mipmap-anydpi/ic_launcher.xml). Android itself
// only ever shows the adaptive icon; the PNGs are for whatever reads the APK without drawing vectors, like an app
// store's indexer, which otherwise shows some other image it finds in the APK.

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/** An adaptive icon's layers are 108dp wide, of which a launcher shows the centre 72dp. */
private const val LAYER = 108.0
private const val VISIBLE = 72.0

/** The legacy launcher icon is 48dp: its width in pixels by density. */
private val densities = mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)

/** Something in the brand's icon this can't draw. The build goes on without the PNGs and says why. */
internal class LauncherIconUnsupported(message: String) : Exception(message)

private fun unsupported(message: String): Nothing = throw LauncherIconUnsupported(message)

abstract class GenerateLauncherIconTask : DefaultTask() {
    /** The brand's res/ directory. */
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val brandRes: DirectoryProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val res = brandRes.get().asFile
        // A brand with launcher PNGs of its own keeps them.
        if (
            res.listFiles { dir -> dir.name.startsWith("mipmap") }
                .orEmpty()
                .any { dir -> dir.listFiles().orEmpty().any(::isLauncherBitmap) }
        )
            return
        try {
            val icon = LauncherIcon(res)
            // Nothing is written unless every size could be drawn.
            densities
                .mapValues { (_, size) -> icon.render(size).toPng() }
                .forEach { (density, png) -> out.resolve("mipmap-$density").apply { mkdirs() }.resolve("ic_launcher.png").writeBytes(png) }
        } catch (e: LauncherIconUnsupported) {
            logger.warn(
                "No launcher icon PNGs: ${e.message}. Android shows the adaptive icon either way; an app store that can't draw " +
                    "it may show another image. To give it one, add res/mipmap-xxxhdpi/ic_launcher.png (192 px) to the brand."
            )
        }
    }

    private fun isLauncherBitmap(file: File) = file.nameWithoutExtension == "ic_launcher" && file.extension != "xml"
}

/** The brand's adaptive icon, read from its [res] directory: colours and vector drawables, as far as they go. */
internal class LauncherIcon(private val res: File) {
    private val colors: Map<String, String> by lazy {
        res.resolve("values")
            .listFiles { file -> file.extension == "xml" }
            .orEmpty()
            .sorted()
            .flatMap { file -> parse(file).children("color").map { it.getAttribute("name") to it.textContent.trim() } }
            .toMap()
    }

    /** The icon [size] pixels wide: the layers' centre, square and opaque, as a launcher shows it before masking. */
    fun render(size: Int): BufferedImage {
        System.setProperty("java.awt.headless", "true")
        val file =
            res.listFiles { dir -> dir.name.startsWith("mipmap-anydpi") }
                .orEmpty()
                .sorted()
                .map { it.resolve("ic_launcher.xml") }
                .firstOrNull(File::exists) ?: unsupported("the brand has no res/mipmap-anydpi/ic_launcher.xml")
        val icon = parse(file)
        if (icon.tagName != "adaptive-icon") unsupported("${file.name} is not an adaptive icon")
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.scale(size / VISIBLE, size / VISIBLE)
        g.translate(-(LAYER - VISIBLE) / 2, -(LAYER - VISIBLE) / 2)
        for (layer in listOf("background", "foreground")) {
            val drawable = icon.children(layer).firstOrNull()?.getAttributeNS(ANDROID_NS, "drawable").orEmpty()
            if (drawable.isEmpty()) if (layer == "foreground") unsupported("${file.name} has no foreground drawable") else continue
            drawLayer(g, drawable)
        }
        g.dispose()
        return image
    }

    private fun drawLayer(g: Graphics2D, drawable: String) {
        if (drawable.startsWith("#") || drawable.startsWith("@color/") || drawable.startsWith("@android:color/")) {
            g.color = color(drawable)
            g.fill(Rectangle2D.Double(0.0, 0.0, LAYER, LAYER))
            return
        }
        val name =
            drawable.substringAfter("@drawable/", "").ifEmpty { unsupported("the layer $drawable is neither a colour nor a drawable") }
        val file = res.resolve("drawable/$name.xml")
        if (!file.exists()) unsupported("the layer $drawable is not a vector drawable in the brand's res/drawable")
        val vector = parse(file)
        if (vector.tagName != "vector") unsupported("res/drawable/$name.xml is not a vector drawable")
        for (attribute in listOf("tint", "alpha")) {
            if (vector.hasAttributeNS(ANDROID_NS, attribute)) unsupported("res/drawable/$name.xml has android:$attribute")
        }
        val width = vector.number("viewportWidth", 0.0)
        val height = vector.number("viewportHeight", 0.0)
        if (width <= 0 || height <= 0) unsupported("res/drawable/$name.xml has no viewport")
        val layer = g.create() as Graphics2D
        layer.scale(LAYER / width, LAYER / height)
        drawChildren(layer, vector, "res/drawable/$name.xml")
        layer.dispose()
    }

    /** Draws a vector's or a group's children in order; a clip-path clips those after it, up to the group's end. */
    private fun drawChildren(g: Graphics2D, parent: Element, source: String) {
        for (child in parent.children()) {
            when (child.tagName) {
                "group" -> {
                    val group = g.create() as Graphics2D
                    val pivotX = child.number("pivotX", 0.0)
                    val pivotY = child.number("pivotY", 0.0)
                    group.translate(child.number("translateX", 0.0) + pivotX, child.number("translateY", 0.0) + pivotY)
                    group.rotate(Math.toRadians(child.number("rotation", 0.0)))
                    group.scale(child.number("scaleX", 1.0), child.number("scaleY", 1.0))
                    group.translate(-pivotX, -pivotY)
                    drawChildren(group, child, source)
                    group.dispose()
                }
                "clip-path" -> g.clip(parsePathData(child.getAttributeNS(ANDROID_NS, "pathData"), evenOdd = false))
                "path" -> drawPath(g, child, source)
                else -> unsupported("$source has a <${child.tagName}>")
            }
        }
    }

    private fun drawPath(g: Graphics2D, path: Element, source: String) {
        if (path.children().isNotEmpty()) unsupported("$source has a gradient or another nested attribute")
        for (attribute in listOf("trimPathStart", "trimPathEnd", "trimPathOffset")) {
            if (path.hasAttributeNS(ANDROID_NS, attribute)) unsupported("$source has android:$attribute")
        }
        val shape =
            parsePathData(path.getAttributeNS(ANDROID_NS, "pathData"), evenOdd = path.getAttributeNS(ANDROID_NS, "fillType") == "evenOdd")
        path.getAttributeNS(ANDROID_NS, "fillColor").takeIf(String::isNotEmpty)?.let { fill ->
            g.color = color(fill, path.number("fillAlpha", 1.0))
            g.fill(shape)
        }
        val strokeWidth = path.number("strokeWidth", 0.0)
        path
            .getAttributeNS(ANDROID_NS, "strokeColor")
            .takeIf { it.isNotEmpty() && strokeWidth > 0 }
            ?.let { stroke ->
                val cap =
                    when (val value = path.getAttributeNS(ANDROID_NS, "strokeLineCap")) {
                        "",
                        "butt" -> BasicStroke.CAP_BUTT
                        "round" -> BasicStroke.CAP_ROUND
                        "square" -> BasicStroke.CAP_SQUARE
                        else -> unsupported("$source has android:strokeLineCap=\"$value\"")
                    }
                val join =
                    when (val value = path.getAttributeNS(ANDROID_NS, "strokeLineJoin")) {
                        "",
                        "miter" -> BasicStroke.JOIN_MITER
                        "round" -> BasicStroke.JOIN_ROUND
                        "bevel" -> BasicStroke.JOIN_BEVEL
                        else -> unsupported("$source has android:strokeLineJoin=\"$value\"")
                    }
                g.color = color(stroke, path.number("strokeAlpha", 1.0))
                g.stroke = BasicStroke(strokeWidth.toFloat(), cap, join, max(1.0, path.number("strokeMiterLimit", 4.0)).toFloat())
                g.draw(shape)
            }
    }

    /** A colour literal (#RGB, #ARGB, #RRGGBB, #AARRGGBB) or a reference to one of the brand's colours. */
    private fun color(value: String, alpha: Double = 1.0): Color {
        var literal = value.trim()
        repeat(16) {
            if (literal.startsWith("@color/")) {
                val name = literal.removePrefix("@color/")
                literal = colors[name] ?: unsupported("the colour @color/$name is not in the brand's res/values")
            }
        }
        literal =
            when (literal) {
                "@android:color/white" -> "#FFFFFF"
                "@android:color/black" -> "#000000"
                "@android:color/transparent" -> "#00000000"
                else -> literal
            }
        val digits = literal.removePrefix("#").let { if (it.length in 3..4) it.map { digit -> "$digit$digit" }.joinToString("") else it }
        val argb =
            digits.takeIf { literal.startsWith("#") && it.length in setOf(6, 8) }?.padStart(8, 'F')?.toLongOrNull(16)
                ?: unsupported("the colour '$value' is not a colour this can read")
        val a = ((argb shr 24 and 0xFF) * alpha.coerceIn(0.0, 1.0)).toInt()
        return Color((argb shr 16 and 0xFF).toInt(), (argb shr 8 and 0xFF).toInt(), (argb and 0xFF).toInt(), a)
    }

    private fun Element.number(attribute: String, default: Double): Double {
        val value = getAttributeNS(ANDROID_NS, attribute)
        return if (value.isEmpty()) default else value.toDoubleOrNull() ?: unsupported("android:$attribute=\"$value\" is not a number")
    }
}

private fun parse(file: File): Element =
    DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement

private fun Element.children(tag: String? = null): List<Element> =
    (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>().filter { tag == null || it.tagName == tag }

/** A vector drawable's pathData (SVG path syntax) as a shape. */
internal fun parsePathData(data: String, evenOdd: Boolean): Path2D.Double {
    val path = Path2D.Double(if (evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO)
    var i = 0

    fun skipSeparators() {
        while (i < data.length && (data[i].isWhitespace() || data[i] == ',')) i++
    }

    fun digits() {
        while (i < data.length && data[i].isDigit()) i++
    }

    fun hasNumber(): Boolean {
        skipSeparators()
        return i < data.length && (data[i].isDigit() || data[i] in "+-.")
    }

    fun number(): Double {
        skipSeparators()
        val start = i
        if (i < data.length && data[i] in "+-") i++
        digits()
        if (i < data.length && data[i] == '.') {
            i++
            digits()
        }
        if (i < data.length && data[i] in "eE") {
            i++
            if (i < data.length && data[i] in "+-") i++
            digits()
        }
        return data.substring(start, i).toDoubleOrNull() ?: unsupported("path data that can't be read near position $start")
    }

    // An arc's two flags are single digits, and may be written without anything between them.
    fun flag(): Boolean {
        skipSeparators()
        if (i >= data.length || data[i] !in "01") unsupported("path data that can't be read near position $i")
        return data[i++] == '1'
    }

    // The current point, the subpath's start, and the last control point for the smooth curves that mirror it.
    var x = 0.0
    var y = 0.0
    var startX = 0.0
    var startY = 0.0
    var controlX = 0.0
    var controlY = 0.0
    var previous = ' '

    while (true) {
        skipSeparators()
        if (i >= data.length) break
        var command = data[i++]
        if (command !in "MmLlHhVvCcSsQqTtAaZz") unsupported("path data that can't be read near position ${i - 1}")
        if (command == 'Z' || command == 'z') {
            path.closePath()
            x = startX
            y = startY
            previous = 'z'
            continue
        }
        if (previous == ' ' && command.lowercaseChar() != 'm') unsupported("path data that doesn't start with a move")
        do {
            val relative = command.isLowerCase()
            val dx = if (relative) x else 0.0
            val dy = if (relative) y else 0.0
            when (command.lowercaseChar()) {
                'm' -> {
                    x = number() + dx
                    y = number() + dy
                    path.moveTo(x, y)
                    startX = x
                    startY = y
                }
                'l' -> {
                    x = number() + dx
                    y = number() + dy
                    path.lineTo(x, y)
                }
                'h' -> {
                    x = number() + dx
                    path.lineTo(x, y)
                }
                'v' -> {
                    y = number() + dy
                    path.lineTo(x, y)
                }
                'c' -> {
                    val x1 = number() + dx
                    val y1 = number() + dy
                    controlX = number() + dx
                    controlY = number() + dy
                    x = number() + dx
                    y = number() + dy
                    path.curveTo(x1, y1, controlX, controlY, x, y)
                }
                's' -> {
                    val smooth = previous == 'c' || previous == 's'
                    val x1 = if (smooth) 2 * x - controlX else x
                    val y1 = if (smooth) 2 * y - controlY else y
                    controlX = number() + dx
                    controlY = number() + dy
                    x = number() + dx
                    y = number() + dy
                    path.curveTo(x1, y1, controlX, controlY, x, y)
                }
                'q' -> {
                    controlX = number() + dx
                    controlY = number() + dy
                    x = number() + dx
                    y = number() + dy
                    path.quadTo(controlX, controlY, x, y)
                }
                't' -> {
                    val smooth = previous == 'q' || previous == 't'
                    controlX = if (smooth) 2 * x - controlX else x
                    controlY = if (smooth) 2 * y - controlY else y
                    x = number() + dx
                    y = number() + dy
                    path.quadTo(controlX, controlY, x, y)
                }
                'a' -> {
                    val rx = number()
                    val ry = number()
                    val rotation = number()
                    val largeArc = flag()
                    val sweep = flag()
                    val endX = number() + dx
                    val endY = number() + dy
                    path.arcTo(x, y, rx, ry, rotation, largeArc, sweep, endX, endY)
                    x = endX
                    y = endY
                }
            }
            previous = command.lowercaseChar()
            // More numbers repeat the command; after a move they are lines.
            if (command == 'M') command = 'L' else if (command == 'm') command = 'l'
        } while (hasNumber())
    }
    return path
}

/** An SVG elliptical arc from ([x0], [y0]) to ([x], [y]), as cubic curves of a quarter turn at most. */
private fun Path2D.Double.arcTo(
    x0: Double,
    y0: Double,
    rxIn: Double,
    ryIn: Double,
    rotation: Double,
    largeArc: Boolean,
    sweep: Boolean,
    x: Double,
    y: Double,
) {
    if (x0 == x && y0 == y) return
    var rx = abs(rxIn)
    var ry = abs(ryIn)
    if (rx == 0.0 || ry == 0.0) {
        lineTo(x, y)
        return
    }
    val cosPhi = cos(Math.toRadians(rotation))
    val sinPhi = sin(Math.toRadians(rotation))
    // The endpoints in the ellipse's own axes, around their midpoint.
    val px = cosPhi * (x0 - x) / 2 + sinPhi * (y0 - y) / 2
    val py = -sinPhi * (x0 - x) / 2 + cosPhi * (y0 - y) / 2
    // Radii too small to reach are scaled up until they do.
    val reach = px * px / (rx * rx) + py * py / (ry * ry)
    if (reach > 1) {
        rx *= sqrt(reach)
        ry *= sqrt(reach)
    }
    val spread = rx * rx * py * py + ry * ry * px * px
    val factor = sqrt(max(0.0, rx * rx * ry * ry - spread) / spread) * if (largeArc == sweep) -1 else 1
    val centerPx = factor * rx * py / ry
    val centerPy = -factor * ry * px / rx
    val centerX = cosPhi * centerPx - sinPhi * centerPy + (x0 + x) / 2
    val centerY = sinPhi * centerPx + cosPhi * centerPy + (y0 + y) / 2
    val startAngle = atan2((py - centerPy) / ry, (px - centerPx) / rx)
    var turn = atan2((-py - centerPy) / ry, (-px - centerPx) / rx) - startAngle
    if (sweep && turn < 0) turn += 2 * Math.PI else if (!sweep && turn > 0) turn -= 2 * Math.PI
    val segments = max(1, ceil(abs(turn) / (Math.PI / 2) - 1e-9).toInt())
    val step = turn / segments
    val handle = 4.0 / 3.0 * tan(step / 4)

    // A point of the unit circle, on the ellipse.
    fun pointX(u: Double, v: Double) = centerX + rx * u * cosPhi - ry * v * sinPhi
    fun pointY(u: Double, v: Double) = centerY + rx * u * sinPhi + ry * v * cosPhi

    var angle = startAngle
    repeat(segments) { segment ->
        val next = angle + step
        val (c1, s1) = cos(angle) to sin(angle)
        val (c2, s2) = cos(next) to sin(next)
        val last = segment == segments - 1
        curveTo(
            pointX(c1 - handle * s1, s1 + handle * c1),
            pointY(c1 - handle * s1, s1 + handle * c1),
            pointX(c2 + handle * s2, s2 - handle * c2),
            pointY(c2 + handle * s2, s2 - handle * c2),
            if (last) x else pointX(c2, s2),
            if (last) y else pointY(c2, s2),
        )
        angle = next
    }
}

/**
 * The image as an opaque PNG. Its pixels are stored, not compressed: zlib's output differs between JDK builds, and a release is the same
 * bytes wherever it is built. aapt2 compresses the PNGs when it packs a release.
 */
internal fun BufferedImage.toPng(): ByteArray {
    val row = 1 + width * 3
    val pixels = ByteArray(height * row)
    for (py in 0 until height) {
        for (px in 0 until width) {
            val rgb = getRGB(px, py)
            val at = py * row + 1 + px * 3
            pixels[at] = (rgb shr 16).toByte()
            pixels[at + 1] = (rgb shr 8).toByte()
            pixels[at + 2] = rgb.toByte()
        }
    }
    val zlib = ByteArrayOutputStream()
    DataOutputStream(zlib).apply {
        writeShort(0x7801)
        var offset = 0
        while (offset < pixels.size) {
            val length = min(0xFFFF, pixels.size - offset)
            writeByte(if (offset + length == pixels.size) 1 else 0)
            writeShort(Integer.reverseBytes(length) ushr 16)
            writeShort(Integer.reverseBytes(length.inv()) ushr 16)
            write(pixels, offset, length)
            offset += length
        }
        writeInt(Adler32().apply { update(pixels) }.value.toInt())
    }
    val header = ByteArrayOutputStream()
    DataOutputStream(header).apply {
        writeInt(width)
        writeInt(height)
        // 8 bits a channel, RGB, and PNG's only compression, filter and interlace methods.
        write(byteArrayOf(8, 2, 0, 0, 0))
    }
    val png = ByteArrayOutputStream()
    DataOutputStream(png).apply {
        write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        for ((type, data) in listOf("IHDR" to header.toByteArray(), "IDAT" to zlib.toByteArray(), "IEND" to ByteArray(0))) {
            writeInt(data.size)
            write(type.toByteArray(Charsets.US_ASCII))
            write(data)
            writeInt(
                CRC32()
                    .apply {
                        update(type.toByteArray(Charsets.US_ASCII))
                        update(data)
                    }
                    .value
                    .toInt()
            )
        }
    }
    return png.toByteArray()
}
