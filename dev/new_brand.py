"""Scaffolds a complete brand directory (see docs/WHITE_LABEL.md). Run through dev/new-brand.sh; --help lists the options.

Standard library only. Writes brand.properties, light and dark colors derived from one primary color, the launcher icon
and in-app logo from an SVG or Android vector logo, a Zapstore listing and a template for the brand-term strings. Then
it checks the result (application ID, mint, contrast) and, unless told not to, runs dev/check-brand.sh and builds a
debug APK with the brand.
"""

from __future__ import annotations

import argparse
import colorsys
import json
import math
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANDROID = "http://schemas.android.com/apk/res/android"
SVG = "http://www.w3.org/2000/svg"
ET.register_namespace("android", ANDROID)

# The original brand's application ID: a fork must not ship under it.
ORIGINAL_ID = "com.example.whitewallet"
# Adaptive icons are drawn on a 108dp canvas; launchers may mask everything outside the central 66dp circle. The logo
# is fitted inside a slightly smaller circle so masks never touch it.
ICON_CANVAS = 108.0
ICON_SAFE_DIAMETER = 64.0
# The surfaces the brand colors sit on (Material 3 baseline), for the contrast checks.
LIGHT_SURFACE = "#FEF7FF"
DARK_SURFACE = "#141218"


class BrandError(Exception):
    """A problem in the input; the message says what to change."""


# --- Input checks -----------------------------------------------------------------------------------------------------

APPLICATION_ID = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$")
HOST = re.compile(r"^(?=.{1,253}(:|$))([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)*[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(:\d{1,5})?$")
# Java keywords can't be package segments, so they can't be in an application ID either.
JAVA_KEYWORDS = set(
    "abstract assert boolean break byte case catch char class const continue default do double else enum extends final "
    "finally float for goto if implements import instanceof int interface long native new package private protected "
    "public return short static strictfp super switch synchronized this throw throws transient try void volatile while "
    "true false null".split()
)


def check_application_id(application_id: str) -> list[str]:
    """Raises for an ID Android rejects or the original brand's; returns warnings for IDs that work but shouldn't ship."""
    if not APPLICATION_ID.match(application_id):
        raise BrandError(f"'{application_id}' is not a valid application ID: use at least two dot-separated parts of letters, digits and _, each starting with a letter (e.g. com.acme.cash)")
    keywords = [part for part in application_id.split(".") if part in JAVA_KEYWORDS]
    if keywords:
        raise BrandError(f"'{application_id}' contains the Java keyword '{keywords[0]}', which Android doesn't allow")
    if application_id == ORIGINAL_ID or application_id.startswith(ORIGINAL_ID + "."):
        raise BrandError(f"'{application_id}' is the original app's ID; pick one under a domain you own")
    if application_id.startswith("com.example."):
        return [f"'{application_id}' uses com.example, which app stores refuse; fine for trying, change it before release"]
    return []


def check_host(host: str) -> None:
    if not HOST.match(host):
        raise BrandError(f"'{host}' is not a host name: give the mint as host or host:port, without https:// or a path (e.g. mint.acme.com)")


def mint_url(host: str) -> str:
    """The mint's own payRequest, the same URL the app reads. Local hosts are plain http, as in the app's debug builds."""
    name = host.split(":")[0]
    scheme = "http" if name in ("localhost", "127.0.0.1", "10.0.2.2") or name.endswith(".onion") else "https"
    return f"{scheme}://{host}/.well-known/lnurlp/_"


def check_mint(host: str, timeout: float = 10.0) -> None:
    """Raises unless [host] answers as an LNURLcash mint: a payRequest that mints notes (LUD-25: withdrawLink)."""
    url = mint_url(host)
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            body = json.load(response)
    except urllib.error.HTTPError as e:
        raise BrandError(f"{url} answered HTTP {e.code}: is {host} an LNURLcash mint? (skip with --no-mint-check)") from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        reason = getattr(e, "reason", e)
        raise BrandError(f"couldn't reach {url}: {reason} (skip with --no-mint-check)") from e
    except json.JSONDecodeError as e:
        raise BrandError(f"{url} didn't answer with JSON: is {host} an LNURLcash mint? (skip with --no-mint-check)") from e
    if not isinstance(body, dict) or body.get("tag") != "payRequest":
        raise BrandError(f"{url} is not an LNURL-pay endpoint (expected tag payRequest)")
    if not body.get("withdrawLink"):
        raise BrandError(f"{host} answers LNURL-pay but doesn't mint notes (no withdrawLink): it isn't an LNURLcash mint")


# --- Colors -----------------------------------------------------------------------------------------------------------

