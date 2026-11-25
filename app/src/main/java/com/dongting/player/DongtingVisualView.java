package com.dongting.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简苹果风大屏舞台视图：
 * 全宽展示高可读性歌词 / 朗读正文，顶部配极简状态胶囊与动态音律条，
 * 无歌词时呈现柔和呼吸同心光环。使用硬件加速渲染，暂停时静止省电。
 */
final class DongtingVisualView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private String title = "地洞播放器";
    private String subtitle = "耳朵在树洞里听见了声音";
    private String body = "扫描文件夹、打开影音或导入 TXT 后，这里会大屏显示歌词与朗读文字。";
    private boolean active;
    private boolean reading;
    /** 朗读文字和带时间轴歌词都带 ▶ 或 【 标记，自动聚焦高亮当前行。 */
    private boolean focusMode;
    private float effect = 0.6f;
    private final long startedAt = System.currentTimeMillis();

    DongtingVisualView(Context context) {
        super(context);
    }

    void setState(String nextTitle, String nextSubtitle, String nextBody, boolean nextActive, boolean nextReading, float nextEffect) {
        title = nextTitle == null || nextTitle.trim().isEmpty() ? "地洞播放器" : nextTitle;
        subtitle = nextSubtitle == null || nextSubtitle.trim().isEmpty() ? "耳朵在树洞里听见了声音" : nextSubtitle;
        body = nextBody == null || nextBody.trim().isEmpty() ? "" : nextBody;
        // Canvas content also needs a readable accessibility representation.
        String description = title + "\n" + subtitle + "\n" + body;
        if (!description.contentEquals(getContentDescription() == null ? "" : getContentDescription())) {
            setContentDescription(description);
        }
        active = nextActive;
        reading = nextReading;
        focusMode = nextReading || body.contains("▶") || body.contains("【");
        effect = Math.max(0.25f, Math.min(2f, nextEffect));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        try {
            drawVisualContent(canvas, width, height);
        } catch (Throwable ignored) {
        }
    }

    private void drawVisualContent(Canvas canvas, int width, int height) {
        float t = (System.currentTimeMillis() - startedAt) / 1000f;
        int accentColor = reading ? UiKit.SECONDARY : UiKit.ACCENT;

        // 1. 苹果风大圆角柔和渐变卡片底
        float radius = dp(22);
        int topColor = UiKit.IS_LIGHT ? UiKit.SURFACE : UiKit.SURFACE_HI;
        int bottomColor = UiKit.SURFACE;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, width, height, topColor, bottomColor, Shader.TileMode.CLAMP));
        rect.set(0, 0, width, height);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);

        // 2. 顶部极柔和主题氛围微光（深色主题更有层次，浅色主题保持通透）
        float glowRadius = Math.max(width, height) * 0.65f;
        float pulseShift = (active || reading) ? (float) Math.sin(t * 1.4f) * dp(12) : 0f;
        paint.setShader(new RadialGradient(
                width * 0.82f + pulseShift, height * 0.16f, glowRadius,
                UiKit.withAlpha(accentColor, UiKit.IS_LIGHT ? 0.10f : 0.16f),
                0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);

        // 3. 细腻描边
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(UiKit.withAlpha(UiKit.STROKE, UiKit.IS_LIGHT ? 0.8f : 0.9f));
        rect.set(dp(1) * 0.5f, dp(1) * 0.5f, width - dp(1) * 0.5f, height - dp(1) * 0.5f);
        canvas.drawRoundRect(rect, radius, radius, paint);

        // 4. 顶部状态胶囊 + 极简动态音律柱
        drawTopBar(canvas, width, accentColor, t);

        // 5. 核心内容区：有歌词/朗读正文时全宽大屏排版；无正文时展示极简优雅封面声波
        boolean hasRichText = !body.isEmpty() && !body.startsWith("暂无歌词");
        if (hasRichText) {
            drawLyricsStage(canvas, width, height, accentColor);
        } else {
            drawAmbientStage(canvas, width, height, accentColor, t);
        }

        // 6. 底部极细微光律动条
        drawBottomWave(canvas, width, height, accentColor, t);

        if (active || reading) {
            postInvalidateDelayed(40);
        }
    }

    private void drawTopBar(Canvas canvas, int width, int accentColor, float t) {
        float padX = dp(18);
        float topY = dp(16);
        String badge = reading ? (active ? "朗读中" : "朗读已暂停")
                : (active ? "正在播放" : "已暂停");

        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(dp(11));
        float textW = textPaint.measureText(badge);
        float pillW = textW + dp(26);
        float pillH = dp(24);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(UiKit.withAlpha(accentColor, UiKit.IS_LIGHT ? 0.14f : 0.18f));
        rect.set(padX, topY, padX + pillW, topY + pillH);
        canvas.drawRoundRect(rect, dp(12), dp(12), paint);

        // 小圆点
        paint.setColor(accentColor);
        canvas.drawCircle(padX + dp(11), topY + pillH / 2f, dp(3), paint);

        textPaint.setColor(accentColor);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textBaseline = topY + pillH / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(badge, padX + dp(19), textBaseline, textPaint);

        // 右侧 4 根极简 Apple Music 风格音律柱
        float barBaseX = width - padX - dp(22);
        float barBottomY = topY + pillH - dp(4);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(UiKit.withAlpha(accentColor, (active || reading) ? 0.9f : 0.35f));
        for (int i = 0; i < 4; i++) {
            float wave = (active || reading)
                    ? 0.35f + 0.65f * Math.abs((float) Math.sin(t * (3.2f + i * 0.7f) + i * 1.1f))
                    : (0.28f + (i % 2) * 0.18f);
            float h = dp(14) * wave;
            float x = barBaseX + i * dp(6);
            rect.set(x, barBottomY - h, x + dp(3), barBottomY);
            canvas.drawRoundRect(rect, dp(2), dp(2), paint);
        }
    }

    private void drawLyricsStage(Canvas canvas, int width, int height, int accentColor) {
        float left = dp(20);
        float right = width - dp(20);
        float maxW = Math.max(dp(80), right - left);

        // 标题与副标题紧凑排在上方
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(dp(16));
        textPaint.setColor(UiKit.TEXT);
        drawSingleLine(canvas, title, left, dp(64), maxW, textPaint);

        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(dp(12));
        textPaint.setColor(UiKit.SUBTLE);
        drawSingleLine(canvas, subtitle, left, dp(84), maxW, textPaint);

        // 正文区域：尽可能多占高度，大字号 + 舒适行高，支持用户字号缩放
        float contentTop = dp(110);
        float contentBottom = height - dp(22);
        float fontScale = UiKit.stageFontScale();
        int textSp = Math.round((reading ? 17 : 16) * fontScale);
        int lineStep = dp(Math.round((reading ? 29 : 27) * fontScale));
        textPaint.setTextSize(dp(textSp));

        List<String> lines = wrapText(body.replace("\r", ""), textPaint, maxW - dp(8));
        int maxLines = Math.max(1, (int) ((contentBottom - contentTop) / lineStep));

        int focusLine = 0;
        if (focusMode) {
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("▶") || lines.get(i).contains("【")) {
                    focusLine = i;
                    break;
                }
            }
        }
        int offset = 0;
        if (focusMode && lines.size() > maxLines) {
            offset = Math.max(0, Math.min(lines.size() - maxLines, focusLine - maxLines / 3));
        }

        int visibleCount = Math.min(maxLines, lines.size() - offset);
        // 行数较少时垂直居中，观感更像 Apple Music 歌词页
        float totalBlockHeight = visibleCount * lineStep;
        float availableH = contentBottom - contentTop;
        float startY = contentTop + (availableH > totalBlockHeight + dp(16)
                ? (availableH - totalBlockHeight) * 0.42f + dp(16)
                : dp(14));

        float y = startY;
        for (int i = 0; i < visibleCount; i++) {
            String rawLine = lines.get(offset + i);
            boolean current = focusMode && (rawLine.contains("▶") || rawLine.contains("【"));
            String display = rawLine.startsWith("▶ ") ? rawLine.substring(2) : rawLine;

            if (current) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(UiKit.withAlpha(accentColor, UiKit.IS_LIGHT ? 0.14f : 0.20f));
                rect.set(left - dp(10), y - dp(textSp + 3), right + dp(6), y + dp(8));
                canvas.drawRoundRect(rect, dp(10), dp(10), paint);
                // 左侧精致强调竖条
                paint.setColor(accentColor);
                rect.set(left - dp(10), y - dp(textSp - 1), left - dp(7), y + dp(4));
                canvas.drawRoundRect(rect, dp(2), dp(2), paint);
            }

            textPaint.setTypeface(current ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            if (current) {
                textPaint.setColor(UiKit.TEXT);
            } else if (focusMode) {
                textPaint.setColor(UiKit.withAlpha(UiKit.SUBTLE, 0.82f));
            } else {
                textPaint.setColor(UiKit.TEXT);
            }
            canvas.drawText(display, left, y, textPaint);
            y += lineStep;
        }
    }

    private void drawAmbientStage(Canvas canvas, int width, int height, int accentColor, float t) {
        float cx = width * 0.5f;
        float cy = height * 0.46f;
        float baseR = Math.min(width, height) * 0.22f;

        // 极简同心呼吸光环
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 3; i++) {
            float pulse = active ? (float) Math.sin(t * 2.0f - i * 0.8f) * dp(4) * effect : 0f;
            float r = baseR * (0.62f + i * 0.34f) + pulse;
            paint.setStrokeWidth(dp(i == 0 ? 3 : 2));
            paint.setColor(UiKit.withAlpha(accentColor, (0.45f - i * 0.12f) * (active ? 1f : 0.65f)));
            canvas.drawCircle(cx, cy, r, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(UiKit.withAlpha(accentColor, UiKit.IS_LIGHT ? 0.14f : 0.20f));
        canvas.drawCircle(cx, cy, baseR * 0.45f, paint);

        // 居中大标题与副标题
        float padX = dp(24);
        float maxW = Math.max(dp(100), width - padX * 2);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(dp(18));
        textPaint.setColor(UiKit.TEXT);
        textPaint.setTextAlign(Paint.Align.CENTER);
        drawSingleLineCentered(canvas, title, cx, height * 0.78f, maxW, textPaint);

        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(dp(13));
        textPaint.setColor(UiKit.SUBTLE);
        String sub = body.isEmpty() ? subtitle : body.replace('\n', ' ');
        drawSingleLineCentered(canvas, sub, cx, height * 0.78f + dp(24), maxW, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawBottomWave(Canvas canvas, int width, int height, int accentColor, float t) {
        float startX = dp(22);
        float endX = width - dp(22);
        float y = height - dp(10);
        paint.setStyle(Paint.Style.FILL);
        int dots = 18;
        float span = endX - startX;
        for (int i = 0; i < dots; i++) {
            float p = i / (float) (dots - 1);
            float wave = (active || reading)
                    ? (float) Math.sin(t * 2.8f + i * 0.45f)
                    : 0f;
            float r = dp(1) + (active || reading ? Math.abs(wave) * dp(1) * 0.9f : 0f);
            float alpha = (active || reading) ? 0.25f + 0.55f * Math.abs(wave) : 0.18f;
            paint.setColor(UiKit.withAlpha(accentColor, alpha));
            canvas.drawCircle(startX + p * span, y + wave * dp(2), r, paint);
        }
    }

    private void drawSingleLine(Canvas canvas, String text, float x, float y, float maxWidth, Paint p) {
        canvas.drawText(ellipsize(text, maxWidth, p), x, y, p);
    }

    private void drawSingleLineCentered(Canvas canvas, String text, float cx, float y, float maxWidth, Paint p) {
        canvas.drawText(ellipsize(text, maxWidth, p), cx, y, p);
    }

    private String ellipsize(String text, float maxWidth, Paint p) {
        String value = text == null ? "" : text;
        if (p.measureText(value) <= maxWidth) return value;
        while (value.length() > 1 && p.measureText(value + "…") > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }
        return value + "…";
    }

    private List<String> wrapText(String text, Paint p, float maxWidth) {
        List<String> lines = new ArrayList<>();
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return lines;
        String[] paragraphs = value.split("\n");
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;
            boolean current = trimmed.startsWith("▶ ");
            if (current) trimmed = trimmed.substring(2);
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < trimmed.length(); i++) {
                char ch = trimmed.charAt(i);
                line.append(ch);
                if (p.measureText(line.toString()) >= maxWidth) {
                    lines.add((current ? "▶ " : "") + line.toString().trim());
                    line.setLength(0);
                }
            }
            if (line.length() > 0) lines.add((current ? "▶ " : "") + line.toString().trim());
        }
        return lines;
    }

    private int dp(int value) {
        return UiKit.dp(getContext(), value);
    }
}
