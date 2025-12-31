package com.deepmind.controller;

import com.deepmind.util.FileUtil;
import com.deepmind.util.MarkdownParser;
import com.deepmind.util.NoteMetadata;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import java.io.IOException;
import java.util.List;


public class MainController {

    // --- 核心编辑区 ---
    @FXML private TextArea editorArea;
    @FXML private WebView webView;
    @FXML private Label wordCountLabel;

    // --- 左侧单栏文件树 ---
    @FXML private TreeView<String> fileTree;
    @FXML private TextField searchField;
    @FXML private VBox sidebarContainer;

    // --- 右侧大纲 ---
    @FXML private ListView<String> outlineListView;
    @FXML private VBox outlineContainer;

    // --- 整体布局与工具栏 ---
    @FXML private VBox rootContainer;
    @FXML private SplitPane splitPane;

    // 注意：btnToggleMenu 已删除
    @FXML private ToggleButton btnToggleSidebar;
    @FXML private MenuBar mainMenuBar;

    // --- 状态变量 ---
    private String currentNoteTitle = "";
    private double lastDividerPosition = 0.2;

    @FXML
    public void initialize() {
        FileUtil.initStorage();

        refreshFileTree();
        setupTreeSelection();
        setupContextMenu();

        setupSearch();
        setupOutline();
        setupWordCount();

        showRandomReview();
        showWelcomePage();

    }

