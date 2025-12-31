package com.deepmind.util;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

public class MarkdownParser {
    public static String parse(String md) {
        MutableDataSet options = new MutableDataSet();
        // 如果有扩展插件需求可以在此配置
        Parser parser = Parser.builder(options).build();
        HtmlRenderer renderer = HtmlRenderer.builder(options).build();

        // 仅返回渲染后的 HTML 内容，如 <p>Hello</p>
        return renderer.render(parser.parse(md));
    }
}