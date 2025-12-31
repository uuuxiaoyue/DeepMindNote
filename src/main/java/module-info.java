module com.deepmind {
    // 1. 声明需要的 JavaFX 模块
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;

    requires com.google.gson;
    requires flexmark;
    requires flexmark.util.ast;
    requires flexmark.util.data;
    requires flexmark.util.sequence;
    requires flexmark.util.html;
    requires flexmark.util.options;
    requires java.desktop;
    requires openhtmltopdf.pdfbox;
    requires javafx.swing; // 必须添加，用于处理图片导出
    requires org.apache.poi.ooxml; // Word 导出

    //允许 Gson 访问你的 util 包进行数据的序列化和反序列化
    opens com.deepmind.util to com.google.gson;
    // 3. 允许 JavaFX 反射访问你的控制器（极其重要）
    opens com.deepmind.controller to javafx.fxml;

    // 4. 导出主程序所在的包
    exports com.deepmind;
}