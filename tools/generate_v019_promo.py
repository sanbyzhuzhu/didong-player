from pathlib import Path
import math
import shutil
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(r"e:\Anti\didongplayer")
OUT = ROOT / "marketing" / "v0.0.19"
OUT.mkdir(parents=True, exist_ok=True)
ARTIFACT_DIR = Path(r"C:\Users\Administrator\.gemini\antigravity\brain\cb248ae5-2d93-40af-9bf1-c2a2cb5bbadd")

ICON_NEW_PATH = ROOT / "docs" / "images" / "app-icon-ocean.png"

FONT_BOLD = r"C:\Windows\Fonts\Noto Sans SC Bold (TrueType).otf"
FONT_MED = r"C:\Windows\Fonts\Noto Sans SC Medium (TrueType).otf"
FONT_REG = r"C:\Windows\Fonts\Noto Sans SC (TrueType).otf"
if not Path(FONT_BOLD).exists():
    FONT_BOLD = r"C:\Windows\Fonts\msyhbd.ttc"
    FONT_MED = r"C:\Windows\Fonts\msyh.ttc"
    FONT_REG = r"C:\Windows\Fonts\msyh.ttc"


def fnt(size, weight="regular"):
    path = FONT_BOLD if weight == "bold" else (FONT_MED if weight == "medium" else FONT_REG)
    return ImageFont.truetype(path, size)


def make_ocean_bg(size, glow1=(56, 189, 248, 48), glow2=(52, 211, 153, 36)):
    w, h = size
    base = Image.new("RGBA", (w, h), (11, 19, 32, 255))
    # 柔和背景渐变与双氛围光斑
    overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)
    d.ellipse((-w * 0.25, -h * 0.12, w * 0.75, h * 0.52), fill=glow1)
    d.ellipse((w * 0.35, h * 0.45, w * 1.25, h * 1.10), fill=glow2)
    overlay = overlay.filter(ImageFilter.GaussianBlur(radius=int(min(w, h) * 0.14)))
    return Image.alpha_composite(base, overlay)


def blend_color(base_rgb, fg_rgba):
    if fg_rgba is None:
        return None
    if len(fg_rgba) == 3 or fg_rgba[3] >= 255:
        return fg_rgba[:3] + (255,)
    a = fg_rgba[3] / 255.0
    return (
        int(base_rgb[0] * (1 - a) + fg_rgba[0] * a),
        int(base_rgb[1] * (1 - a) + fg_rgba[1] * a),
        int(base_rgb[2] * (1 - a) + fg_rgba[2] * a),
        255,
    )


def rounded_rect(draw, box, r, fill=None, outline=None, width=1, base_rgb=(11, 19, 32)):
    f = blend_color(base_rgb, fill) if fill is not None else None
    o = blend_color(base_rgb, outline) if outline is not None else None
    draw.rounded_rectangle(box, radius=r, fill=f, outline=o, width=width)


def paste_rounded_icon(canvas, icon_img, xy, size, radius, shadow=True):
    x, y = xy
    resized = icon_img.resize((size, size), Image.Resampling.LANCZOS).convert("RGBA")
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    if shadow:
        sh = Image.new("RGBA", (size + 60, size + 60), (0, 0, 0, 0))
        ImageDraw.Draw(sh).rounded_rectangle((30, 34, size + 29, size + 33), radius=radius, fill=(0, 0, 0, 120))
        sh = sh.filter(ImageFilter.GaussianBlur(radius=18))
        canvas.alpha_composite(sh, (x - 30, y - 24))
    icon_layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    icon_layer.paste(resized, (0, 0), mask)
    canvas.alpha_composite(icon_layer, (x, y))


