package com.deepmind.controller;

import com.deepmind.util.FileUtil;
import com.deepmind.util.MarkdownParser;
import com.deepmind.util.NoteMetadata;
import com.deepmind.util.OutlineItem;
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
    @FXML
    private ListView<OutlineItem> outlineListView;
    @FXML private VBox outlineContainer;

    // --- 整体布局与工具栏 ---
    @FXML private VBox rootContainer;
    @FXML private SplitPane splitPane;

    @FXML private ToggleButton btnToggleSidebar;
    @FXML private MenuBar mainMenuBar;

    // --- 状态变量 ---
    private String currentNoteTitle = "";
    private double lastDividerPosition = 0.2;

    // 用于在 Editor -> Preview 切换时传递滚动位置
    private double pendingScrollRatio = -1;

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
    // 在类成员变量位置定义
    private javafx.animation.PauseTransition debounceTimer = new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
    private java.io.File currentNoteFile;
    @FXML
    public void initialize() {
        FileUtil.initStorage();
        fileTree.setEditable(true);
        refreshFileTree();
        setupTreeSelection();
        setupSearch();
        setupOutline();
        setupWordCount();
        showWelcomePage();
        initContextMenu();         // 编辑区的右键菜单
        initFileTreeContextMenu(); // 文件树的右键菜单
        setupTreeSelection();
        setupDragAndDrop();
        setupDualDragAndDrop();
        setupPasteLogic();
        setupFindFeature();

         //1. 点击 WebView 进入编辑模式
        webView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 3) {
                handleEditMode();
            }
        });

        editorArea.setOnMouseClicked(event -> {
            if (event.getClickCount() == 3) {
                handleEditMode();
            }
        });


        editorArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                if (handleAutoList()) {
                    event.consume(); // 拦截原生回车，使用我们自定义的换行逻辑
                }
            }
        });
        // 优化后的监听逻辑
        editorArea.textProperty().addListener((obs, oldVal, newVal) -> {
            debounceTimer.stop(); // 关键：先停止之前的计时
            debounceTimer.setOnFinished(event -> {
                // 使用 Platform.runLater 确保在 UI 线程平滑渲染
                javafx.application.Platform.runLater(this::updatePreview);
            });
            debounceTimer.playFromStart();
        });
        new Thread(() -> {
            try {
                // 这种方式不会报错，且能安全地触发 PDFBox 扫描系统字体并建立缓存
                org.apache.pdfbox.pdmodel.font.PDType1Font font = org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA;
                font.getSpaceWidth();
                System.out.println("PDF 引擎初始化成功");
            } catch (Exception ignored) {}
        }).start();
        // 默认显示预览模式
        showEditor(false);
    }

    /**
     * 核心逻辑：文件树加载
     */
    /**
     * 核心逻辑升级：支持无限层级文件夹 (A_B_C_Note.md)
     */
    private void refreshFileTree() {
        TreeItem<String> root = new TreeItem<>("Root");

        try {
            List<String> allFiles = FileUtil.listAllNotes();

            // 先按字母排序，保证文件夹顺序一致
            allFiles.sort(String::compareTo);

            for (String fullFileName : allFiles) {
                // 1. 分割文件名：使用 "_" 分割，但要处理文件名本身可能不包含 _ 的情况
                // 例如: "A_B_Note.md" -> ["A", "B", "Note.md"]
                // 这里的逻辑是：只要不是最后一部分，前面的都当做文件夹
                String[] parts = fullFileName.split("_");

                TreeItem<String> currentParent = root;

                // 2. 遍历路径构建文件夹 (排除最后一部分，那是文件名)
                for (int i = 0; i < parts.length - 1; i++) {
                    String folderName = parts[i];
                    currentParent = findOrCreateChild(currentParent, folderName);
                }

                // 3. 创建最后的文件节点
                String simpleFileName = parts[parts.length - 1]; // 纯文件名

                // 使用匿名类重写 toString，让树只显示短名字，但 Value 存长名字(用于读取)
                TreeItem<String> noteItem = new TreeItem<>(fullFileName) {
                    @Override public String toString() { return simpleFileName; }
                };

                currentParent.getChildren().add(noteItem);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        fileTree.setRoot(root);
        fileTree.setShowRoot(false);
    }

    // 辅助方法：在父节点下查找同名文件夹，没有则创建
    private TreeItem<String> findOrCreateChild(TreeItem<String> parent, String name) {
        for (TreeItem<String> child : parent.getChildren()) {
            // 注意：文件夹节点的 Value 此时只存了文件夹名（如 "Java"），而不是全路径
            // 这与文件节点不同，文件节点存的是 fullFileName
            if (child.getValue().equals(name) && !child.isLeaf()) {
                return child;
            }
        }
        // 没找到，创建一个新的文件夹节点
        TreeItem<String> newFolder = new TreeItem<>(name);
        newFolder.setExpanded(true); // 默认展开
        parent.getChildren().add(newFolder);

        // 简单的排序：让文件夹也按字母排
        parent.getChildren().sort(Comparator.comparing(TreeItem::getValue));

        return newFolder;
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
        // 1. 计算前缀（也就是所在的路径）
        TreeItem<String> selected = fileTree.getSelectionModel().getSelectedItem();
        StringBuilder prefixBuilder = new StringBuilder();

        TreeItem<String> parentFolder;

        if (selected == null) {
            parentFolder = fileTree.getRoot();
        } else {
            // 判断选中项是文件还是文件夹
            boolean isNote = isNoteNode(selected); // 需抽取一个辅助判断方法

            if (isNote) {
                parentFolder = selected.getParent();
            } else {
                parentFolder = selected;
            }
        }

        // 2. 递归向上遍历，拼接路径 (例如: Java_基础_)
        // 只要 parentFolder 不是 Root，就往上找
        getPathPrefix(parentFolder, prefixBuilder);
        String categoryPrefix = prefixBuilder.toString();

        // 3. 自动生成文件名
        String baseName = "新笔记";
        String pureName = baseName;
        String fullFileName = categoryPrefix + pureName;

        try {
            List<String> existingFiles = FileUtil.listAllNotes();
            int count = 1;
            while (existingFiles.contains(fullFileName)) {
                pureName = baseName + count;
                fullFileName = categoryPrefix + pureName;
                count++;
            }

            // 4. 创建
            String initialContent = "# " + pureName;
            FileUtil.save(fullFileName, initialContent);

            // 5. 刷新
            refreshFileTree();

            // 6. 选中新文件 (这里的选中逻辑可能需要适配新的树结构，简单起见先刷树)
            // 进阶：你可以写一个递归查找 selectFileInTree(fullFileName)
            selectFileInTree(fullFileName);

        } catch (IOException e) {
            showError("创建失败", e.getMessage());
        }
    }

    // 辅助：递归获取路径前缀 (从下往上拼，最后反转? 或者 insert(0, ...))
    private void getPathPrefix(TreeItem<String> item, StringBuilder sb) {
        if (item == null || item.getValue().equals("Root")) return;

        // 先处理父级，确保顺序是 A_B_
        getPathPrefix(item.getParent(), sb);

        sb.append(item.getValue()).append("_");
    }

    // 辅助：判断是否是笔记节点 (依据：是否在硬盘上真实存在该文件)
    private boolean isNoteNode(TreeItem<String> item) {
        if (item == null || item.getValue() == null) return false;
        // 我们的逻辑是：文件节点存的是 "A_B_Note"，文件夹节点存的是 "B"
        // 且文件节点通常是叶子节点 (isLeaf)，但文件夹刚创建时也是 leaf，所以得看文件是否存在
        File f = new File("notes/" + item.getValue() + ".md");
        return f.exists() && f.isFile();
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
            // 1. 获取当前编辑器内容
            String content = editorArea.getText();
            if (content == null) content = "";

            // --- 【新增逻辑开始】自动重命名 ---
            String extractedTitle = extractTitleFromContent(content);

            if (extractedTitle != null && !extractedTitle.isEmpty()) {
                // 2.1 处理非法字符
                String safeNewName = sanitizeFileName(extractedTitle);

                // 2.2 处理分类前缀逻辑 (保留原有的分类)
                // 比如原文件名是 "课程_Java基础"，content里改成了 "# Java进阶"
                // 我们希望新文件名是 "课程_Java进阶"
                String currentCategory = "";
                String currentPureName = currentNoteTitle;

                if (currentNoteTitle.contains("_")) {
                    int underscoreIndex = currentNoteTitle.indexOf("_");
                    currentCategory = currentNoteTitle.substring(0, underscoreIndex + 1); // 保留 "课程_"
                    currentPureName = currentNoteTitle.substring(underscoreIndex + 1);
                }

                // 2.3 拼接完整的新文件名
                String newFullFileName = currentCategory + safeNewName;

                // 2.4 只有当名字真正发生改变时，才执行重命名
                // 注意：这里比较的是“去除分类后的纯文件名”是否一致
                if (!currentPureName.equals(safeNewName)) {
                    System.out.println("检测到标题变化，准备重命名: " + currentNoteTitle + " -> " + newFullFileName);

                    try {
                        // 执行物理重命名
                        FileUtil.rename(currentNoteTitle, newFullFileName);

                        // 更新当前内存中的标题记录
                        currentNoteTitle = newFullFileName;

                        // 刷新左侧文件树，显示新名字
                        refreshFileTree();

                        // 重新选中该节点 (可选优化，防止树列表跳动)
                        // selectFileInTree(newFullFileName);

                    } catch (IOException e) {
                        System.err.println("自动重命名失败 (可能是文件名已存在): " + e.getMessage());
                        // 如果重命名失败（比如名字冲突），可以选择弹窗提示，或者默默保持原名继续保存内容
                    }
                }
            }
            // --- 【新增逻辑结束】 ---

            // 3. 核心保存：将内容写入文件 (此时 currentNoteTitle 已经是新的名字了)
            FileUtil.save(currentNoteTitle, content);

            // 4. 更新字数统计 (保持原有逻辑)
            String filtered = content.replaceAll("!\\[.*?\\]\\(.*?\\)", "");
            int count = filtered.replaceAll("\\s", "").length();
            wordCountLabel.setText("字数: " + count);

            System.out.println("已保存: " + currentNoteTitle);

        } catch (IOException e) {
            System.err.println("保存笔记失败: " + e.getMessage());
            e.printStackTrace();
            showError("保存失败", e.getMessage());
        }
    }

    @FXML
    private void handleEditMode() {
        // 逻辑：如果当前在看编辑框，就传 false (去预览)；否则传 true (去编辑)
        showEditor(!editorArea.isVisible());
    }

    @FXML
    private void handlePreviewMode() {
        updatePreview();
        editorArea.setVisible(false);
        webView.setVisible(true);
    }


    /**
     * 核心：更新预览区
     * 1. 解析 Markdown
     * 2. 获取当前主题 CSS
     * 3. 注入 JS (高亮支持)
     * 4. 渲染到 WebView
     */
    private void updatePreview() {
        String mdContent = editorArea.getText();
        if (mdContent == null) mdContent = "";

        // 1. 解析 Markdown -> HTML 片段
        String markdownHtml = MarkdownParser.parse(mdContent);

        // 2. 获取当前主题对应的 CSS (核心修改)
        String themeCss = getThemeRenderCss();

        // 3. 准备 Base URL (用于加载本地图片)
        File notesDir = new File("notes/");
        String baseUrl = notesDir.toURI().toString();

        // 4. 准备 JavaScript (搜索高亮功能，保持不变)
        String jsScript = """
            <script>
                function removeHighlights() {
                    const highlights = document.querySelectorAll('span.search-highlight');
                    highlights.forEach(span => {
                        const parent = span.parentNode;
                        parent.replaceChild(document.createTextNode(span.textContent), span);
                        parent.normalize();
                    });
                }
                function highlightAll(keyword) {
                    removeHighlights();
                    if (!keyword) return 0;
                    const walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                    const nodes = [];
                    while(walk.nextNode()) nodes.push(walk.currentNode);
                    let count = 0;
                    const escapeRegExp = (string) => string.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&');
                    const regex = new RegExp('(' + escapeRegExp(keyword) + ')', 'gi');
                    nodes.forEach(node => {
                        if (node.parentNode.nodeName === "SCRIPT" || node.parentNode.nodeName === "STYLE") return;
                        const text = node.nodeValue;
                        if (regex.test(text)) {
                            const fragment = document.createDocumentFragment();
                            let lastIdx = 0;
                            text.replace(regex, (match, p1, offset) => {
                                fragment.appendChild(document.createTextNode(text.slice(lastIdx, offset)));
                                const span = document.createElement('span');
                                span.className = 'search-highlight';
                                span.textContent = match;
                                if (count === 0) span.id = 'first-match';
                                fragment.appendChild(span);
                                lastIdx = offset + match.length;
                                count++;
                            });
                            fragment.appendChild(document.createTextNode(text.slice(lastIdx)));
                            node.parentNode.replaceChild(fragment, node);
                        }
                    });
                    const first = document.getElementById('first-match');
                    if (first) first.scrollIntoView({behavior: "smooth", block: "center"});
                    return count;
                }
                
                    // --- 3. 跳转到第 N 个查找结果 (用于 FindNext) ---
                    function scrollToMatch(index) {
                         const highlights = document.querySelectorAll('span.search-highlight');
                         if (highlights.length === 0) return -1;
                
                         // 循环逻辑
                         if (index >= highlights.length) index = 0;
                         if (index < 0) index = highlights.length - 1;
                
                         // 重置颜色
                         highlights.forEach(span => span.style.backgroundColor = "#ffeb3b"); 
                
                         // 选中目标
                         const target = highlights[index];
                         target.style.backgroundColor = "#ff9800"; // 橙色选中
                         target.scrollIntoView({behavior: "smooth", block: "center"});
                         return index;
                    }
                
                    // --- 4. 跳转到大纲标题 (用于 Outline) ---
                    function scrollToHeading(index) {
                        const headers = document.querySelectorAll('h1, h2, h3, h4, h5, h6');
                        if (headers[index]) {
                            headers[index].scrollIntoView({behavior: "smooth", block: "start"});
                
                            // 闪烁效果 (直接用具体颜色，防止 var 变量失效)
                            headers[index].style.transition = "background-color 0.5s";
                            const originalBg = headers[index].style.backgroundColor;
                            headers[index].style.backgroundColor = "#fff3cd"; // 浅黄闪烁
                            setTimeout(() => {
                                headers[index].style.backgroundColor = originalBg;
                            }, 1000);
                        }
                    }
            </script>
            """;

        // 5. 组装最终的完整 HTML
        String fullHtml = "<!DOCTYPE html>"
                + "<html>"
                + "<head>"
                + "    <meta charset=\"UTF-8\">"
                + "    <base href=\"" + baseUrl + "\">"
                + "    <style>"
                + themeCss
                + "        /* 强制高亮样式，确保可见 */"
                + "        .search-highlight { background-color: #ffeb3b !important; color: #000 !important; }"
                + "    </style>"
                + "</head>"
                + "<body>"
                + markdownHtml
                + jsScript
                + "</body>"
                + "</html>";

        // 6. 错误监控 (方便调试)
        webView.getEngine().setOnError(event -> System.err.println("JS Error: " + event.getMessage()));

        // 7. 绑定加载监听器 (这是取代 Timeline 的关键)
        webView.getEngine().getLoadWorker().stateProperty().removeListener(this::onWebViewLoadStateChanged);
        webView.getEngine().getLoadWorker().stateProperty().addListener(this::onWebViewLoadStateChanged);

        // 8. 开始加载
        webView.getEngine().loadContent(fullHtml);
    }

    /**
     * WebView 加载状态监听 (只有在这里执行 JS 才是 100% 安全的)
     */
    private void onWebViewLoadStateChanged(javafx.beans.value.ObservableValue<? extends javafx.concurrent.Worker.State> obs, javafx.concurrent.Worker.State oldState, javafx.concurrent.Worker.State newState) {
        if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {

            // --- 【修复后的滚动逻辑】 ---
            if (pendingScrollRatio >= 0) {
                final double ratio = pendingScrollRatio; // 复制一份给内部用

                // 稍微加长一点延迟，确保图片占位符已经渲染，高度计算才准确
                javafx.animation.Timeline scrollDelay = new javafx.animation.Timeline(
                        new javafx.animation.KeyFrame(javafx.util.Duration.millis(150), e -> {

                            // 1. 掐头去尾：如果在顶部或底部，直接锁死，不要计算
                            if (ratio <= 0.05) {
                                webView.getEngine().executeScript("window.scrollTo(0, 0);");
                            } else if (ratio >= 0.95) {
                                webView.getEngine().executeScript("window.scrollTo(0, document.body.scrollHeight);");
                            } else {
                                // 2. 中间部分：精确计算 (总高 - 视口) * 比例
                                String script = """
                                            var h = document.documentElement.scrollHeight || document.body.scrollHeight;
                                            var v = document.documentElement.clientHeight || document.body.clientHeight;
                                            window.scrollTo(0, (h - v) * %f);
                                        """.formatted(ratio);
                                webView.getEngine().executeScript(script);
                            }
                            webView.setOpacity(1);
                            pendingScrollRatio = -1; // 重置
                        })
                );
                scrollDelay.play();
            }

            // --- B. 处理搜索高亮 (如果查找框没关且有字) ---
            if (editorFindPane != null && editorFindPane.isVisible() && !editorFindField.getText().isEmpty()) {
                // 这里 true 表示执行高亮动作
                updateMatchStatus(true);
            }

            // 显示 WebView (防止加载时闪烁)
            webView.setOpacity(1);
        }
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
        //原来你是这样写的：
        //String welcomeMD = "# 欢迎使用 DeepMind Note\n\n> 这是一个基于 JavaFX 的交互式笔记演示原型。";

        // --- 修改后：直接使用帮助文档的内容 ---
        editorArea.setText(HELP_MARKDOWN_CONTENT);

        // 将标题设为空，表示这是一个临时页面（或者你可以设为 "DeepMind_Help"）
        currentNoteTitle = "";

        // 核心步骤：渲染预览并切换到预览模式
        // 这样软件一启动，用户看到的就是渲染好的漂亮文档，而不是 Markdown 源码
        updatePreview();
        handlePreviewMode();
    }

    private void setupWordCount() {
        editorArea.textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null || newValue.isEmpty()) {
                wordCountLabel.setText("字数: 0");
                return;
            }

            // 1. 过滤 Markdown 图片语法: ! [描述] (链接)
            // 这个正则会匹配以 ! 开头，紧跟 [] 和 () 的内容并将其替换为空字符串
            String filtered = newValue.replaceAll("!\\[.*?\\]\\(.*?\\)", "");

            // 2. 过滤所有空白字符（空格、制表符、换行符）
            // \\s 包含所有看不见的空白
            int count = filtered.replaceAll("\\s", "").length();

            wordCountLabel.setText("字数: " + count);
        });
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
        // 1. 生成大纲
        editorArea.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.isEmpty()) {
                outlineListView.getItems().clear();
                return;
            }

            class TempHeading {
                int level;
                String text;
                int index;
                int order;

                TempHeading(int l, String t, int i, int o) {
                    this.level = l;
                    this.text = t;
                    this.index = i;
                    this.order = o;
                }
            }
            List<TempHeading> tempList = new ArrayList<>();
            TreeSet<Integer> levels = new TreeSet<>();

            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?m)^(#+)\\s+(.*)$");
            java.util.regex.Matcher matcher = pattern.matcher(newVal);

            int headingCount = 0; // 【新增】标题计数器

            while (matcher.find()) {
                String hashes = matcher.group(1);
                String title = matcher.group(2);
                int startIndex = matcher.start();
                int level = hashes.length();

                // 记录这是第几个标题 (headingCount++)
                tempList.add(new TempHeading(level, title, startIndex, headingCount++));
                levels.add(level);
            }

            List<Integer> sortedLevels = new ArrayList<>(levels);
            List<OutlineItem> displayItems = new ArrayList<>();

            for (TempHeading h : tempList) {
                int mappedLevel = sortedLevels.indexOf(h.level);
                String marker = switch (mappedLevel) {
                    case 0 -> "◈ ";
                    case 1 -> "◇ ";
                    case 2 -> "▹ ";
                    default -> "▪ ";
                };
                String indent = "  ".repeat(Math.max(0, mappedLevel));

                // 【关键】传入 h.order (第几个标题)
                displayItems.add(new OutlineItem(indent + marker + h.text, h.index, h.order));
            }

            outlineListView.getItems().setAll(displayItems);
        });

        // 2. 点击跳转 (支持双模式)
        outlineListView.setOnMouseClicked(event -> {
            OutlineItem selected = outlineListView.getSelectionModel().getSelectedItem();
            if (selected != null) {

                // =================================================
                // 场景 A: 预览模式 (WebView) -> 调用 JS 跳转
                // =================================================
                if (webView.isVisible()) {
                    // 调用刚才注入的 JS 函数，传入 orderIndex
                    webView.getEngine().executeScript("scrollToHeading(" + selected.orderIndex + ")");
                    // 让 WebView 获取焦点，以便键盘上下滚动
                    webView.requestFocus();
                }

                // =================================================
                // 场景 B: 编辑模式 (TextArea) -> 光标跳转
                // =================================================
                else {
                    editorArea.requestFocus();
                    editorArea.positionCaret(selected.startIndex);

                    String text = editorArea.getText();
                    int endIndex = text.indexOf('\n', selected.startIndex);
                    if (endIndex == -1) endIndex = text.length();

                    editorArea.selectRange(selected.startIndex, endIndex);
                    editorArea.setScrollTop(Double.MIN_VALUE);
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
    /**
     * 专门为左侧 TreeView 设置右键菜单
     * 逻辑：根据 isFolderNode 判断结果，动态显示/隐藏“新建”选项
     */
    private void initFileTreeContextMenu() {
        ContextMenu contextMenu = new ContextMenu();

        // --- 1. 定义菜单项 ---
        MenuItem newNoteItem = new MenuItem("新建笔记");
        newNoteItem.setOnAction(e -> handleNewNote());

        MenuItem newFolderItem = new MenuItem("新建文件夹");
        newFolderItem.setOnAction(e -> handleNewFolder());

        // 分割线 (用来把新建和编辑分开)
        SeparatorMenuItem separator1 = new SeparatorMenuItem();

        MenuItem renameItem = new MenuItem("重命名");
        renameItem.setOnAction(e -> handleRenameNote());

        MenuItem moveItem = new MenuItem("移动到文件夹...");
        moveItem.setOnAction(e -> handleMoveNote());

        SeparatorMenuItem separator2 = new SeparatorMenuItem();

        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setStyle("-fx-text-fill: red;");
        deleteItem.setOnAction(e -> handleDeleteNote());

        // --- 2. 初始全部加入 ---
        contextMenu.getItems().addAll(
                newNoteItem, newFolderItem, separator1,
                renameItem, moveItem, separator2, deleteItem
        );

        fileTree.setContextMenu(contextMenu);

        // --- 3. 核心：右键点击前动态判断 ---
        fileTree.setOnContextMenuRequested(e -> {
            // A. 获取鼠标点中的节点
            javafx.scene.Node node = e.getPickResult().getIntersectedNode();
            while (node != null && !(node instanceof TreeCell) && node != fileTree) {
                node = node.getParent();
            }

            // B. 判断点中的内容
            if (node instanceof TreeCell<?> cell && !cell.isEmpty()) {
                // 1. 强制选中当前行 (优化体验)
                TreeItem<String> item = (TreeItem<String>) cell.getTreeItem();
                fileTree.getSelectionModel().select(item);

                // 2. 使用你提供的 isFolderNode 方法判断
                boolean isFolder = isFolderNode(item);

                if (isFolder) {
                    // === 情况 A: 右键点击的是文件夹 ===
                    // 显示“新建”系列
                    newNoteItem.setVisible(true);
                    newFolderItem.setVisible(true);
                    separator1.setVisible(true);

                    // 文件夹也可以重命名和删除，所以保持显示
                    renameItem.setVisible(true);
                    moveItem.setVisible(true); // 文件夹一般不移动，或者看你需求，这里先留着
                    deleteItem.setVisible(true);
                    separator2.setVisible(true);
                } else {
                    // === 情况 B: 右键点击的是笔记文件 ===
                    // 隐藏“新建”系列
                    newNoteItem.setVisible(false);
                    newFolderItem.setVisible(false);
                    separator1.setVisible(false);

                    // 显示文件操作
                    renameItem.setVisible(true);
                    moveItem.setVisible(true);
                    deleteItem.setVisible(true);
                    separator2.setVisible(true);
                }

            } else {
                // === 情况 C: 右键点击的是空白处 ===
                // 视为在根目录操作，取消选中
                fileTree.getSelectionModel().clearSelection();

                // 只显示“新建”，隐藏针对特定文件的操作
                newNoteItem.setVisible(true);
                newFolderItem.setVisible(true);
                separator1.setVisible(true); // 保留分割线看起来舒服点，或者隐藏也可以

                renameItem.setVisible(false);
                moveItem.setVisible(false);
                deleteItem.setVisible(false);
                separator2.setVisible(false);
            }
        });
    }


    /**
     * 设置文件树的单元格工厂：
     * 1. 负责显示（去前缀）
     * 2. 负责拖拽（Drag & Drop）
     * 3. 负责行内编辑（重命名）
     */
    // MainController.java

    private void setupDragAndDrop() {
        fileTree.setCellFactory(tv -> new TreeCell<String>() {
            private TextField textField;

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    if (isEditing()) {
                        if (textField != null) {
                            setText(null);
                            setGraphic(textField);
                        }
                        return;
                    }
                    // 显示逻辑：如果是文件(叶子)，去掉前缀显示；如果是文件夹，直接显示名字
                    if (getTreeItem().isLeaf() && item.contains("_")) {
                        setText(item.substring(item.lastIndexOf("_") + 1));
                    } else {
                        setText(item);
                    }
                    setGraphic(null);
                }
            }

            @Override
            public void startEdit() {
                // 允许编辑所有节点（不再限制只编辑叶子节点）
                if (!isEmpty()) {
                    super.startEdit();
                    createTextField();
                    setText(null);
                    setGraphic(textField);
                    textField.selectAll();
                    textField.requestFocus();
                }
            }

            @Override
            public void cancelEdit() {
                super.cancelEdit();
                // 恢复显示文本
                String textToShow = getItem();
                if (getTreeItem().isLeaf() && textToShow.contains("_")) {
                    textToShow = textToShow.substring(textToShow.lastIndexOf("_") + 1);
                }
                setText(textToShow);
                setGraphic(null);
            }

            @Override
            public void commitEdit(String newName) {
                // 1. 获取重命名之前的值
                String oldName = getItem(); // 对于文件夹，这是 "Category"；对于文件，这是 "Category_Note"

                // 如果名字没变，直接返回
                if (oldName.equals(newName)) {
                    super.commitEdit(oldName);
                    return;
                }

                // 2. 简单的非法字符检查
                if (newName.contains("/") || newName.contains("\\") || newName.contains(":")) {
                    showError("重命名失败", "名称包含非法字符");
                    cancelEdit();
                    return;
                }

                // --- 分支 A: 如果是笔记文件 (叶子节点) ---
                if (getTreeItem().isLeaf()) {
                    // 计算旧的全路径和新的全路径
                    String prefix = "";
                    if (oldName.contains("_")) {
                        prefix = oldName.substring(0, oldName.lastIndexOf("_") + 1);
                    }
                    String newFullName = prefix + newName;

                    try {
                        FileUtil.rename(oldName, newFullName);
                        // 同步更新文件内部的 # 标题
                        syncH1TitleInFile(newFullName, newName, oldName);

                        super.commitEdit(newFullName);
                    } catch (IOException e) {
                        showError("重命名失败", "文件名可能已存在或被占用。");
                        cancelEdit();
                    }
                }
                // --- 分支 B: 如果是文件夹 (分类节点) ---
                else {
                    try {
                        // 执行批量重命名逻辑
                        boolean success = renameCategory(getTreeItem(), oldName, newName);
                        if (success) {
                            super.commitEdit(newName);
                            // 文件夹改名涉及大量文件变动，必须刷新整个树以更新所有子节点
                            refreshFileTree();
                        } else {
                            cancelEdit();
                        }
                    } catch (IOException e) {
                        showError("文件夹重命名失败", e.getMessage());
                        cancelEdit();
                    }
                }
            }

            private void createTextField() {
                // 编辑时显示的文本：如果是文件，显示纯文件名；如果是文件夹，显示文件夹名
                String textToEdit = getItem();
                if (getTreeItem().isLeaf() && textToEdit.contains("_")) {
                    textToEdit = textToEdit.substring(textToEdit.lastIndexOf("_") + 1);
                }

                textField = new TextField(textToEdit);
                textField.getStyleClass().add("rename-input");
                textField.setOnKeyReleased(t -> {
                    if (t.getCode() == KeyCode.ENTER) commitEdit(textField.getText());
                    else if (t.getCode() == KeyCode.ESCAPE) cancelEdit();
                });
                // 失去焦点时自动提交
                textField.focusedProperty().addListener((obs, oldVal, newVal) -> {
                    if (!newVal) commitEdit(textField.getText());
                });
            }

            // --- 拖拽逻辑保持不变 (为了简洁我省略了部分重复代码，请保留你原有的拖拽代码) ---
            {
                setOnDragDetected(event -> {
                    if (!isEmpty() && getTreeItem().isLeaf()) {
                        Dragboard db = startDragAndDrop(TransferMode.MOVE);
                        ClipboardContent content = new ClipboardContent();
                        content.putString(getItem());
                        db.setContent(content);
                        event.consume();
                    }
                });
                setOnDragOver(event -> {
                    if (event.getGestureSource() != this && event.getDragboard().hasString()) {
                        if (!isEmpty()) event.acceptTransferModes(TransferMode.MOVE);
                    }
                    event.consume();
                });
                setOnDragDropped(event -> {
                    Dragboard db = event.getDragboard();
                    boolean success = false;
                    if (db.hasString()) {
                        String sourceName = db.getString();
                        TreeItem<String> targetItem = getTreeItem();

                        // 计算目标分类前缀
                        StringBuilder prefixBuilder = new StringBuilder();
                        TreeItem<String> folderNode = targetItem.isLeaf() ? targetItem.getParent() : targetItem;
                        getPathPrefix(folderNode, prefixBuilder);
                        String targetPrefix = prefixBuilder.toString(); // 例如 "学习_Java_"

                        // 获取源文件的纯文件名
                        String pureName = sourceName.contains("_") ?
                                sourceName.substring(sourceName.lastIndexOf("_") + 1) : sourceName;

                        String destName = targetPrefix + pureName;
                        if (!sourceName.equals(destName)) {
                            success = moveNoteFile(sourceName, destName);
                        }
                    }
                    event.setDropCompleted(success);
                    event.consume();
                    if (success) refreshFileTree();
                });
            }
        });
    }

    /**
     * 核心逻辑：重命名文件夹（实际上是批量重命名所有拥有该前缀的文件）
     * @param item 被修改的树节点
     * @param oldCategoryName 旧的文件夹名 (例如 "Java")
     * @param newCategoryName 新的文件夹名 (例如 "Java新")
     */
    private boolean renameCategory(TreeItem<String> item, String oldCategoryName, String newCategoryName) throws IOException {
        // 1. 计算该文件夹的“完整前缀路径”
        // 例如：Root -> 学习 -> Java (我们正在改 Java)
        // 父级前缀是 "学习_"，旧前缀是 "学习_Java_"，新前缀是 "学习_Java新_"

        StringBuilder parentPrefixBuilder = new StringBuilder();
        getPathPrefix(item.getParent(), parentPrefixBuilder);
        String parentPrefix = parentPrefixBuilder.toString(); // 例如 "学习_" (如果是根目录则为空)

        String oldFullPrefix = parentPrefix + oldCategoryName + "_";
        String newFullPrefix = parentPrefix + newCategoryName + "_";

        System.out.println("准备将前缀 [" + oldFullPrefix + "] 批量改为 [" + newFullPrefix + "]");

        // 2. 获取所有文件
        List<String> allFiles = FileUtil.listAllNotes();
        int successCount = 0;

        // 3. 遍历并筛选需要改名的文件
        for (String fileName : allFiles) {
            if (fileName.startsWith(oldFullPrefix)) {
                // 构造新文件名
                // replaceFirst 仅替换开头匹配的部分，防止文件名中间恰好也有相同的字符串
                String newFileName = fileName.replaceFirst(java.util.regex.Pattern.quote(oldFullPrefix), newFullPrefix);

                FileUtil.rename(fileName, newFileName);
                successCount++;

                // 如果恰好正在编辑这个文件，更新 currentNoteTitle
                if (currentNoteTitle.equals(fileName)) {
                    currentNoteTitle = newFileName;
                }
            }
        }

        System.out.println("成功重命名了 " + successCount + " 个文件。");
        return true;
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
    // MainController.java

    @FXML
    private void handleRenameNote() {
        TreeItem<String> selectedItem = fileTree.getSelectionModel().getSelectedItem();
        if (selectedItem == null) return;

        // 不再弹窗，直接让 TreeView 进入编辑状态
        // 这会自动调用我们下面要写的 TreeCell.startEdit()
        fileTree.edit(selectedItem);
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

        // === 使用新弹窗 ===
        boolean confirm = showConfirmDialog(
                "删除笔记",
                "确定要永久删除 [" + title + "] 吗？\n此操作无法撤销。",
                true // true 表示这是危险操作，按钮会变红
        );

        if (confirm) {
            try {
                FileUtil.delete(title);
                selectedItem.getParent().getChildren().remove(selectedItem);

                // 如果删除的是当前正在编辑的文件，清空编辑器
                if (currentNoteTitle.equals(title)) {
                    editorArea.clear();
                    webView.getEngine().loadContent("");
                    currentNoteTitle = "";
                }
            } catch (IOException e) {
                showError("删除失败", "文件可能被占用或不存在。");
            }
        }
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
        TreeItem<String> selected = fileTree.getSelectionModel().getSelectedItem();
        TreeItem<String> targetParent;

        // 1. 确定要把新文件夹加到哪里
        if (selected == null || selected == fileTree.getRoot()) {
            targetParent = fileTree.getRoot(); // 没选中，加到根目录
        } else {
            // 这里的逻辑：我们通过判断 fullFileName 是否存在来推测它是不是文件
            // 更好的方式是看它是否有子节点，或者我们之前约定的 leaf 逻辑
            String val = selected.getValue();
            boolean isFile = false;
            try {
                // 简单的判断：如果硬盘上有这个名字的文件，那就是文件，否则就是文件夹节点
                // 注意：因为文件夹节点存的是短名(如"Java")，硬盘上应该没有 "Java" 这个文件(除非没后缀)
                // 而文件节点存的是 "Java_Note.md"
                File f = new File("notes/" + val + ".md"); // 尝试补全后缀判断
                if(f.exists() && f.isFile()) isFile = true;
                else if(new File("notes/" + val).exists() && new File("notes/" + val).isFile()) isFile = true; // 兼容无后缀
            } catch (Exception e) {}

            if (isFile) {
                targetParent = selected.getParent(); // 选中了文件，加到同级
            } else {
                targetParent = selected; // 选中了文件夹，加到它里面
            }
        }

        if (targetParent == null) targetParent = fileTree.getRoot();

        // 2. 生成不重复的文件夹名
        String baseName = "新建文件夹";
        String finalName = baseName;
        int counter = 1;

        boolean exists;
        do {
            exists = false;
            for (TreeItem<String> item : targetParent.getChildren()) {
                // 这里比较的是显示的短名字
                String itemDisplayName = item.getValue();
                // 如果是文件节点，getValue是全名，需要截取最后一段比较?
                // 不，文件夹节点的getValue就是文件夹名，所以直接比
                if (item.toString().equals(finalName)) {
                    exists = true;
                    finalName = baseName + counter;
                    counter++;
                    break;
                }
            }
        } while (exists);

        // 3. 创建并添加
        TreeItem<String> newCategory = new TreeItem<>(finalName);
        targetParent.getChildren().add(newCategory);
        targetParent.setExpanded(true);

        // 选中新文件夹
        fileTree.getSelectionModel().select(newCategory);

        // 滚动到该位置
        int row = fileTree.getRow(newCategory);
        if(row >= 0) fileTree.scrollTo(row);
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
        fileChooser.setTitle("多格式导入笔记");
        fileChooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("支持格式", "*.md", "*.txt", "*.docx", "*.pdf"));

        File selectedFile = fileChooser.showOpenDialog(rootContainer.getScene().getWindow());
        if (selectedFile == null) return;

        // 获取分类前缀逻辑
        TreeItem<String> selectedItem = fileTree.getSelectionModel().getSelectedItem();
        final String prefix = getFolderPathPrefix(selectedItem);
        final String originName = selectedFile.getName();
        final String suffix = originName.substring(originName.lastIndexOf(".")).toLowerCase();

        // 开启异步任务，防止软件界面“白屏”卡死
        javafx.concurrent.Task<String> task = new javafx.concurrent.Task<>() {
            @Override
            protected String call() throws Exception {
                if (suffix.equals(".pdf")) return com.deepmind.util.PdfUtil.readPdf(selectedFile);
                if (suffix.equals(".docx")) return com.deepmind.util.WordUtil.readDocx(selectedFile);
                return com.deepmind.util.FileUtil.readFromExternal(selectedFile);
            }
        };

        task.setOnSucceeded(e -> {
            String content = task.getValue();
            // 清洗文件名：解决日志中的 invalid input
            String pureName = originName.contains(".") ? originName.substring(0, originName.lastIndexOf(".")) : originName;
            String safeName = pureName.replaceAll("[\\\\/:*?\"<>|\\r\\n]", " ").trim();

            // 记录标题并更新编辑器
            currentNoteTitle = prefix + safeName;
            editorArea.setText(content);

            // 如果是导入 Markdown，执行“外部资源本地化”
            if (suffix.equals(".md")) {
                content = localizeImages(content, selectedFile); // 执行拷贝
            }

            // 重要：执行物理保存，确保 currentNoteFile 被赋值，否则预览图看不见
            handleSave();
            refreshFileTree();
            showEditor(false);
        });

        task.setOnFailed(e -> {
            task.getException().printStackTrace();
            showError("导入失败", "文件解析出错");
        });

        new Thread(task).start();
    }

    /**
     * 核心：本地化外部图片资源
     * 扫描 Markdown 内容，将所有 C:\path\to\img.png 拷贝到 notes/images/
     */
    private String localizeImages(String content, File sourceFile) {
        // 匹配 ![alt](path)
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("!\\[(.*?)\\]\\((.*?)\\)");
        java.util.regex.Matcher matcher = pattern.matcher(content);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;

        File projectImageDir = new File("notes/images");
        if (!projectImageDir.exists()) projectImageDir.mkdirs();

        while (matcher.find()) {
            sb.append(content, lastEnd, matcher.start());
            String alt = matcher.group(1);
            String path = matcher.group(2);

            // 如果是相对路径或网络路径，跳过
            if (path.startsWith("http") || path.startsWith("images/")) {
                sb.append(matcher.group(0));
            } else {
                try {
                    File imgFile = new File(path);
                    // 如果是相对路径，相对于导入的 MD 文件
                    if (!imgFile.isAbsolute()) {
                        imgFile = new File(sourceFile.getParent(), path);
                    }

                    if (imgFile.exists()) {
                        String newName = System.currentTimeMillis() + "_" + imgFile.getName();
                        java.nio.file.Files.copy(imgFile.toPath(), new File(projectImageDir, newName).toPath());
                        sb.append("![").append(alt).append("](images/").append(newName).append(")");
                    } else {
                        sb.append(matcher.group(0));
                    }
                } catch (Exception ex) {
                    sb.append(matcher.group(0));
                }
            }
            lastEnd = matcher.end();
        }
        sb.append(content.substring(lastEnd));
        return sb.toString();
    }

    // 辅助方法：获取路径前缀
    private String getFolderPathPrefix(TreeItem<String> item) {
        if (item == null) return "";
        if (item.isLeaf()) {
            String val = item.getValue();
            return val.contains("_") ? val.substring(0, val.lastIndexOf("_") + 1) : "";
        }
        return item.getValue() + "_";
    }
    /**
     * 简单的路径修复逻辑示例
     */
    private String fixImagePath(String content, String folderPath) {
        // 这里的逻辑是将 ![alt](image.png) 替换为 ![alt](file:///C:/path/to/image.png)
        // 这样 WebView 才能正确加载本地磁盘图片
        String absoluteFolderPath = new java.io.File(folderPath).toURI().toString();
        return content.replaceAll("\\!\\[(.*?)\\]\\((?!http)(.*?)\\)", "![$1](" + absoluteFolderPath + "$2)");
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
    // MainController.java

    @FXML
    private void handleDelete() {
        if (currentNoteTitle == null || currentNoteTitle.isEmpty()) return;

        // 修改：使用新的 showConfirmDialog
        boolean confirm = showConfirmDialog(
                "删除笔记",
                "确定要删除当前打开的笔记 [" + currentNoteTitle + "] 吗？\n此操作无法撤销。",
                true // true = 红色按钮
        );

        if (confirm) {
            try {
                FileUtil.delete(currentNoteTitle);
                refreshFileTree();
                showWelcomePage();
            } catch (IOException e) {
                showError("删除失败", e.getMessage());
            }
        }
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

    @FXML private void handleUnorderedList() {
//        insertAtLineStart("-");
        processListAction(false);
    }
    @FXML private void handleOrderedList() {
//        insertAtLineStart("1.");
        processListAction(true);
    }

    /**
     * 通用列表处理逻辑：支持多行、支持有序列表自动递增
     * @param isOrdered true=有序列表(1. 2.), false=无序列表(- )
     */
    private void processListAction(boolean isOrdered) {
        String text = editorArea.getText();
        IndexRange selection = editorArea.getSelection();

        // 1. 确定我们要处理的文本范围（扩展到行首和行尾）
        int start = selection.getStart();
        int end = selection.getEnd();

        // 找到第一行的行首
        int lineStart = (start == 0) ? 0 : text.lastIndexOf('\n', start - 1) + 1;
        // 找到最后一行的行尾
        int lineEnd = text.indexOf('\n', end);
        if (lineEnd == -1) lineEnd = text.length();

        // 2. 截取这段完整的文本
        String selectedContent = text.substring(lineStart, lineEnd);
        String[] lines = selectedContent.split("\n", -1); // -1 保留空行结构

        StringBuilder newContent = new StringBuilder();
        int currentNumber = 1;

        // 3. 如果是有序列表，先侦测上一行的数字
        if (isOrdered && lineStart > 0) {
            // 往前找一行
            int prevLineEnd = lineStart - 1;
            int prevLineStart = text.lastIndexOf('\n', prevLineEnd - 1) + 1;
            if (prevLineStart >= 0 && prevLineEnd > prevLineStart) {
                String prevLine = text.substring(prevLineStart, prevLineEnd);
                // 正则匹配行首的数字 (例如 "1. " 或 "10. ")
                java.util.regex.Pattern p = java.util.regex.Pattern.compile("^\\s*(\\d+)\\.");
                java.util.regex.Matcher m = p.matcher(prevLine);
                if (m.find()) {
                    try {
                        currentNumber = Integer.parseInt(m.group(1)) + 1;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        // 4. 遍历每一行进行处理
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (isOrdered) {
                // 有序列表：添加 "1. ", "2. " ...
                // 逻辑：直接在行首加数字
                newContent.append(currentNumber).append(". ").append(line);
                currentNumber++;
            } else {
                // 无序列表：添加 "- "
                // 逻辑：直接在行首加 "- "
                newContent.append("- ").append(line);
            }

            // 如果不是最后一行，补回换行符
            if (i < lines.length - 1) {
                newContent.append("\n");
            }
        }

        // 5. 替换编辑区文本
        editorArea.replaceText(lineStart, lineEnd, newContent.toString());

        // 6. 恢复选中状态（可选，方便用户连续操作）
        editorArea.selectRange(lineStart, lineStart + newContent.length());
        editorArea.requestFocus();
    }

    private void insertListMarker(String marker) {
        int caretPos = editorArea.getCaretPosition();
        int lineStart = getLineStartPosition(caretPos);

        editorArea.insertText(lineStart, marker);
    }
    /**
     * 处理回车键的自动列表逻辑
     * @return true=已处理(拦截默认换行), false=未处理(执行默认换行)
     */
    private boolean handleAutoList() {
        int caretPos = editorArea.getCaretPosition();
        String text = editorArea.getText();

        // 防止在文件最开始按回车报错
        if (caretPos == 0) return false;

        int start = getLineStartPosition(caretPos);
        int end = caretPos;

        // 获取当前行光标之前的内容
        String currentLine = text.substring(start, end);

        // --- 1. 判断有序列表 (匹配 "1. ", "2. " 等) ---
        java.util.regex.Pattern orderedPattern = java.util.regex.Pattern.compile("^(\\d+)\\.\\s.*");
        java.util.regex.Matcher orderedMatcher = orderedPattern.matcher(currentLine);

        if (orderedMatcher.find()) {
            // 情况 A: 当前行只有 "1. " (空项)，用户按回车 -> 结束列表
            if (currentLine.trim().matches("^\\d+\\.$")) {
                editorArea.replaceText(start, end, ""); // 清空当前行的标记
                return true; // 【关键修复】返回 true，阻止系统再换一行！
            }

            // 情况 B: 正常换行，自动生成下一级数字
            try {
                int currentNum = Integer.parseInt(orderedMatcher.group(1));
                String nextMarker = "\n" + (currentNum + 1) + ". ";
                editorArea.insertText(caretPos, nextMarker);
                return true; // 拦截默认回车
            } catch (NumberFormatException e) {
                return false;
            }
        }

        // --- 2. 判断无序列表 (匹配 "- ") ---
        if (currentLine.startsWith("- ")) {
            // 情况 A: 当前行只有 "- " (空项) -> 结束列表
            if (currentLine.trim().equals("-")) {
                editorArea.replaceText(start, end, "");
                return true; // 【关键修复】返回 true，阻止系统再换一行！
            }

            // 情况 B: 正常换行，延续 "- "
            editorArea.insertText(caretPos, "\n- ");
            return true; // 拦截默认回车
        }

        return false;
    }

    // 辅助方法：获取行首位置
    private int getLineStartPosition(int caretPos) {
        String text = editorArea.getText();
        int lastNewLine = text.lastIndexOf('\n', caretPos - 1);
        return (lastNewLine == -1) ? 0 : lastNewLine + 1;
    }
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

   //代码块
   @FXML
   private void handleCodeBlock() {
       // 如果还没进入编辑模式，强制进入
       if (!editorArea.isVisible()) {
           showEditor(true);
       }

       String selectedText = editorArea.getSelectedText();
       int caretPos = editorArea.getCaretPosition();

       if (selectedText.isEmpty()) {
           // 插入空代码块，并将光标放在中间行
           editorArea.insertText(caretPos, "```\n\n```");
           editorArea.positionCaret(caretPos + 4);
       } else {
           // 包裹选中的文字
           editorArea.replaceSelection("```\n" + selectedText + "\n```");
       }
   }
    private void showEditor(boolean editMode) {
        if (editorArea.isVisible() == editMode) return;

        // === 1. 记录当前位置比例 (0.0 ~ 1.0) ===
        double currentRatio = 0;

        if (editorArea.isVisible()) {
            // A. 编辑器 -> 预览
            ScrollBar vBar = (ScrollBar) editorArea.lookup(".scroll-bar:vertical");
            if (vBar != null && vBar.getMax() > 0) {
                // value 是当前滚动的像素，max 是可滚动的最大像素
                currentRatio = vBar.getValue() / vBar.getMax();
            }
        } else if (webView.isVisible()) {
            // B. 预览 -> 编辑器
            try {
                // 获取：scrollTop(已滚动的距离), scrollHeight(总高度), clientHeight(视口高度)
                int scrollTop = (Integer) webView.getEngine().executeScript("document.documentElement.scrollTop || document.body.scrollTop");
                int scrollHeight = (Integer) webView.getEngine().executeScript("document.documentElement.scrollHeight || document.body.scrollHeight");
                int clientHeight = (Integer) webView.getEngine().executeScript("document.documentElement.clientHeight || document.body.clientHeight");

                // 核心修复：分母必须是 (总高度 - 视口高度)
                // 比如总高1000，视口800，其实你只能滚200。如果你滚了100，那就是50%。
                int maxScrollable = scrollHeight - clientHeight;

                if (maxScrollable > 0) {
                    currentRatio = (double) scrollTop / maxScrollable;
                } else {
                    currentRatio = 0; // 内容还没屏幕高，就在顶部
                }
            } catch (Exception e) {
                currentRatio = 0;
            }
        }

        // === 2. 切换界面 ===
        editorArea.setVisible(editMode);
        editorArea.setManaged(editMode);
        webView.setVisible(!editMode);
        webView.setManaged(!editMode);

        // === 3. 恢复位置 ===
        if (editMode) {
            // ---> 切回编辑器
            editorArea.requestFocus();
            final double targetRatio = currentRatio;

            // 延迟执行，等待布局完成
            javafx.application.Platform.runLater(() -> {
                ScrollBar vBar = (ScrollBar) editorArea.lookup(".scroll-bar:vertical");
                if (vBar != null) {
                    // 掐头去尾：如果在最上面或最下面，强制吸附
                    if (targetRatio <= 0.05) vBar.setValue(0);
                    else if (targetRatio >= 0.95) vBar.setValue(vBar.getMax());
                    else vBar.setValue(targetRatio * vBar.getMax());
                }
            });
        } else {
            // ---> 切回预览
            try { handleSave(); } catch (Exception e) {}
            pendingScrollRatio = currentRatio; // 存起来给 updatePreview 用
            webView.setOpacity(0);
            updatePreview();
        }
    }

    /**
     * 辅助方法：从 Markdown 内容中提取第一个一级标题 (# 标题)
     */
    private String extractTitleFromContent(String content) {
        if (content == null) return null;
        String[] lines = content.split("\n");
        for (String line : lines) {
            // 找到第一个以 "# " 开头的行 (忽略前面的空白)
            if (line.trim().startsWith("# ")) {
                // 去掉 "# " 并去除首尾空格
                return line.trim().substring(2).trim();
            }
        }
        return null; // 没有找到一级标题
    }

    /**
     * 辅助方法：净化文件名 (去除 Windows/Linux 不允许的字符)
     */
    private String sanitizeFileName(String name) {
        // 移除 Windows 系统不允许的文件名字符
        // 并限制长度，防止 PDF 标题过长导致系统路径溢出
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", " ").trim();
        if (safe.length() > 100) {
            safe = safe.substring(0, 100);
        }
        return safe;
    }


    // =====================================================
//  Custom Dialog Logic (美化版弹窗工具)
// =====================================================

    /**
     * 核心工具：显示一个现代化的确认弹窗
     * @param title 标题
     * @param message 消息内容
     * @param isDestructive 是否是破坏性操作（如删除），如果是，按钮会变红
     * @return 用户是否点击了“确定”
     */
    /**
     * 核心工具：显示一个确认弹窗
     */
    private boolean showConfirmDialog(String title, String message, boolean isDestructive) {
        // 1. 创建 Stage
        javafx.stage.Stage stage = new javafx.stage.Stage();
        stage.initModality(javafx.stage.Modality.APPLICATION_MODAL); // 阻塞父窗口
        stage.initStyle(javafx.stage.StageStyle.TRANSPARENT);
        stage.initOwner(rootContainer.getScene().getWindow());

        // 2. 布局容器
        VBox root = new VBox();
        root.getStyleClass().add("modern-dialog-root"); // 应用 CSS

        //// 关键：继承主界面的 CSS (Dark/Light 模式)
        //root.getStylesheets().addAll(rootContainer.getScene().getStylesheets());


        // 方案 A: 直接从 rootContainer 获取 (推荐)
        if (rootContainer.getStylesheets().isEmpty()) {
            // 双重保险：如果 root 也没获取到，直接加载文件路径
            try {
                String cssPath = getClass().getResource("/css/style.css").toExternalForm();
                root.getStylesheets().add(cssPath);
            } catch (Exception e) {
                System.err.println("CSS 加载失败: " + e.getMessage());
            }
        } else {
            root.getStylesheets().addAll(rootContainer.getStylesheets());
        }


        // 关键：传递当前的主题 Class (例如 theme-dark)，确保弹窗颜色正确
        rootContainer.getStyleClass().forEach(style -> root.getStyleClass().add(style));

        // 3. 标题与内容
        Label lblTitle = new Label(title);
        lblTitle.getStyleClass().add("modern-dialog-title");

        Label lblMsg = new Label(message);
        lblMsg.getStyleClass().add("modern-dialog-message");
        lblMsg.setWrapText(true);
        lblMsg.setPrefWidth(350); // 增加一点宽度

        // 4. 按钮设计
        Button btnCancel = new Button("取消");
        btnCancel.getStyleClass().addAll("dialog-btn", "dialog-btn-cancel");

        // 根据是否是破坏性操作（删除），决定按钮是 蓝色 还是 红色
        Button btnConfirm = new Button(isDestructive ? "删除" : "确定");
        btnConfirm.getStyleClass().addAll("dialog-btn", isDestructive ? "dialog-btn-danger" : "dialog-btn-confirm");
        // 默认聚焦在取消按钮上，防止误删 (IDEA 逻辑)
        if (isDestructive) {
            btnCancel.setDefaultButton(true);
        } else {
            btnConfirm.setDefaultButton(true);
        }

        // IDEA 风格：按钮靠右
        HBox btnBar = new HBox(btnCancel, btnConfirm);
        btnBar.getStyleClass().add("modern-dialog-btn-bar");

        root.getChildren().addAll(lblTitle, lblMsg, btnBar);

        // 5. 事件处理
        java.util.concurrent.atomic.AtomicBoolean result = new java.util.concurrent.atomic.AtomicBoolean(false);

        btnCancel.setOnAction(e -> stage.close());
        btnConfirm.setOnAction(e -> {
            result.set(true);
            stage.close();
        });

        // 键盘支持 (回车确认，ESC取消)
        root.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ENTER -> {
                    // 如果是删除操作，为了安全，必须显式按到确认按钮，回车默认触发 defaultButton
                    if (btnConfirm.isDefaultButton()) btnConfirm.fire();
                    else btnCancel.fire();
                }
                case ESCAPE -> btnCancel.fire();
            }
        });

        // 6. 让无边框窗口可以拖动
        makeDraggable(stage, root);

        // 7. 显示
        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        scene.setFill(null); // 场景透明
        stage.setScene(scene);
        stage.showAndWait();

        return result.get();
    }

    /**
     * 核心工具：显示一个现代化的输入弹窗
     * @return 用户输入的字符串，取消则返回 null
     */
    private String showInputDialog(String title, String message, String defaultValue) {
        javafx.stage.Stage stage = new javafx.stage.Stage();
        stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        stage.initStyle(javafx.stage.StageStyle.TRANSPARENT);
        stage.initOwner(rootContainer.getScene().getWindow());

        VBox root = new VBox(10);
        root.getStyleClass().add("modern-dialog-root");
        root.getStylesheets().addAll(rootContainer.getScene().getStylesheets());
        rootContainer.getStyleClass().forEach(style -> root.getStyleClass().add(style));

        Label lblTitle = new Label(title);
        lblTitle.getStyleClass().add("modern-dialog-title");

        Label lblMsg = new Label(message);
        lblMsg.getStyleClass().add("modern-dialog-message");

        TextField inputField = new TextField(defaultValue);
        inputField.getStyleClass().add("modern-dialog-input");

        Button btnCancel = new Button("取消");
        btnCancel.getStyleClass().addAll("dialog-btn", "dialog-btn-cancel");
        Button btnConfirm = new Button("保存");
        btnConfirm.getStyleClass().addAll("dialog-btn", "dialog-btn-confirm");

        HBox btnBar = new HBox(btnCancel, btnConfirm);
        btnBar.getStyleClass().add("modern-dialog-btn-bar");

        root.getChildren().addAll(lblTitle, lblMsg, inputField, btnBar);

        // 结果容器
        StringBuilder result = new StringBuilder();

        btnCancel.setOnAction(e -> stage.close());
        btnConfirm.setOnAction(e -> {
            result.append(inputField.getText());
            stage.close();
        });

        // 自动聚焦并全选
        javafx.application.Platform.runLater(() -> {
            inputField.requestFocus();
            inputField.selectAll();
        });

        inputField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) btnConfirm.fire();
            if (e.getCode() == KeyCode.ESCAPE) btnCancel.fire();
        });

        makeDraggable(stage, root);

        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        scene.setFill(null);
        stage.setScene(scene);
        stage.showAndWait();

        return result.length() > 0 ? result.toString() : null;
    }

    // 辅助方法：实现窗口拖拽
    private void makeDraggable(javafx.stage.Stage stage, javafx.scene.Node node) {
        final double[] xOffset = {0};
        final double[] yOffset = {0};
        node.setOnMousePressed(event -> {
            xOffset[0] = event.getSceneX();
            yOffset[0] = event.getSceneY();
        });
        node.setOnMouseDragged(event -> {
            stage.setX(event.getScreenX() - xOffset[0]);
            stage.setY(event.getScreenY() - yOffset[0]);
        });
    }

    /**
     * 当文件名在侧边栏被修改后，同步修改文件内容里的 # 标题
     * @param fullFileName 新的完整文件名 (用于读取/保存文件)
     * @param newTitle     新的标题文字 (用于替换内容里的 # 标题)
     * @param oldFullName  旧文件名 (用于判断是否是当前打开的文件)
     */
    private void syncH1TitleInFile(String fullFileName, String newTitle, String oldFullName) throws IOException {
        String content;
        boolean isCurrentFile = currentNoteTitle.equals(oldFullName);

        // 1. 获取内容
        if (isCurrentFile) {
            // 如果改的是当前正在编辑的文件，直接从编辑器拿最新内容
            content = editorArea.getText();
        } else {
            // 如果改的是后台文件，从硬盘读 (注意：此时文件已经被 rename 到 fullFileName 了)
            content = FileUtil.read(fullFileName);
        }

        if (content == null) content = "";

        // 2. 使用正则替换第一个 H1 标题
        // (?m) 开启多行模式，^ 匹配行首
        // 匹配以 # 开头，后面跟一个空格，然后是任意文字的行
        // 替换为 # 新标题
        // replaceFirst 只换第一个
        String newContent = content.replaceFirst("(?m)^#\\s+.*", "# " + newTitle);

        // 如果文中没有 # 标题，可以选择不加，或者强制加在头部：
        if (content.equals(newContent) && !content.startsWith("# ")) {
            // 没找到标题，帮他加上
            newContent = "# " + newTitle + "\n\n" + content;
        }

        // 3. 保存回硬盘
        FileUtil.save(fullFileName, newContent);

        // 4. 如果是当前文件，还需要刷新编辑器显示和状态变量
        if (isCurrentFile) {
            currentNoteTitle = fullFileName; // 更新内存中的文件名

            // 只有当内容真的变了才刷新编辑器（防止光标跳动太厉害）
            if (!editorArea.getText().equals(newContent)) {
                // 记住光标位置
                int caret = editorArea.getCaretPosition();
                editorArea.setText(newContent);
                // 尽量恢复光标（如果标题变短了可能会越界，简单处理一下）
                editorArea.positionCaret(Math.min(caret, newContent.length()));
            }
            if (webView.isVisible()) {
                updatePreview();
            }
        }

    }


    // =====================================================
    // 帮助菜单逻辑
    // =====================================================

    /**
     * 打开用户手册
     * 逻辑：检查是否有 DeepMind_Help.md，没有则创建，有则直接打开
     */
    @FXML
    private void handleOpenHelp() {
        String helpFileName = "DeepMind_Help"; // 帮助文件的文件名（不带后缀）
        File helpFile = new File("notes/" + helpFileName + ".md");

        // 1. 如果文件不存在，自动创建并写入默认内容
        if (!helpFile.exists()) {
            try {
                FileUtil.save(helpFileName, HELP_MARKDOWN_CONTENT);
                // 刷新左侧文件树，让新文件显示出来
                refreshFileTree();
            } catch (IOException e) {
                showError("创建失败", "无法生成帮助文件: " + e.getMessage());
                return;
            }
        }

        // 2. 在左侧树中找到这个文件并选中它 (这样会触发 loadNoteContent)
        selectFileInTree(helpFileName);

        // 3. 强制切到预览模式，方便阅读
        handlePreviewMode();
    }

    /**
     * 关于弹窗
     */
    @FXML
    private void handleAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("关于 DeepMind Note");
        alert.setHeaderText("DeepMind Note v1.0");
        alert.setContentText("开发者：DeepMind Dev Team\n\n一款专注于沉浸式写作与知识管理的\nJavaFX 轻量级笔记应用。");
        alert.showAndWait();
    }

    /**
     * 辅助方法：在文件树中自动选中指定文件
     * (这个方法对于提升体验很重要，让左侧列表高亮当前打开的文件)
     */
    private void selectFileInTree(String targetFileName) {
        if (fileTree.getRoot() == null) return;

        // 遍历所有节点寻找目标
        for (TreeItem<String> category : fileTree.getRoot().getChildren()) {
            // 如果是未分类的直接节点
            if (category.getValue().equals(targetFileName)) {
                fileTree.getSelectionModel().select(category);
                return;
            }
            // 遍历子节点
            for (TreeItem<String> note : category.getChildren()) {
                // 注意：树节点里的 value 可能是 "分类_文件名" 也可能是 "文件名"
                // 你的逻辑里 TreeItem 存的是 fullFileName (比如 "DeepMind_Help")
                if (note.getValue().equals(targetFileName)) {
                    category.setExpanded(true); // 展开文件夹
                    fileTree.getSelectionModel().select(note); // 选中
                    fileTree.scrollTo(fileTree.getRow(note)); // 滚动到可见位置
                    return;
                }
            }
        }
    }


    /**
     * 判断一个节点是否应该被视为文件夹
     * 逻辑：如果它有子节点，或者它在硬盘上找不到对应的 .md 文件，它就是文件夹
     */
    private boolean isFolderNode(TreeItem<String> item) {
        if (item == null) return false;
        if (!item.isLeaf()) return true; // 有子节点肯定是文件夹

        // 如果是叶子节点，检查硬盘上是否存在该文件
        // 注意：FileUtil.listAllNotes() 返回的是全名，但这里我们需要构建路径判断
        String potentialFileName = item.getValue() + ".md";
        File f = new File("notes/" + potentialFileName);

        // 如果文件存在，它是笔记；如果不存在（且它是树上的有效节点），它是空文件夹
        return !f.exists();
    }


    @FXML
    private void handleExpandAll() {
        if (fileTree.getRoot() != null) {
            setTreeExpanded(fileTree.getRoot(), true);
        }
    }

    @FXML
    private void handleCollapseAll() {
        if (fileTree.getRoot() != null) {
            // 如果你希望根节点（Root）保持展开，只折叠子节点，请保留下面这一行：
            fileTree.getRoot().setExpanded(true);
            // 遍历子节点进行折叠
            for (TreeItem<?> child : fileTree.getRoot().getChildren()) {
                setTreeExpanded(child, false);
            }

            // 如果你想连根节点也完全折叠，直接用下面这行代替上面所有逻辑：
            // setTreeExpanded(fileTree.getRoot(), false);
        }
    }

    // 递归辅助方法
    private void setTreeExpanded(TreeItem<?> item, boolean expanded) {
        item.setExpanded(expanded);
        if (!item.isLeaf()) {
            for (TreeItem<?> child : item.getChildren()) {
                setTreeExpanded(child, expanded);
            }
        }
    }

    // 这里存放帮助文档的内容常量
    private static final String HELP_MARKDOWN_CONTENT = """
# DeepMind Note 用户手册

**欢迎使用 DeepMind Note** —— 这是一款专为开发者和知识工作者设计的轻量级、交互式 Markdown 笔记应用。它结合了极简的编辑体验与强大的文件管理功能，助你高效构建知识体系。

---

## 1. 快速入门

### 界面概览
DeepMind Note 的界面主要分为三个区域：
* **左侧：文件资源管理器** —— 管理你的笔记文件夹和文件。
* **中间：编辑/预览区** —— 核心工作区，支持 Markdown 源码编辑和富文本实时预览。
* **右侧：大纲视图** —— 根据标题自动生成目录，点击可快速跳转。

### 创建第一篇笔记
1. 点击工具栏的 **“新建笔记”** 按钮（或右键左侧空白处）。
2. 输入标题（默认文件名自动生成）。
3. 在中间编辑区开始写作。
4. 按下 `Ctrl + S` 保存（软件也会在切换焦点时自动保存）。

---

## 2. 核心编辑功能

DeepMind Note 全面支持标准 Markdown 语法。

### 常用格式

| 功能 | 语法示例 | 快捷键/操作 |
| :--- | :--- | :--- |
| **标题** | `# 一级标题`, `## 二级标题` | 右键菜单 -> 标题级别 |
| **加粗** | `**重点内容**` | 右键 -> 加粗 |
| **斜体** | `*斜体内容*` | 右键 -> 倾斜 |
| **引用** | `> 引用文本` | 右键 -> 引用块 |
| **列表** | `- 项目` 或 `1. 项目` | 自动补全（回车自动换行） |
| **代码块** | \\`\\`\\`java ... \\`\\`\\` | 选中代码 -> 右键代码块 |
| **高亮** | `==高亮文本==` | 右键 -> 高亮 |

### 图片与文件处理
DeepMind Note 拥有强大的图片管理功能：
* **拖拽上传**：直接将图片文件拖入**编辑区**（插入光标处）或**预览区**（追加到文末）。
* **截图粘贴**：使用系统截图工具截图后，在编辑区按 `Ctrl + V`，图片会自动保存到 `notes/images` 目录并生成 Markdown 链接。
* **文件粘贴**：复制电脑中的图片文件，直接在编辑器中粘贴即可。

---

## 3. 智能文件管理

### 双向同步重命名
这是 DeepMind Note 的特色功能：
* **改标题即改文件名**：在编辑器第一行修改 `# 标题`，文件列表中的文件名会自动同步修改。
* **改文件名即改标题**：在左侧文件树重命名文件，笔记内容里的 `# 标题` 也会自动更新。

### 分类与移动
* **虚拟文件夹**：笔记采用 `分类_笔记名.md` 的方式存储，但在软件中以文件夹树的形式展示。
* **移动笔记**：在左侧树中**拖拽**笔记到不同文件夹，或右键选择“移动到文件夹”，即可轻松整理知识库。
* **新建文件夹**：点击“新建文件夹”按钮，创建新的分类节点。

### 搜索
* **全局搜索**：在左侧顶部的搜索框输入关键词，可过滤文件名。
* **内容查找**：在编辑器中按 `Ctrl + F` 开启查找栏，支持高亮所有匹配项；按 `Ctrl + H` 进行替换。
* **快速打开**：使用“快速打开”功能（快捷键需自行绑定），弹窗列出所有文件，支持模糊搜索跳转。

---

## 4. 预览与导出

### 模式切换
* **编辑模式**：专注于源码编写。
* **预览模式**：查看渲染后的网页效果。
* **自动切换**：点击预览区的任意位置可进入编辑；编辑完成后点击外部或手动切换可回预览。

### 导出功能
支持将笔记导出为多种格式，方便分享：
* **Markdown (.md)**：原始格式。
* **PDF (.pdf)**：高质量文档，支持中文（基于 Microsoft YaHei 字体）。
* **Word (.docx)**：办公文档格式。
* **HTML**：支持导出“带样式网页”或“纯净HTML”。
* **长图 (.png)**：将整篇笔记生成为一张长图片。
* **打印**：直接调用系统打印机打印笔记。

---

## 5. 个性化设置

### 主题切换
在菜单栏中选择 **“主题”**，DeepMind Note 提供三种精心调配的配色：
1. **暗夜黑**：适合夜间编码，护眼高对比度。
2. **森系绿**：清新自然，缓解视觉疲劳。
3. **暖阳橙**：温暖活力，激发创作灵感。

### 界面布局
* **侧边栏**：点击左上角图标可收起/展开文件树，专注写作。
* **大纲栏**：点击右上角图标可收起/展开右侧大纲。

---

## 6. 数据存储说明

* **本地存储**：所有笔记均保存在软件运行目录下的 `notes/` 文件夹中。
* **图片资源**：笔记中的图片保存在 `notes/images/` 中。
* **备份建议**：由于是纯本地文件，您可以直接复制 `notes` 文件夹进行备份，或使用 Git 进行版本控制。

---

> **关于 DeepMind Note**
>
> 开发者：DeepMind Dev Team
> 版本：v1.0.0 (Preview)
> *记录思维，连接未来。*
""";

    /**
     * 【新方法】根据当前 JavaFX 主题获取对应的 WebView CSS 样式
     * 颜色值与 style.css 严格对应
     */

    private String getThemeRenderCss() {
        boolean isDark = rootContainer.getStyleClass().contains("theme-dark");
        boolean isGreen = rootContainer.getStyleClass().contains("theme-green");
        boolean isOrange = rootContainer.getStyleClass().contains("theme-orange");

        // === 变量声明 ===
        String bgColor, mainTextColor, linkColor;
        String selectionBg, selectionText;
        String blockCodeBg, blockCodeColor;
        String inlineCodeBg, inlineCodeText, inlineCodeBorder;
        String tableHeaderBg, tableBorderColor, tableRowHover;
        String quoteBorder, quoteText;

        if (isDark) {
            // === 暗夜黑 ===
            bgColor = "#1e1f22";
            mainTextColor = "#d1d5db";
            linkColor = "#58a6ff";
            blockCodeBg = "#26292e"; blockCodeColor = "#c9d1d9";
            inlineCodeBg = "rgba(110, 118, 129, 0.4)"; inlineCodeText = "#e6edf3"; inlineCodeBorder = "rgba(240, 246, 252, 0.15)";
            selectionBg = "#264f78"; selectionText = "#ffffff";
            tableHeaderBg = "#2d333b"; tableBorderColor = "#30363d"; tableRowHover = "#282c34";
            quoteBorder = "#3b434b"; quoteText = "#8b949e";
        } else if (isGreen) {
            // === 森系绿 ===
            bgColor = "#fcfdfc";
            mainTextColor = "#2c3e50";
            linkColor = "#42b983";
            blockCodeBg = "#f1f8f6"; blockCodeColor = "#2c3e50";
            inlineCodeBg = "rgba(66, 185, 131, 0.12)"; inlineCodeText = "#244f3b"; inlineCodeBorder = "rgba(66, 185, 131, 0.2)";
            selectionBg = "#a8e6cf"; selectionText = "#1b4d3e"; // 绿色高亮
            tableHeaderBg = "#e8f5e9"; tableBorderColor = "#c8e6c9"; tableRowHover = "#f1f8e9";
            quoteBorder = "#a5d6a7"; quoteText = "#587c65";
        } else if (isOrange) {
            // === 暖阳橙 ===
            bgColor = "#fffdf9";
            mainTextColor = "#5d4037";
            linkColor = "#fb8c00";
            blockCodeBg = "#fff8e1"; blockCodeColor = "#5d4037";
            inlineCodeBg = "rgba(255, 167, 38, 0.15)"; inlineCodeText = "#6d4c41"; inlineCodeBorder = "rgba(255, 167, 38, 0.25)";
            selectionBg = "#ffe0b2"; selectionText = "#3e2723"; // 橙色高亮
            tableHeaderBg = "#fff3e0"; tableBorderColor = "#ffe0b2"; tableRowHover = "#fff8e1";
            quoteBorder = "#ffcc80"; quoteText = "#8d6e63";
        } else {
            // === 默认 (蓝色) ===
            bgColor = "#ffffff";
            mainTextColor = "#24292f";
            linkColor = "#0969da";
            blockCodeBg = "#f6f8fa"; blockCodeColor = "#24292f";
            inlineCodeBg = "rgba(175, 184, 193, 0.2)"; inlineCodeText = "#24292f"; inlineCodeBorder = "rgba(175, 184, 193, 0.2)";
            selectionBg = "#cce5ff"; selectionText = "#004085"; // 蓝色高亮
            tableHeaderBg = "#f6f8fa"; tableBorderColor = "#d0d7de"; tableRowHover = "#f2f2f2";
            quoteBorder = "#dfe2e5"; quoteText = "#57606a";
        }

        // 构建 CSS，注意 %3$s 被赋值给了 --flash-bg
        return String.format("""
        body {
            --flash-bg: %3$s;  /* 【关键】将 Java 的选中色传给 CSS 变量 */
            background-color: %1$s;
            color: %2$s !important;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", Helvetica, Arial, sans-serif;
            font-size: 15px;
            line-height: 1.6;
            padding: 20px 30px;
            margin: 0;
        }
        h1, h2, h3, h4, h5, h6 { color: %2$s !important; font-weight: 600; margin-top: 24px; margin-bottom: 16px; line-height: 1.25; }
        h1, h2 { border-bottom: 1px solid %11$s; padding-bottom: 0.3em; }
        a { color: %5$s; text-decoration: none; transition: all 0.2s; }
        a:hover { text-decoration: underline; opacity: 0.8; }
        blockquote { border-left: 4px solid %13$s; margin: 0 0 16px 0; padding: 0 1em; color: %14$s !important; opacity: 0.9; }
        
        /* 表格 */
        table { border-spacing: 0; border-collapse: collapse; width: 100%%; margin: 16px 0; font-size: 14px; border-radius: 6px; overflow: hidden; border: 1px solid %11$s; }
        th { background-color: %10$s !important; color: %2$s !important; font-weight: 600; text-align: left; padding: 10px 13px; border-bottom: 1px solid %11$s; border-right: 1px solid %11$s; }
        th:last-child { border-right: none; }
        td { padding: 10px 13px; border-bottom: 1px solid %11$s; border-right: 1px solid %11$s; color: %2$s !important; }
        td:last-child { border-right: none; }
        tr:last-child td { border-bottom: none; }
        tr:hover { background-color: %12$s; }

        /* 代码块 */
        pre { background-color: %6$s; color: %7$s; padding: 16px; border-radius: 6px; overflow-x: auto; font-family: 'JetBrains Mono', 'Consolas', monospace; font-size: 85%%; line-height: 1.45; border: 1px solid %11$s; }
        code { background-color: %8$s !important; color: %9$s !important; border: 1px solid %15$s; font-family: 'JetBrains Mono', 'Consolas', monospace; padding: 0.2em 0.4em; margin: 0; font-size: 85%%; border-radius: 6px; }
        pre code { background-color: transparent !important; color: inherit !important; padding: 0; border: none; font-size: 100%%; }
        
        /* 选中与图片 */
        ::selection { background-color: %3$s; color: %4$s; }
        img { max-width: 100%%; border-radius: 4px; box-shadow: 0 4px 10px rgba(0,0,0,0.1); margin: 10px 0; }
        
        /* 滚动条 */
        ::-webkit-scrollbar { width: 10px; height: 10px; }
        ::-webkit-scrollbar-thumb { background: rgba(120, 120, 120, 0.4); border-radius: 5px; border: 2px solid %1$s; }
        ::-webkit-scrollbar-track { background: transparent; }
        """,
                bgColor, mainTextColor, selectionBg, selectionText, linkColor,
                blockCodeBg, blockCodeColor, inlineCodeBg, inlineCodeText,
                tableHeaderBg, tableBorderColor, tableRowHover,
                quoteBorder, quoteText, inlineCodeBorder
        );
    }

}