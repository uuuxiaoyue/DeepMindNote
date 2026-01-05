package com.deepmind.util;

/**
 * 内部类：用于存储大纲条目
 * 包含：显示的文本 + 在文档中的绝对位置
 */
public class OutlineItem {
    public int startIndex;
    public int orderIndex;// 在编辑器中的字符索引位置 (比如 1024)
    String displayText; // 比如 "  ◈ 第一章"

    public OutlineItem(String displayText, int startIndex, int orderIndex) {
        this.displayText = displayText;
        this.startIndex = startIndex;
        this.orderIndex = orderIndex;
    }

    // ListView 默认显示 toString() 的内容
    @Override
    public String toString() {
        return displayText;
    }
}