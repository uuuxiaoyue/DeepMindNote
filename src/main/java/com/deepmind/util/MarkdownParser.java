package com.deepmind.util;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

public class MarkdownParser {
    public static String parse(String md) {
        MutableDataSet options = new MutableDataSet();
        // 设置一些扩展插件（可选）
        Parser parser = Parser.builder(options).build();
        HtmlRenderer renderer = HtmlRenderer.builder(options).build();

        String content = renderer.render(parser.parse(md));

        // 关键：给 HTML 加上 UTF-8 编码和基础 CSS，防止中文乱码和样式太丑
        return "<html><head><meta charset='UTF-8'><style>" +
                "body { font-family: 'Microsoft YaHei', sans-serif; padding: 15px; line-height: 1.5; }" +
                "pre { background: #f0f0f0; padding: 10px; border-radius: 5px; }" +
                "blockquote { color: gray; border-left: 4px solid #ccc; padding-left: 10px; }" +
                "</style></head><body>" + content + "</body></html>";
    }
}