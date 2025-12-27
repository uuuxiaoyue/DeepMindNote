module com.deepmind {
    // 1. 声明需要的 JavaFX 模块
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;

    // 2. 声明需要的第三方库模块
    // 注意：flexmark 的模块名有时会根据版本变动，如果这里报错，
    // 请尝试 requires flexmark; 或按照 IDE 提示修改
    requires flexmark;
    requires flexmark.util.ast;
    requires flexmark.util.data;
    requires flexmark.util.sequence;
    requires flexmark.util.html;
    requires flexmark.util.options;

    // 3. 允许 JavaFX 反射访问你的控制器（极其重要）
    opens com.deepmind.controller to javafx.fxml;

    // 4. 导出主程序所在的包
    exports com.deepmind;
}