def parse_color(text: str) -> tuple[float, float, float]:
    """#RGB or #RRGGBB to (r, g, b) in 0..1."""
    value = text.strip().lstrip("#")
    if re.fullmatch(r"[0-9a-fA-F]{3}", value):
        value = "".join(c * 2 for c in value)
    if not re.fullmatch(r"[0-9a-fA-F]{6}", value):
        raise BrandError(f"'{text}' is not a color: use #RRGGBB (e.g. #0B6E4F)")
    return tuple(int(value[i:i + 2], 16) / 255 for i in (0, 2, 4))  # type: ignore[return-value]


def hex_color(rgb: tuple[float, float, float]) -> str:
    return "#" + "".join(f"{round(min(1.0, max(0.0, c)) * 255):02X}" for c in rgb)


def luminance(rgb: tuple[float, float, float]) -> float:
    """WCAG relative luminance."""
    def channel(c: float) -> float:
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (channel(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a: str, b: str) -> float:
    """WCAG contrast ratio of two colors, 1..21."""
    la, lb = sorted((luminance(parse_color(a)), luminance(parse_color(b))), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def with_hsl(color: str, hue_shift: float = 0.0, lightness: float | None = None, saturation_scale: float = 1.0) -> str:
    h, l, s = colorsys.rgb_to_hls(*parse_color(color))
    return hex_color(colorsys.hls_to_rgb((h + hue_shift / 360) % 1.0, l if lightness is None else lightness, min(1.0, s * saturation_scale)))


def text_on(background: str) -> str:
    """White or black, whichever reads better on [background]. One of them always reaches 4.5:1."""
    return "#FFFFFF" if contrast("#FFFFFF", background) >= contrast("#000000", background) else "#000000"


def palette(primary: str, secondary: str | None = None, tertiary: str | None = None) -> tuple[dict[str, str], dict[str, str]]:
    """The light and dark brand colors, from one primary (secondary and tertiary derived unless given)."""
    primary = hex_color(parse_color(primary))
    secondary = hex_color(parse_color(secondary)) if secondary else with_hsl(primary, saturation_scale=0.6, lightness=min(0.6, colorsys.rgb_to_hls(*parse_color(primary))[1] + 0.1))
    tertiary = hex_color(parse_color(tertiary)) if tertiary else with_hsl(primary, hue_shift=60)
    light = {
        "brand_primary": primary,
        "brand_on_primary": text_on(primary),
        "brand_secondary": secondary,
        "brand_tertiary": tertiary,
        "brand_launcher_background": primary,
    }
    # Dark theme: the same hues as light tints, with dark text on them (as Material's tone 80 / tone 20).
    dark_primary = with_hsl(primary, lightness=0.8)
    dark = {
        "brand_primary": dark_primary,
        "brand_on_primary": with_hsl(primary, lightness=0.18),
        "brand_secondary": with_hsl(secondary, lightness=0.82),
        "brand_tertiary": with_hsl(tertiary, lightness=0.78),
    }
    if contrast(dark["brand_on_primary"], dark_primary) < 4.5:
        dark["brand_on_primary"] = text_on(dark_primary)
    return light, dark


def color_warnings(light: dict[str, str], dark: dict[str, str]) -> list[str]:
    """Contrast problems: text on buttons (4.5:1) and the primary color as text or outline on the background (3:1)."""
    warnings = []
    for theme, colors, surface in (("light", light, LIGHT_SURFACE), ("dark", dark, DARK_SURFACE)):
        ratio = contrast(colors["brand_on_primary"], colors["brand_primary"])
        if ratio < 4.5:
            warnings.append(f"{theme} theme: button text has only {ratio:.1f}:1 contrast on {colors['brand_primary']} (needs 4.5:1)")
        ratio = contrast(colors["brand_primary"], surface)
        if ratio < 3.0:
            warnings.append(f"{theme} theme: {colors['brand_primary']} has only {ratio:.1f}:1 contrast on the background (needs 3:1 for links, outlines and switches); pick a {'darker' if theme == 'light' else 'lighter'} primary")
    return warnings


# --- Logo -------------------------------------------------------------------------------------------------------------

class Logo:
    """A logo as Android vector drawable children (paths and groups) on a viewport."""

    def __init__(self, x: float, y: float, width: float, height: float, children: list[ET.Element]):
        if width <= 0 or height <= 0:
            raise BrandError("the logo has no size: give the SVG a viewBox, or width and height")
        if not children:
            raise BrandError("the logo has nothing to draw: no paths or shapes found")
        self.x, self.y, self.width, self.height, self.children = x, y, width, height, children


def a(name: str) -> str:
    return f"{{{ANDROID}}}{name}"


def number(text: str | None, default: float = 0.0) -> float:
    if text is None or text.strip() == "":
        return default
    match = re.match(r"\s*([-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?)(px)?\s*$", text)
    if not match:
        raise BrandError(f"the logo uses the length '{text}': only plain numbers (or px) are supported")
    return float(match.group(1))


NAMED_COLORS = {"black": "#000000", "white": "#FFFFFF", "red": "#FF0000", "green": "#008000", "blue": "#0000FF"}


def svg_color(text: str) -> str | None:
    """An SVG paint as #RRGGBB, or None for no paint."""
    value = text.strip()
    if value in ("none", "transparent"):
        return None
    if value == "currentColor":
        return "#000000"
    if value.lower() in NAMED_COLORS:
        return NAMED_COLORS[value.lower()]
    match = re.fullmatch(r"rgb\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)", value)
    if match:
        return hex_color(tuple(int(match.group(i)) / 255 for i in (1, 2, 3)))  # type: ignore[arg-type]
    if value.startswith("url("):
        raise BrandError("the logo uses a gradient or pattern fill, which this tool doesn't convert: flatten it to solid colors, or convert it with Android Studio's Vector Asset tool and pass the .xml")
    try:
        return hex_color(parse_color(value))
    except BrandError:
        raise BrandError(f"the logo uses the color '{value}': use #RRGGBB, rgb(...) or none") from None


def style_of(element: ET.Element, inherited: dict[str, str]) -> dict[str, str]:
    """The presentation attributes in effect on [element]: inherited ones, its own attributes, then its style."""
    style = dict(inherited)
    for key in ("fill", "fill-rule", "fill-opacity", "stroke", "stroke-width", "stroke-opacity", "opacity"):
        if key in element.attrib:
            style[key] = element.attrib[key]
    for declaration in element.attrib.get("style", "").split(";"):
        if ":" in declaration:
            key, value = declaration.split(":", 1)
            style[key.strip()] = value.strip()
    return style


def shape_path(tag: str, element: ET.Element) -> str | None:
    """SVG basic shapes as path data."""
    g = element.attrib.get
    if tag == "path":
        return g("d")
    if tag == "rect":
        x, y, w, h = number(g("x")), number(g("y")), number(g("width")), number(g("height"))
        rx = number(g("rx"), number(g("ry"), 0.0))
        ry = number(g("ry"), rx)
        rx, ry = min(rx, w / 2), min(ry, h / 2)
        if rx == 0 or ry == 0:
            return f"M{x},{y}h{w}v{h}h{-w}z"
        return (f"M{x + rx},{y}h{w - 2 * rx}a{rx},{ry} 0 0 1 {rx},{ry}v{h - 2 * ry}a{rx},{ry} 0 0 1 {-rx},{ry}"
                f"h{-(w - 2 * rx)}a{rx},{ry} 0 0 1 {-rx},{-ry}v{-(h - 2 * ry)}a{rx},{ry} 0 0 1 {rx},{-ry}z")
    if tag in ("circle", "ellipse"):
        cx, cy = number(g("cx")), number(g("cy"))
        rx = number(g("r")) if tag == "circle" else number(g("rx"))
        ry = number(g("r")) if tag == "circle" else number(g("ry"))
        return f"M{cx - rx},{cy}a{rx},{ry} 0 1 0 {2 * rx},0a{rx},{ry} 0 1 0 {-2 * rx},0z"
    if tag in ("polygon", "polyline"):
        points = re.findall(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?", g("points", ""))
        if len(points) < 4:
            return None
        pairs = [f"{points[i]},{points[i + 1]}" for i in range(0, len(points) - 1, 2)]
        return "M" + "L".join(pairs) + ("z" if tag == "polygon" else "")
    return None


def group_for(transform: str) -> ET.Element:
    """An Android <group> for an SVG transform list (translate, scale, rotate about a point)."""
    group = ET.Element("group")
    operations = re.findall(r"(\w+)\s*\(([^)]*)\)", transform)
    if len(operations) > 1:
        raise BrandError(f"the logo uses the combined transform '{transform}': apply transforms to the paths first (e.g. Inkscape: Path > Object to Path, then save as plain SVG)")
    for name, args in operations:
        values = [float(v) for v in re.findall(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?", args)]
        if name == "translate" and values:
            group.set(a("translateX"), fmt(values[0]))
            group.set(a("translateY"), fmt(values[1] if len(values) > 1 else 0.0))
        elif name == "scale" and values:
            group.set(a("scaleX"), fmt(values[0]))
            group.set(a("scaleY"), fmt(values[1] if len(values) > 1 else values[0]))
        elif name == "rotate" and values:
            group.set(a("rotation"), fmt(values[0]))
            if len(values) == 3:
                group.set(a("pivotX"), fmt(values[1]))
                group.set(a("pivotY"), fmt(values[2]))
        else:
            raise BrandError(f"the logo uses the transform '{name}({args})', which Android vectors can't express: apply it to the paths first")
    return group


def convert_svg(root: ET.Element) -> Logo:
    view_box = root.attrib.get("viewBox")
    if view_box:
        x, y, width, height = (float(v) for v in re.split(r"[\s,]+", view_box.strip()))
    else:
        x, y, width, height = 0.0, 0.0, number(root.attrib.get("width")), number(root.attrib.get("height"))

    def walk(element: ET.Element, inherited: dict[str, str]) -> list[ET.Element]:
        out: list[ET.Element] = []
        for child in element:
            tag = child.tag.split("}")[-1]
            if tag in ("defs", "title", "desc", "metadata", "style"):
                if tag == "style" and (child.text or "").strip():
                    raise BrandError("the logo uses a <style> sheet: export it with presentation attributes instead (e.g. Inkscape: Save As > Optimized SVG)")
                continue
            style = style_of(child, inherited)
            if tag in ("text", "image", "use", "mask", "clipPath", "linearGradient", "radialGradient", "pattern", "filter"):
                raise BrandError(f"the logo contains <{tag}>, which this tool doesn't convert: convert text and images to paths, or use Android Studio's Vector Asset tool and pass the .xml")
            if tag == "g" or tag == "svg":
                children = walk(child, style)
                if "transform" in child.attrib:
                    group = group_for(child.attrib["transform"])
                    group.extend(children)
                    out.append(group)
                else:
                    out.extend(children)
                continue
            data = shape_path(tag, child)
            if not data:
                continue
            path = ET.Element("path")
            path.set(a("pathData"), " ".join(data.split()))
            opacity = float(style.get("opacity", "1"))
            fill = svg_color(style.get("fill", "#000000"))
            if fill:
                path.set(a("fillColor"), fill)
                fill_alpha = float(style.get("fill-opacity", "1")) * opacity
                if fill_alpha < 1:
                    path.set(a("fillAlpha"), fmt(fill_alpha))
                if style.get("fill-rule") == "evenodd":
                    path.set(a("fillType"), "evenOdd")
            stroke = svg_color(style.get("stroke", "none"))
            if stroke:
                path.set(a("strokeColor"), stroke)
                path.set(a("strokeWidth"), fmt(number(style.get("stroke-width"), 1.0)))
                stroke_alpha = float(style.get("stroke-opacity", "1")) * opacity
                if stroke_alpha < 1:
                    path.set(a("strokeAlpha"), fmt(stroke_alpha))
            if fill or stroke:
                if "transform" in child.attrib:
                    group = group_for(child.attrib["transform"])
                    group.append(path)
                    out.append(group)
                else:
                    out.append(path)
        return out

    return Logo(x, y, width, height, walk(root, {}))


def convert_vector(root: ET.Element) -> Logo:
    """An Android vector drawable as a logo: its viewport and children, unchanged."""
    width = number(root.attrib.get(a("viewportWidth")))
    height = number(root.attrib.get(a("viewportHeight")))
    return Logo(0.0, 0.0, width, height, list(root))


def load_logo(path: Path) -> Logo:
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as e:
        raise BrandError(f"couldn't read the logo {path}: {e}") from e
    tag = root.tag.split("}")[-1]
    if tag == "svg":
        return convert_svg(root)
    if tag == "vector":
        return convert_vector(root)
    raise BrandError(f"{path} is neither an SVG nor an Android vector drawable (PNG logos aren't supported: icons must stay sharp at every size)")


def fmt(value: float) -> str:
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


def recolored(elements: list[ET.Element], color: str) -> list[ET.Element]:
    """Copies of [elements] with every fill and stroke in [color], keeping their alpha."""
    copies = []
    for element in elements:
        copy = ET.fromstring(ET.tostring(element))
        for node in copy.iter():
            for attribute in ("fillColor", "strokeColor"):
                if node.get(a(attribute)):
                    node.set(a(attribute), color)
        copies.append(copy)
    return copies


def logo_paints(elements: list[ET.Element]) -> set[str]:
    """Every fill and stroke the logo uses: colors and, in a vector drawable, color references (@color/...)."""
    return {node.get(a(attr)) for element in elements for node in element.iter() for attr in ("fillColor", "strokeColor") if node.get(a(attr))}


def logo_colors(elements: list[ET.Element]) -> set[str]:
    """The logo's literal colors (#RRGGBB), the ones the contrast checks can judge."""
    return {paint for paint in logo_paints(elements) if paint.startswith("#")}


def vanishes(logo: Logo, background: str) -> bool:
    """True if none of the logo's colors stands out from [background] (a multi-color logo only needs one that does)."""
    colors = logo_colors(logo.children)
    return bool(colors) and max(contrast(c, background) for c in colors) < 1.5


def vector_xml(comment: str, width_dp: float, height_dp: float, viewport_w: float, viewport_h: float, children: list[ET.Element]) -> str:
    vector = ET.Element("vector", {a("width"): f"{fmt(width_dp)}dp", a("height"): f"{fmt(height_dp)}dp", a("viewportWidth"): fmt(viewport_w), a("viewportHeight"): fmt(viewport_h)})
    vector.extend(children)
    ET.indent(vector, space="    ")
    body = ET.tostring(vector, encoding="unicode")
    return f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {comment} -->\n{body}\n'


def launcher_foreground(logo: Logo, color: str | None) -> str:
    """The logo centered on the 108dp adaptive-icon canvas, inside the safe circle; in [color] unless None."""
    scale = ICON_SAFE_DIAMETER / math.hypot(logo.width, logo.height)
    group = ET.Element("group", {
        a("translateX"): fmt((ICON_CANVAS - logo.width * scale) / 2 - logo.x * scale),
        a("translateY"): fmt((ICON_CANVAS - logo.height * scale) / 2 - logo.y * scale),
        a("scaleX"): fmt(scale),
        a("scaleY"): fmt(scale),
    })
    group.extend(recolored(logo.children, color) if color else [ET.fromstring(ET.tostring(c)) for c in logo.children])
    return vector_xml("Launcher icon foreground, generated by dev/new-brand.sh from your logo (kept inside the central safe zone).", ICON_CANVAS, ICON_CANVAS, ICON_CANVAS, ICON_CANVAS, [group])


def brand_logo(logo: Logo) -> str:
    """The in-app logo (onboarding, app lock): the logo in its own colors, at most 96dp on its longer side."""
    scale = 96.0 / max(logo.width, logo.height)
    children = [ET.fromstring(ET.tostring(c)) for c in logo.children]
    if logo.x or logo.y:
        group = ET.Element("group", {a("translateX"): fmt(-logo.x), a("translateY"): fmt(-logo.y)})
        group.extend(children)
        children = [group]
    return vector_xml("In-app logo (onboarding, app lock), generated by dev/new-brand.sh from your logo.", logo.width * scale, logo.height * scale, logo.width, logo.height, children)


# --- Files ------------------------------------------------------------------------------------------------------------

def properties(o: argparse.Namespace) -> str:
    suggestions = ",".join(dict.fromkeys([o.mint] + [h.strip() for h in o.suggest.split(",") if h.strip()])) if o.suggest else ""
    return f"""# {o.name}: everything that brands the app. Generated by dev/new-brand.sh; edit freely. See docs/WHITE_LABEL.md.
appName={o.name}
applicationId={o.id}
versionCode=1
versionName=0.1.0
# The LNURLcash mint preselected during onboarding (host, optionally with :port).
defaultMintHost={o.mint}
# Mints offered in the onboarding dropdown (comma-separated; the default is always included). Users can also pick "Custom".
mintSuggestions={suggestions}
allowMintChange={"false" if o.lock else "true"}
# How amounts are shown until the user picks a unit in Settings: bitcoin (BIP-177: ₿1,000) or sats (1,000 sats).
defaultAmountUnit={o.unit}
# Whether users may switch the unit under Settings → Amounts. With false, amounts always show in defaultAmountUnit.
allowUnitChange={"false" if o.lock_unit else "true"}
# Whether every account needs a Lightning address. With false, setup only creates the account (a single button when
# allowMintChange=false); users receive by invoice and can still get an address later in Settings.
requireAddress={"false" if o.no_address else "true"}
# Accounts the app holds (1 hides everything about accounts) and Lightning addresses an account may register per
# mint. 0 means no limit.
maxAccounts={o.max_accounts}
maxAddresses={o.max_addresses}
# Note strategies users can choose from, in this order (built in: single, manual; a code/ module can add its own),
# and the one a new account starts with.
noteStrategies={o.note_strategy if o.no_strategy_choice else ",".join(dict.fromkeys([o.note_strategy, "single", "manual"]))}
defaultNoteStrategy={o.note_strategy}
# "Collect at home mint": notes on other mints are moved to the account's home mint automatically, when the move
# costs at most collectFeeCap percent. hidden = not offered, off = offered and starts off, on = offered and starts on,
# always = every account collects and users have no switch.
collectAtHomeMint={o.collect_at_home}
collectFeeCap={o.collect_fee_cap}
# Where users manage their notes (share, split, combine, recheck, renew): tab = a Notes tab next to Wallet and
# History, settings = a page under Settings → Account, hidden = nowhere (the note strategy keeps them by itself).
notesView={o.notes_view}
# Filters on the History tab: types = received, sent, moved and note maintenance, maintenance = only showing or hiding
# note maintenance, none = no filters (note maintenance stays hidden).
historyFilter={o.history_filter}
# false hides everything about mints: Settings → Mints, the home mint's page, and which mint a claimed note is on.
showMints={"false" if o.hide_mints else "true"}
# Developer options in Settings: shown = always, unlock = hidden until the version in About is tapped seven times,
# hidden = never in release builds (debug builds treat hidden as unlock).
developerOptions={o.developer_options}
# How finished the app is, shown at setup and in About: auto = beta while versionName is below 1.0.0, then stable;
# or alpha, beta, stable as set.
releaseStage=auto
# Mints shown as trusted from the start, each as host:pubkey (the mint's signing key), comma-separated. Users can
# untrust them; each account's home mint is trusted by default anyway.
trustedMints={",".join(o.trusted_mint)}
supportUrl={o.support_url}
"""


def colors_xml(colors: dict[str, str], comment: str) -> str:
    lines = "\n".join(f'    <color name="{name}">{value}</color>' for name, value in colors.items())
    return f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {comment} -->\n<resources>\n{lines}\n</resources>\n'


LAUNCHER_ICON = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/brand_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
"""


def brand_terms(qualifier: str) -> list[tuple[str, str]]:
    """
    The brand-term strings (and plurals) of every feature module in one language, in module order, each as its whole
    element. The block marked "Brand term" in the English strings names them; the elements come from that language's file.
    """
    terms = []
    for english in sorted(ROOT.glob("feature/*/src/main/res/values/strings.xml")):
        block = re.search(r"Brand term:.*?-->(.*?)(?:\n\s*\n|</resources>)", english.read_text(encoding="utf-8"), re.S)
        if not block:
            continue
        translated = english.parent.parent / qualifier / "strings.xml"
        elements = {m.group(2): m.group(0) for m in re.finditer(r'<(string|plurals) name="([^"]+)">.*?</\1>', translated.read_text(encoding="utf-8"), re.S)} if translated.exists() else {}
        terms += [(name, elements[name]) for name in re.findall(r'<(?:string|plurals) name="([^"]+)">', block.group(1)) if name in elements]
    return terms


def languages() -> list[str]:
    """The values qualifiers the app ships strings in: values (English) and values-<lang>."""
    found = {p.parent.name for p in ROOT.glob("feature/*/src/main/res/values*/strings.xml")}
    return sorted(q for q in found if q == "values" or re.fullmatch(r"values-[a-z]{2,3}(-r[A-Z]{2})?", q))


def strings_template(qualifier: str) -> str:
    """The brand-term strings as a commented-out override, so the defaults apply until the brand edits them."""
    terms = brand_terms(qualifier)
    lines = "\n".join(f"    {element}" for _, element in terms)
    language = "English" if qualifier == "values" else qualifier.removeprefix("values-")
    return f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!--
      Brand term ({language}). The app calls the party that holds users' funds a "mint". To use another word, move
      these strings out of this comment and rewrite each as a whole sentence in this language (keep the %1$s
      placeholders). Do it for every language the app ships. See docs/WHITE_LABEL.md.

{lines}
    -->
</resources>
"""


def zapstore_yaml(o: argparse.Namespace) -> str:
    return f"""# Zapstore listing for {o.name}, read by the zsp CLI (https://github.com/zapstore/zsp). Generated by dev/new-brand.sh:
# replace the summary, description and repository with your own; `pubkey` is added by `zsp publish --wizard`.
# See docs/WHITE_LABEL.md §4.
repository: {o.repository}
release_source: ./app/build/outputs/apk/release/app-release.apk
name: {o.name}
summary: A Lightning wallet with bearer notes
description: |
  {o.name} is a Lightning wallet built on LNURLcash: your money is held as bearer notes on {o.mint}, and your
  recovery phrase stays on your phone.
tags:
  - bitcoin
  - lightning
  - wallet
license: MIT
website: {o.support_url}
"""


def write_brand(o: argparse.Namespace, out: Path) -> list[str]:
    """Writes every file of the brand into [out]; returns warnings."""
    warnings: list[str] = []
    light, dark = palette(o.primary, o.secondary, o.tertiary)
    warnings += color_warnings(light, dark)

    if o.logo:
        logo = load_logo(Path(o.logo))
        # A single-color mark is drawn in the button text color, so it always shows on the brand color; a multi-color
        # logo keeps its colors (recoloring would merge its parts into one shape).
        multi_color = len(logo_paints(logo.children)) > 1
        icon_color = None if o.keep_icon_colors or multi_color else light["brand_on_primary"]
        foreground = launcher_foreground(logo, icon_color)
        in_app = brand_logo(logo)
        if icon_color is None and vanishes(logo, light["brand_launcher_background"]):
            warnings.append(f"the logo barely shows on the icon background {light['brand_launcher_background']}: pick another --primary, or use a single-color logo")
        if multi_color:
            warnings.append("the logo has several colors: themed icons (Android's monochrome launcher style) show it as one flat shape; check how it looks, or pass a single-color mark")
        for theme, surface in (("light", LIGHT_SURFACE), ("dark", DARK_SURFACE)):
            if vanishes(logo, surface):
                warnings.append(f"the logo barely shows on the {theme} background of the onboarding screen: give it an outline or a backdrop shape")
    else:
        foreground = (ROOT / "brand/res/drawable/ic_launcher_foreground.xml").read_text(encoding="utf-8")
        in_app = (ROOT / "brand/res/drawable/ic_brand_logo.xml").read_text(encoding="utf-8")
        warnings.append("no --logo given: the brand uses the placeholder banknote icon")

    files = {
        "brand.properties": properties(o),
        "zapstore.yaml": zapstore_yaml(o),
        "res/values/brand_colors.xml": colors_xml(light, f"{o.name} palette, generated by dev/new-brand.sh from {light['brand_primary']}. values-night/ holds the dark variants."),
        "res/values-night/brand_colors.xml": colors_xml(dark, "Dark-theme variants."),
        "res/drawable/ic_launcher_foreground.xml": foreground,
        "res/drawable/ic_brand_logo.xml": in_app,
        "res/mipmap-anydpi/ic_launcher.xml": LAUNCHER_ICON,
    }
    for qualifier in languages():
        files[f"res/{qualifier}/strings.xml"] = strings_template(qualifier)
    for relative, content in files.items():
        target = out / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
    return warnings


# --- Command line -----------------------------------------------------------------------------------------------------

def slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") or "brand"


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="dev/new-brand.sh",
        description="Create a complete brand directory for White Wallet, check it and build it.",
        epilog="Example: dev/new-brand.sh --name 'Acme Cash' --id com.acme.cash --mint mint.acme.com --primary '#0B6E4F' --logo logo.svg",
    )
    parser.add_argument("--name", required=True, help="app name, as shown under the launcher icon")
    parser.add_argument("--id", required=True, help="Android application ID, under a domain you own (e.g. com.acme.cash)")
    parser.add_argument("--mint", required=True, help="the LNURLcash mint preselected in onboarding: host or host:port")
    parser.add_argument("--primary", required=True, help="brand color #RRGGBB; the rest of the palette is derived from it")
    parser.add_argument("--secondary", help="secondary color #RRGGBB (default: derived)")
    parser.add_argument("--tertiary", help="tertiary color #RRGGBB (default: derived)")
    parser.add_argument("--logo", help="logo as SVG or Android vector drawable (.xml); default: the placeholder")
    parser.add_argument("--keep-icon-colors", action="store_true", help="keep a single-color logo's color in the launcher icon (default: drawn in the button text color on the brand color; multi-color logos always keep theirs)")
    parser.add_argument("--unit", choices=("bitcoin", "sats"), default="bitcoin", help="default amount unit (default: bitcoin, BIP-177)")
    parser.add_argument("--lock-unit", action="store_true", help="always show amounts in --unit; users can't switch")
    parser.add_argument("--lock", action="store_true", help="lock users to --mint (hide the mint choice in onboarding)")
    parser.add_argument("--no-address", action="store_true", help="don't ask for a Lightning address at setup; users receive by invoice and can add one later")
    parser.add_argument("--max-accounts", type=int, default=0, help="accounts the app holds; 1 hides accounts entirely (default: 0, no limit)")
    parser.add_argument("--max-addresses", type=int, default=1, help="Lightning addresses per account and mint (default: 1; 0 for no limit)")
    parser.add_argument("--note-strategy", choices=("single", "manual"), default="single", help="how a new account keeps its notes: single (keep one note per mint) or manual (default: single)")
    parser.add_argument("--no-strategy-choice", action="store_true", help="offer only --note-strategy, so users have nothing to choose")
    parser.add_argument("--collect-at-home", choices=("hidden", "off", "on", "always"), default="off", help="moving notes from other mints to the home mint automatically: hidden, off (offered), on, or always (no switch) (default: off)")
    parser.add_argument("--trusted-mint", action="append", default=[], metavar="HOST:PUBKEY", help="a mint shown as trusted from the start, with its signing key (repeatable)")
    parser.add_argument("--history-filter", choices=("types", "maintenance", "none"), default="types", help="filters on the History tab: kinds of entries, only note maintenance, or none (default: types)")
    parser.add_argument("--notes-view", choices=("tab", "settings", "hidden"), default="tab", help="where users manage their notes: a Notes tab, a page in Settings → Account, or hidden (default: tab)")
    parser.add_argument("--developer-options", choices=("shown", "unlock", "hidden"), default="shown", help="Developer options in Settings: shown, unlock (tap the version in About seven times) or hidden in release builds (default: shown)")
    parser.add_argument("--hide-mints", action="store_true", help="hide everything about mints (Settings → Mints, the home mint's page, a claimed note's mint)")
    parser.add_argument("--collect-fee-cap", type=int, default=2, help="the most such a move may cost, in percent (default: 2)")
    parser.add_argument("--suggest", default="", help="further mints for the onboarding dropdown, comma-separated")
    parser.add_argument("--support-url", help="support website (default: https://<mint host>)")
    parser.add_argument("--repository", default="https://github.com/your-org/your-wallet", help="source repository for the Zapstore listing")
    parser.add_argument("--out", help="brand directory to create (default: brands/<name>, ignored by git; for a real brand, its own repository, e.g. ../acme-brand)")
    parser.add_argument("--force", action="store_true", help="replace --out if it exists")
    parser.add_argument("--no-mint-check", action="store_true", help="don't check that the mint answers")
    parser.add_argument("--no-build", action="store_true", help="only write and check the files; don't build an APK")
    o = parser.parse_args(argv)
    o.support_url = o.support_url or f"https://{o.mint.split(':')[0]}"
    o.out = o.out or f"brands/{slug(o.name)}"
    return o


def run(argv: list[str]) -> int:
    sys.stdout.reconfigure(line_buffering=True)
    o = parse_args(argv)
    out = (ROOT / o.out).resolve() if not Path(o.out).is_absolute() else Path(o.out)
    try:
        warnings = check_application_id(o.id)
        check_host(o.mint)
        for host in filter(None, (h.strip() for h in o.suggest.split(","))):
            check_host(host)
        for entry in o.trusted_mint:
            host, _, key = entry.rpartition(":")
            if not host or not re.fullmatch(r"0[23][0-9a-fA-F]{64}", key):
                raise BrandError(f"--trusted-mint {entry}: expected host:pubkey (66 hex characters, 02 or 03 first)")
        if not 0 <= o.collect_fee_cap <= 100:
            raise BrandError("--collect-fee-cap must be 0 to 100 (percent)")
        if o.max_accounts < 0 or o.max_addresses < 0:
            raise BrandError("--max-accounts and --max-addresses must be 0 (no limit) or more")
        if not o.name.strip() or any(c in o.name for c in "\n\\"):
            raise BrandError("--name must be a single line of text")
        if out.exists() and any(out.iterdir()):
            if not o.force:
                raise BrandError(f"{out} already exists; pass --force to replace it")
            if out == ROOT or ROOT.is_relative_to(out):
                raise BrandError(f"refusing to replace {out}")
        if not o.no_mint_check:
            print(f"Checking {mint_url(o.mint)} …")
            check_mint(o.mint)
        if out.exists() and o.force:
            shutil.rmtree(out)
        warnings += write_brand(o, out)
    except BrandError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2

    shown = out.relative_to(ROOT) if out.is_relative_to(ROOT) else out
    print(f"Wrote {shown}/")
    for warning in warnings:
        print(f"warning: {warning}")
    if subprocess.call([str(ROOT / "dev/check-brand.sh"), str(out)], cwd=ROOT) != 0:
        return 1
    if o.no_build:
        print(f"Build it with: ./gradlew :app:assembleDebug -PbrandDir={shown}")
        return 0
    print(f"Building {o.name} …")
    if subprocess.call(["./gradlew", "-q", ":app:assembleDebug", f"-PbrandDir={out}"], cwd=ROOT) != 0:
        return 1
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    print(f"Built {apk.relative_to(ROOT)}: {o.name} ({o.id}.debug), installs next to the original app:")
    print(f"  adb install -r {apk.relative_to(ROOT)}")
    print("Next: edit the brand-term strings and zapstore.yaml in the brand directory, then see docs/WHITE_LABEL.md §4 to sign and publish.")
    return 0


if __name__ == "__main__":
    sys.exit(run(sys.argv[1:]))