    /**
     * 核心逻辑：文件树加载
     */
    private void refreshFileTree() {
        TreeItem<String> root = new TreeItem<>("Root");

        // 我们用一个列表来保存已创建的分类节点，避免重复
        java.util.Map<String, TreeItem<String>> categoryMap = new java.util.HashMap<>();

        // 1. 先把我们想要的默认分类加上（可选）
        // createCategoryNode("课程学习", root, categoryMap);
        // createCategoryNode("个人项目", root, categoryMap);
        // createCategoryNode("未分类", root, categoryMap);

        try {
            List<String> allFiles = FileUtil.listAllNotes();

            for (String fullFileName : allFiles) {
                String categoryName = "未分类";
                String noteName = fullFileName;

                // 2. 解析分类：如果文件名包含 "_"，则前面是分类，后面是歌名
                if (fullFileName.contains("_")) {
                    String[] parts = fullFileName.split("_", 2);
                    categoryName = parts[0];
                    noteName = parts[1]; // 只显示下划线后面的部分
                }

                // 3. 获取或创建分类节点
                TreeItem<String> categoryItem = categoryMap.get(categoryName);
                if (categoryItem == null) {
                    categoryItem = new TreeItem<>(categoryName);
                    categoryItem.setExpanded(true);
                    root.getChildren().add(categoryItem);
                    categoryMap.put(categoryName, categoryItem);
                }

                // 4. 创建笔记节点
                // 使用匿名类重写 toString，让树只显示短名字，但 Value 存长名字
                final String displayName = noteName;
                TreeItem<String> noteItem = new TreeItem<>(fullFileName) {
                    @Override public String toString() { return displayName; }
                };
                categoryItem.getChildren().add(noteItem);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // 排序：让分类按名称排个序，好看点
        root.getChildren().sort((o1, o2) -> o1.getValue().compareTo(o2.getValue()));

        fileTree.setRoot(root);
        fileTree.setShowRoot(false);
    }

    /**
     * 监听树的选择：如果是“真实笔记文件”，则加载内容
     * 【修复】增加了文件存在性检查，防止点击空文件夹时报错
     */
    private void setupTreeSelection() {
        fileTree.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.isLeaf()) {
                String fileName = newVal.getValue();

                // 核心修复：检查文件是否真的存在
                // 假设你的笔记存储在 "notes" 目录下，且后缀是 .md
                // (根据你的 FileUtil 逻辑，这里可能需要调整路径，但通常是这样)
                java.io.File f = new java.io.File("notes/" + fileName + ".md");

                // 只有当它是一个真实存在的文件时，才去加载
                if (f.exists() && f.isFile()) {
                    loadNoteContent(fileName);
                } else {
                    // 如果文件不存在，说明它只是一个刚创建的空文件夹
                    // 我们什么都不做，或者清空编辑器
                    // editorArea.clear();
                    // currentNoteTitle = "";
                }
            }
        });
    }

    private boolean isCategoryNode(String name) {
        return "Root".equals(name) || "课程学习".equals(name) || "个人项目".equals(name) || "未分类".equals(name);
    }

    private void loadNoteContent(String fileName) {
        try {
            currentNoteTitle = fileName;
            String content = FileUtil.read(fileName);
            editorArea.setText(content);
            if (webView.isVisible()) updatePreview();
        } catch (IOException e) { System.err.println("加载失败: " + e.getMessage()); }
    }

    @FXML
    private void handleNewNote() {
        // 1. 智能判断分类前缀
        TreeItem<String> selected = fileTree.getSelectionModel().getSelectedItem();
        String categoryPrefix = "未分类_";

        if (selected != null) {
            String val = selected.getValue();

            // 判断当前选中项是“文件夹”还是“笔记”
            // 逻辑：如果它对应的 .md 文件存在，那它就是笔记，我们要找它的爸爸（父分类）
            // 如果不存在，那它自己就是分类文件夹
            java.io.File f = new java.io.File("notes/" + val + ".md");

            if (f.exists() && f.isFile()) {
                // 选中了笔记 -> 取父节点名字作为分类前缀
                // (排除 Root 节点)
                if (selected.getParent() != null && !selected.getParent().getValue().equals("Root")) {
                    categoryPrefix = selected.getParent().getValue() + "_";
                }
            } else {
                // 选中了文件夹 -> 直接用这个文件夹的名字
                if (!val.equals("Root")) {
                    categoryPrefix = val + "_";
                }
            }
        }

        // 2. 自动生成不重复的文件名
        String baseName = "新笔记";
        String finalName = baseName;
        int counter = 1;

        try {
            List<String> existingFiles = FileUtil.listAllNotes();
            // 循环检查：如果 "新建文件夹_新笔记" 存在，就试 "新建文件夹_新笔记1"...
            while (existingFiles.contains(categoryPrefix + finalName)) {
                finalName = baseName + counter;
                counter++;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        String fullFileName = categoryPrefix + finalName;
        String initialContent = "# " + finalName;

        // 3. 创建并跳转
        try {
            FileUtil.save(fullFileName, initialContent);
            refreshFileTree();
            selectAndFocusNewNote(fullFileName, finalName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 辅助方法：在树中查找文件，选中它，并让编辑器聚焦到标题文字上
     */
    private void selectAndFocusNewNote(String targetFileName, String simpleName) {
        // 确保切回编辑模式（防止当前在预览模式无法编辑）
        handleEditMode();

        // 遍历树寻找新节点
        if (fileTree.getRoot() == null) return;

        for (TreeItem<String> categoryItem : fileTree.getRoot().getChildren()) {
            for (TreeItem<String> noteItem : categoryItem.getChildren()) {
                // 找到刚才创建的文件
                if (noteItem.getValue().equals(targetFileName)) {
                    // 1. 展开分类
                    categoryItem.setExpanded(true);

                    // 2. 选中列表项 (这会自动触发 loadNoteContent)
                    fileTree.getSelectionModel().select(noteItem);

                    // 3. 延迟一点点，等文件内容加载进 TextArea 后，再进行高亮
                    javafx.application.Platform.runLater(() -> {
                        editorArea.requestFocus(); // 聚焦编辑器

                        // 初始内容是 "# 新笔记"
                        // 我们想选中 "新笔记" 这部分，方便用户直接打字覆盖
                        // "# " 长度是 2，所以从索引 2 开始选
                        if (editorArea.getText().startsWith("# " + simpleName)) {
                            editorArea.selectRange(2, 2 + simpleName.length());
                        } else {
                            // 兜底：如果格式不对，就全选第一行
                            editorArea.positionCaret(0);
                        }
                    });
                    return;
                }
            }
        }
    }

    private void setupContextMenu() {
        ContextMenu contextMenu = new ContextMenu();
        MenuItem deleteItem = new MenuItem("删除笔记");
        deleteItem.setStyle("-fx-text-fill: red;");
        deleteItem.setOnAction(event -> {
            TreeItem<String> selected = fileTree.getSelectionModel().getSelectedItem();
            if (selected != null && selected.isLeaf() && !isCategoryNode(selected.getValue())) {
                String fileName = selected.getValue();
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "确定删除 [" + fileName + "] 吗？", ButtonType.YES, ButtonType.NO);
                alert.showAndWait().ifPresent(response -> {
                    if (response == ButtonType.YES) {
                        try {
                            FileUtil.delete(fileName);
                            refreshFileTree();
                            showWelcomePage();
                        } catch (IOException e) { e.printStackTrace(); }
                    }
                });
            }
        });
        fileTree.setContextMenu(contextMenu);
    }

    private void setupSearch() {
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null || newValue.trim().isEmpty()) {
                refreshFileTree();
                return;
            }
            try {
                TreeItem<String> searchRoot = new TreeItem<>("搜索结果");
                List<String> allFiles = FileUtil.listAllNotes();
                for (String file : allFiles) {
                    if (file.toLowerCase().contains(newValue.toLowerCase())) {
                        searchRoot.getChildren().add(new TreeItem<>(file));
                    }
                }
                fileTree.setRoot(searchRoot);
                searchRoot.setExpanded(true);
            } catch (IOException e) { e.printStackTrace(); }
        });
    }


    @FXML
    private void handleToggleSidebar() {
        boolean show = btnToggleSidebar.isSelected();
        if (show) {
            if (!splitPane.getItems().contains(sidebarContainer)) {
                splitPane.getItems().add(0, sidebarContainer);
                splitPane.setDividerPositions(lastDividerPosition, 0.8);
            }
        } else {
            double[] dividers = splitPane.getDividerPositions();
            if (dividers.length > 0) lastDividerPosition = dividers[0];
            splitPane.getItems().remove(sidebarContainer);
        }
    }

    @FXML
    private void handleSave() {
        if (currentNoteTitle == null || currentNoteTitle.isEmpty()) return;
        try {
            FileUtil.save(currentNoteTitle, editorArea.getText());
            List<String> moods = List.of("😊 豁然开朗", "😐 平静如水", "😫 烧脑痛苦", "🧠 深度思考");
            ChoiceDialog<String> dialog = new ChoiceDialog<>("😐 平静如水", moods);
            dialog.setTitle("保存成功");
            dialog.setHeaderText("记录一下此时的心境");
            dialog.setContentText("心情状态:");

            dialog.showAndWait().ifPresent(selectedMood -> {
                NoteMetadata meta = FileUtil.readMetadata(currentNoteTitle);
                meta.title = currentNoteTitle;
                meta.lastMood = selectedMood;
                meta.nextReviewDate = java.time.LocalDate.now().plusDays(3).toString();
                try {
                    FileUtil.saveMetadata(currentNoteTitle, meta);
                    wordCountLabel.setText("字数: " + editorArea.getText().length() + " | 最近心情: " + selectedMood);
                } catch (IOException e) { e.printStackTrace(); }
            });
        } catch (IOException e) { e.printStackTrace(); }
    }

    @FXML
    private void handleEditMode() {
        editorArea.setVisible(true);
        webView.setVisible(false);
    }

    @FXML
    private void handlePreviewMode() {
        updatePreview();
        editorArea.setVisible(false);
        webView.setVisible(true);
    }

    private void updatePreview() {
        String mdContent = editorArea.getText();
        // 1. 解析 Markdown
        String htmlBody = MarkdownParser.parse(mdContent);

        // 2. 检查当前是否是暗色模式
        // (简单的判断方法：看 rootContainer 的样式类里有没有 theme-dark)
        boolean isDark = rootContainer.getStyleClass().contains("theme-dark");

        // 3. 构建完整的 HTML，注入 CSS 样式
        String html = buildHtml(htmlBody, isDark);

        // 4. 加载
        webView.getEngine().loadContent(html);
    }

    private String buildHtml(String bodyContent, boolean isDarkMode) {
        // 定义颜色
        String bgColor = isDarkMode ? "#1e1f22" : "#ffffff";
        String textColor = isDarkMode ? "#bcbec4" : "#212529";
        String linkColor = isDarkMode ? "#589df6" : "#007bff";
        String codeBg = isDarkMode ? "#2b2d30" : "#f8f9fa";

        // 严谨的 XHTML 格式头部
        return "<html xmlns='http://www.w3.org/1999/xhtml'>" +
                "<head>" +
                "<title>Note Export</title>" +
                "<meta charset='UTF-8' />" + // 必须自闭合
                "<style>" +
                "body { " +
                "   font-family: 'Microsoft YaHei', sans-serif; " + // 必须包含 PDF 注入的字体名
                "   background-color: " + bgColor + "; " +
                "   color: " + textColor + "; " +
                "   padding: 20px; " +
                "   line-height: 1.6; " +
                "} " +
                "a { color: " + linkColor + "; text-decoration: none; } " +
                "pre, code { " +
                "   background-color: " + codeBg + "; " +
                "   padding: 5px; " +
                "   border-radius: 4px; " +
                "   font-family: 'Consolas', monospace; " +
                "} " +
                "blockquote { " +
                "   border-left: 4px solid " + linkColor + "; " +
                "   margin: 0; " +
                "   padding-left: 15px; " +
                "   color: #888; " +
                "} " +
                "img { max-width: 100%; } " +
                "</style>" +
                "</head>" +
                "<body>" +
                bodyContent +
                "</body>" +
                "</html>";
    }

    private void showWelcomePage() {
        String welcomeMD = "# 欢迎使用 DeepMind Note\n\n> 这是一个基于 JavaFX 的交互式笔记演示原型。";
        editorArea.setText(welcomeMD);
        currentNoteTitle = "";
        updatePreview();
        handlePreviewMode();
    }

    private void setupWordCount() {
        editorArea.textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null) { wordCountLabel.setText("字数: 0"); return; }
            wordCountLabel.setText("字数: " + newValue.length());
        });
    }

    private void showRandomReview() {
        try {
            List<String> all = FileUtil.listAllNotes();
            if (all.isEmpty()) return;
            String randomTitle = all.get((int) (Math.random() * all.size()));
            NoteMetadata meta = FileUtil.readMetadata(randomTitle);
            if ("😫 烧脑痛苦".equals(meta.lastMood)) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("复习提醒");
                alert.setHeaderText("你之前记录这篇笔记时感到很辛苦...");
                alert.setContentText("要不要回顾一下 [" + randomTitle + "]？");
                alert.show();
            }
        } catch (IOException e) {}
    }

    private void setupOutline() {
        editorArea.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) return;
            List<String> headings = new java.util.ArrayList<>();
            String[] lines = newVal.split("\n");
            for (String line : lines) {
                String trimmedLine = line.trim();
                if (trimmedLine.startsWith("#")) { headings.add(trimmedLine); }
            }
            outlineListView.getItems().setAll(headings);
        });

        outlineListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                String content = editorArea.getText();
                int index = content.indexOf(newVal);
                if (index != -1) {
                    editorArea.requestFocus();
                    editorArea.selectRange(index, index + newVal.length());
                }
            }
        });
    }

    /**
     * 切换右侧大纲栏显示/隐藏
     * 逻辑：真正地从 SplitPane 中移除组件，这样中间区域才会变宽
     */
    @FXML
    private void toggleOutline() {
        // 判断当前大纲栏是否已经在 SplitPane 里
        boolean isShowing = splitPane.getItems().contains(outlineContainer);

        if (isShowing) {
            // --- 隐藏操作 ---
            // 直接移除组件，中间区域会自动扩展占满右边
            splitPane.getItems().remove(outlineContainer);
        } else {
            // --- 显示操作 ---
            // 把大纲栏加回到最后面
            splitPane.getItems().add(outlineContainer);

            // 重新调整分割线位置 (根据左侧栏是否显示，策略不同)
            boolean isLeftSidebarShowing = splitPane.getItems().contains(sidebarContainer);

            if (isLeftSidebarShowing) {
                // 如果左、中、右都在：左边给20%，右边分割线在80%处
                splitPane.setDividerPositions(0.2, 0.8);
            } else {
                // 如果只有 中、右：分割线在80%处
                splitPane.setDividerPositions(0.8);
            }
        }
    }


    private void applyTheme(String themeName) {
        rootContainer.getStyleClass().removeAll("theme-dark", "theme-green", "theme-orange");
        switch (themeName) {
            case "暗夜黑": rootContainer.getStyleClass().add("theme-dark"); break;
            case "森系绿": rootContainer.getStyleClass().add("theme-green"); break;
            case "暖阳橙": rootContainer.getStyleClass().add("theme-orange"); break;
        }

        // === 新增：切换主题后，如果在预览模式，需要刷新一下 WebView 才能变色 ===
        if (webView.isVisible()) {
            updatePreview();
        }
    }

    /**
     * 新建文件夹（分类）
     * 逻辑：创建一个新的树节点。
     * 注意：因为我们是基于文件名前缀模拟文件夹的，所以这个文件夹在变为空之前，
     * 只有当你往里面创建了笔记（如 "新建文件夹_笔记1.md"）后，它才会在硬盘上"存在"。
     */
    @FXML
    private void handleNewFolder() {
        // 1. 生成不重复的文件夹名
        String baseName = "新建文件夹";
        String finalName = baseName;
        int counter = 1;

        // 检查当前树里有没有重名的
        if (fileTree.getRoot() != null) {
            boolean exists;
            do {
                exists = false;
                for (TreeItem<String> item : fileTree.getRoot().getChildren()) {
                    if (item.getValue().equals(finalName)) {
                        exists = true;
                        finalName = baseName + counter;
                        counter++;
                        break;
                    }
                }
            } while (exists);
        }

        // 2. 创建新节点并添加到树中
        TreeItem<String> newCategory = new TreeItem<>(finalName);
        if (fileTree.getRoot() == null) {
            fileTree.setRoot(new TreeItem<>("Root"));
        }
        fileTree.getRoot().getChildren().add(newCategory);

        // 3. 自动选中并展开，方便用户直接点左边那个“新建笔记”按钮
        fileTree.getSelectionModel().select(newCategory);
        newCategory.setExpanded(true);

        // 提示：你可以在这里加一个逻辑，允许用户像 IDEA 一样直接重命名
        // 但目前先保持直接创建
    }

    /**
     * 【新功能】处理菜单栏的主题切换
     * 这个方法绑定到了 FXML 里的 RadioMenuItem 上
     */
    @FXML
    private void handleThemeMenuAction(javafx.event.ActionEvent event) {
        // 获取被点击的菜单项
        if (event.getSource() instanceof MenuItem) {
            MenuItem item = (MenuItem) event.getSource();
            String themeName = item.getText(); // 获取文字，例如 "暗夜黑"

            // 调用你原有的应用主题逻辑
            applyTheme(themeName);
        }
    }

    /**
     * 处理外部文件导入
     */
    @FXML
    private void handleImport() {
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle("导入笔记");
        // 设置支持的格式过滤器
        fileChooser.getExtensionFilters().addAll(
                new javafx.stage.FileChooser.ExtensionFilter("支持的文本", "*.md", "*.txt"),
                new javafx.stage.FileChooser.ExtensionFilter("所有文件", "*.*")
        );

        java.io.File selectedFile = fileChooser.showOpenDialog(rootContainer.getScene().getWindow());
        if (selectedFile != null) {
            try {
                // 调用 FileUtil 读取外部文件内容
                String content = FileUtil.readFromExternal(selectedFile);
                editorArea.setText(content);
                // 导入后可以默认设置当前标题为空，强制用户保存时起新名，或根据文件名自动设置
                currentNoteTitle = "";
                handleEditMode(); // 切换到编辑模式
            } catch (IOException e) {
                showError("导入失败", "无法读取文件: " + e.getMessage());
            }
        }
    }

    private void showError(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setContentText(content);
        alert.showAndWait();
    }

    // --- 导出逻辑 (MainController.java) ---

    /**
     * 核心通用方法：获取用户保存路径
     */
    private java.io.File getSaveFile(String title, String description, String extension) {
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle(title);
        // 设置默认文件名：如果当前有笔记标题则使用，否则用“未命名笔记”
        String baseName = (currentNoteTitle == null || currentNoteTitle.isEmpty()) ? "未命名笔记" : currentNoteTitle;
        fileChooser.setInitialFileName(baseName);
        fileChooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(description, extension));
        return fileChooser.showSaveDialog(rootContainer.getScene().getWindow());
    }

    @FXML
    private void handleExportMarkdown() {
        java.io.File file = getSaveFile("导出 Markdown", "Markdown (.md)", "*.md");
        if (file != null) {
            try {
                // 修正：调用统一的外部保存方法
                FileUtil.writeToExternal(file, editorArea.getText());
            } catch (IOException e) {
                showError("保存失败", e.getMessage());
            }
        }
    }

    @FXML
    private void handleExportPDF() {
        java.io.File file = getSaveFile("导出 PDF", "PDF (.pdf)", "*.pdf");
        if (file != null) {
            try { exportAsPdf(file); }
            catch (Exception e) { showError("PDF 导出失败", e.getMessage()); }
        }
    }

    @FXML
    private void handleExportWord() {
        java.io.File file = getSaveFile("导出 Word", "Word (.docx)", "*.docx");
        if (file != null) {
            try { exportAsDocx(file); }
            catch (Exception e) { showError("Word 导出失败", e.getMessage()); }
        }
    }

    @FXML
    private void handleExportHTMLFull() {
        java.io.File file = getSaveFile("导出带样式网页", "HTML (.html)", "*.html");
        if (file != null) {
            try {
                // 使用现有渲染逻辑
                String fullHtml = buildHtml(MarkdownParser.parse(editorArea.getText()), false);
                FileUtil.writeToExternal(file, fullHtml);
            } catch (IOException e) { showError("HTML 导出失败", e.getMessage()); }
        }
    }

    @FXML
    private void handleExportHTMLRaw() {
        java.io.File file = getSaveFile("导出纯净网页", "HTML (.html)", "*.html");
        if (file != null) {
            try {
                // 只取解析后的 Body 部分
                String rawHtml = MarkdownParser.parse(editorArea.getText());
                FileUtil.writeToExternal(file, rawHtml);
            } catch (IOException e) { showError("HTML 导出失败", e.getMessage()); }
        }
    }

    @FXML
    private void handleExportImage() {
        java.io.File file = getSaveFile("导出图片", "图片 (.png)", "*.png");
        if (file != null) {
            try { exportAsImage(file); }
            catch (IOException e) { showError("图片生成失败", e.getMessage()); }
        }
    }

