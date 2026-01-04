package com.deepmind.controller;

import com.deepmind.util.FileUtil;
import com.deepmind.util.MarkdownParser;
import com.deepmind.util.NoteMetadata;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
@SuppressWarnings("ALL")
public class MainController {


    // --- 核心编辑区 ---
    @FXML private TextArea editorArea;
    @FXML private WebView webView;
    @FXML private Label wordCountLabel;

    // --- 左侧单栏文件树 ---
    @FXML private TreeView<String> fileTree;
    @FXML
    private TextField sidebarSearchField;
    @FXML private VBox sidebarContainer;

    // --- 右侧大纲 ---
    @FXML private ListView<String> outlineListView;
    @FXML private VBox outlineContainer;

    // --- 整体布局与工具栏 ---
    @FXML private VBox rootContainer;
    @FXML private SplitPane splitPane;

    @FXML private ToggleButton btnToggleSidebar;
    @FXML private MenuBar mainMenuBar;

    // --- 状态变量 ---
    private String currentNoteTitle = "";
    private double lastDividerPosition = 0.2;

    //关于查找和搜索
    private int lastSideSearchIndex = 0;
    @FXML
    private VBox editorFindPane;
    @FXML private HBox replaceBox;
    @FXML
    private TextField editorFindField;
    @FXML private TextField replaceInputField;
    @FXML
    private Label lblMatchCount; // 对应 FXML 里的 fx:id
    // 用于记录上一次查找的位置
    private int lastWebSearchIndex = -1;

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

        setupTreeSelection();

        setupDragAndDrop();
        setupDualDragAndDrop();
        setupPasteLogic();