def draw_pill(draw, cx, y, text, font_obj, bg_color, text_color, outline=None, pad_x=24, pad_y=10):
    tw = draw.textlength(text, font=font_obj)
    bbox = draw.textbbox((0, 0), text, font=font_obj)
    th = bbox[3] - bbox[1]
    pw = int(tw + pad_x * 2)
    ph = int(th + pad_y * 2)
    x1 = int(cx - pw / 2)
    x2 = x1 + pw
    y2 = y + ph
    rounded_rect(draw, (x1, y, x2, y2), ph // 2, fill=bg_color, outline=outline, width=2 if outline else 0)
    draw.text((x1 + pad_x, y + pad_y - bbox[1]), text, font=font_obj, fill=text_color)
    return pw, ph


def draw_centered_text(draw, cx, y, text, font_obj, fill):
    tw = draw.textlength(text, font=font_obj)
    draw.text((cx - tw / 2, y), text, font=font_obj, fill=fill)


def draw_old_treehole_icon(size=260):
    """还原旧版暖棕扁平树洞图标，用于新旧对比图。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img, "RGBA")
    rounded_rect(d, (0, 0, size - 1, size - 1), int(size * 0.22), fill=(36, 20, 11, 255))
    cx, cy = size * 0.5, size * 0.52
    r = size * 0.36
    d.ellipse((cx - r * 0.92, cy - r, cx + r * 0.92, cy + r), fill=(118, 60, 22, 255))
    d.ellipse((cx - r * 0.62, cy - r * 0.72, cx + r * 0.62, cy + r * 0.72), fill=(26, 15, 9, 255))
    for i in range(3):
        rr = r * (0.34 + i * 0.16)
        d.ellipse((cx - rr, cy - rr * 0.76, cx + rr, cy + rr * 0.76), outline=(255, 178, 74, 190), width=4)
    # 音符简笔
    d.rounded_rectangle((cx + r * 0.22, cy - r * 0.45, cx + r * 0.38, cy + r * 0.12), radius=4, fill=(255, 224, 155, 255))
    d.ellipse((cx + r * 0.05, cy - r * 0.02, cx + r * 0.38, cy + r * 0.24), fill=(255, 224, 155, 255))
    return img


def draw_phone_ui(canvas, box, theme, reading_mode=False, compact=False):
    """高保真绘制 V0.0.19 手机真实界面（支持 5 套主题与影音/朗读模式）。"""
    x1, y1, x2, y2 = box
    w, h = x2 - x1, y2 - y1
    d = ImageDraw.Draw(canvas, "RGBA")
    s = w / 520.0  # 缩放系数

    # 手机外框阴影
    sh = Image.new("RGBA", (w + 80, h + 80), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle((40, 46, w + 39, h + 45), radius=int(56 * s), fill=(0, 0, 0, 145))
    sh = sh.filter(ImageFilter.GaussianBlur(radius=22))
    canvas.alpha_composite(sh, (x1 - 40, y1 - 36))

    # 手机机身与屏幕底色
    rounded_rect(d, (x1, y1, x2, y2), int(54 * s), fill=(32, 38, 48, 255), outline=(100, 116, 139, 120), width=max(2, int(3 * s)))
    pad = int(12 * s)
    sx1, sy1, sx2, sy2 = x1 + pad, y1 + pad, x2 - pad, y2 - pad
    sw, sh_h = sx2 - sx1, sy2 - sy1
    rounded_rect(d, (sx1, sy1, sx2, sy2), int(44 * s), fill=theme["bg"])

    # 灵动岛/状态栏
    island_w = int(sw * 0.28)
    island_h = int(20 * s)
    rounded_rect(d, (sx1 + (sw - island_w) // 2, sy1 + int(12 * s), sx1 + (sw + island_w) // 2, sy1 + int(12 * s) + island_h), island_h // 2, fill=(0, 0, 0, 230))
    if not compact:
        d.text((sx1 + int(24 * s), sy1 + int(12 * s)), "20:00", font=fnt(max(11, int(15 * s)), "bold"), fill=theme["text"])
        d.text((sx2 - int(72 * s), sy1 + int(12 * s)), "5G 98%", font=fnt(max(10, int(14 * s)), "medium"), fill=theme["subtle"])

    # 顶部极简导航栏
    top_y = sy1 + int(48 * s)
    px = int(22 * s)
    title_txt = "《认知觉醒》.epub" if reading_mode else "03 深度专注课.mp3"
    sub_txt = "TXT/EPUB 逐句跟读 · 书架" if reading_mode else "本地课程 · 1.50x"
    d.text((sx1 + px, top_y), title_txt, font=fnt(max(12, int(21 * s)), "bold"), fill=theme["text"])
    d.text((sx1 + px, top_y + int(28 * s)), sub_txt, font=fnt(max(9, int(13 * s))), fill=theme["subtle"])

    if not compact:
        # 右侧三个小胶囊：字号 / 主题 / 播放页
        chips = ["字号", "主题", "播放页"]
        cx_r = sx2 - px
        cf = fnt(max(10, int(13 * s)), "medium")
        for ctext in reversed(chips):
            cw = int(d.textlength(ctext, font=cf) + 22 * s)
            ch = int(28 * s)
            rounded_rect(d, (cx_r - cw, top_y + int(4 * s), cx_r, top_y + int(4 * s) + ch), ch // 2, fill=theme["surface"], outline=theme["stroke"], width=1)
            d.text((cx_r - cw + int(11 * s), top_y + int(8 * s)), ctext, font=cf, fill=theme["subtle"])
            cx_r -= cw + int(7 * s)

    # 中部大屏舞台区（占比 ~56%）
    st_y1 = top_y + int(56 * s)
    st_y2 = sy2 - int(258 * s) if not compact else sy2 - int(198 * s)
    rounded_rect(d, (sx1 + px, st_y1, sx2 - px, st_y2), int(26 * s), fill=theme["surface"], outline=theme["stroke"], width=max(1, int(1.5 * s)))

    accent = theme["secondary"] if reading_mode else theme["accent"]
    badge_txt = "●  朗读中" if reading_mode else "●  正在播放"
    bf = fnt(max(9, int(13 * s)), "bold")
    bw = int(d.textlength(badge_txt, font=bf) + 24 * s)
    bh = int(28 * s)
    rounded_rect(d, (sx1 + px + int(18 * s), st_y1 + int(18 * s), sx1 + px + int(18 * s) + bw, st_y1 + int(18 * s) + bh), bh // 2, fill=accent[:3] + (42,), base_rgb=theme["surface"][:3])
    d.text((sx1 + px + int(30 * s), st_y1 + int(22 * s)), badge_txt, font=bf, fill=accent)

    # 右上 4 根音律柱
    bx = sx2 - px - int(46 * s)
    by = st_y1 + int(40 * s)
    heights = [11, 20, 14, 18]
    for i, hh in enumerate(heights):
        rounded_rect(d, (bx + int(i * 8 * s), by - int(hh * s), bx + int((i * 8 + 4) * s), by), int(2 * s), fill=accent)

    # 舞台歌词/逐句跟读正文
    lyrics_y = st_y1 + int(74 * s)
    if reading_mode:
        lines = [
            ("真正拉开人与人差距的，往往不是努力程度，", False),
            ("而是能否在浮躁的世界里保持深度专注。", True),
            ("每天哪怕只有三十分钟沉浸式听书与思考，", True),
            ("日积月累也会形成难以复制的认知复利。", False),
        ]
    else:
        lines = [
            ("耳朵在深海般的静谧空间里听见声音", False),
            ("支持 LRC / SRT / WebVTT 同名自动匹配", True),
            ("当前句柔和聚焦高亮 · 四档字号一键切换", False),
        ]
    lf_norm = fnt(max(10, int(18 * s)), "regular")
    lf_bold = fnt(max(10, int(19 * s)), "bold")
    step = int(42 * s) if not compact else int(30 * s)
    for idx, (line, is_cur) in enumerate(lines):
        ly = lyrics_y + idx * step
        if ly + step > st_y2 - int(22 * s):
            break
        if is_cur:
            rounded_rect(d, (sx1 + px + int(14 * s), ly - int(6 * s), sx2 - px - int(14 * s), ly + int(30 * s)), int(10 * s), fill=accent[:3] + (40,), base_rgb=theme["surface"][:3])
            rounded_rect(d, (sx1 + px + int(14 * s), ly - int(2 * s), sx1 + px + int(19 * s), ly + int(26 * s)), int(3 * s), fill=accent)
            d.text((sx1 + px + int(28 * s), ly), line if not compact else line[:14] + "…", font=lf_bold, fill=theme["text"])
        else:
            d.text((sx1 + px + int(28 * s), ly), line if not compact else line[:14] + "…", font=lf_norm, fill=theme["subtle"])

    # 底部极简控制卡片
    card_y1 = st_y2 + int(14 * s)
    card_y2 = sy2 - int(18 * s)
    rounded_rect(d, (sx1 + px, card_y1, sx2 - px, card_y2), int(24 * s), fill=theme["surface"], outline=theme["stroke"], width=max(1, int(1.5 * s)))

    # 进度文字与进度条
    py = card_y1 + int(16 * s)
    p_left = "朗读 第 12 / 86 段" if reading_mode else "12:38 / 45:20"
    d.text((sx1 + px + int(18 * s), py), p_left, font=fnt(max(9, int(14 * s))), fill=theme["subtle"])
    if not compact:
        rt = "1.50x · 列表循环"
        rf = fnt(max(9, int(14 * s)), "medium")
        d.text((sx2 - px - int(18 * s) - d.textlength(rt, font=rf), py), rt, font=rf, fill=theme["accent"])

    bar_y = py + int(28 * s)
    bx1, bx2 = sx1 + px + int(18 * s), sx2 - px - int(18 * s)
    rounded_rect(d, (bx1, bar_y, bx2, bar_y + max(4, int(6 * s))), int(3 * s), fill=theme["stroke"])
    prog_x = int(bx1 + (bx2 - bx1) * 0.48)
    rounded_rect(d, (bx1, bar_y, prog_x, bar_y + max(4, int(6 * s))), int(3 * s), fill=theme["accent"])
    r_thumb = max(5, int(9 * s))
    d.ellipse((prog_x - r_thumb, bar_y + int(3 * s) - r_thumb, prog_x + r_thumb, bar_y + int(3 * s) + r_thumb), fill=theme["accent"])

    # 核心并排双主按钮：上一首 | 播放/暂停 | 朗读/暂停 | 下一首
    btn_y = bar_y + int(24 * s)
    btn_h = int(56 * s) if not compact else int(42 * s)
    side_w = int(46 * s) if not compact else int(30 * s)
    gap = int(10 * s)
    inner_w = (bx2 - bx1) - side_w * 2 - gap * 3
    main_w = inner_w // 2

    # 上一首
    cy_btn = btn_y + btn_h // 2
    rounded_rect(d, (bx1, btn_y + (btn_h - side_w) // 2, bx1 + side_w, btn_y + (btn_h + side_w) // 2), side_w // 2, fill=theme["surface_hi"])
    pcx = bx1 + side_w // 2
    d.polygon([(pcx - int(6 * s), cy_btn), (pcx + int(5 * s), cy_btn - int(7 * s)), (pcx + int(5 * s), cy_btn + int(7 * s))], fill=theme["text"])
    d.rectangle((pcx - int(8 * s), cy_btn - int(7 * s), pcx - int(6 * s), cy_btn + int(7 * s)), fill=theme["text"])
    # 播放主按钮
    p1x = bx1 + side_w + gap
    rounded_rect(d, (p1x, btn_y, p1x + main_w, btn_y + btn_h), btn_h // 2, fill=theme["accent"])
    pf = fnt(max(10, int(18 * s)), "bold")
    p1t = "播放" if reading_mode else "暂停"
    d.text((p1x + (main_w - d.textlength(p1t, font=pf)) / 2, btn_y + int(14 * s if not compact else 10 * s)), p1t, font=pf, fill=theme["on_accent"])
    # 朗读主按钮
    p2x = p1x + main_w + gap
    rounded_rect(d, (p2x, btn_y, p2x + main_w, btn_y + btn_h), btn_h // 2, fill=theme["secondary"])
    p2t = "暂停朗读" if reading_mode else "朗读"
    d.text((p2x + (main_w - d.textlength(p2t, font=pf)) / 2, btn_y + int(14 * s if not compact else 10 * s)), p2t, font=pf, fill=theme["on_secondary"])
    # 下一首
    p3x = p2x + main_w + gap
    rounded_rect(d, (p3x, btn_y + (btn_h - side_w) // 2, p3x + side_w, btn_y + (btn_h + side_w) // 2), side_w // 2, fill=theme["surface_hi"])
    ncx = p3x + side_w // 2
    d.polygon([(ncx + int(6 * s), cy_btn), (ncx - int(5 * s), cy_btn - int(7 * s)), (ncx - int(5 * s), cy_btn + int(7 * s))], fill=theme["text"])
    d.rectangle((ncx + int(6 * s), cy_btn - int(7 * s), ncx + int(8 * s), cy_btn + int(7 * s)), fill=theme["text"])

    # 底部 4 个极简入口
    if not compact:
        dock_y = btn_y + btn_h + int(16 * s)
        dock_h = int(48 * s)
        docks = ["列表/书架", "工具箱", "音效", "设置"]
        dw = (bx2 - bx1 - gap * 3) // 4
        df = fnt(max(9, int(13 * s)), "medium")
        for i, label in enumerate(docks):
            dx = bx1 + i * (dw + gap)
            rounded_rect(d, (dx, dock_y, dx + dw, dock_y + dock_h), int(14 * s), fill=theme["bg"], outline=theme["stroke"], width=1)
            d.text((dx + (dw - d.textlength(label, font=df)) / 2, dock_y + int(15 * s)), label, font=df, fill=theme["subtle"])


THEMES = [
    {
        "name": "静谧深海",
        "bg": (11, 19, 32, 255), "surface": (19, 30, 48, 255), "surface_hi": (28, 42, 66, 255),
        "stroke": (38, 55, 84, 255), "text": (241, 245, 249, 255), "subtle": (148, 163, 184, 255),
        "accent": (56, 189, 248, 255), "on_accent": (7, 19, 32, 255),
        "secondary": (52, 211, 153, 255), "on_secondary": (6, 32, 24, 255),
    },
    {
        "name": "Apple 极简白",
        "bg": (242, 242, 247, 255), "surface": (255, 255, 255, 255), "surface_hi": (233, 233, 239, 255),
        "stroke": (216, 216, 224, 255), "text": (28, 28, 30, 255), "subtle": (99, 99, 102, 255),
        "accent": (0, 122, 255, 255), "on_accent": (255, 255, 255, 255),
        "secondary": (52, 199, 89, 255), "on_secondary": (255, 255, 255, 255),
    },
    {
        "name": "Apple 午夜黑",
        "bg": (0, 0, 0, 255), "surface": (28, 28, 30, 255), "surface_hi": (44, 44, 46, 255),
        "stroke": (56, 56, 58, 255), "text": (245, 245, 247, 255), "subtle": (174, 174, 178, 255),
        "accent": (250, 45, 72, 255), "on_accent": (255, 255, 255, 255),
        "secondary": (48, 209, 88, 255), "on_secondary": (4, 29, 10, 255),
    },
    {
        "name": "暖纸护眼",
        "bg": (246, 240, 228, 255), "surface": (253, 251, 247, 255), "surface_hi": (237, 227, 209, 255),
        "stroke": (221, 208, 186, 255), "text": (43, 33, 24, 255), "subtle": (110, 93, 77, 255),
        "accent": (200, 109, 45, 255), "on_accent": (255, 255, 255, 255),
        "secondary": (46, 139, 120, 255), "on_secondary": (255, 255, 255, 255),
    },
    {
        "name": "树洞暖琥珀",
        "bg": (12, 8, 5, 255), "surface": (24, 16, 9, 255), "surface_hi": (34, 23, 16, 255),
        "stroke": (51, 36, 26, 255), "text": (245, 237, 226, 255), "subtle": (179, 160, 138, 255),
        "accent": (255, 180, 81, 255), "on_accent": (20, 12, 5, 255),
        "secondary": (79, 216, 196, 255), "on_secondary": (5, 26, 23, 255),
    },
]

ICON_NEW = Image.open(ICON_NEW_PATH).convert("RGBA")


def save_poster(img, filename):
    out_path = OUT / filename
    img.convert("RGB").save(out_path, quality=95, optimize=True)
    # 同步一份到 artifact 目录便于聊天内预览
    shutil.copy2(out_path, ARTIFACT_DIR / f"promo_{filename}")
    print("Saved:", out_path)


def build_01_cover_hero():
    w, h = 1242, 1660
    img = make_ocean_bg((w, h))
    d = ImageDraw.Draw(img, "RGBA")

    # 顶部徽章 + 新图标
    paste_rounded_icon(img, ICON_NEW, (w // 2 - 64, 72), 128, 30)
    draw_pill(d, w // 2, 224, "V0.0.19  全新极简苹果风", fnt(28, "bold"), (56, 189, 248, 36), (56, 189, 248, 255), outline=(56, 189, 248, 90))
    draw_centered_text(d, w // 2, 300, "地洞播放器", fnt(90, "bold"), (248, 250, 252, 255))
    draw_centered_text(d, w // 2, 412, "大屏字幕 · 双主控一键听书 · 5 套主题随心切", fnt(34, "medium"), (148, 163, 184, 255))

    # 居中手机样机（静谧深海主界面）
    draw_phone_ui(img, (w // 2 - 285, 495, w // 2 + 285, 1515), THEMES[0], reading_mode=False)

    # 底部标签
    draw_centered_text(d, w // 2, 1565, "本地影音 · EPUB / TXT 逐句跟读 · 7.8MB 零广告开源  |  公众号：小二菜园", fnt(26, "medium"), (148, 163, 184, 230))
    save_poster(img, "01_cover_hero_1242x1660.png")


def build_02_icon_evolution():
    w, h = 1242, 1660
    img = make_ocean_bg((w, h), glow1=(56, 189, 248, 54), glow2=(52, 211, 153, 40))
    d = ImageDraw.Draw(img, "RGBA")

    draw_pill(d, w // 2, 92, "视觉全面进化", fnt(28, "bold"), (56, 189, 248, 36), (56, 189, 248, 255), outline=(56, 189, 248, 90))
    draw_centered_text(d, w // 2, 174, "全新「静谧深海」图标", fnt(76, "bold"), (248, 250, 252, 255))
    draw_centered_text(d, w // 2, 276, "告别旧版杂乱线条，苹果级磨砂玻璃声洞质感", fnt(34), (148, 163, 184, 255))

    # 新旧对比大卡片
    rounded_rect(d, (76, 365, w - 76, 975), 36, fill=(19, 30, 48, 225), outline=(56, 189, 248, 70), width=2)

    # 左侧旧图标
    old_icon = draw_old_treehole_icon(250)
    paste_rounded_icon(img, old_icon, (165, 515), 240, 54, shadow=True)
    draw_centered_text(d, 285, 790, "旧版图标", fnt(34, "bold"), (148, 163, 184, 255))
    draw_centered_text(d, 285, 840, "V0.0.16 扁平暖棕", fnt(26), (100, 116, 139, 255))

    # 中间大箭头
    ax1, ax2, ay = 475, 600, 635
    d.line((ax1, ay, ax2, ay), fill=(56, 189, 248, 255), width=8)
    d.polygon([(ax2 + 24, ay), (ax2 - 10, ay - 22), (ax2 - 10, ay + 22)], fill=(56, 189, 248, 255))
    draw_centered_text(d, 545, 568, "焕新升级", fnt(26, "bold"), (56, 189, 248, 255))

    # 右侧超大新图标 + 呼吸光晕
    glow = Image.new("RGBA", (460, 460), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((40, 40, 420, 420), fill=(56, 189, 248, 75))
    glow = glow.filter(ImageFilter.GaussianBlur(radius=45))
    img.alpha_composite(glow, (640, 405))
    paste_rounded_icon(img, ICON_NEW, (695, 455), 350, 82, shadow=True)
    draw_centered_text(d, 870, 835, "新版 · 静谧深海", fnt(38, "bold"), (56, 189, 248, 255))
    draw_centered_text(d, 870, 890, "V0.0.19 磨砂声洞", fnt(26), (226, 232, 240, 255))

    # 下方 3 张细节亮点卡片
    cards = [
        ("自适应安全边距", "专为荣耀 / 小米 / 原生桌面圆角优化\n双环光晕完整居中，绝不裁切"),
        ("状态栏小图标同步", "后台播放与后台朗读通知小图标\n同步升级为极简双环 + 播放三角"),
        ("锁屏封面与 Windows 同款", "无专辑图自动展示深海高清封面\nWindows 网页版同步换装"),
    ]
    cy = 1025
    for idx, (ct, cd) in enumerate(cards):
        rounded_rect(d, (76, cy, w - 76, cy + 162), 26, fill=(19, 30, 48, 235), outline=(38, 55, 84, 255), width=2)
        rounded_rect(d, (112, cy + 44, 122, cy + 118), 5, fill=(52, 211, 153, 255) if idx == 1 else (56, 189, 248, 255))
        d.text((152, cy + 28), ct, font=fnt(36, "bold"), fill=(241, 245, 249, 255))
        d.text((152, cy + 84), cd, font=fnt(25), fill=(148, 163, 184, 255), spacing=8)
        cy += 186

    save_poster(img, "02_icon_evolution_1242x1660.png")


def build_03_reading_highlight():
    w, h = 1242, 1660
    img = make_ocean_bg((w, h), glow1=(52, 211, 153, 52), glow2=(56, 189, 248, 42))
    d = ImageDraw.Draw(img, "RGBA")

    draw_pill(d, w // 2, 76, "听书神器 · 离线免流", fnt(28, "bold"), (52, 211, 153, 38), (52, 211, 153, 255), outline=(52, 211, 153, 100))
    draw_centered_text(d, w // 2, 156, "直接听 EPUB & TXT", fnt(80, "bold"), (248, 250, 252, 255))
    draw_centered_text(d, w // 2, 260, "逐句高亮跟读 · 四档字号 · 段落进度自由拖", fnt(34, "medium"), (148, 163, 184, 255))

    # 居中手机样机（朗读高亮模式）
    draw_phone_ui(img, (w // 2 - 285, 345, w // 2 + 285, 1340), THEMES[0], reading_mode=True)

    # 底部两行 6 枚亮点胶囊
    tags_row1 = ["直读 EPUB / TXT / JSON", "逐句高亮不丢半句", "四档舞台字号"]
    tags_row2 = ["章节目录 & 书签搜索", "支持 MultiTTS 接口", "朗读背景轻音乐"]
    for row_idx, row_tags in enumerate([tags_row1, tags_row2]):
        y = 1395 + row_idx * 92
        xs = [235, 621, 1007]
        for cx, tag in zip(xs, row_tags):
            draw_pill(d, cx, y, tag, fnt(26, "medium"), (19, 30, 48, 240), (226, 232, 240, 255), outline=(52, 211, 153, 95), pad_x=26, pad_y=14)

    save_poster(img, "03_reading_epub_txt_1242x1660.png")


def build_04_five_themes():
    w, h = 1242, 1660
    img = make_ocean_bg((w, h), glow1=(56, 189, 248, 45), glow2=(168, 85, 247, 36))
    d = ImageDraw.Draw(img, "RGBA")

    draw_pill(d, w // 2, 74, "界面彻底重构", fnt(28, "bold"), (56, 189, 248, 36), (56, 189, 248, 255), outline=(56, 189, 248, 90))
    draw_centered_text(d, w // 2, 152, "极简苹果风 · 5 套主题", fnt(76, "bold"), (248, 250, 252, 255))
    draw_centered_text(d, w // 2, 254, "大舞台 + 同屏双主按钮，低频功能收进底部抽屉", fnt(33), (148, 163, 184, 255))

    # 上排 3 台手机（静谧深海 / Apple 极简白 / Apple 午夜黑）
    top_boxes = [
        (68, 345, 418, 915),
        (446, 345, 796, 915),
        (824, 345, 1174, 915),
    ]
    for i, b in enumerate(top_boxes):
        draw_phone_ui(img, b, THEMES[i], reading_mode=(i == 1), compact=True)
        draw_centered_text(d, (b[0] + b[2]) // 2, b[3] + 18, THEMES[i]["name"], fnt(30, "bold"), (241, 245, 249, 255))

    # 下排 2 台手机（暖纸护眼 / 树洞暖琥珀）
    bot_boxes = [
        (240, 1005, 590, 1565),
        (652, 1005, 1002, 1565),
    ]
    for idx, b in enumerate(bot_boxes):
        t = THEMES[idx + 3]
        draw_phone_ui(img, b, t, reading_mode=(idx == 0), compact=True)
        draw_centered_text(d, (b[0] + b[2]) // 2, b[3] + 18, t["name"], fnt(30, "bold"), (241, 245, 249, 255))

    save_poster(img, "04_five_themes_1242x1660.png")


def build_05_bento_features():
    w, h = 1242, 1660
    img = make_ocean_bg((w, h))
    d = ImageDraw.Draw(img, "RGBA")

    paste_rounded_icon(img, ICON_NEW, (76, 76), 92, 22, shadow=False)
    d.text((192, 76), "地洞播放器 V0.0.19", font=fnt(52, "bold"), fill=(248, 250, 252, 255))
    d.text((192, 142), "听课 · 听书 · 本地影音一站式开源小工具", font=fnt(28), fill=(148, 163, 184, 255))

    # 2 列 × 4 行 Bento 卡片
    items = [
        ("EPUB & TXT 听书", "直接导入 EPUB / TXT / JSON\n支持书架、章节目录、搜索与书签", (52, 211, 153, 255)),
        ("逐句高亮跟读", "不依赖引擎字词回调，逐句精准高亮\n四档字号缩放，暂停续读不丢半句", (56, 189, 248, 255)),
        ("极简双主控界面", "「播放/暂停」与「朗读/暂停」同屏并排\n舞台占比超 60%，其余收进底部抽屉", (56, 189, 248, 255)),
        ("5 套苹果风主题", "静谧深海 / Apple 极简白 / 午夜黑\n暖纸护眼 / 树洞暖琥珀一键切换", (168, 85, 247, 255)),
        ("0.25x ~ 8.0x 倍速", "文件夹级音视频独立记忆倍速与进度\n每 5 秒自动落盘，重启不丢位置", (255, 180, 81, 255)),
        ("AB 循环 & 字幕", "AB 点复读 + ±1 秒精准微调\nLRC / SRT / WebVTT 同名自动匹配", (52, 211, 153, 255)),
        ("五段 EQ & 稳后台", "6 套音效预设 + 无损增益\n摘耳机 / 来电自动暂停，后台稳固", (56, 189, 248, 255)),
        ("Android + Windows", "安装包仅 7.8MB，零广告零联网追踪\n附赠 Windows 极简多主题网页版", (255, 180, 81, 255)),
    ]

    pad_x = 72
    gap = 26
    card_w = (w - pad_x * 2 - gap) // 2
    card_h = 285
    start_y = 230

    for i, (title, desc, acc) in enumerate(items):
        row = i // 2
        col = i % 2
        x1 = pad_x + col * (card_w + gap)
        y1 = start_y + row * (card_h + gap)
        x2, y2 = x1 + card_w, y1 + card_h
        rounded_rect(d, (x1, y1, x2, y2), 28, fill=(19, 30, 48, 238), outline=(38, 55, 84, 255), width=2)
        # 顶部强调色圆角小条
        rounded_rect(d, (x1 + 30, y1 + 30, x1 + 88, y1 + 40), 5, fill=acc)
        d.text((x1 + 30, y1 + 60), title, font=fnt(38, "bold"), fill=(248, 250, 252, 255))
        d.text((x1 + 30, y1 + 128), desc, font=fnt(25), fill=(148, 163, 184, 255), spacing=12)

    draw_centered_text(d, w // 2, 1562, "Apache-2.0 开源免费  ·  首发公众号：小二菜园  ·  微信/QQ：254850837", fnt(27, "medium"), (148, 163, 184, 235))
    save_poster(img, "05_features_bento_1242x1660.png")


def build_06_wechat_cover():
    w, h = 900, 383
    img = make_ocean_bg((w, h), glow1=(56, 189, 248, 60), glow2=(52, 211, 153, 45))
    d = ImageDraw.Draw(img, "RGBA")

    paste_rounded_icon(img, ICON_NEW, (64, 82), 218, 50, shadow=True)
    tx = 326
    draw_pill(d, tx + 110, 68, "V0.0.19 全面焕新", fnt(22, "bold"), (56, 189, 248, 38), (56, 189, 248, 255), outline=(56, 189, 248, 90), pad_x=18, pad_y=6)
    d.text((tx, 128), "地洞播放器", font=fnt(64, "bold"), fill=(248, 250, 252, 255))
    d.text((tx, 218), "极简苹果风 · 5 套主题 · EPUB/TXT 逐句跟读", font=fnt(26, "medium"), fill=(203, 213, 225, 255))
    d.text((tx, 276), "本地影音 · 双主控一键听书 · 7.8MB 零广告开源", font=fnt(22), fill=(148, 163, 184, 255))
    d.text((w - 220, h - 42), "公众号：小二菜园", font=fnt(20), fill=(100, 116, 139, 255))

    save_poster(img, "06_wechat_cover_900x383.png")


if __name__ == "__main__":
    build_01_cover_hero()
    build_02_icon_evolution()
    build_03_reading_highlight()
    build_04_five_themes()
    build_05_bento_features()
    build_06_wechat_cover()
    print("ALL_6_PROMO_POSTERS_DONE")