// --- 导出底层的私有实现 ---

    private void exportAsPdf(java.io.File file) throws Exception {
        // 1. 调用 Parser 获取纯 HTML 片段
        String htmlFragment = MarkdownParser.parse(editorArea.getText());

        // 2. 使用 buildHtml 包装成标准的、唯一的 XHTML 完整文档
        String fullXhtml = buildHtml(htmlFragment, false);

        try (java.io.OutputStream os = new java.io.FileOutputStream(file)) {
            com.openhtmltopdf.pdfboxout.PdfRendererBuilder builder = new com.openhtmltopdf.pdfboxout.PdfRendererBuilder();

            // 3. 注入中文字体（确保路径正确）
            java.io.File fontFile = new java.io.File("C:/Windows/Fonts/msyh.ttc");
            if (fontFile.exists()) {
                builder.useFont(fontFile, "Microsoft YaHei");
            }

            builder.withHtmlContent(fullXhtml, "/");
            builder.toStream(os);
            builder.run();
        }
    }

    private void exportAsDocx(java.io.File file) throws Exception {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            org.apache.poi.xwpf.usermodel.XWPFParagraph p = doc.createParagraph();
            org.apache.poi.xwpf.usermodel.XWPFRun run = p.createRun();
            run.setText(editorArea.getText());
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                doc.write(out);
            }
        }
    }

    private void exportAsImage(java.io.File file) throws IOException {
        if (!webView.isVisible()) {
            updatePreview(); // 确保 WebView 已渲染
        }
        javafx.scene.image.WritableImage image = webView.snapshot(null, null);
        java.awt.image.BufferedImage bufferedImage = javafx.embed.swing.SwingFXUtils.fromFXImage(image, null);
        javax.imageio.ImageIO.write(bufferedImage, "png", file);
    }
}