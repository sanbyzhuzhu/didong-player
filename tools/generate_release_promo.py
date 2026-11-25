"""Render v0.0.20 release cards from the existing icon and real app screenshot.

Requires Pillow; has no effect on Android APK dependencies.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/promo/v0.0.20"
OUT.mkdir(parents=True, exist_ok=True)
FONT = "C:/Windows/Fonts/msyh.ttc"
BOLD = "C:/Windows/Fonts/msyhbd.ttc"
BG = "#0B1320"
FG = "#F1F5F9"
MUTED = "#94A3B8"
ACCENT = "#38BDF8"
GREEN = "#34D399"


def text(draw, xy, value, size, color=FG, bold=False):
    draw.text(xy, value, font=ImageFont.truetype(BOLD if bold else FONT, size), fill=color)


def icon(canvas, xy, size):
    image = Image.open(ROOT / "docs/images/app-icon-ocean.png").convert("RGBA")
    image.thumbnail((size, size), Image.Resampling.LANCZOS)
    canvas.paste(image, xy, image)


def main():
    cover = Image.new("RGB", (900, 383), BG)
    d = ImageDraw.Draw(cover)
    d.rounded_rectangle((24, 24, 876, 359), radius=28, fill="#131E30", outline="#263754", width=2)
    icon(cover, (55, 55), 112)
    text(d, (190, 58), "地洞播放器", 48, bold=True)
    text(d, (192, 124), "V0.0.20 · 电子书朗读更新", 23, ACCENT)
    text(d, (58, 206), "电子书，也能一句一句听", 42, bold=True)
    text(d, (60, 283), "EPUB 目录定位  /  MD · HTML · DOCX  /  逐句高亮", 23, GREEN)
    cover.save(OUT / "wechat-cover-900x383.png", optimize=True)

    poster = Image.new("RGB", (1080, 1440), BG)
    d = ImageDraw.Draw(poster)
    icon(poster, (64, 57), 108)
    text(d, (200, 68), "地洞播放器", 50, bold=True)
    text(d, (204, 136), "V0.0.20  ·  Android 电子书朗读", 24, ACCENT)
    text(d, (64, 218), "把手里的书，", 64, bold=True)
    text(d, (64, 302), "一句一句听下去。", 64, GREEN, True)
    cards = [
        ("EPUB 顺着读", "按书内正文顺序，兼容 EPUB 2 / 3 目录。"),
        ("目录点到句", "章节、小节可定位，当前句高亮跟随。"),
        ("多种文件都能听", "TXT / EPUB / Markdown / HTML / DOCX / JSON"),
    ]
    y = 428
    for heading, body in cards:
        d.rounded_rectangle((64, y, 1016, y + 125), radius=24, fill="#131E30", outline="#263754", width=2)
        text(d, (92, y + 18), heading, 34, bold=True)
        text(d, (94, y + 72), body, 25, MUTED)
        y += 147
    d.rounded_rectangle((64, 902, 1016, 1198), radius=24, fill="#101C2A")
    text(d, (92, 928), "本地影音 + 电子书朗读", 34, bold=True)
    for index, line in enumerate(["倍速 / AB 循环 / 播放记忆 / 段落书签",
                                  "系统 TTS / 可选 MultiTTS / 暂停后手动继续",
                                  "约 8 MB · Android 8.0+ · Apache-2.0 开源"]):
        text(d, (94, 997 + index * 51), line, 25, MUTED)
    text(d, (66, 1240), "GitHub  sanbyzhuzhu/didong-player", 27, ACCENT)
    text(d, (66, 1286), "Gitee     sanbyzhu/didong-player", 27, ACCENT)
    text(d, (66, 1352), "纯文字朗读；不支持 DRM、扫描件及旧版 DOC。", 22, MUTED)
    poster.save(OUT / "release-features-1080x1440.png", optimize=True)
    print("Rendered release cards in docs/promo/v0.0.20")


if __name__ == "__main__":
    main()
