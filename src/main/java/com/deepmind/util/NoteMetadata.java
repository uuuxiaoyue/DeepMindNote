package com.deepmind.util;

import java.util.HashMap;
import java.util.Map;

public class NoteMetadata {
    public String title;
    public String lastMood;       // 最近一次保存时的心情
    public String createDate;     // 创建日期
    public String nextReviewDate; // 遗忘曲线计算出的下次复习日期
    public int reviewCount = 0;   // 已复习次数

    // 如果你想做更高级的，可以加这个：
    public Map<String, String> tags = new HashMap<>();

}
