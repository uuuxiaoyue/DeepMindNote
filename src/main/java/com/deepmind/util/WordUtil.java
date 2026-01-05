package com.deepmind.util;

import org.apache.poi.xwpf.usermodel.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.util.*;

public class WordUtil {
    public static String readDocx(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        File imageDir = new File("notes/images");
        if (!imageDir.exists()) imageDir.mkdirs();

        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis)) {

            // 1. 提取所有图片并记录，用于保底和去重
            Map<String, String> imageIdToPath = new HashMap<>();
            Set<String> placedImages = new HashSet<>(); // 记录已经放入正文的图片路径

            for (XWPFPictureData pic : document.getAllPictures()) {
                String rId = document.getRelationId(pic);
                String fileName = "word_v3_" + System.currentTimeMillis() + "_" + pic.getFileName();
                File outPic = new File(imageDir, fileName);
                try (FileOutputStream fos = new FileOutputStream(outPic)) {
                    fos.write(pic.getData());
                }
                imageIdToPath.put(rId, "images/" + fileName);
            }

            // 2. 遍历正文（尝试按顺序插图）
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph para) {
                    processParagraph(para, sb, imageIdToPath, placedImages);
                    sb.append("\n\n");
                } else if (element instanceof XWPFTable table) {
                    sb.append(parseTableToMarkdown(table)).append("\n\n");
                }
            }

            // 3. 【核心保底】检查是否有漏掉的图片（比如浮动图片、背景图等）
            boolean hasMissingImages = false;
            for (String path : imageIdToPath.values()) {
                if (!placedImages.contains(path)) {
                    if (!hasMissingImages) {
                        sb.append("\n\n---\n> ### 附加图片 (未能自动定位位置)\n\n");
                        hasMissingImages = true;
                    }
                    sb.append("![](").append(path).append(")  \n");
                }
            }
        }
        return sb.toString();
    }

    private static void processParagraph(XWPFParagraph para, StringBuilder sb, Map<String, String> imageMap, Set<String> placedImages) {
        for (XWPFRun run : para.getRuns()) {
            // 处理文字
            String text = run.getText(0);
            if (text != null) sb.append(text);

            // 处理图片 (针对嵌入式图片)
            List<XWPFPicture> pictures = run.getEmbeddedPictures();
            for (XWPFPicture pic : pictures) {
                try {
                    String rId = pic.getCTPicture().getBlipFill().getBlip().getEmbed();
                    String path = imageMap.get(rId);
                    if (path != null) {
                        sb.append("\n\n![](").append(path).append(")\n\n");
                        placedImages.add(path); // 标记已放置
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private static String parseTableToMarkdown(XWPFTable table) {
        StringBuilder tableContent = new StringBuilder();
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return "";

        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            tableContent.append("| ");
            for (XWPFTableCell cell : row.getTableCells()) {
                String cellText = cell.getText().trim().replace("\n", "<br>");
                tableContent.append(cellText).append(" | ");
            }
            tableContent.append("\n");

            if (i == 0) {
                tableContent.append("|");
                for (int j = 0; j < row.getTableCells().size(); j++) {
                    tableContent.append(" --- |");
                }
                tableContent.append("\n");
            }
        }
        return tableContent.toString();
    }
}