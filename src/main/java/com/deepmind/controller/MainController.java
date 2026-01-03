package com.deepmind.controller;

import com.deepmind.util.FileUtil;
import com.deepmind.util.MarkdownParser;
import com.deepmind.util.NoteMetadata;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;


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

    //关于查找和搜索
    private int lastSearchIndex = 0;
    private String lastSearchText = "";
    @FXML private VBox findReplacePane;
    @FXML private HBox replaceBox;
    @FXML private TextField findInputField;
    @FXML private TextField replaceInputField;;

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

        initContextMenu();         // 编辑区的右键菜单
        initFileTreeContextMenu(); // 文件树的右键菜单
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
        // 1. 智能判断分类前缀 (保留你原有的逻辑)
        TreeItem<String> selected = fileTree.getSelectionModel().getSelectedItem();
        String categoryPrefix = ""; // 默认改为空字符串，表示根目录

        if (selected != null) {
            String val = selected.getValue();
            java.io.File f = new java.io.File("notes/" + val + ".md");

            if (f.exists() && f.isFile()) {
                // 选中了笔记 -> 取父节点名字作为分类前缀
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

        // 2. 弹出对话框让用户命名 (替换掉原本自动生成的计数逻辑)
        String displayPrefix = categoryPrefix.isEmpty() ? "根目录" : categoryPrefix.replace("_", "");
        TextInputDialog dialog = new TextInputDialog("新笔记");
        dialog.setTitle("新建笔记");
        dialog.setHeaderText("创建位置: " + displayPrefix);
        dialog.setContentText("请输入笔记名称:");

        // 这一步是为了在 lambda 表达式中使用
        final String finalPrefix = categoryPrefix;

        dialog.showAndWait().ifPresent(fileName -> {
            String pureName = fileName.trim();
            if (pureName.isEmpty()) return;

            // 检查文件名是否包含非法字符（如下划线，因为它会干扰分类逻辑）
            if (pureName.contains("_")) {
                showError("命名无效", "笔记名称中请不要包含下划线 '_'。");
                return;
            }

            String fullFileName = finalPrefix + pureName;
            String initialContent = "# " + pureName;

            try {
                // 检查重名
                List<String> existingFiles = FileUtil.listAllNotes();
                if (existingFiles.contains(fullFileName)) {
                    showError("创建失败", "当前分类下已存在同名笔记。");
                    return;
                }

                // 3. 执行创建并跳转 (保留你原有的刷新和聚焦逻辑)
                FileUtil.save(fullFileName, initialContent);
                refreshFileTree();

                // 注意：这里确保调用你原有的 selectAndFocusNewNote 方法
                selectAndFocusNewNote(fullFileName, pureName);

            } catch (IOException e) {
                showError("创建失败", e.getMessage());
            }
        });
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
        // 调试日志，方便你观察是否触发了方法
        System.out.println("当前编辑区状态: TextArea=" + editorArea.isVisible() + ", WebView=" + webView.isVisible());

        if (editorArea.isVisible()) {
            // --- 切换到预览模式 ---
            updatePreview(); // 先渲染内容
            editorArea.setVisible(false);
            editorArea.setManaged(false); // 这一行很重要：让它不占用布局空间

            webView.setVisible(true);
            webView.setManaged(true);
            webView.requestFocus();
        } else {
            // --- 切换到编辑模式 ---
            webView.setVisible(false);
            webView.setManaged(false);

            editorArea.setVisible(true);
            editorArea.setManaged(true);
            editorArea.requestFocus(); // 回到编辑模式必须强行拿回焦点
        }
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

            // 1. 第一步：先解析出所有原始标题和它们的实际层级
            class Heading {
                int originalLevel;
                String text;
                Heading(int l, String t) { this.originalLevel = l; this.text = t; }
            }

            List<Heading> rawHeadings = new ArrayList<>();
            TreeSet<Integer> actualLevels = new TreeSet<>(); // 自动去重并排序等级

            String[] lines = newVal.split("\n");
            for (String line : lines) {
                String trimmedLine = line.trim();
                if (trimmedLine.startsWith("#")) {
                    int level = 0;
                    while (level < trimmedLine.length() && trimmedLine.charAt(level) == '#') level++;
                    String titleText = trimmedLine.substring(level).trim();
                    if (!titleText.isEmpty()) {
                        rawHeadings.add(new Heading(level, titleText));
                        actualLevels.add(level); // 记录出现的等级，比如 1, 2, 4
                    }
                }
            }

            // 2. 第二步：建立等级映射 (解决你说的 4 级跳 3 级的问题)
            // 比如实际出现了 [1, 2, 4]，那么 4 级会被映射为索引 2 (即第 3 个出现的等级)
            List<Integer> sortedLevels = new ArrayList<>(actualLevels);

            List<String> displayHeadings = new ArrayList<>();
            for (Heading h : rawHeadings) {
                // 获取当前标题在“实际出现的等级”中的位置
                int mappedLevel = sortedLevels.indexOf(h.originalLevel);

                // 3. 更有设计感的图标
                // 第一级用实心菱形，第二级用空心菱形，之后用小箭头
                String marker = switch (mappedLevel) {
                    case 0 -> "◈ ";
                    case 1 -> "◇ ";
                    case 2 -> "▹ ";
                    default -> "  ▪ ";
                };

                // 使用映射后的等级来计算缩进，每个级别缩进 2 个全角空格或 4 个半角
                String indent = "  ".repeat(Math.max(0, mappedLevel));
                displayHeadings.add(indent + marker + h.text);
            }

            outlineListView.getItems().setAll(displayHeadings);
        });

        // 点击跳转逻辑 (保持不变，但修正了之前的 scrollTop 警告)
        outlineListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                // 增强正则：过滤掉所有前缀符号和缩进
                String pureTitle = newVal.trim().replaceAll("^[◈◇▹▪\\s]+", "");
                String content = editorArea.getText();

                String[] lines = content.split("\n");
                int currentIndex = 0;
                for (String line : lines) {
                    if (line.trim().contains("#") && line.contains(pureTitle)) {
                        editorArea.requestFocus();
                        editorArea.selectRange(currentIndex, currentIndex + line.length());
                        editorArea.scrollTopProperty(); // 使用这个代替 scrollTopProperty 避免警告
                        break;
                    }
                    currentIndex += line.length() + 1;
                }
            }
        });
    }

    /**
     * 构建自定义右键菜单
     * 注意：这会覆盖系统默认菜单，所以我们需要手动把 撤销/复制/粘贴 加回来
     */
    private void initContextMenu() {
        // 1. 创建全新的右键菜单
        ContextMenu contextMenu = new ContextMenu();

        // 2. 基础编辑功能
        MenuItem undoItem = new MenuItem("撤销");
        undoItem.setOnAction(e -> editorArea.undo());

        MenuItem redoItem = new MenuItem("重做");
        redoItem.setOnAction(e -> editorArea.redo());

        MenuItem cutItem = new MenuItem("剪切");
        cutItem.setOnAction(e -> editorArea.cut());

        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> editorArea.copy());

        MenuItem pasteItem = new MenuItem("粘贴");
        pasteItem.setOnAction(e -> editorArea.paste());

        MenuItem selectAllItem = new MenuItem("全选");
        selectAllItem.setOnAction(e -> editorArea.selectAll());

        // 3. 文本格式子菜单
        Menu formatMenu = new Menu("文本格式");
        MenuItem boldItem = new MenuItem("加粗");
        boldItem.setOnAction(e -> handleBold());
        MenuItem italicItem = new MenuItem("倾斜");
        italicItem.setOnAction(e -> handleItalic());
        MenuItem strikeItem = new MenuItem("删除线");
        strikeItem.setOnAction(e -> handleStrikethrough());
        MenuItem highlightItem = new MenuItem("高亮");
        highlightItem.setOnAction(e -> handleHighlight());
        formatMenu.getItems().addAll(boldItem, italicItem, strikeItem, highlightItem);

        // 4. 段落设置子菜单
        Menu paragraphMenu = new Menu("段落设置");
        Menu headerMenu = new Menu("标题级别");
        MenuItem h1 = new MenuItem("H1 一级标题"); h1.setOnAction(e -> handleH1());
        MenuItem h2 = new MenuItem("H2 二级标题"); h2.setOnAction(e -> handleH2());
        MenuItem h3 = new MenuItem("H3 三级标题"); h3.setOnAction(e -> handleH3());
        MenuItem h4 = new MenuItem("H4 四级标题"); h3.setOnAction(e -> handleH4());
        MenuItem h5 = new MenuItem("H5 五级标题"); h3.setOnAction(e -> handleH5());
        MenuItem h6 = new MenuItem("H6 六级标题"); h3.setOnAction(e -> handleH6());

        headerMenu.getItems().addAll(h1, h2, h3, h4, h5, h6);

        MenuItem ulItem = new MenuItem("无序列表");
        ulItem.setOnAction(e -> handleUnorderedList());
        MenuItem olItem = new MenuItem("有序列表");
        olItem.setOnAction(e -> handleOrderedList());
        MenuItem quoteItem = new MenuItem("引用块");
        quoteItem.setOnAction(e -> handleBlockquote());
        paragraphMenu.getItems().addAll(headerMenu, new SeparatorMenuItem(), ulItem, olItem, quoteItem);

        // 5. 将所有项添加到 ContextMenu (注意添加顺序)
        contextMenu.getItems().addAll(
                undoItem, redoItem,
                new SeparatorMenuItem(),
                cutItem, copyItem, pasteItem,
                new SeparatorMenuItem(),
                selectAllItem,
                new SeparatorMenuItem(),
                formatMenu,
                paragraphMenu
        );

        // 6. 关键一步：绑定到编辑器
        editorArea.setContextMenu(contextMenu);
    }


    /**
     * 专门为左侧 TreeView 设置右键菜单
     */
    private void initFileTreeContextMenu() {
        ContextMenu fileContextMenu = new ContextMenu();

        MenuItem renameItem = new MenuItem("重命名");
        renameItem.setOnAction(e -> handleRenameNote());

        MenuItem moveItem = new MenuItem("移动到文件夹...");
        moveItem.setOnAction(e -> handleMoveNote()); // 绑定上面新写的函数

        MenuItem deleteItem = new MenuItem("删除笔记");
        deleteItem.setStyle("-fx-text-fill: red;");
        deleteItem.setOnAction(e -> handleDeleteNote());

        fileContextMenu.getItems().addAll(renameItem, moveItem, new SeparatorMenuItem(), deleteItem);
        fileTree.setContextMenu(fileContextMenu);
    }

    /**
     * 整合后的重命名逻辑
     */
    @FXML
    private void handleRenameNote() {
        TreeItem<String> selectedItem = fileTree.getSelectionModel().getSelectedItem();
        if (selectedItem == null || selectedItem.getParent() == null) return;

        String oldTitle = selectedItem.getValue();

        TextInputDialog dialog = new TextInputDialog(oldTitle);
        dialog.setTitle("重命名笔记");
        dialog.setHeaderText("注意：修改下划线前的文字将改变所属文件夹");
        dialog.setContentText("请输入新名称:");

        dialog.showAndWait().ifPresent(newTitle -> {
            if (!newTitle.isEmpty() && !newTitle.equals(oldTitle)) {
                try {
                    // 1. 物理重命名 (FileUtil 会处理 .md 和 .json)
                    FileUtil.rename(oldTitle, newTitle);

                    // 2. 更新当前变量
                    if (currentNoteTitle.equals(oldTitle)) {
                        currentNoteTitle = newTitle;
                    }

                    // 3. 关键修复：直接刷新整棵树！
                    refreshFileTree();

                } catch (IOException e) {
                    showError("重命名失败", "可能是名称冲突或文件被占用。");
                }
            }
        });
    }

    /**
     * 移动笔记：实际上是修改文件名的前缀（虚拟文件夹）
     */
    @FXML
    private void handleMoveNote() {
        // 1. 获取当前选中的 TreeItem
        TreeItem<String> selectedItem = fileTree.getSelectionModel().getSelectedItem();

        // 如果选中的是文件夹根节点或为空，则返回
        if (selectedItem == null || selectedItem.getParent() == null || selectedItem.getParent() == fileTree.getRoot()) {
            return;
        }

        String currentFullName = selectedItem.getValue();
        String currentFolder = "";
        String pureFileName;

        // 2. 解析前缀：例如 "学习_Java笔记" -> 文件夹: 学习, 文件名: Java笔记
        if (currentFullName.contains("_")) {
            int index = currentFullName.indexOf("_");
            currentFolder = currentFullName.substring(0, index);
            pureFileName = currentFullName.substring(index + 1);
        } else {
            pureFileName = currentFullName;
        }

        // 3. 弹出对话框
        TextInputDialog dialog = new TextInputDialog(currentFolder);
        dialog.setTitle("移动笔记分类");
        dialog.setHeaderText("移动笔记: " + pureFileName);
        dialog.setContentText("请输入新的分类名称 (留空则移出文件夹):");

        dialog.showAndWait().ifPresent(newFolderName -> {
            String newTitle;
            String trimmedFolder = newFolderName.trim();

            // 根据输入拼接新名称
            if (trimmedFolder.isEmpty()) {
                newTitle = pureFileName; // 变为根目录文件
            } else {
                newTitle = trimmedFolder + "_" + pureFileName;
            }

            if (!newTitle.equals(currentFullName)) {
                try {
                    // 4. 调用你已经改好的 FileUtil.rename
                    FileUtil.rename(currentFullName, newTitle);

                    // 5. 同步当前编辑状态
                    if (currentNoteTitle.equals(currentFullName)) {
                        currentNoteTitle = newTitle;
                    }

                    // 6. 核心：必须调用 refreshFileTree() 重新构建整个左侧树
                    refreshFileTree();

                } catch (IOException e) {
                    showError("移动失败", "无法移动笔记，请检查名称是否合法或已存在。");
                }
            }
        });
    }

    /**
     * 整合后的删除逻辑
     */
    @FXML
    private void handleDeleteNote() {
        TreeItem<String> selectedItem = fileTree.getSelectionModel().getSelectedItem();
        if (selectedItem == null || selectedItem.getParent() == null) return;

        String title = selectedItem.getValue();

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "确定要删除笔记 [" + title + "] 吗？", ButtonType.YES, ButtonType.NO);
        confirm.setTitle("删除确认");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                try {
                    FileUtil.delete(title);
                    selectedItem.getParent().getChildren().remove(selectedItem);

                    // 如果删除的是当前编辑的笔记，清空内容
                    if (currentNoteTitle.equals(title)) {
                        editorArea.clear();
                        webView.getEngine().loadContent("");
                        currentNoteTitle = "";
                    }
                } catch (IOException e) {
                    showError("删除失败", "文件删除时出错。");
                }
            }
        });
    }

    /**
     * 抽取出的统一错误提示
     */
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
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