        setupFindFeature();
    }

    /**
     * 核心逻辑：文件树加载
     */
    private void refreshFileTree() {
        TreeItem<String> root = new TreeItem<>("Root");

        // 我们用一个列表来保存已创建的分类节点，避免重复
        java.util.Map<String, TreeItem<String>> categoryMap = new java.util.HashMap<>();


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
        root.getChildren().sort(Comparator.comparing(TreeItem::getValue));

        fileTree.setRoot(root);
        fileTree.setShowRoot(false);
    }

    private void setupTreeSelection() {
        fileTree.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.isLeaf()) {
                String fileName = newVal.getValue();

                java.io.File f = new java.io.File("notes/" + fileName + ".md");

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

    /**
     * 执行自定义粘贴逻辑
     *
     * @return true 表示成功粘贴了图片/文件；false 表示剪贴板里没有图片/文件（需要执行默认文本粘贴）
     */
    private boolean performCustomPaste() {
        Clipboard clipboard = Clipboard.getSystemClipboard();

        // 1. 检查文件 (复制的文件)
        if (clipboard.hasFiles()) {
            File file = clipboard.getFiles().get(0);
            if (isImageFile(file)) {
                String relativePath = saveImageToProject(file);
                if (relativePath != null) {
                    insertMarkdownImage(file.getName(), relativePath);
                    return true; // 拦截成功
                }
            }
        }
        // 2. 检查图片 (截图)
        else if (clipboard.hasImage()) {
            Image image = clipboard.getImage();
            String fileName = "screenshot_" + System.currentTimeMillis() + ".png";
            String relativePath = saveRawImageToProject(image, fileName);

            if (relativePath != null) {
                insertMarkdownImage(fileName, relativePath);
                return true; // 拦截成功
            }
        }

        return false; // 没有图片，继续执行后续逻辑（粘贴文本）
    }

    /**
     * 设置粘贴功能 (支持 Ctrl+V 粘贴截图和图片文件)
     */
    private void setupPasteLogic() {
        editorArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            KeyCombination pasteKey = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);

            if (pasteKey.match(event)) {
                // 调用刚才提取的方法
                boolean handled = performCustomPaste();

                // 如果成功粘贴了图片，就消耗掉事件，防止 TextArea 再去粘贴一遍文件名
                if (handled) {
                    event.consume();
                }
                // 如果 handled 为 false，事件会继续传递，TextArea 会自动执行默认的文本粘贴
            }
        });
    }

    /**
     * 将内存中的 Image 对象 (截图) 保存为文件
     */
    private String saveRawImageToProject(Image image, String fileName) {
        try {
            // 1. 确定目录
            File imageDir = new File("notes/images");
            if (!imageDir.exists()) imageDir.mkdirs();

            File targetFile = new File(imageDir, fileName);

            // 2. JavaFX Image 转 BufferedImage
            BufferedImage bImage = SwingFXUtils.fromFXImage(image, null);

            // 3. 写入硬盘
            ImageIO.write(bImage, "png", targetFile);

            return "images/" + fileName;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void setupFindFeature() {
        // 1. 定义通用更新器 (只更新计数标签，不移动光标)
        javafx.beans.InvalidationListener counterUpdater = o -> updateMatchStatus(false);

        // 2. 监听光标移动、模式切换 -> 只更新计数
        editorArea.caretPositionProperty().addListener(counterUpdater);
        editorArea.visibleProperty().addListener((obs, oldVal, newVisible) -> {
            if (editorFindPane.isVisible()) {
                if (newVisible) updateMatchStatus(true); // 切回编辑模式，尝试高亮当前
                else javafx.application.Platform.runLater(() -> updateMatchStatus(true)); // 切到预览，执行JS
            }
        });

        // 3. 【核心修改】监听输入框文字变化 -> 实时选中 + 更新计数
        editorFindField.textProperty().addListener((obs, oldVal, newVal) -> {
            // 输入文字时，强制执行一次“从头查找并选中”
            handleIncrementalSearch(newVal);
        });

        // 4. 回车 -> 查找下一个
        editorFindField.setOnAction(e -> findNext());
    }

    /**
     * 辅助方法：插入 Markdown 图片语法
     */
    private void insertMarkdownImage(String imageName, String path) {
        String markdown = String.format("![%s](%s)", imageName, path);
        int caretPos = editorArea.getCaretPosition();
        editorArea.insertText(caretPos, markdown);
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
        sidebarSearchField.textProperty().addListener((observable, oldValue, newValue) -> {
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
        boolean show = splitPane.getItems().contains(sidebarContainer);
        if (show) {
            double[] dividers = splitPane.getDividerPositions();
            if (dividers.length > 0) lastDividerPosition = dividers[0];
            splitPane.getItems().remove(sidebarContainer);
        } else {

            splitPane.getItems().addFirst(sidebarContainer);
            splitPane.setDividerPositions(lastDividerPosition, 0.8);

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
        String markdownHtml = MarkdownParser.parse(mdContent);

        // 2. 检查当前是否是暗色模式
        // (简单的判断方法：看 rootContainer 的样式类里有没有 theme-dark)
        boolean isDark = rootContainer.getStyleClass().contains("theme-dark");

        // 3. 构建完整的 HTML，注入 CSS 样式
        File notesDir = new File("notes/");
        String baseUrl = notesDir.toURI().toString();
        // 1. removeHighlights(): 清除旧的高亮
        // 2. highlightAll(keyword): 遍历文本节点，给匹配的词加上 <span class="search-highlight">
        String jsScript = """
                    <script>
                        // 清除所有高亮标签，还原文本
                        function removeHighlights() {
                            const highlights = document.querySelectorAll('span.search-highlight');
                            highlights.forEach(span => {
                                const parent = span.parentNode;
                                parent.replaceChild(document.createTextNode(span.textContent), span);
                                parent.normalize(); // 合并相邻文本节点
                            });
                        }
                
                        // 高亮关键词并返回匹配数量
                        function highlightAll(keyword) {
                            removeHighlights(); // 先清除旧的
                            if (!keyword) return 0;
                
                            // 使用 TreeWalker 遍历纯文本节点，避免破坏 HTML 标签结构
                            const walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                            const nodes = [];
                            while(walk.nextNode()) nodes.push(walk.currentNode);
                
                            let count = 0;
                            // 正则：转义特殊字符，gi 表示全局+忽略大小写
                            const escapeRegExp = (string) => string.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&');
                            const regex = new RegExp('(' + escapeRegExp(keyword) + ')', 'gi');
                
                            nodes.forEach(node => {
                                // 跳过 script 和 style 标签内部
                                if (node.parentNode.nodeName === "SCRIPT" || node.parentNode.nodeName === "STYLE") return;
                
                                const text = node.nodeValue;
                                if (regex.test(text)) {
                                    const fragment = document.createDocumentFragment();
                                    let lastIdx = 0;
                
                                    text.replace(regex, (match, p1, offset) => {
                                        // 1. 添加匹配前的普通文本
                                        fragment.appendChild(document.createTextNode(text.slice(lastIdx, offset)));
                
                                        // 2. 添加高亮节点
                                        const span = document.createElement('span');
                                        span.className = 'search-highlight';
                                        span.textContent = match;
                                        if (count === 0) span.id = 'first-match'; // 标记第一个
                                        fragment.appendChild(span);
                
                                        lastIdx = offset + match.length;
                                        count++;
                                    });
                
                                    // 3. 添加剩余文本
                                    fragment.appendChild(document.createTextNode(text.slice(lastIdx)));
                                    node.parentNode.replaceChild(fragment, node);
                                }
                            });
                
                            // 自动滚动到第一个结果
                            const first = document.getElementById('first-match');
                            if (first) first.scrollIntoView({behavior: "smooth", block: "center"});
                
                            return count;
                        }
                    </script>
                """;
        // 3. 拼接完整的 HTML 结构
        String htmlContent = "<!DOCTYPE html>"
                + "<html>"
                + "<head>"
                + "    <meta charset=\"UTF-8\">"
                // 关键点：告诉 WebView 所有的相对路径(如 images/1.png) 都要去 baseUrl 下面找
                + "    <base href=\"" + baseUrl + "\">"
                + "    <style>"
                + "        body { font-family: sans-serif; padding: 20px; line-height: 1.6; }"
                + "        /* 推荐：限制图片最大宽度，防止图片太大撑破屏幕 */"
                + "        img { max-width: 100%; height: auto; }"
                // --- CSS 高亮样式 ---
                + "    .search-highlight { background-color: #ffeb3b; color: #000; border-radius: 2px; box-shadow: 0 0 2px rgba(0,0,0,0.2); }"
                + "    </style>"
                + "</head>"
                + "<body>"
                + markdownHtml
                + jsScript // 注入 JS
                + "</body>"
                + "</html>";
        String html = buildHtml(htmlContent, isDark);
        // 4. 加载内容
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
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 设置编辑器的图片拖拽功能
     * 功能：拖入图片 -> 自动复制到 images 目录 -> 插入 Markdown 语法
     */
    private void setupDualDragAndDrop() {
        // 1. 给 TextArea 设置拖拽
        setupNodeDragHandlers(editorArea, true);

        // 2. 给 WebView 设置拖拽 (关键：防止跳转)
        setupNodeDragHandlers(webView, false);
    }

    /**
     * 通用的节点拖拽处理器
     * @param node 目标节点 (TextArea 或 WebView)
     * @param isEditor 是否是编辑器 (如果是编辑器，插入光标处；如果是WebView，追加到文末)
     */
    /**
     * 通用的节点拖拽处理器 (已更新：支持光标跟随)
     */
    private void setupNodeDragHandlers(javafx.scene.Node node, boolean isEditor) {
        // --- 拖拽经过 (DragOver) ---
        node.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                File file = event.getDragboard().getFiles().get(0);
                if (isImageFile(file)) {
                    event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);

                    // 【新增】如果是编辑器，强制光标跟随鼠标移动
                    if (isEditor && node instanceof TextArea) {
                        moveCaretToMouse((TextArea) node, event.getX(), event.getY());
                        ((TextArea) node).requestFocus(); // 获取焦点，让光标闪烁可见
                    }
                }
            }
            event.consume();
        });

        // --- 拖拽释放 (DragDropped) ---
        node.setOnDragDropped(event -> {
            javafx.scene.input.Dragboard db = event.getDragboard();
            boolean success = false;

            if (db.hasFiles()) {
                File sourceFile = db.getFiles().get(0);
                if (isImageFile(sourceFile)) {
                    String relativePath = saveImageToProject(sourceFile);
                    if (relativePath != null) {
                        String markdownImage = String.format("![%s](%s)", sourceFile.getName(), relativePath);

                        // 此时光标已经在 DragOver 中移动到了正确位置，直接插入即可
                        insertMarkdownText(markdownImage, isEditor);
                        success = true;
                    }
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    /**
     * 【核心算法】根据鼠标坐标移动光标
     * 注意：这基于等宽字体计算，非等宽字体会有误差，但不会报错
     */
    private void moveCaretToMouse(TextArea area, double mouseX, double mouseY) {
        // 1. 获取字体度量 (假设是等宽字体)
        javafx.scene.text.Text helper = new javafx.scene.text.Text("W");
        helper.setFont(area.getFont());
        double lineHeight = helper.getLayoutBounds().getHeight(); // 单行高度
        double charWidth = helper.getLayoutBounds().getWidth();   // 单字符宽度

        // 2. 加上滚动条的偏移量 (TextArea 的内容可能被卷上去了)
        double scrolledX = mouseX + area.getScrollLeft();
        double scrolledY = mouseY + area.getScrollTop();

        // 3. 简单的内边距修正 (TextArea 默认有一点 padding)
        double paddingX = 5.0;
        double paddingY = 5.0; // 视 CSS 而定，通常 5-7px

        // 4. 计算行号和列号
        int row = (int) ((scrolledY - paddingY) / lineHeight);
        int col = (int) ((scrolledX - paddingX) / charWidth);

        // 5. 将行列转换为文本索引 (Index)
        // 这一步比较麻烦，因为要考虑每一行的实际长度
        try {
            String text = area.getText();
            String[] lines = text.split("\n", -1); // -1 保留末尾空行

            int targetIndex = 0;

            // 限制行号不越界
            if (row < 0) row = 0;
            if (row >= lines.length) row = lines.length - 1;

            // 累加目标行之前的字符数
            for (int i = 0; i < row; i++) {
                targetIndex += lines[i].length() + 1; // +1 是换行符
            }

            // 加上列偏移
            int lineLen = lines[row].length();
            if (col < 0) col = 0;
            if (col > lineLen) col = lineLen; // 限制光标不能超过行尾

            targetIndex += col;

            // 6. 移动光标
            area.positionCaret(targetIndex);

        } catch (Exception e) {
            // 计算出错时忽略，保持原位
        }
    }

    /**
     * 辅助方法：判断文件是否为图片
     */
    private boolean isImageFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".png") || name.endsWith(".jpg") ||
                name.endsWith(".jpeg") || name.endsWith(".gif") ||
                name.endsWith(".bmp") || name.endsWith(".webp");
    }

    /**
     * 辅助方法：插入 Markdown 文本
     */
    private void insertMarkdownText(String text, boolean insertAtCursor) {
        if (insertAtCursor) {
            // 如果是拖入 TextArea，插入到光标位置
            int caretPos = editorArea.getCaretPosition();
            editorArea.insertText(caretPos, "\n" + text + "\n");
        } else {
            // 如果是拖入 WebView，因为无法获取具体的 HTML 对应位置，通常追加到文末
            // 或者你可以选择插入到当前光标位置（即使拖到了 WebView 上）
            editorArea.appendText("\n" + text + "\n");
        }

        // 强制触发一次 Markdown 重新渲染 (如果你的 TextProperty 监听器没触发的话)
        // handleTextChanged();
    }

    /**
     * 保存图片逻辑 (保持不变)
     */


    /**
     * 将图片复制到项目的 images 文件夹中
     *
     * @return 返回相对路径 (用于 Markdown)
     */
    private String saveImageToProject(File sourceFile) {
        try {
            // 1. 确定存放图片的目录 (建议放在 notes/images 下)
            File imageDir = new File("notes/images");
            if (!imageDir.exists()) {
                imageDir.mkdirs();
            }

            // 2. 生成新文件名 (防止重名覆盖，加个时间戳)
            String extension = "";
            int i = sourceFile.getName().lastIndexOf('.');
            if (i > 0) {
                extension = sourceFile.getName().substring(i);
            }
            String newFileName = System.currentTimeMillis() + extension;
            File targetFile = new File(imageDir, newFileName);

            // 3. 执行复制
            java.nio.file.Files.copy(sourceFile.toPath(), targetFile.toPath());

            // 4. 返回相对路径 (注意：Markdown 中路径分隔符最好用 /)
            return "images/" + newFileName;

        } catch (IOException e) {
            e.printStackTrace();
            return null; // 或者返回绝对路径 sourceFile.getAbsolutePath()
        }
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
        pasteItem.setOnAction(e -> {
            // 先尝试作为图片/文件粘贴
            boolean handled = performCustomPaste();

            // 如果没有粘贴图片（handled 为 false），则执行默认的文本粘贴
            if (!handled) {
                editorArea.paste();
            }
        });

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
     * 设置文件树的拖拽功能
     */
    private void setupDragAndDrop() {
        fileTree.setCellFactory(tree -> {
            TreeCell<String> cell = new TreeCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                    } else {
                        // --- 保持你原有的显示逻辑 ---
                        // 如果是叶子节点（笔记），只显示下划线后面的部分
                        // 如果是文件夹（分类），直接显示名字
                        TreeItem<String> treeItem = getTreeItem();
                        if (treeItem != null && treeItem.isLeaf()) {
                            // 笔记：解析 "分类_笔记名" -> "笔记名"
                            if (item.contains("_")) {
                                setText(item.substring(item.indexOf("_") + 1));
                            } else {
                                setText(item);
                            }
                            // 可以加个图标
                            // setGraphic(new ImageView(new Image("...")));
                        } else {
                            // 文件夹
                            setText(item);
                        }
                    }
                }
            };

            // --- 1. 拖拽探测 (Drag Detected) ---
            cell.setOnDragDetected(event -> {
                if (!cell.isEmpty() && cell.getTreeItem().isLeaf()) {
                    // 只有“笔记”可以被拖拽，文件夹不能拖
                    javafx.scene.input.Dragboard db = cell.startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
                    javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                    // 我们把完整的 "分类_笔记名" 放入剪贴板
                    content.putString(cell.getItem());
                    db.setContent(content);
                    event.consume();
                }
            });

            // --- 2. 拖拽经过 (Drag Over) ---
            cell.setOnDragOver(event -> {
                // 接受条件：拖拽有内容，且目标不是自己
                if (event.getGestureSource() != cell && event.getDragboard().hasString()) {
                    // 只能拖到“文件夹”上，或者拖到“文件夹里的其他笔记”上（意为归入该文件夹）
                    if (!cell.isEmpty()) {
                        event.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
                    }
                }
                event.consume();
            });

            // --- 3. 拖拽释放 (Drag Dropped) - 核心逻辑 ---
            cell.setOnDragDropped(event -> {
                javafx.scene.input.Dragboard db = event.getDragboard();
                boolean success = false;

                if (db.hasString()) {
                    String sourceFullFileName = db.getString(); // 例如 "课程学习_Java笔记"
                    TreeItem<String> targetTreeItem = cell.getTreeItem();

                    // 1. 确定目标分类名称
                    String targetCategory;
                    if (targetTreeItem.isLeaf()) {
                        // 如果拖到了另一个笔记上，就取那个笔记的父节点（文件夹）作为目标
                        targetCategory = targetTreeItem.getParent().getValue();
                    } else {
                        // 如果直接拖到了文件夹上
                        targetCategory = targetTreeItem.getValue();
                    }

                    // 2. 提取原笔记的纯标题
                    String noteTitle = sourceFullFileName;
                    if (sourceFullFileName.contains("_")) {
                        noteTitle = sourceFullFileName.substring(sourceFullFileName.indexOf("_") + 1);
                    }

                    // 3. 构造新的文件名
                    String newFullFileName = targetCategory + "_" + noteTitle;

                    // 4. 执行文件重命名操作
                    if (!sourceFullFileName.equals(newFullFileName)) {
                        success = moveNoteFile(sourceFullFileName, newFullFileName);
                    }
                }

                event.setDropCompleted(success);
                event.consume();

                // 5. 如果成功，刷新文件树
                if (success) {
                    refreshFileTree();
                }
            });

            return cell;
        });
    }

    /**
     * 物理移动文件（重命名）
     */
    private boolean moveNoteFile(String oldName, String newName) {
        // 假设你的笔记都在 "notes/" 目录下，根据你的 FileUtil 调整路径
        java.io.File oldFile = new java.io.File("notes/" + oldName + ".md");
        java.io.File newFile = new java.io.File("notes/" + newName + ".md");

        if (oldFile.exists() && !newFile.exists()) {
            boolean renamed = oldFile.renameTo(newFile);
            if (renamed) {
                System.out.println("笔记已移动: " + oldName + " -> " + newName);

                // 可选：如果当前正在编辑这个文件，需要更新当前编辑器的状态
                // checkAndUpdateCurrentEditor(newName);
                return true;
            } else {
                System.out.println("文件移动失败，可能是被占用");
            }
        } else {
            System.out.println("源文件不存在或目标文件已存在");
        }
        return false;
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
        if (event.getSource() instanceof MenuItem item) {
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
        editorFindPane.setVisible(true);
        editorFindPane.setManaged(true);
        replaceBox.setVisible(false); // 查找模式下隐藏替换输入框
        editorFindField.requestFocus();
        // 如果有选中文本，自动填入查找框
        String selected = editorArea.getSelectedText();
        if (!selected.isEmpty()) {
            editorFindField.setText(selected);
        } else {
            // 如果没填入文字，可能需要清空计数
            updateMatchStatus(true);
        }
    }

    /**
     * 更新查找结果计数 (格式：第 X 个 / 共 Y 个)
     */
    private void updateMatchStatus(boolean performHighlight) {
        String query = editorFindField.getText();
        String content = editorArea.getText();

        // 1. 如果搜索框为空，清空标签
        if (!editorFindPane.isVisible() || editorFindField.getText().isEmpty()) {
            lblMatchCount.setText("");
            editorFindField.setStyle(""); // 恢复输入框样式
            return;
        }
        if (editorArea.isVisible()) {
            String target = query;
            String textToSearch = content;

            int totalMatches = 0;
            int currentMatchIndex = 0;

            // 获取参照位置 (选区起点 或 光标位置)
            int anchorPos = editorArea.getCaretPosition();
            if (editorArea.getSelectedText().length() > 0) {
                anchorPos = editorArea.getSelection().getStart();
            }

            // 遍历统计
            int index = 0;
            while ((index = textToSearch.indexOf(target, index)) != -1) {
                totalMatches++;
                if (index <= anchorPos) {
                    currentMatchIndex = totalMatches;
                }
                index += target.length();
            }

            updateLabelUI(currentMatchIndex, totalMatches);
        }

        // =========================================================
        // 场景 B: 预览模式 (WebView)
        // =========================================================
        else if (webView.isVisible()) {
            try {
                // 调用 JS 进行高亮，并获取 JS 返回的 int 计数
                Object result = webView.getEngine().executeScript("highlightAll('" + escapeJs(query) + "')");
                int totalMatches = (Integer) result;

                // WebView 比较难获取当前滚动到了第几个，这里简化为只显示总数
                if (totalMatches > 0) {
                    lblMatchCount.setText("共 " + totalMatches + " 个");
                    lblMatchCount.setStyle("-fx-text-fill: #999;");
                } else {
                    lblMatchCount.setText("无结果");
                    lblMatchCount.setStyle("-fx-text-fill: #ff6b6b;");
                }
            } catch (Exception e) {
                // 忽略 JS 执行错误 (比如页面还没加载完)
            }
        }
    }

    /**
     * 辅助：更新 UI 标签颜色和文字
     */
    private void updateLabelUI(int current, int total) {
        if (total == 0) {
            lblMatchCount.setText("无结果");
            lblMatchCount.setStyle("-fx-text-fill: #ff6b6b;"); // 红色
        } else {
            // 修正：如果光标在第一个词之前，current可能为0，显示 0/N 或者 1/N 均可
            lblMatchCount.setText(String.format("%d / %d", current, total));
            lblMatchCount.setStyle("-fx-text-fill: #999;"); // 灰色
        }
    }

    /**
     * 辅助：防止查询包含单引号导致 JS 报错
     */
    private String escapeJs(String str) {
        return str.replace("'", "\\'");
    }
    // 菜单点击“替换” (Ctrl+H) 触发
    @FXML
    private void handleReplace() {
        editorFindPane.setVisible(true);
        editorFindPane.setManaged(true);
        replaceBox.setVisible(true);  // 替换模式下显示替换输入框
        editorFindField.requestFocus();
    }

    @FXML
    private void findPrevious() {
        String query = editorFindField.getText();
        if (query == null || query.isEmpty()) return;

        String content = editorArea.getText();

        // 获取当前选中的起始位置 (如果没有选中，则为光标位置)
        // 我们要从这个位置的前一个字符开始往回找
        int currentPos = editorArea.getSelection().getStart();

        // 核心逻辑：倒序查找 lastIndexOf
        // 从 currentPos - 1 开始往前找
        int index = content.lastIndexOf(query, currentPos - 1);

        if (index != -1) {
            selectAndScrollTo(index, query.length());
        } else {
            // 没找到：循环查找，从文本末尾开始找
            // flashNode(editorFindField); // 可选：给个输入框闪烁提示没找到
            int retry = content.lastIndexOf(query);
            if (retry != -1) {
                selectAndScrollTo(retry, query.length());
            }
        }
        updateMatchStatus(true);
    }
    // 查找下一个 (↓ 按钮触发)
    @FXML
    private void findNext() {
        String query = editorFindField.getText();
        if (query == null || query.isEmpty()) return;

        String content = editorArea.getText();

        // 获取当前光标位置 (或选区结束位置)
        // 从这个位置往后找
        int currentPos = editorArea.getCaretPosition();

        // 核心逻辑：正序查找 indexOf
        int index = content.indexOf(query, currentPos);

        if (index != -1) {
            selectAndScrollTo(index, query.length());
        } else {
            // 没找到：循环查找，从文本开头开始找
            int retry = content.indexOf(query);
            if (retry != -1) {
                selectAndScrollTo(retry, query.length());
            }
        }

        updateMatchStatus(true);
    }

    private void selectAndScrollTo(int index, int length) {
        // 1. 必须先让编辑器获取焦点，否则用户看不见光标闪烁
        editorArea.requestFocus();

        // 2. 选中查找到的文本
        editorArea.selectRange(index, index + length);

    }

    // 5. 全部替换 (面板内“全部”按钮触发)
    @FXML
    private void handleReplaceAll() {
        String query = editorFindField.getText();
        String target = replaceInputField.getText();
        if (query == null || query.isEmpty()) return;

        String content = editorArea.getText();
        // 使用 replace 方法替换所有匹配项
        editorArea.setText(content.replace(query, target));
    }

    // 替换当前 (面板内“替换”按钮触发)
    @FXML
    private void handleReplaceSingle() {
        String query = editorFindField.getText();
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
        editorFindPane.setVisible(false);
        editorFindPane.setManaged(false);
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
    @FXML private void handleH6() { insertAtLineStart("######");
    }

    /**
     * 实时查找：输入什么，就马上选中什么
     */
    private void handleIncrementalSearch(String query) {
        if (query == null || query.isEmpty()) {
            lblMatchCount.setText("");
            return;
        }

        // 仅在编辑模式下执行“选中”动作
        if (editorArea.isVisible()) {
            String content = editorArea.getText();

            // 策略：从当前光标位置开始找，为了让用户看到最近的一个
            int startPos = editorArea.getCaretPosition();
            int index = content.indexOf(query, startPos);

            // 如果后面没有，就从头找
            if (index == -1) {
                index = content.indexOf(query);
            }

            if (index != -1) {
                // 【关键步骤】选中它！这就是 TextArea 的“高亮”
                editorArea.positionCaret(index);
                editorArea.selectRange(index, index + query.length());
            }
        }

        // 无论选中与否，都要更新计数标签
        updateMatchStatus(false);
    }
}