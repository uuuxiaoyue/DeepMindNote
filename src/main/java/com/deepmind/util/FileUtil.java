package com.deepmind.util;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;


public class FileUtil {
    private static final String BASE_DIR = "notes";
    private static final Gson gson = new Gson();

    // 确保目录存在
    public static void initStorage() {
        try {
            Files.createDirectories(Paths.get(BASE_DIR));
        } catch (IOException e) {
            System.err.println("无法创建存储目录");
        }
    }

    public static void save(String title, String content) throws IOException {
        Files.writeString(Paths.get(BASE_DIR, title + ".md"), content);
    }

    public static String read(String title) throws IOException {
        return Files.readString(Paths.get(BASE_DIR, title + ".md"));
    }

    public static List<String> listAllNotes() throws IOException {
        return Files.list(Paths.get(BASE_DIR))
                .filter(path -> path.toString().endsWith(".md"))
                .map(path -> path.getFileName().toString().replace(".md", ""))
                .collect(Collectors.toList());
    }

    public static void delete(String title) throws IOException {
        Path filePath = Paths.get(BASE_DIR, title + ".md");
        Files.deleteIfExists(filePath);
    }

    // 保存元数据
    public static void saveMetadata(String title, NoteMetadata meta) throws IOException {
        Path path = Paths.get(BASE_DIR, title + ".json");
        Files.writeString(path, gson.toJson(meta));
    }

    // 读取元数据
    public static NoteMetadata readMetadata(String title) {
        Path path = Paths.get(BASE_DIR, title + ".json");
        if (!Files.exists(path)) return new NoteMetadata(); // 返回空对象防止报错
        try {
            return gson.fromJson(Files.readString(path), NoteMetadata.class);
        } catch (IOException e) {
            return new NoteMetadata();
        }
    }

    /**
     * 从外部文件读取内容（用于导入）
     */
    public static String readFromExternal(java.io.File file) throws IOException {
        return java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 将内容写入外部文件（用于导出 Markdown 和 HTML）
     */
    public static void writeToExternal(java.io.File file, String content) throws IOException {
        java.nio.file.Files.writeString(file.toPath(), content, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void rename(String oldTitle, String newTitle) throws IOException {
        Path source = Paths.get(BASE_DIR, oldTitle + ".md");
        Path target = Paths.get(BASE_DIR, newTitle + ".md");

        // 检查目标文件是否已存在
        if (Files.exists(target)) {
            throw new IOException("目标文件名已存在");
        }

        // 移动主文件
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);

        // 移动元数据文件 (json)
        Path metaSource = Paths.get(BASE_DIR, oldTitle + ".json");
        Path metaTarget = Paths.get(BASE_DIR, newTitle + ".json");
        if (Files.exists(metaSource)) {
            Files.move(metaSource, metaTarget, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
