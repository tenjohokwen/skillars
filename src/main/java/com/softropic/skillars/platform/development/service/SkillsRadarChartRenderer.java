package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.SkillRadarEntry;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

public class SkillsRadarChartRenderer {

    static {
        // Fallback: only takes effect if set before the AWT toolkit initialises.
        // For reliable enforcement, add -Djava.awt.headless=true to JVM startup args.
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
    }

    private SkillsRadarChartRenderer() {}

    public static byte[] renderToPng(List<SkillRadarEntry> skills, int widthPx) {
        List<SkillRadarEntry> active = skills.stream()
            .filter(s -> s.compositeScore() != null)
            .toList();

        BufferedImage img = new BufferedImage(widthPx, widthPx, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, widthPx, widthPx);

        int cx = widthPx / 2, cy = widthPx / 2;
        int r = (int) (widthPx * 0.38);

        int n = active.size();
        if (n < 3) {
            g.setColor(Color.LIGHT_GRAY);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.drawString("No assessment data", cx - 60, cy);
            g.dispose();
            return toPngBytes(img);
        }

        g.setColor(new Color(220, 220, 220));
        g.setStroke(new BasicStroke(1f));
        for (int pct : new int[]{25, 50, 75, 100}) {
            int cr = r * pct / 100;
            g.drawOval(cx - cr, cy - cr, cr * 2, cr * 2);
        }

        g.setColor(new Color(200, 200, 200));
        for (int i = 0; i < n; i++) {
            double angle = (2 * Math.PI * i / n) - Math.PI / 2;
            int x = (int) (cx + r * Math.cos(angle));
            int y = (int) (cy + r * Math.sin(angle));
            g.drawLine(cx, cy, x, y);
        }

        int[] scoreXs = new int[n], scoreYs = new int[n];
        for (int i = 0; i < n; i++) {
            double angle = (2 * Math.PI * i / n) - Math.PI / 2;
            double score = active.get(i).compositeScore().doubleValue();
            double clamped = Math.max(0, Math.min(score, 100));
            int pr = (int) (r * clamped / 100);
            scoreXs[i] = (int) (cx + pr * Math.cos(angle));
            scoreYs[i] = (int) (cy + pr * Math.sin(angle));
        }
        g.setColor(new Color(59, 130, 246, 80));
        g.fillPolygon(scoreXs, scoreYs, n);
        g.setColor(new Color(59, 130, 246));
        g.setStroke(new BasicStroke(2f));
        g.drawPolygon(scoreXs, scoreYs, n);

        boolean hasBaseline = active.stream().anyMatch(s -> s.baselineScore() != null);
        if (hasBaseline) {
            boolean allHaveBaseline = active.stream().allMatch(s -> s.baselineScore() != null);
            if (allHaveBaseline) {
                int[] baseXs = new int[n], baseYs = new int[n];
                for (int i = 0; i < n; i++) {
                    double angle = (2 * Math.PI * i / n) - Math.PI / 2;
                    double clamped = Math.max(0, Math.min(active.get(i).baselineScore().doubleValue(), 100));
                    int pr = (int) (r * clamped / 100);
                    baseXs[i] = (int) (cx + pr * Math.cos(angle));
                    baseYs[i] = (int) (cy + pr * Math.sin(angle));
                }
                g.setColor(new Color(150, 150, 150, 100));
                float[] dash = {4f, 3f};
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f));
                g.drawPolygon(baseXs, baseYs, n);
            } else {
                // Partial baselines — draw dots per axis to avoid a distorted polygon
                g.setColor(new Color(150, 150, 150));
                g.setStroke(new BasicStroke(1.5f));
                for (int i = 0; i < n; i++) {
                    BigDecimal bs = active.get(i).baselineScore();
                    if (bs == null) continue;
                    double angle = (2 * Math.PI * i / n) - Math.PI / 2;
                    double clamped = Math.max(0, Math.min(bs.doubleValue(), 100));
                    int pr = (int) (r * clamped / 100);
                    int bx = (int) (cx + pr * Math.cos(angle));
                    int by = (int) (cy + pr * Math.sin(angle));
                    g.fillOval(bx - 3, by - 3, 6, 6);
                }
            }
        }

        g.setColor(new Color(60, 60, 60));
        g.setFont(new Font("SansSerif", Font.BOLD, Math.max(8, widthPx / 40)));
        FontMetrics fm = g.getFontMetrics();
        int labelR = r + 20;
        for (int i = 0; i < n; i++) {
            double angle = (2 * Math.PI * i / n) - Math.PI / 2;
            int lx = (int) (cx + labelR * Math.cos(angle));
            int ly = (int) (cy + labelR * Math.sin(angle));
            String code = active.get(i).skillCode();
            g.drawString(code, lx - fm.stringWidth(code) / 2, ly + fm.getAscent() / 2);
        }

        g.dispose();
        return toPngBytes(img);
    }

    private static byte[] toPngBytes(BufferedImage img) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "PNG", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode radar chart PNG", e);
        }
    }
}
