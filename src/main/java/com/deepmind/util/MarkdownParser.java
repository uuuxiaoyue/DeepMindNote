package com.deepmind.util;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.parser.ParserEmulationProfile;

import java.util.Arrays;

public class MarkdownParser {
    public static String parse(String md) {
        if (md == null) return "";
        // 1. 高亮: ==文本== -> <mark>文本</mark>
        md = md.replaceAll("==([^=\\n]+)==", "<mark>$1</mark>");
        // 2. 删除线: ~~文本~~ -> <del>文本</del>
        md = md.replaceAll("~~([^~\\n]+)~~", "<del>$1</del>");
        // 3. 倾斜: *文本* -> <i>文本</i>
        md = md.replaceAll("(?<!\\*)\\*([^\\*\\n]+)\\*(?!\\*)", "<i>$1</i>");

//        // 仅返回渲染后的 HTML 内容，如 <p>Hello</p>
//        return renderer.render(parser.parse(md));
        // 2. 标准解析
        MutableDataSet options = new MutableDataSet();
        options.setFrom(ParserEmulationProfile.GITHUB_DOC);
        // 允许表格解析
        options.set(Parser.EXTENSIONS, Arrays.asList(TablesExtension.create()));

        options.set(HtmlRenderer.SOFT_BREAK, "<br />");
        Parser parser = Parser.builder(options).build();
        HtmlRenderer renderer = HtmlRenderer.builder(options).build();
        String contentHtml = renderer.render(parser.parse(md));

        // 3. 核心：注入针对 WebView 的样式表
        // 这里定义的 CSS 会直接作用于 WebView 渲染的内容
        String internalCss =
                "<style>" +
                        "  body { font-family: 'Microsoft YaHei', sans-serif; font-size: 16px; line-height: 1.6; color: #333; }" +
                        "  i, em { " +
                        "    font-style: italic !important; " +
                        "    display: inline-block; " +
                        "    transform: skewX(-15deg); " + // 物理拉伸实现倾斜
                        "    margin-right: 2px; " +
                        "  } "+         // 强制倾斜
                        "  p { margin-top: 0; margin-bottom: 16px; } " + // 修复：给段落增加底部间距
                        "  del { text-decoration: line-through; color: #a0a0a0; } " +     // 灰色删除线
                        "  mark { background-color: #ffeb3b; color: black; padding: 2px; border-radius: 3px; } " + // 高亮
                        "  pre { background-color: #f5f5f5; padding: 10px; border-radius: 5px; overflow-x: auto; } " +
                        "  code { font-family: 'Consolas', monospace; background-color: rgba(27,31,35,0.05); } " +
                        "  img { max-width: 100%; height: auto; display: block; margin: 10px 0; } " + // 保证图片不超出
                        "  pre code { background-color: transparent; padding: 0; } " +
                        ":not(pre) > code { background-color: #f0f0f0; color: #e06c75; padding: 2px 4px; border-radius: 3px; }" +
                        "</style>";

        // 返回一个带样式的完整 HTML
        return "<html><head>" + internalCss + "</head><body>" + contentHtml + "</body></html>";

    }
}