//    private void showError(String title, String content) {
//        Alert alert = new Alert(Alert.AlertType.ERROR);
//        alert.setTitle(title);
//        alert.setContentText(content);
//        alert.showAndWait();
//    }

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

    @FXML
    private void handleQuickOpen() {
        // 1. 创建弹窗容器
        VBox container = new VBox(10);
        container.setPadding(new javafx.geometry.Insets(10));
        container.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cccccc; -fx-border-radius: 5;");

        TextField searchBar = new TextField();
        searchBar.setPromptText("按文件名查找");
        searchBar.setStyle("-fx-font-size: 14px;");

        ListView<String> listView = new ListView<>();
        listView.setPrefHeight(250);

        // 2. 加载数据（从 FileUtil 获取所有笔记）
        try {
            List<String> allNotes = FileUtil.listAllNotes();
            listView.getItems().setAll(allNotes);

            // 3. 搜索过滤逻辑
            searchBar.textProperty().addListener((obs, oldVal, newVal) -> {
                List<String> filtered = allNotes.stream()
                        .filter(s -> s.toLowerCase().contains(newVal.toLowerCase()))
                        .collect(java.util.stream.Collectors.toList());
                listView.getItems().setAll(filtered);
            });
        } catch (IOException e) { e.printStackTrace(); }

        container.getChildren().addAll(searchBar, new Label("最近打开的文件"), listView);

        // 4. 创建 Stage (弹窗窗口)
        javafx.stage.Stage popupStage = new javafx.stage.Stage();
        popupStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        popupStage.initStyle(javafx.stage.StageStyle.UNDECORATED); // 无边框更美观
        popupStage.setScene(new javafx.scene.Scene(container, 400, 350));

        // 5. 选择并跳转逻辑
        listView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                loadNoteContent(newVal);
                popupStage.close();
            }
        });

        popupStage.show();
    }

    @FXML
    private void handleNewWindow() {
        try {
            // 重新加载 FXML 创建新的窗口实例
            FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
            javafx.scene.Scene scene = new javafx.scene.Scene(fxmlLoader.load());
            javafx.stage.Stage newStage = new javafx.stage.Stage();
            newStage.setTitle("DeepMind Note - New Window");
            newStage.setScene(scene);
            newStage.show();
        } catch (IOException e) {
            showError("新建窗口失败", e.getMessage());
        }
    }

    @FXML
    private void handleOpenFile() {
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Markdown", "*.md"));
        java.io.File file = fileChooser.showOpenDialog(rootContainer.getScene().getWindow());

        if (file != null) {
            try {
                String content = FileUtil.readFromExternal(file);
                editorArea.setText(content);
                currentNoteTitle = ""; // 清空当前标题，防止误删库内同名文件
                handleEditMode();
            } catch (IOException e) {
                showError("打开失败", e.getMessage());
            }
        }
    }

    // 打开系统资源管理器 (定位到笔记根目录)
    @FXML
    private void handleOpenFolder() {
        try {
            // 使用 java.desktop 模块功能
            java.awt.Desktop.getDesktop().open(new java.io.File("notes"));
        } catch (IOException e) {
            showError("打开失败", "无法访问存储目录: " + e.getMessage());
        }
    }

    // 另存为 (复用 Markdown 导出逻辑)
    @FXML
    private void handleSaveAs() {
        handleExportMarkdown(); // 逻辑一致，弹出文件选择器存至外部
    }

    // 弹出属性对话框 (展示 NoteMetadata 信息)
    @FXML
    private void handleShowProperties() {
        if (currentNoteTitle == null || currentNoteTitle.isEmpty()) return;

        // 从 FileUtil 加载该笔记的元数据
        NoteMetadata meta = FileUtil.readMetadata(currentNoteTitle);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("笔记属性");
        alert.setHeaderText("文件: " + currentNoteTitle + ".md");

        // 构建显示内容
        String content = String.format(
                "最后心情: %s\n复习次数: %d\n下次复习: %s\n创建日期: %s",
                meta.lastMood != null ? meta.lastMood : "无记录",
                meta.reviewCount,
                meta.nextReviewDate != null ? meta.nextReviewDate : "未排期",
                meta.createDate != null ? meta.createDate : "未知"
        );

        alert.setContentText(content);
        alert.showAndWait();
    }

    //  执行删除当前笔记逻辑
    @FXML
    private void handleDelete() {
        if (currentNoteTitle == null || currentNoteTitle.isEmpty()) return;

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "确定要删除笔记 [" + currentNoteTitle + "] 吗？", ButtonType.YES, ButtonType.NO);
        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                try {
                    FileUtil.delete(currentNoteTitle); // 从磁盘删除
                    refreshFileTree(); // 刷新左侧树
                    showWelcomePage(); // 回到欢迎页
                } catch (IOException e) {
                    showError("删除失败", e.getMessage());
                }
            }
        });
    }

    //  实现打印逻辑 (利用 WebView 引擎)
    @FXML
    private void handlePrint() {
        // 1. 获取 WebEngine
        javafx.scene.web.WebEngine engine = webView.getEngine();

        // 2. 确保在打印前，WebView 里的内容是最新的 Markdown 渲染结果
        // 如果当前处于编辑模式（WebView 可能是隐藏的），先静默更新一下
        updatePreview();

        // 3. 创建打印作业 (PrinterJob)
        javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();

        if (job != null) {
            // 弹出系统标准的打印设置对话框（让用户选择打印机、页码等）
            boolean proceed = job.showPrintDialog(rootContainer.getScene().getWindow());

            if (proceed) {
                // 核心逻辑：直接将 WebEngine 的内容发送给打印作业
                // 这种方式会自动保留 HTML 的样式、字体和图片
                engine.print(job);

                // 结束作业
                job.endJob();
            }
        } else {
            showError("打印失败", "未检测到可用的打印机设备。");
        }
    }

    //  关闭当前窗口
    @FXML
    private void handleClose() {
        // 通过容器获取 Stage 并关闭
        javafx.stage.Stage stage = (javafx.stage.Stage) rootContainer.getScene().getWindow();
        stage.close();
    }

    // --- 编辑菜单功能实现 ---

    /**
     * 撤销操作
     * TextArea 内部维护了一个修改历史栈
     */
    @FXML
    private void handleUndo() {
        editorArea.requestFocus(); // 确保焦点在编辑器
        if (editorArea.isUndoable()) {
            editorArea.undo();
        }
    }

    /**
     * 重做操作
     */
    @FXML
    private void handleRedo() {
        editorArea.requestFocus();
        if (editorArea.isRedoable()) {
            editorArea.redo();
        }
    }

    /**
     * 剪切操作
     * 将选中的内容移动到系统剪贴板
     */
    @FXML
    private void handleCut() {
        editorArea.requestFocus();
        editorArea.cut();
    }

    /**
     * 复制操作
     * 将选中的内容拷贝到系统剪贴板
     */
    @FXML
    private void handleCopy() {
        editorArea.requestFocus();
        editorArea.copy();
    }

    /**
     * 粘贴操作
     * 从系统剪贴板读取内容并插入到光标位置
     */
    @FXML
    private void handlePaste() {
        editorArea.requestFocus();
        editorArea.paste();
    }

    // 菜单点击“查找” (Ctrl+F) 触发
    @FXML
    private void handleFind() {
        findReplacePane.setVisible(true);
        findReplacePane.setManaged(true);
        replaceBox.setVisible(false); // 查找模式下隐藏替换输入框
        findInputField.requestFocus();
        // 如果有选中文本，自动填入查找框
        String selected = editorArea.getSelectedText();
        if (!selected.isEmpty()) {
            findInputField.setText(selected);
        }
    }

    // 菜单点击“替换” (Ctrl+H) 触发
    @FXML
    private void handleReplace() {
        findReplacePane.setVisible(true);
        findReplacePane.setManaged(true);
        replaceBox.setVisible(true);  // 替换模式下显示替换输入框
        findInputField.requestFocus();
    }

    // 查找下一个 (↓ 按钮触发)
    @FXML
    private void findNext() {
        String query = findInputField.getText();
        if (query == null || query.isEmpty()) return;

        String content = editorArea.getText();
        int index = content.indexOf(query, lastSearchIndex);

        if (index != -1) {
            editorArea.requestFocus();
            editorArea.selectRange(index, index + query.length());
            lastSearchIndex = index + query.length();
        } else {
            // 回滚到开头循环查找
            lastSearchIndex = 0;
            int retry = content.indexOf(query, 0);
            if (retry != -1) {
                editorArea.requestFocus();
                editorArea.selectRange(retry, retry + query.length());
                lastSearchIndex = retry + query.length();
            }
        }
    }

    // 5. 全部替换 (面板内“全部”按钮触发)
    @FXML
    private void handleReplaceAll() {
        String query = findInputField.getText();
        String target = replaceInputField.getText();
        if (query == null || query.isEmpty()) return;

        String content = editorArea.getText();
        // 使用 replace 方法替换所有匹配项
        editorArea.setText(content.replace(query, target));
    }

    // 替换当前 (面板内“替换”按钮触发)
    @FXML
    private void handleReplaceSingle() {
        String query = findInputField.getText();
        String target = replaceInputField.getText();

        // 如果当前选中的正是查找的内容，执行替换
        if (editorArea.getSelectedText().equals(query)) {
            editorArea.replaceSelection(target);
            findNext(); // 自动找下一个
        } else {
            findNext(); // 否则先定位到下一个匹配项
        }
    }

    @FXML
    private void closeFindPane() {
        findReplacePane.setVisible(false);
        findReplacePane.setManaged(false);
        editorArea.requestFocus();
    }

    // =====================================================
    // 1. 文本操作辅助方法 (核心引擎)
    // =====================================================

    /**
     * 辅助方法：在选中文本前后插入符号（用于加粗、高亮等）
     */
    private void wrapSelection(String prefix, String suffix) {
        String selectedText = editorArea.getSelectedText();
        // 如果没有选中文字，也可以直接插入占位符，这里简单处理为包裹空字符串
        editorArea.replaceSelection(prefix + selectedText + suffix);
        editorArea.requestFocus(); // 操作完保持焦点
    }

    /**
     * 辅助方法：在当前行首插入符号（用于标题、列表）
     */
    private void insertAtLineStart(String prefix) {
        int caretPos = editorArea.getCaretPosition();
        String text = editorArea.getText();

        // 向前查找最近的一个换行符，确定行首位置
        // 如果 caretPos 是 0，lastIndexOf 返回 -1，结果正是 0 (正确)
        int lineStart = text.lastIndexOf('\n', caretPos - 1) + 1;

        editorArea.insertText(lineStart, prefix + " ");
        editorArea.requestFocus();
    }

    // =====================================================
    // 2. 格式功能实现 (对应菜单和右键)
    // =====================================================

    @FXML private void handleBold() { wrapSelection("**", "**"); }
    @FXML private void handleItalic() { wrapSelection("*", "*"); }
    @FXML private void handleStrikethrough() { wrapSelection("~~", "~~"); }
    @FXML private void handleHighlight() { wrapSelection("==", "=="); }

    // =====================================================
    // 3. 段落功能实现 (对应菜单和右键)
    // =====================================================

    @FXML private void handleUnorderedList() { insertAtLineStart("-"); }
    @FXML private void handleOrderedList() { insertAtLineStart("1."); }
//    @FXML private void handleTaskList() { insertAtLineStart("- [ ]"); }
    @FXML private void handleBlockquote() { insertAtLineStart(">"); }

    // 标题
    @FXML private void handleH1() { insertAtLineStart("#"); }
    @FXML private void handleH2() { insertAtLineStart("##"); }
    @FXML private void handleH3() { insertAtLineStart("###"); }
    @FXML private void handleH4() { insertAtLineStart("####"); }
    @FXML private void handleH5() { insertAtLineStart("#####"); }
    @FXML private void handleH6() { insertAtLineStart("######"); }
}