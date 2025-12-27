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
    @FXML private TextArea editorArea;
    @FXML private WebView webView;
    @FXML private ListView<String> noteListView;
    @FXML private TreeView<String> categoryTree;
    // 追踪当前正在编辑的笔记文件名（不含.md）
    private String currentNoteTitle = "";
    @FXML private TextField searchField;
    @FXML private Label wordCountLabel;
    @FXML private ListView<String> outlineListView;
    @FXML private VBox outlineContainer;
    @FXML private Button toggleOutlineBtn;

    @FXML
    public void initialize() {
        FileUtil.initStorage();
        initCategoryTree();

        // 关键：监听 TreeView 变化来过滤列表
        categoryTree.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            refreshNoteList();
        });

        setupSelectionListeners();
        setupContextMenu(); // 启用右键菜单
        setupSearch();    // 启用搜索
        setupOutline();      // 右侧大纲会实时更新
        showRandomReview();  // 启动时检查是否有需要复习的“烧脑”笔记
        setupWordCount(); // 启用字数统计
        showWelcomePage();
    }

    private void initCategoryTree() {
        TreeItem<String> root = new TreeItem<>("全部笔记");
        root.getChildren().add(new TreeItem<>("课程学习"));
        root.getChildren().add(new TreeItem<>("个人项目"));
        categoryTree.setRoot(root);
        categoryTree.setShowRoot(true);
    }

    /**
     * 从磁盘读取所有 .md 文件并显示在中间列表中
     */
    private void refreshNoteList() {
        try {
            List<String> allFiles = FileUtil.listAllNotes();
            TreeItem<String> selectedItem = categoryTree.getSelectionModel().getSelectedItem();

            if (selectedItem == null || selectedItem.getValue().equals("全部笔记")) {
                // 显示所有笔记，但去掉文件名的前缀显示
                noteListView.getItems().setAll(allFiles);
            } else {
                // 过滤出包含当前分类名称的文件
                String filter = selectedItem.getValue() + "_";
                List<String> filtered = allFiles.stream()
                        .filter(name -> name.startsWith(filter))
                        .toList();
                noteListView.getItems().setAll(filtered);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void setupContextMenu() {
        ContextMenu contextMenu = new ContextMenu();
        MenuItem deleteItem = new MenuItem("删除笔记");
        deleteItem.setStyle("-fx-text-fill: red;");

        deleteItem.setOnAction(event -> {
            String selected = noteListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                // 弹出确认对话框
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "确定要删除 [" + selected + "] 吗？", ButtonType.YES, ButtonType.NO);
                alert.showAndWait().ifPresent(response -> {
                    if (response == ButtonType.YES) {
                        try {
                            FileUtil.delete(selected);
                            refreshNoteList(); // 刷新界面
                            showWelcomePage(); // 回到欢迎页
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
            }
        });

        contextMenu.getItems().add(deleteItem);
        // 绑定到列表
        noteListView.setContextMenu(contextMenu);
    }

    /**
     * 新建笔记按钮逻辑
     * 绑定到 FXML 的新建按钮: onAction="#handleNewNote"
     */
    @FXML
    private void handleNewNote() {
        // 获取当前 TreeView 选中的分类
        TreeItem<String> selectedCategory = categoryTree.getSelectionModel().getSelectedItem();
        String categoryPrefix = (selectedCategory != null && selectedCategory.getParent() != null)
                ? selectedCategory.getValue() + "_" : "";

        TextInputDialog dialog = new TextInputDialog("新笔记");
        dialog.setTitle("新建笔记");
        dialog.setHeaderText("在 [" + (categoryPrefix.isEmpty() ? "全部" : selectedCategory.getValue()) + "] 下创建笔记");

        dialog.showAndWait().ifPresent(name -> {
            String fullTitle = categoryPrefix + name; // 实际存的文件名是 "分类_名称"
            try {
                FileUtil.save(fullTitle, "# " + name);
                refreshNoteList(); // 刷新列表
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }


    @FXML
    private void handleSave() {
        if (currentNoteTitle == null || currentNoteTitle.isEmpty()) return;

        try {
            FileUtil.save(currentNoteTitle, editorArea.getText());

            // 统一心情定义
            List<String> moods = List.of("😊 豁然开朗", "😐 平静如水", "😫 烧脑痛苦", "🧠 深度思考");
            ChoiceDialog<String> dialog = new ChoiceDialog<>("😐 平静如水", moods);
            dialog.setTitle("保存成功");
            dialog.setHeaderText("记录一下此时的心境");
            dialog.setContentText("心情状态:");

            dialog.showAndWait().ifPresent(selectedMood -> {
                NoteMetadata meta = FileUtil.readMetadata(currentNoteTitle);
                meta.title = currentNoteTitle;
                meta.lastMood = selectedMood;
                // 模拟遗忘曲线
                meta.nextReviewDate = java.time.LocalDate.now().plusDays(3).toString();

                try {
                    FileUtil.saveMetadata(currentNoteTitle, meta);
                    // 更新底部状态栏显示
                    wordCountLabel.setText("字数: " + editorArea.getText().length() + " | 最近心情: " + selectedMood);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

        } catch (IOException e) {
            e.printStackTrace();
        }
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
        String html = MarkdownParser.parse(mdContent);
        webView.getEngine().loadContent(html);
    }

    private void loadNoteContent(String title) {
        try {
            currentNoteTitle = title;
            String content = FileUtil.read(title);
            editorArea.setText(content);

            // 如果当前在预览模式，切换笔记时自动更新预览内容
            if (webView.isVisible()) {
                updatePreview();
            }
        } catch (IOException e) {
            editorArea.setText("读取文件失败: " + e.getMessage());
        }
    }

    private void showWelcomePage() {
        String welcomeMD = "# 欢迎使用 DeepMind Note\n\n" +
                "### 快速上手指南：\n" +
                "1. **新建**：点击新建按钮创建您的第一篇笔记。\n" +
                "2. **编辑**：在右侧区域输入 Markdown 语法内容。\n" +
                "3. **预览**：点击预览模式查看排版效果。\n" +
                "4. **保存**：养成随时保存的好习惯！\n\n" +
                "> 这是一个基于 JavaFX 的交互式笔记演示原型。";
        editorArea.setText(welcomeMD);
        currentNoteTitle = ""; // 欢迎页不对应具体文件，防止误覆盖
        updatePreview();
        handlePreviewMode();
    }

    private void setupSelectionListeners() {
        // 监听笔记列表点击事件
        noteListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                try {
                    // 1. 更新当前正在编辑的文件名
                    currentNoteTitle = newVal;

                    // 2. 从磁盘读取内容
                    String content = FileUtil.read(newVal);

                    // 3. 将内容填入编辑器
                    editorArea.setText(content);

                    // 4. 如果当前处于预览模式，自动更新预览
                    if (webView.isVisible()) {
                        updatePreview();
                    }
                } catch (IOException e) {
                    // 如果是“欢迎使用”这种不存在真实文件的项，展示欢迎页
                    if (newVal.equals("欢迎使用 DeepMind Note")) {
                        showWelcomePage();
                    } else {
                        System.err.println("读取文件失败: " + e.getMessage());
                    }
                }
            }
        });
    }

    private void setupSearch() {
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            try {
                // 1. 获取所有真实的文件名
                List<String> allNotes = FileUtil.listAllNotes();

                // 2. 过滤出包含关键字的内容
                List<String> filteredNotes = allNotes.stream()
                        .filter(name -> name.toLowerCase().contains(newValue.toLowerCase()))
                        .toList();

                // 3. 更新列表显示
                noteListView.getItems().setAll(filteredNotes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void setupWordCount() {
        editorArea.textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null) {
                wordCountLabel.setText("字数: 0");
                return;
            }
            // 简单的字数统计（包含中英文和空格）
            int count = newValue.length();
            wordCountLabel.setText("字数: " + count);
        });
    }

    private void showRandomReview() {
        try {
            List<String> all = FileUtil.listAllNotes();
            if (all.isEmpty()) return;

            // 随机抽一个
            String randomTitle = all.get((int) (Math.random() * all.size()));
            NoteMetadata meta = FileUtil.readMetadata(randomTitle);

            // 只有心情不好的或者很久没看的才提醒（逻辑自拟）
            if ("😫 压力山大".equals(meta.lastMood)) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("复习提醒");
                alert.setHeaderText("你之前记录这篇笔记时感到很辛苦...");
                alert.setContentText("要不要回顾一下 [" + randomTitle + "]？");
                alert.show();
            }
        } catch (IOException e) {}
    }
    private void setupOutline() {
        // 1. 监听文本变化，实时提取标题
        editorArea.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) return;

            List<String> headings = new java.util.ArrayList<>();
            String[] lines = newVal.split("\n");
            for (String line : lines) {
                String trimmedLine = line.trim();
                // 匹配 # 开头的 Markdown 标题
                if (trimmedLine.startsWith("#")) {
                    headings.add(trimmedLine);
                }
            }
            outlineListView.getItems().setAll(headings);
        });

        // 2. 点击大纲项，跳转到编辑器对应位置
        outlineListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                String content = editorArea.getText();
                int index = content.indexOf(newVal);
                if (index != -1) {
                    editorArea.requestFocus();
                    // 选中标题并让光标跳转
                    editorArea.selectRange(index, index + newVal.length());
                }
            }
        });
    }

    @FXML
    private void toggleOutline() {
        boolean isVisible = outlineContainer.isManaged();
        if (isVisible) {
            // 隐藏
            outlineContainer.setVisible(false);
            outlineContainer.setManaged(false);
            toggleOutlineBtn.setText("展开大纲");
        } else {
            // 显示
            outlineContainer.setVisible(true);
            outlineContainer.setManaged(true);
            toggleOutlineBtn.setText("📑");
        }
    }
}