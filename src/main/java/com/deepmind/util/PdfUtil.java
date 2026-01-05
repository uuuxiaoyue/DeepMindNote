package com.deepmind.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import java.io.File;
import java.io.IOException;

public class PdfUtil {
    public static String readPdf(File file) throws Exception {
        // 提示：PDFBox 第一次运行会扫描系统字体，可能会有警告日志，属于正常现象
        try (PDDocument document = PDDocument.load(file)) {
            if (document.isEncrypted()) {
                throw new IOException("无法导入：该文件已加密保护。");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true); // 保证分栏排版的阅读顺序

            String text = stripper.getText(document);

            if (text == null || text.trim().isEmpty()) {
                return "### 导入失败\n> 该 PDF 可能是纯图片扫描件，请使用 OCR 软件转文字后再导入。";
            }

            // 格式美化：尝试修复 PDF 常见的强制换行问题（可选）
            // text = text.replaceAll("(?<![。！？])\\r\\n", "");

            return text;
        } catch (Exception e) {
            // 针对字体 EOFException 的特殊处理提示
            if (e.getMessage() != null && e.getMessage().contains("EOF")) {
                throw new Exception("PDF字体库加载异常，请尝试重新打开软件或更新PDFBox版本。");
            }
            throw e;
        }
    }
}